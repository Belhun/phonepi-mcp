import crypto from "crypto";
import fs from "fs";
import os from "os";
import path from "path";

/**
 * On-box running log of Pixel notifications captured by the PhonePi poller.
 * One JSON object per line under ~/.phonepi/notifications/YYYY-MM.jsonl,
 * filed by the month the box captured the entry. Directory 700, files 600.
 *
 * A line is a full snapshot of an entry. A later line with the same id and a
 * newer updatedAt replaces the earlier one when the log is loaded. Compaction
 * (startup + daily) rewrites the files with one line per live entry.
 *
 * Never log titles or texts from here. Callers log counts only.
 */

export const DEFAULT_NOTIFICATION_RETENTION_DAYS = 30;
export const NOTIFICATION_LOG_DEFAULT_LIMIT = 100;
export const NOTIFICATION_LOG_MAX_LIMIT = 500;
/** Same key + same title/text within this window is a repeat, not a new entry. */
export const REPEAT_COLLAPSE_WINDOW_MS = 12 * 60 * 60 * 1000;
/** Persist repeat counters at most this often per entry (removed flips always persist). */
export const REPEAT_PERSIST_EVERY_MS = 30 * 60 * 1000;

const FILE_RE = /^(\d{4})-(\d{2})\.jsonl$/;
const MAX_FIELD = 4000;
const DAY_MS = 24 * 60 * 60 * 1000;

export interface NotificationEntry {
  id: string;
  key: string;
  notificationId: number | null;
  package: string;
  appName: string | null;
  title: string;
  text: string;
  hiddenFields: string[];
  postedAt: number;
  postedAtIso: string;
  capturedAt: number;
  capturedAtIso: string;
  /** true when the phone reported it no longer active; null when unknown. */
  removed: boolean | null;
  /** Latest postedAt among collapsed identical re-posts. */
  lastPostedAt: number;
  /** Identical re-posts folded into this entry (0 = none seen). */
  repeatCount: number;
  updatedAt: number;
}

export interface NotificationIngestResult {
  fetched: number;
  added: number;
  duplicates: number;
  collapsed: number;
  updated: number;
  invalid: number;
}

export interface NotificationQuery {
  sinceCaptured: number | null;
  untilCaptured: number | null;
  postedSince: number | null;
  postedUntil: number | null;
  packageName: string | null;
  query: string | null;
  limit: number;
}

export interface NotificationQueryResult {
  entries: NotificationEntry[];
  hasMore: boolean;
  nextSince: number | null;
  totalInLog: number;
}

export function retentionDaysFromEnv(env: NodeJS.ProcessEnv = process.env): number {
  const raw = env.PHONEPI_NOTIFICATION_RETENTION_DAYS;
  if (raw == null || raw.trim() === "") return DEFAULT_NOTIFICATION_RETENTION_DAYS;
  const parsed = Number(raw);
  if (Number.isInteger(parsed) && parsed >= 1 && parsed <= 365) return parsed;
  return DEFAULT_NOTIFICATION_RETENTION_DAYS;
}

export function openNotificationLog(
  env: NodeJS.ProcessEnv = process.env,
  now: () => number = Date.now
): NotificationLog {
  const dir =
    env.PHONEPI_NOTIFICATION_DIR?.trim() ||
    path.join(os.homedir(), ".phonepi", "notifications");
  return new NotificationLog(dir, retentionDaysFromEnv(env), now);
}

export class NotificationLog {
  private entries = new Map<string, NotificationEntry>();
  /** Latest entry id per notification key (by postedAt). */
  private byKey = new Map<string, string>();
  private lastCapturedAt = 0;
  private maxPosted = 0;
  private loaded = false;

  constructor(
    readonly dir: string,
    readonly retentionDays: number = DEFAULT_NOTIFICATION_RETENTION_DAYS,
    private readonly now: () => number = Date.now
  ) {}

  /** Highest postedAt seen (0 when empty). The poller uses it as its cursor. */
  maxPostedAt(): number {
    this.ensureLoaded();
    return this.maxPosted;
  }

  size(): number {
    this.ensureLoaded();
    return this.entries.size;
  }

  ingest(raw: unknown): NotificationIngestResult {
    this.ensureLoaded();
    const result: NotificationIngestResult = {
      fetched: 0,
      added: 0,
      duplicates: 0,
      collapsed: 0,
      updated: 0,
      invalid: 0,
    };
    if (!Array.isArray(raw)) return result;
    result.fetched = raw.length;
    const parsed: ParsedNotification[] = [];
    for (const item of raw) {
      const p = parseNotification(item);
      if (p) parsed.push(p);
      else result.invalid += 1;
    }
    parsed.sort((a, b) => a.postedAt - b.postedAt);
    const cutoff = this.cutoffMs();
    const toWrite = new Map<string, NotificationEntry>();
    const t = this.now();
    for (const n of parsed) {
      if (n.postedAt < cutoff) {
        result.invalid += 1;
        continue;
      }
      if (n.postedAt > this.maxPosted) this.maxPosted = n.postedAt;
      const id = entryId(n);
      const existing = this.entries.get(id);
      if (existing) {
        result.duplicates += 1;
        if (n.removed != null && existing.removed !== n.removed) {
          existing.removed = n.removed;
          existing.updatedAt = t;
          toWrite.set(existing.id, existing);
          result.updated += 1;
        }
        continue;
      }
      const prevId = this.byKey.get(n.key);
      const prev = prevId ? this.entries.get(prevId) : undefined;
      if (
        prev &&
        prev.package === n.package &&
        prev.title === n.title &&
        prev.text === n.text &&
        n.postedAt >= prev.postedAt &&
        n.postedAt - prev.lastPostedAt <= REPEAT_COLLAPSE_WINDOW_MS
      ) {
        result.collapsed += 1;
        let persist = false;
        if (n.postedAt > prev.lastPostedAt) {
          prev.lastPostedAt = n.postedAt;
          prev.repeatCount += 1;
          if (t - prev.updatedAt >= REPEAT_PERSIST_EVERY_MS) persist = true;
        }
        if (n.removed != null && prev.removed !== n.removed) {
          prev.removed = n.removed;
          persist = true;
          result.updated += 1;
        }
        if (persist) {
          prev.updatedAt = t;
          toWrite.set(prev.id, prev);
        }
        continue;
      }
      const capturedAt = Math.max(t, this.lastCapturedAt + 1);
      this.lastCapturedAt = capturedAt;
      const entry: NotificationEntry = {
        id,
        key: n.key,
        notificationId: n.notificationId,
        package: n.package,
        appName: n.appName,
        title: n.title,
        text: n.text,
        hiddenFields: n.hiddenFields,
        postedAt: n.postedAt,
        postedAtIso: new Date(n.postedAt).toISOString(),
        capturedAt,
        capturedAtIso: new Date(capturedAt).toISOString(),
        removed: n.removed,
        lastPostedAt: n.postedAt,
        repeatCount: 0,
        updatedAt: capturedAt,
      };
      this.entries.set(id, entry);
      if (!prev || n.postedAt >= prev.postedAt) this.byKey.set(n.key, id);
      toWrite.set(id, entry);
      result.added += 1;
    }
    if (toWrite.size > 0) this.appendLines([...toWrite.values()]);
    return result;
  }

  query(q: NotificationQuery): NotificationQueryResult {
    this.ensureLoaded();
    const cutoff = this.cutoffMs();
    const tokens = (q.query ?? "")
      .toLowerCase()
      .split(/\s+/)
      .filter((part) => part.length > 0);
    const pkg = q.packageName?.toLowerCase() ?? null;
    let matches = [...this.entries.values()].filter((e) => {
      if (e.lastPostedAt < cutoff) return false;
      if (q.sinceCaptured != null && e.capturedAt <= q.sinceCaptured) return false;
      if (q.untilCaptured != null && e.capturedAt > q.untilCaptured) return false;
      if (q.postedSince != null && e.postedAt < q.postedSince) return false;
      if (q.postedUntil != null && e.postedAt > q.postedUntil) return false;
      if (pkg && e.package.toLowerCase() !== pkg) return false;
      if (tokens.length) {
        const hay = `${e.appName ?? ""} ${e.package} ${e.title} ${e.text}`.toLowerCase();
        if (!tokens.every((tok) => hay.includes(tok))) return false;
      }
      return true;
    });
    matches.sort((a, b) => a.capturedAt - b.capturedAt);
    let hasMore = false;
    if (matches.length > q.limit) {
      hasMore = true;
      // With a since cursor, page forward from the oldest so nothing is skipped.
      // Without one, return the newest entries.
      matches = q.sinceCaptured != null ? matches.slice(0, q.limit) : matches.slice(-q.limit);
    }
    const last = matches[matches.length - 1];
    const nextSince = last
      ? last.capturedAt
      : q.sinceCaptured ?? (this.lastCapturedAt || null);
    return {
      entries: matches.map((e) => ({ ...e, hiddenFields: [...e.hiddenFields] })),
      hasMore,
      nextSince,
      totalInLog: this.entries.size,
    };
  }

  /** Drop entries past retention and rewrite the files with one line per entry. */
  compact(): { kept: number; dropped: number } {
    this.ensureLoaded();
    const cutoff = this.cutoffMs();
    let dropped = 0;
    for (const [id, e] of this.entries) {
      if (e.lastPostedAt < cutoff) {
        this.entries.delete(id);
        dropped += 1;
      }
    }
    this.rebuildKeyIndex();
    this.ensureDir();
    const byMonth = new Map<string, NotificationEntry[]>();
    for (const e of this.entries.values()) {
      const name = monthName(e.capturedAt);
      const list = byMonth.get(name) ?? [];
      list.push(e);
      byMonth.set(name, list);
    }
    for (const file of this.listFiles()) {
      if (!byMonth.has(path.basename(file))) fs.rmSync(file, { force: true });
    }
    for (const [name, list] of byMonth) {
      list.sort((a, b) => a.capturedAt - b.capturedAt);
      const target = path.join(this.dir, name);
      const tmp = `${target}.tmp-${process.pid}`;
      fs.writeFileSync(tmp, list.map((e) => JSON.stringify(e)).join("\n") + "\n", {
        encoding: "utf8",
        mode: 0o600,
      });
      fs.chmodSync(tmp, 0o600);
      fs.renameSync(tmp, target);
    }
    return { kept: this.entries.size, dropped };
  }

  private ensureLoaded(): void {
    if (this.loaded) return;
    this.loaded = true;
    for (const file of this.listFiles()) {
      let text: string;
      try {
        text = fs.readFileSync(file, "utf8");
      } catch {
        continue;
      }
      for (const line of text.split("\n")) {
        if (!line.trim()) continue;
        let raw: unknown;
        try {
          raw = JSON.parse(line);
        } catch {
          continue;
        }
        const e = parseStoredEntry(raw);
        if (!e) continue;
        const prev = this.entries.get(e.id);
        if (!prev || e.updatedAt >= prev.updatedAt) this.entries.set(e.id, e);
      }
    }
    this.rebuildKeyIndex();
  }

  private rebuildKeyIndex(): void {
    this.byKey.clear();
    let lastCaptured = this.lastCapturedAt;
    let maxPosted = 0;
    for (const e of this.entries.values()) {
      const prevId = this.byKey.get(e.key);
      const prev = prevId ? this.entries.get(prevId) : undefined;
      if (!prev || e.postedAt >= prev.postedAt) this.byKey.set(e.key, e.id);
      if (e.capturedAt > lastCaptured) lastCaptured = e.capturedAt;
      if (e.lastPostedAt > maxPosted) maxPosted = e.lastPostedAt;
    }
    this.lastCapturedAt = lastCaptured;
    this.maxPosted = Math.max(this.maxPosted, maxPosted);
  }

  private cutoffMs(): number {
    return this.now() - this.retentionDays * DAY_MS;
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
      .sort()
      .map((name) => path.join(this.dir, name));
  }

  private appendLines(list: NotificationEntry[]): void {
    this.ensureDir();
    const byFile = new Map<string, string[]>();
    for (const e of list) {
      const file = path.join(this.dir, monthName(e.capturedAt));
      const lines = byFile.get(file) ?? [];
      lines.push(JSON.stringify(e));
      byFile.set(file, lines);
    }
    for (const [file, lines] of byFile) {
      const existed = fs.existsSync(file);
      fs.appendFileSync(file, lines.join("\n") + "\n", { encoding: "utf8", mode: 0o600 });
      if (!existed) fs.chmodSync(file, 0o600);
    }
  }
}

interface ParsedNotification {
  key: string;
  notificationId: number | null;
  package: string;
  appName: string | null;
  title: string;
  text: string;
  hiddenFields: string[];
  postedAt: number;
  removed: boolean | null;
}

export function parseNotification(raw: unknown): ParsedNotification | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const r = raw as Record<string, unknown>;
  const pkg = typeof r.package === "string" ? r.package.trim() : "";
  if (!pkg || pkg.length > 200) return null;
  let postedAt = typeof r.postedAt === "number" && Number.isFinite(r.postedAt) ? r.postedAt : NaN;
  if (Number.isNaN(postedAt) && typeof r.time === "string") postedAt = Date.parse(r.time);
  if (!Number.isFinite(postedAt) || postedAt <= 0) return null;
  const notificationId =
    typeof r.notificationId === "number" && Number.isFinite(r.notificationId)
      ? r.notificationId
      : null;
  const key =
    typeof r.key === "string" && r.key.trim()
      ? r.key.trim().slice(0, 400)
      : `${pkg}|${notificationId ?? "?"}`;
  const hiddenFields: string[] = [];
  if (Array.isArray(r.hiddenFields)) {
    for (const h of r.hiddenFields) {
      const field =
        h && typeof h === "object" && typeof (h as Record<string, unknown>).field === "string"
          ? String((h as Record<string, unknown>).field)
          : typeof h === "string"
            ? h
            : null;
      if (field && !hiddenFields.includes(field)) hiddenFields.push(field.slice(0, 40));
    }
  }
  return {
    key,
    notificationId,
    package: pkg,
    appName: typeof r.appName === "string" && r.appName.trim() ? r.appName.trim().slice(0, 200) : null,
    title: typeof r.title === "string" ? r.title.slice(0, MAX_FIELD) : "",
    text: typeof r.text === "string" ? r.text.slice(0, MAX_FIELD) : "",
    hiddenFields,
    postedAt,
    removed: r.isActive === true ? false : r.isActive === false ? true : typeof r.removed === "boolean" ? r.removed : null,
  };
}

function entryId(n: ParsedNotification): string {
  return crypto
    .createHash("sha256")
    .update(`${n.key}\u0000${n.postedAt}\u0000${n.title}\u0000${n.text}`)
    .digest("hex")
    .slice(0, 24);
}

function parseStoredEntry(raw: unknown): NotificationEntry | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const r = raw as Record<string, unknown>;
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);
  if (typeof r.id !== "string" || !/^[0-9a-f]{24}$/.test(r.id)) return null;
  if (typeof r.key !== "string" || typeof r.package !== "string") return null;
  const postedAt = num(r.postedAt);
  const capturedAt = num(r.capturedAt);
  if (postedAt == null || capturedAt == null) return null;
  return {
    id: r.id,
    key: r.key,
    notificationId: num(r.notificationId),
    package: r.package,
    appName: typeof r.appName === "string" ? r.appName : null,
    title: typeof r.title === "string" ? r.title : "",
    text: typeof r.text === "string" ? r.text : "",
    hiddenFields: Array.isArray(r.hiddenFields)
      ? r.hiddenFields.filter((h): h is string => typeof h === "string")
      : [],
    postedAt,
    postedAtIso: new Date(postedAt).toISOString(),
    capturedAt,
    capturedAtIso: new Date(capturedAt).toISOString(),
    removed: typeof r.removed === "boolean" ? r.removed : null,
    lastPostedAt: Math.max(num(r.lastPostedAt) ?? postedAt, postedAt),
    repeatCount: Math.max(0, Math.floor(num(r.repeatCount) ?? 0)),
    updatedAt: num(r.updatedAt) ?? capturedAt,
  };
}

function monthName(ms: number): string {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}.jsonl`;
}
