import { NotificationLog } from "./notification-log.js";

/**
 * Polls the phone's get_notifications every intervalMs while it is connected
 * and stores the results in the box NotificationLog. Backfills right after a
 * (re)connect. Logs counts only, never notification contents.
 */

export const DEFAULT_POLL_MS = 60_000;
export const POLL_OVERLAP_MS = 10 * 60 * 1000;
export const PHONE_FETCH_LIMIT = 200;
const CHECK_EVERY_MS = 15_000;
const COMPACT_EVERY_MS = 24 * 60 * 60 * 1000;

export function pollIntervalFromEnv(env: NodeJS.ProcessEnv = process.env): number {
  const raw = env.PHONEPI_NOTIFICATION_POLL_SEC;
  if (raw == null || raw.trim() === "") return DEFAULT_POLL_MS;
  const n = Number(raw);
  if (Number.isInteger(n) && n >= 30 && n <= 3600) return n * 1000;
  return DEFAULT_POLL_MS;
}

export function captureEnabledFromEnv(env: NodeJS.ProcessEnv = process.env): boolean {
  const raw = (env.PHONEPI_NOTIFICATION_CAPTURE ?? "").trim().toLowerCase();
  return !(raw === "off" || raw === "0" || raw === "false");
}

export interface NotificationPollerOptions {
  log: NotificationLog;
  isConnected: () => boolean;
  fetch: (args: Record<string, unknown>) => Promise<unknown>;
  intervalMs?: number;
  logLine?: (line: string) => void;
  now?: () => number;
}

export interface CaptureStatus {
  enabled: boolean;
  intervalSec: number;
  lastPollAt: string | null;
  lastPollOk: boolean | null;
  lastError: string | null;
  lastAddedAt: string | null;
  phoneListenerConnected: boolean | null;
  phoneShareAll: boolean | null;
}

export class NotificationPoller {
  private timer: NodeJS.Timeout | null = null;
  private compactTimer: NodeJS.Timeout | null = null;
  private wasConnected = false;
  private lastPollMs = 0;
  private inFlight = false;
  private readonly intervalMs: number;
  private readonly logLine: (line: string) => void;
  private readonly now: () => number;
  private status: CaptureStatus;

  constructor(private readonly opts: NotificationPollerOptions) {
    this.intervalMs = opts.intervalMs ?? DEFAULT_POLL_MS;
    this.logLine = opts.logLine ?? ((line) => console.error(line));
    this.now = opts.now ?? Date.now;
    this.status = {
      enabled: true,
      intervalSec: Math.round(this.intervalMs / 1000),
      lastPollAt: null,
      lastPollOk: null,
      lastError: null,
      lastAddedAt: null,
      phoneListenerConnected: null,
      phoneShareAll: null,
    };
  }

  captureStatus(): CaptureStatus {
    return { ...this.status };
  }

  start(): void {
    this.compactNow("startup");
    this.timer = setInterval(() => void this.tick(), CHECK_EVERY_MS);
    this.timer.unref?.();
    this.compactTimer = setInterval(() => this.compactNow("daily"), COMPACT_EVERY_MS);
    this.compactTimer.unref?.();
    setTimeout(() => void this.tick(), 5_000).unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    if (this.compactTimer) clearInterval(this.compactTimer);
    this.timer = null;
    this.compactTimer = null;
  }

  compactNow(reason: string): void {
    try {
      const r = this.opts.log.compact();
      this.logLine(`[notification-log] compact (${reason}) kept=${r.kept} dropped=${r.dropped}`);
    } catch (err) {
      this.logLine(`[notification-log] compact failed: ${errorText(err)}`);
    }
  }

  /** One scheduling step. Exposed for tests. */
  async tick(): Promise<void> {
    const connected = this.opts.isConnected();
    if (!connected) {
      this.wasConnected = false;
      return;
    }
    const reconnect = !this.wasConnected;
    this.wasConnected = true;
    if (!reconnect && this.now() - this.lastPollMs < this.intervalMs) return;
    await this.pollOnce(reconnect ? "backfill" : "poll");
  }

  async pollOnce(reason: string): Promise<void> {
    if (this.inFlight) return;
    this.inFlight = true;
    this.lastPollMs = this.now();
    this.status.lastPollAt = new Date(this.lastPollMs).toISOString();
    try {
      const max = this.opts.log.maxPostedAt();
      const since = max > 0 ? Math.max(0, max - POLL_OVERLAP_MS) : 0;
      const raw = await this.opts.fetch({ since, limit: PHONE_FETCH_LIMIT });
      const data = typeof raw === "string" ? safeParse(raw) : raw;
      const record = data && typeof data === "object" ? (data as Record<string, unknown>) : {};
      if (record.status === "error") {
        throw new Error(typeof record.error === "string" ? record.error.slice(0, 160) : "phone error");
      }
      const list = Array.isArray(record.notifications) ? record.notifications : [];
      if (typeof record.listenerConnected === "boolean") {
        this.status.phoneListenerConnected = record.listenerConnected;
      }
      if (typeof record.shareAll === "boolean") this.status.phoneShareAll = record.shareAll;
      const r = this.opts.log.ingest(list);
      this.status.lastPollOk = true;
      this.status.lastError = null;
      if (r.added > 0) this.status.lastAddedAt = new Date(this.now()).toISOString();
      const capped = list.length >= PHONE_FETCH_LIMIT;
      if (reason === "backfill" || r.added > 0 || r.updated > 0 || capped) {
        this.logLine(
          `[notification-log] ${reason} fetched=${r.fetched} new=${r.added} dup=${r.duplicates} collapsed=${r.collapsed} updated=${r.updated} invalid=${r.invalid} total=${this.opts.log.size()}${capped ? " capped=yes" : ""}`
        );
      }
    } catch (err) {
      this.status.lastPollOk = false;
      this.status.lastError = errorText(err);
      this.logLine(`[notification-log] ${reason} failed: ${this.status.lastError}`);
    } finally {
      this.inFlight = false;
    }
  }
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function errorText(err: unknown): string {
  const msg = err instanceof Error ? err.message : String(err);
  return msg.replace(/\s+/g, " ").slice(0, 160);
}
