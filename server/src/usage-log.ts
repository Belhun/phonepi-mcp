import fs from "fs";
import os from "os";
import path from "path";

/**
 * On-box copy of PhonePi daily screen time.
 * One JSON object per local phone day under ~/.phonepi/usage/YYYY-MM-DD.json.
 * The phone is the writer. Bots only read this directory.
 * Files are mode 600. The directory is mode 700.
 * A secret field on a day is rejected and never written.
 */

export const DEFAULT_USAGE_RETENTION_DAYS = 90;
export const USAGE_MAX_DAYS = 90;
export const USAGE_DEFAULT_DAYS = 7;
export const USAGE_MAX_TOP = 25;
export const USAGE_DEFAULT_TOP = 10;
export const USAGE_MAX_APPS_STORED = 50;
export const USAGE_STALE_AFTER_MS = 30 * 60 * 1000;
export const SYNC_DAY_LIMIT = 90;

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
const FILE_RE = /^(\d{4})-(\d{2})-(\d{2})\.json$/;
const META_FILE = "_meta.json";
const SECRET_KEYS = ["token", "wstoken", "authorization", "password", "secret"];

export interface UsageApp {
  packageName: string;
  label: string;
  foregroundMs: number;
  lastUsedAt: number;
}

export interface UsageDay {
  date: string;
  timeZone: string;
  screenOnMs: number;
  unlockCount: number;
  lastUsedAt: number | null;
  apps: UsageApp[];
  updatedAtMs: number;
}

export interface UsageMeta {
  enabled: boolean;
  paused: boolean;
  usageAccessGranted: boolean;
  timeZone: string;
  lastUsedAt: number | null;
  updatedAtMs: number;
  lastSyncedAt: number;
}

export interface UsageQueryResult {
  days: UsageDay[];
  meta: UsageMeta | null;
  lastUsedAt: number | null;
  stale: boolean;
  lastSyncedAt: number | null;
}

export type ParsedDay =
  | { ok: true; day: UsageDay }
  | { ok: false; id: string; error: string };

export interface IngestResult {
  accepted: string[];
  rejected: { id: string; error: string }[];
}

export function openUsageLog(
  env: NodeJS.ProcessEnv = process.env,
  now: () => number = Date.now
): UsageLog {
  const dir =
    env.PHONEPI_USAGE_DIR?.trim() || path.join(os.homedir(), ".phonepi", "usage");
  const raw = env.PHONEPI_USAGE_RETENTION_DAYS;
  let days = DEFAULT_USAGE_RETENTION_DAYS;
  if (raw != null && raw.trim() !== "") {
    const parsed = Number(raw);
    if (Number.isInteger(parsed) && parsed >= 1 && parsed <= 730) {
      days = parsed;
    }
  }
  return new UsageLog(dir, days, now);
}

export function msToMinutes(ms: number): number {
  if (!Number.isFinite(ms) || ms <= 0) return 0;
  return Math.round(ms / 60_000);
}

export class UsageLog {
  constructor(
    readonly dir: string,
    readonly retentionDays: number = DEFAULT_USAGE_RETENTION_DAYS,
    private readonly now: () => number = Date.now
  ) {}

  ingest(rawDays: unknown, rawMeta?: unknown): IngestResult {
    this.ensureDir();
    this.prune();
    if (rawMeta != null) {
      const meta = parseUsageMeta(rawMeta, this.now());
      if (meta) this.writeMeta({ ...meta, lastSyncedAt: this.now() });
    }
    if (!Array.isArray(rawDays)) {
      return {
        accepted: [],
        rejected: [{ id: "", error: "days must be an array" }],
      };
    }
    const accepted: string[] = [];
    const rejected: { id: string; error: string }[] = [];
    const limited = rawDays.slice(0, SYNC_DAY_LIMIT);
    if (rawDays.length > SYNC_DAY_LIMIT) {
      rejected.push({ id: "", error: `batch limit is ${SYNC_DAY_LIMIT}` });
    }
    for (const raw of limited) {
      const parsed = parseUsageDay(raw);
      if (!parsed.ok) {
        rejected.push({ id: parsed.id, error: parsed.error });
        continue;
      }
      if (parsed.day.date < this.cutoffDate()) {
        rejected.push({ id: parsed.day.date, error: "outside retention window" });
        continue;
      }
      const current = this.readDay(parsed.day.date);
      if (current && current.updatedAtMs > parsed.day.updatedAtMs) {
        accepted.push(parsed.day.date);
        continue;
      }
      this.writeDay(parsed.day);
      accepted.push(parsed.day.date);
    }
    return { accepted, rejected };
  }

  clear(): void {
    this.ensureDir();
    for (const file of this.listFiles()) {
      fs.rmSync(file, { force: true });
    }
    const meta = path.join(this.dir, META_FILE);
    if (fs.existsSync(meta)) fs.rmSync(meta, { force: true });
  }

  query(options: {
    fromDate: string | null;
    toDate: string | null;
    days: number;
    top: number;
    phoneConnected: boolean;
  }): UsageQueryResult {
    this.prune();
    const meta = this.readMeta();
    const all = this.readDays()
      .filter((day) => day.date >= this.cutoffDate())
      .sort((a, b) => a.date.localeCompare(b.date));
    let selected = all;
    if (options.fromDate || options.toDate) {
      selected = all.filter((day) => {
        if (options.fromDate && day.date < options.fromDate) return false;
        if (options.toDate && day.date > options.toDate) return false;
        return true;
      });
    } else {
      selected = all.slice(-options.days);
    }
    const days = selected.map((day) => ({
      ...day,
      apps: [...day.apps]
        .sort(
          (a, b) =>
            b.foregroundMs - a.foregroundMs ||
            a.packageName.localeCompare(b.packageName)
        )
        .slice(0, options.top),
    }));
    const lastUsedAt =
      days.reduce<number | null>((max, day) => {
        if (day.lastUsedAt == null) return max;
        if (max == null || day.lastUsedAt > max) return day.lastUsedAt;
        return max;
      }, meta?.lastUsedAt ?? null);
    const lastSyncedAt = meta?.lastSyncedAt ?? null;
    const stale =
      !options.phoneConnected ||
      lastSyncedAt == null ||
      this.now() - lastSyncedAt > USAGE_STALE_AFTER_MS;
    return { days, meta, lastUsedAt, stale, lastSyncedAt };
  }

  prune(): void {
    if (!fs.existsSync(this.dir)) return;
    const cutoff = this.cutoffDate();
    for (const file of this.listFiles()) {
      const name = path.basename(file);
      const match = FILE_RE.exec(name);
      if (!match) continue;
      const date = `${match[1]}-${match[2]}-${match[3]}`;
      if (date < cutoff) fs.rmSync(file, { force: true });
    }
  }

  private cutoffDate(): string {
    const d = new Date(this.now());
    const utc = new Date(
      Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate())
    );
    utc.setUTCDate(utc.getUTCDate() - this.retentionDays);
    return utc.toISOString().slice(0, 10);
  }

  private ensureDir(): void {
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    fs.chmodSync(this.dir, 0o700);
  }

  private listFiles(): string[] {
    if (!fs.existsSync(this.dir)) return [];
    return fs
      .readdirSync(this.dir)
      .filter((name) => FILE_RE.test(name))
      .map((name) => path.join(this.dir, name));
  }

  private writeDay(day: UsageDay): void {
    this.ensureDir();
    const file = path.join(this.dir, `${day.date}.json`);
    const existed = fs.existsSync(file);
    fs.writeFileSync(file, `${JSON.stringify(day)}\n`, {
      encoding: "utf8",
      mode: 0o600,
    });
    if (!existed) fs.chmodSync(file, 0o600);
  }

  private writeMeta(meta: UsageMeta): void {
    this.ensureDir();
    const file = path.join(this.dir, META_FILE);
    const existed = fs.existsSync(file);
    fs.writeFileSync(file, `${JSON.stringify(meta)}\n`, {
      encoding: "utf8",
      mode: 0o600,
    });
    if (!existed) fs.chmodSync(file, 0o600);
  }

  private readDay(date: string): UsageDay | null {
    const file = path.join(this.dir, `${date}.json`);
    if (!fs.existsSync(file)) return null;
    return this.parseFile(file);
  }

  private readDays(): UsageDay[] {
    const days: UsageDay[] = [];
    for (const file of this.listFiles()) {
      const day = this.parseFile(file);
      if (day) days.push(day);
    }
    return days;
  }

  private parseFile(file: string): UsageDay | null {
    const text = fs.readFileSync(file, "utf8");
    let raw: unknown;
    try {
      raw = JSON.parse(text);
    } catch {
      return null;
    }
    const parsed = parseUsageDay(raw);
    return parsed.ok ? parsed.day : null;
  }

  private readMeta(): UsageMeta | null {
    const file = path.join(this.dir, META_FILE);
    if (!fs.existsSync(file)) return null;
    try {
      const raw = JSON.parse(fs.readFileSync(file, "utf8"));
      return parseUsageMeta(raw, this.now());
    } catch {
      return null;
    }
  }
}

export function parseUsageDay(raw: unknown): ParsedDay {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, id: "", error: "day must be an object" };
  }
  const record = raw as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (SECRET_KEYS.includes(key.toLowerCase())) {
      return {
        ok: false,
        id: typeof record.date === "string" ? record.date.slice(0, 10) : "",
        error: "refusing day that carries a secret field",
      };
    }
  }
  const date = typeof record.date === "string" ? record.date.trim() : "";
  if (!DATE_RE.test(date) || !validIsoDate(date)) {
    return { ok: false, id: date.slice(0, 10), error: "date must be YYYY-MM-DD" };
  }
  const timeZone =
    typeof record.timeZone === "string" ? record.timeZone.trim().slice(0, 80) : "";
  if (!timeZone) {
    return { ok: false, id: date, error: "timeZone is required" };
  }
  const screenOnMs = finiteNumber(record.screenOnMs);
  const unlockCount = finiteNumber(record.unlockCount);
  if (screenOnMs == null || screenOnMs < 0 || screenOnMs > 7 * 24 * 60 * 60 * 1000) {
    return { ok: false, id: date, error: "screenOnMs out of range" };
  }
  if (unlockCount == null || unlockCount < 0 || unlockCount > 100_000) {
    return { ok: false, id: date, error: "unlockCount out of range" };
  }
  let lastUsedAt: number | null = null;
  if (record.lastUsedAt != null) {
    lastUsedAt = finiteNumber(record.lastUsedAt);
    if (lastUsedAt == null || lastUsedAt < 0) {
      return { ok: false, id: date, error: "lastUsedAt must be a positive number" };
    }
  }
  const updatedAtMs = finiteNumber(record.updatedAtMs);
  if (updatedAtMs == null || updatedAtMs < 0) {
    return { ok: false, id: date, error: "updatedAtMs must be a positive number" };
  }
  const apps = parseApps(record.apps, date);
  if (!apps.ok) return apps;
  return {
    ok: true,
    day: {
      date,
      timeZone,
      screenOnMs,
      unlockCount: Math.trunc(unlockCount),
      lastUsedAt,
      apps: apps.apps,
      updatedAtMs,
    },
  };
}

function parseApps(
  raw: unknown,
  date: string
): { ok: true; apps: UsageApp[] } | { ok: false; id: string; error: string } {
  if (raw == null) return { ok: true, apps: [] };
  if (!Array.isArray(raw)) {
    return { ok: false, id: date, error: "apps must be an array" };
  }
  const apps: UsageApp[] = [];
  for (const item of raw.slice(0, USAGE_MAX_APPS_STORED)) {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      return { ok: false, id: date, error: "app must be an object" };
    }
    const record = item as Record<string, unknown>;
    for (const key of Object.keys(record)) {
      if (SECRET_KEYS.includes(key.toLowerCase())) {
        return { ok: false, id: date, error: "refusing app that carries a secret field" };
      }
    }
    const packageName =
      typeof record.package === "string"
        ? record.package
        : typeof record.packageName === "string"
          ? record.packageName
          : "";
    const pkg = packageName.trim().slice(0, 256);
    if (!pkg) {
      return { ok: false, id: date, error: "package is required" };
    }
    const labelRaw = typeof record.label === "string" ? record.label.trim().slice(0, 80) : "";
    const foregroundMs = finiteNumber(record.foregroundMs);
    if (foregroundMs == null || foregroundMs < 0 || foregroundMs > 7 * 24 * 60 * 60 * 1000) {
      return { ok: false, id: date, error: "foregroundMs out of range" };
    }
    let lastUsedAt = 0;
    if (record.lastUsedAt != null) {
      const parsed = finiteNumber(record.lastUsedAt);
      if (parsed == null || parsed < 0) {
        return { ok: false, id: date, error: "app lastUsedAt must be a positive number" };
      }
      lastUsedAt = parsed;
    }
    apps.push({
      packageName: pkg,
      label: labelRaw || pkg,
      foregroundMs,
      lastUsedAt,
    });
  }
  return { ok: true, apps };
}

function parseUsageMeta(raw: unknown, nowMs: number): UsageMeta | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const record = raw as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (SECRET_KEYS.includes(key.toLowerCase())) return null;
  }
  const timeZone =
    typeof record.timeZone === "string" ? record.timeZone.trim().slice(0, 80) : "";
  if (!timeZone) return null;
  let lastUsedAt: number | null = null;
  if (record.lastUsedAt != null) {
    lastUsedAt = finiteNumber(record.lastUsedAt);
    if (lastUsedAt == null || lastUsedAt < 0) return null;
  }
  const updatedAtMs = finiteNumber(record.updatedAtMs) ?? nowMs;
  const lastSyncedAt = finiteNumber(record.lastSyncedAt) ?? nowMs;
  return {
    enabled: record.enabled !== false,
    paused: record.paused === true,
    usageAccessGranted: record.usageAccessGranted === true,
    timeZone,
    lastUsedAt,
    updatedAtMs,
    lastSyncedAt,
  };
}

function validIsoDate(date: string): boolean {
  const match = DATE_RE.exec(date);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const utc = new Date(Date.UTC(year, month - 1, day));
  return (
    utc.getUTCFullYear() === year &&
    utc.getUTCMonth() === month - 1 &&
    utc.getUTCDate() === day
  );
}

function finiteNumber(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return value;
}
