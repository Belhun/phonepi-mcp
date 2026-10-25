import fs from "fs";
import path from "path";
import {
  atomicWriteFile,
  ensureBoxDir,
  removeFile,
  resolveBoxDir,
  safeReadFile,
} from "./atomic-file.js";
import { parseIngestTs } from "./ingest-time.js";

/**
 * On-box copy of PhonePi health summaries (daily totals only).
 * latest.json is the current snapshot. YYYY-MM-DD.json is a retained day.
 * Directory 700, files 600. The phone is the writer. Bots only read this.
 * A secret field or a raw heart-rate stream is rejected and never written.
 */

export const DEFAULT_HEALTH_RETENTION_DAYS = 30;
export const HEALTH_READ_PER_MINUTE = 30;
export const SOURCE_LAG_NOTE =
  "Samsung Health can lag minutes to hours before Health Connect has the latest sleep and heart-rate data.";

const DAY_MS = 24 * 60 * 60 * 1000;
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})\.json$/;
const SECRET_KEYS = ["token", "wstoken", "authorization", "password", "secret"];
const RAW_STREAM_KEYS = [
  "heartratesamples",
  "bpmsamples",
  "samples",
  "heartratestream",
  "rawheartrate",
];
const WORKOUT_CAP = 10;
const STEP_DAYS_CAP = 7;

export interface SleepStages {
  awakeMinutes: number;
  lightMinutes: number;
  deepMinutes: number;
  remMinutes: number;
  unspecifiedMinutes: number;
}

export interface LastNightSleep {
  startMs: number;
  startIso: string;
  endMs: number;
  endIso: string;
  totalMinutes: number;
  stages: SleepStages;
}

export interface StepDay {
  date: string;
  count: number;
}

export interface WorkoutDay {
  startMs: number;
  startIso: string;
  endMs: number;
  endIso: string;
  durationMinutes: number;
  type: string;
  title: string | null;
}

export interface HealthPermissions {
  sleep: boolean;
  steps: boolean;
  heartRate: boolean;
  restingHeartRate: boolean;
  exercise: boolean;
  oxygenSaturation: boolean;
  background: boolean;
  history: boolean;
}

export interface HealthSummary {
  syncedAtMs: number;
  syncedAtIso: string;
  timezone: string;
  enabled: boolean;
  paused: boolean;
  permissions: HealthPermissions;
  lastNightSleep: LastNightSleep | null;
  lastNightSleepEndMs: number | null;
  lastNightSleepEndIso: string | null;
  steps: {
    today: number;
    last7Days: StepDay[];
    last7DaysTotal: number;
  };
  heartRate: {
    restingBpm: number | null;
    averageBpm: number | null;
  };
  workouts: WorkoutDay[];
  oxygenSaturationPct: number | null;
  sourceLagNote: string;
}

export type ParsedSummary =
  | { ok: true; summary: HealthSummary }
  | { ok: false; error: string };

export interface IngestResult {
  ok: boolean;
  error?: string;
}

export function openHealthLog(
  env: NodeJS.ProcessEnv = process.env,
  now: () => number = Date.now
): HealthLog {
  const dir = resolveBoxDir("PHONEPI_HEALTH_DIR", "health", env);
  const raw = env.PHONEPI_HEALTH_RETENTION_DAYS;
  let days = DEFAULT_HEALTH_RETENTION_DAYS;
  if (raw != null && raw.trim() !== "") {
    const parsed = Number(raw);
    if (Number.isInteger(parsed) && parsed >= 1 && parsed <= 365) {
      days = parsed;
    }
  }
  return new HealthLog(dir, days, now);
}

export class HealthLog {
  constructor(
    readonly dir: string,
    readonly retentionDays: number = DEFAULT_HEALTH_RETENTION_DAYS,
    private readonly now: () => number = Date.now
  ) {}

  ingest(raw: unknown): IngestResult {
    this.ensureDir();
    const parsed = parseHealthSummary(raw, this.now());
    if (!parsed.ok) return { ok: false, error: parsed.error };
    const current = this.peekLatest();
    if (current && current.syncedAtMs > parsed.summary.syncedAtMs) {
      this.writeDay(parsed.summary);
      return { ok: true };
    }
    this.writeLatest(parsed.summary);
    this.writeDay(parsed.summary);
    return { ok: true };
  }

  peekLatest(): HealthSummary | null {
    const file = path.join(this.dir, "latest.json");
    if (!fs.existsSync(file)) return null;
    try {
      const raw = JSON.parse(safeReadFile(file));
      const parsed = parseHealthSummary(raw, this.now());
      return parsed.ok ? parsed.summary : null;
    } catch {
      return null;
    }
  }

  readLatest(): HealthSummary | null {
    this.prune();
    return this.peekLatest();
  }

  emptySummary(): HealthSummary {
    return emptySummary();
  }

  clear(): void {
    this.ensureDir();
    for (const name of fs.readdirSync(this.dir)) {
      if (name === "latest.json" || DATE_RE.test(name)) {
        fs.rmSync(path.join(this.dir, name), { force: true });
      }
    }
  }

  prune(): void {
    if (!fs.existsSync(this.dir)) return;
    const cutoff = this.cutoffMs();
    for (const name of fs.readdirSync(this.dir)) {
      const match = DATE_RE.exec(name);
      if (!match) continue;
      const start = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
      if (start < cutoff) fs.rmSync(path.join(this.dir, name), { force: true });
    }
    const latest = path.join(this.dir, "latest.json");
    if (fs.existsSync(latest)) {
      try {
        const raw = JSON.parse(safeReadFile(latest));
        const parsed = parseHealthSummary(raw, this.now());
        if (!parsed.ok || parsed.summary.syncedAtMs < cutoff) {
          fs.rmSync(latest, { force: true });
        }
      } catch {
        fs.rmSync(latest, { force: true });
      }
    }
  }

  private cutoffMs(): number {
    const d = new Date(this.now());
    const utc = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
    return utc - this.retentionDays * DAY_MS;
  }

  private ensureDir(): void {
    ensureBoxDir(this.dir);
  }

  private writeLatest(summary: HealthSummary): void {
    this.writeFile("latest.json", summary);
  }

  private writeDay(summary: HealthSummary): void {
    this.writeFile(`${utcDate(summary.syncedAtMs)}.json`, summary);
  }

  private writeFile(name: string, summary: HealthSummary): void {
    this.ensureDir();
    const file = path.join(this.dir, name);
    atomicWriteFile(file, `${JSON.stringify(summary)}\n`);
  }
}

export function parseHealthSummary(
  raw: unknown,
  nowMs: number = Date.now()
): ParsedSummary {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "summary must be an object" };
  }
  const record = raw as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    const lower = key.toLowerCase();
    if (SECRET_KEYS.includes(lower)) {
      return { ok: false, error: "refusing summary that carries a secret field" };
    }
    if (RAW_STREAM_KEYS.includes(lower)) {
      return { ok: false, error: "refusing raw heart-rate stream or samples" };
    }
  }
  const synced = parseIngestTs(record.syncedAtMs, nowMs);
  if (!synced.ok) {
    return { ok: false, error: synced.error };
  }
  const syncedAtMs = synced.ts;
  const timezone = optionalText(record.timezone, 80);
  if (timezone === "bad") return { ok: false, error: "timezone is too long" };
  const enabled = record.enabled === true;
  const paused = record.paused === true;
  const permissions = parsePermissions(record.permissions);
  if (!permissions.ok) return permissions;
  const sleep = parseSleep(record.lastNightSleep);
  if (!sleep.ok) return sleep;
  const endMs = sleep.value?.endMs ?? null;
  const steps = parseSteps(record.steps);
  if (!steps.ok) return steps;
  const heartRate = parseHeartRate(record.heartRate);
  if (!heartRate.ok) return heartRate;
  const workouts = parseWorkouts(record.workouts);
  if (!workouts.ok) return workouts;
  let oxygen: number | null = null;
  if (record.oxygenSaturationPct != null) {
    oxygen = finiteNumber(record.oxygenSaturationPct);
    if (oxygen == null || oxygen < 0 || oxygen > 100) {
      return { ok: false, error: "oxygenSaturationPct must be 0-100" };
    }
    oxygen = Math.round(oxygen);
  }
  return {
    ok: true,
    summary: {
      syncedAtMs,
      syncedAtIso: iso(syncedAtMs),
      timezone: timezone ?? "UTC",
      enabled,
      paused,
      permissions: permissions.value,
      lastNightSleep: sleep.value,
      lastNightSleepEndMs: endMs,
      lastNightSleepEndIso: endMs == null ? null : iso(endMs),
      steps: steps.value,
      heartRate: heartRate.value,
      workouts: workouts.value,
      oxygenSaturationPct: oxygen,
      sourceLagNote: SOURCE_LAG_NOTE,
    },
  };
}

function emptySummary(): HealthSummary {
  return {
    syncedAtMs: 0,
    syncedAtIso: "",
    timezone: "UTC",
    enabled: true,
    paused: false,
    permissions: {
      sleep: false,
      steps: false,
      heartRate: false,
      restingHeartRate: false,
      exercise: false,
      oxygenSaturation: false,
      background: false,
      history: false,
    },
    lastNightSleep: null,
    lastNightSleepEndMs: null,
    lastNightSleepEndIso: null,
    steps: { today: 0, last7Days: [], last7DaysTotal: 0 },
    heartRate: { restingBpm: null, averageBpm: null },
    workouts: [],
    oxygenSaturationPct: null,
    sourceLagNote: SOURCE_LAG_NOTE,
  };
}

function parsePermissions(
  raw: unknown
): { ok: true; value: HealthPermissions } | { ok: false; error: string } {
  const empty: HealthPermissions = {
    sleep: false,
    steps: false,
    heartRate: false,
    restingHeartRate: false,
    exercise: false,
    oxygenSaturation: false,
    background: false,
    history: false,
  };
  if (raw == null) return { ok: true, value: empty };
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "permissions must be an object" };
  }
  const record = raw as Record<string, unknown>;
  return {
    ok: true,
    value: {
      sleep: record.sleep === true,
      steps: record.steps === true,
      heartRate: record.heartRate === true,
      restingHeartRate: record.restingHeartRate === true,
      exercise: record.exercise === true,
      oxygenSaturation: record.oxygenSaturation === true,
      background: record.background === true,
      history: record.history === true,
    },
  };
}

function parseSleep(
  raw: unknown
): { ok: true; value: LastNightSleep | null } | { ok: false; error: string } {
  if (raw == null) return { ok: true, value: null };
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "lastNightSleep must be an object" };
  }
  const record = raw as Record<string, unknown>;
  const startMs = finiteNumber(record.startMs);
  const endMs = finiteNumber(record.endMs);
  if (startMs == null || endMs == null || startMs < 0 || endMs < startMs) {
    return { ok: false, error: "lastNightSleep start/end are invalid" };
  }
  const totalMinutes = finiteNumber(record.totalMinutes);
  const stagesRaw = record.stages;
  const stages = parseStages(stagesRaw);
  if (!stages.ok) return stages;
  const total =
    totalMinutes != null && totalMinutes >= 0
      ? Math.round(totalMinutes)
      : Math.round((endMs - startMs) / 60000);
  return {
    ok: true,
    value: {
      startMs,
      startIso: iso(startMs),
      endMs,
      endIso: iso(endMs),
      totalMinutes: total,
      stages: stages.value,
    },
  };
}

function parseStages(
  raw: unknown
): { ok: true; value: SleepStages } | { ok: false; error: string } {
  const empty: SleepStages = {
    awakeMinutes: 0,
    lightMinutes: 0,
    deepMinutes: 0,
    remMinutes: 0,
    unspecifiedMinutes: 0,
  };
  if (raw == null) return { ok: true, value: empty };
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "sleep stages must be an object" };
  }
  const record = raw as Record<string, unknown>;
  const awake = minuteField(record.awakeMinutes);
  const light = minuteField(record.lightMinutes);
  const deep = minuteField(record.deepMinutes);
  const rem = minuteField(record.remMinutes);
  const unspecified = minuteField(record.unspecifiedMinutes);
  if (
    awake === "bad" ||
    light === "bad" ||
    deep === "bad" ||
    rem === "bad" ||
    unspecified === "bad"
  ) {
    return { ok: false, error: "sleep stage minutes are invalid" };
  }
  return {
    ok: true,
    value: {
      awakeMinutes: awake,
      lightMinutes: light,
      deepMinutes: deep,
      remMinutes: rem,
      unspecifiedMinutes: unspecified,
    },
  };
}

function parseSteps(
  raw: unknown
):
  | { ok: true; value: HealthSummary["steps"] }
  | { ok: false; error: string } {
  const empty = { today: 0, last7Days: [] as StepDay[], last7DaysTotal: 0 };
  if (raw == null) return { ok: true, value: empty };
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "steps must be an object" };
  }
  const record = raw as Record<string, unknown>;
  const today = finiteNumber(record.today);
  if (today == null || today < 0 || today > 1_000_000) {
    return { ok: false, error: "steps.today is invalid" };
  }
  const days: StepDay[] = [];
  if (record.last7Days != null) {
    if (!Array.isArray(record.last7Days)) {
      return { ok: false, error: "steps.last7Days must be an array" };
    }
    for (const row of record.last7Days.slice(0, STEP_DAYS_CAP)) {
      if (!row || typeof row !== "object" || Array.isArray(row)) {
        return { ok: false, error: "step day must be an object" };
      }
      const day = row as Record<string, unknown>;
      const date = optionalText(day.date, 10);
      const count = finiteNumber(day.count);
      if (date === "bad" || date == null || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
        return { ok: false, error: "step day date is invalid" };
      }
      if (count == null || count < 0 || count > 1_000_000) {
        return { ok: false, error: "step day count is invalid" };
      }
      days.push({ date, count: Math.round(count) });
    }
  }
  let total = finiteNumber(record.last7DaysTotal);
  if (total == null) total = days.reduce((sum, day) => sum + day.count, 0);
  if (total < 0 || total > 7_000_000) {
    return { ok: false, error: "steps.last7DaysTotal is invalid" };
  }
  return {
    ok: true,
    value: {
      today: Math.round(today),
      last7Days: days,
      last7DaysTotal: Math.round(total),
    },
  };
}

function parseHeartRate(
  raw: unknown
):
  | { ok: true; value: HealthSummary["heartRate"] }
  | { ok: false; error: string } {
  const empty = { restingBpm: null as number | null, averageBpm: null as number | null };
  if (raw == null) return { ok: true, value: empty };
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "heartRate must be an object" };
  }
  const record = raw as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (RAW_STREAM_KEYS.includes(key.toLowerCase())) {
      return { ok: false, error: "refusing raw heart-rate stream or samples" };
    }
  }
  const resting = optionalBpm(record.restingBpm);
  const average = optionalBpm(record.averageBpm);
  if (resting === "bad" || average === "bad") {
    return { ok: false, error: "heartRate bpm values are invalid" };
  }
  return { ok: true, value: { restingBpm: resting, averageBpm: average } };
}

function parseWorkouts(
  raw: unknown
): { ok: true; value: WorkoutDay[] } | { ok: false; error: string } {
  if (raw == null) return { ok: true, value: [] };
  if (!Array.isArray(raw)) return { ok: false, error: "workouts must be an array" };
  const workouts: WorkoutDay[] = [];
  for (const row of raw.slice(0, WORKOUT_CAP)) {
    if (!row || typeof row !== "object" || Array.isArray(row)) {
      return { ok: false, error: "workout must be an object" };
    }
    const record = row as Record<string, unknown>;
    for (const key of Object.keys(record)) {
      if (RAW_STREAM_KEYS.includes(key.toLowerCase()) || SECRET_KEYS.includes(key.toLowerCase())) {
        return { ok: false, error: "refusing raw heart-rate stream or samples" };
      }
    }
    const startMs = finiteNumber(record.startMs);
    const endMs = finiteNumber(record.endMs);
    if (startMs == null || endMs == null || startMs < 0 || endMs < startMs) {
      return { ok: false, error: "workout start/end are invalid" };
    }
    let duration = finiteNumber(record.durationMinutes);
    if (duration == null) duration = (endMs - startMs) / 60000;
    if (duration < 0 || duration > 24 * 60) {
      return { ok: false, error: "workout durationMinutes is invalid" };
    }
    const type = optionalText(record.type, 40);
    if (type === "bad" || type == null) {
      return { ok: false, error: "workout type is invalid" };
    }
    const title = optionalText(record.title, 80);
    if (title === "bad") return { ok: false, error: "workout title is too long" };
    workouts.push({
      startMs,
      startIso: iso(startMs),
      endMs,
      endIso: iso(endMs),
      durationMinutes: Math.round(duration),
      type,
      title,
    });
  }
  return { ok: true, value: workouts };
}

function minuteField(value: unknown): number | "bad" {
  if (value == null) return 0;
  const n = finiteNumber(value);
  if (n == null || n < 0 || n > 24 * 60) return "bad";
  return Math.round(n);
}

function optionalBpm(value: unknown): number | null | "bad" {
  if (value == null) return null;
  const n = finiteNumber(value);
  if (n == null || n < 20 || n > 250) return "bad";
  return Math.round(n);
}

function finiteNumber(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return value;
}

function optionalText(value: unknown, max: number): string | null | "bad" {
  if (value == null) return null;
  if (typeof value !== "string") return "bad";
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (trimmed.length > max) return "bad";
  return trimmed;
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

function utcDate(ms: number): string {
  const iso = new Date(ms).toISOString();
  if (iso === "Invalid Date") {
    throw new Error("invalid timestamp for filename");
  }
  return iso.slice(0, 10);
}
