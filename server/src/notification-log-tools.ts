import { CommandRateLimiter } from "./command-rate-limit.js";
import {
  NOTIFICATION_LOG_DEFAULT_LIMIT,
  NOTIFICATION_LOG_MAX_LIMIT,
  NotificationLog,
  type NotificationEntry,
} from "./notification-log.js";
import { wrapUntrusted, wrapUntrustedOrEmpty } from "./untrusted.js";

export const NOTIFICATION_LOG_TOOL = "get_notification_log";
export const NOTIFICATION_LOG_READS_PER_MINUTE = 60;

export function isNotificationLogTool(name: string): boolean {
  return name === NOTIFICATION_LOG_TOOL;
}

export const NOTIFICATION_LOG_TOOL_SCHEMAS = [
  {
    name: NOTIFICATION_LOG_TOOL,
    description:
      "Read the box's running log of Pixel notifications (app, title, text, postedAt, capturedAt, removed). The box polls the phone about once a minute and keeps 30 days, so this works when the phone is offline. Poll for new entries by passing the previous response's nextSince as since. since/until filter on capturedAt (when the box logged it; epoch ms or ISO-8601). postedSince/postedUntil filter on the phone's posted time. package filters one app; query matches every word against app, title and text. limit defaults to 100, caps at 500. Entries are oldest first (newest last). Read-only.",
    inputSchema: {
      type: "object",
      properties: {
        since: { description: "Cursor: only entries captured after this (exclusive). Epoch ms or ISO-8601. Use nextSince from the last call." },
        until: { description: "Only entries captured at or before this. Epoch ms or ISO-8601." },
        postedSince: { description: "Only notifications posted at or after this. Epoch ms or ISO-8601." },
        postedUntil: { description: "Only notifications posted at or before this. Epoch ms or ISO-8601." },
        package: { type: "string", description: "Exact Android package, e.g. com.google.android.gm" },
        query: { type: "string", description: "Words that must all appear in app, title or text (case-insensitive)" },
        limit: { type: "number", description: "Max entries (default 100, cap 500)" },
      },
      required: [],
    },
  },
];

export interface NotificationLogReadOutcome {
  status: number;
  body: { text: string; isError: boolean };
}

export function runNotificationLogRead(options: {
  log: NotificationLog;
  limiter: CommandRateLimiter;
  args: Record<string, unknown>;
  capture?: () => Record<string, unknown>;
  wrapUntrusted?: boolean;
  callerId?: string;
}): NotificationLogReadOutcome {
  const parsed = parseArgs(options.args);
  if (!parsed.ok) return { status: 400, body: { text: parsed.error, isError: true } };
  const limitKey = `${options.callerId ?? "anon"}:${NOTIFICATION_LOG_TOOL}`;
  const budget = options.limiter.allow(limitKey, NOTIFICATION_LOG_READS_PER_MINUTE);
  if (!budget.ok) {
    return {
      status: 429,
      body: {
        text: `Rate limited: ${NOTIFICATION_LOG_TOOL}. Try again in ${budget.retryAfterSec}s`,
        isError: true,
      },
    };
  }
  const result = options.log.query(parsed.query);
  const entries = options.wrapUntrusted
    ? result.entries.map(wrapNotificationEntry)
    : result.entries;
  return {
    status: 200,
    body: {
      text: JSON.stringify(
        {
          status: "success",
          count: entries.length,
          hasMore: result.hasMore,
          nextSince: result.nextSince,
          nextSinceIso: result.nextSince != null ? new Date(result.nextSince).toISOString() : null,
          totalInLog: result.totalInLog,
          retentionDays: options.log.retentionDays,
          capture: options.capture?.() ?? null,
          entries,
        },
        null,
        2
      ),
      isError: false,
    },
  };
}

function wrapNotificationEntry(entry: NotificationEntry) {
  return {
    ...entry,
    title: wrapUntrustedOrEmpty(entry.title, `notification_log:${entry.id}:title`),
    text: wrapUntrustedOrEmpty(entry.text, `notification_log:${entry.id}:text`),
    appName: wrapUntrusted(entry.appName, `notification_log:${entry.id}:app`),
  };
}

function parseArgs(args: Record<string, unknown>):
  | { ok: true; query: Parameters<NotificationLog["query"]>[0] }
  | { ok: false; error: string } {
  const since = parseTime(args.since, "since");
  if (!since.ok) return since;
  const until = parseTime(args.until, "until");
  if (!until.ok) return until;
  const postedSince = parseTime(args.postedSince, "postedSince");
  if (!postedSince.ok) return postedSince;
  const postedUntil = parseTime(args.postedUntil, "postedUntil");
  if (!postedUntil.ok) return postedUntil;
  let packageName: string | null = null;
  if (args.package != null && args.package !== "") {
    if (typeof args.package !== "string" || args.package.length > 200) {
      return { ok: false, error: "package must be a string" };
    }
    packageName = args.package.trim();
  }
  let query: string | null = null;
  if (args.query != null && args.query !== "") {
    if (typeof args.query !== "string" || args.query.length > 200) {
      return { ok: false, error: "query must be a string up to 200 characters" };
    }
    query = args.query.trim() || null;
  }
  let limit = NOTIFICATION_LOG_DEFAULT_LIMIT;
  if (args.limit != null) {
    if (
      typeof args.limit !== "number" ||
      !Number.isInteger(args.limit) ||
      args.limit < 1 ||
      args.limit > NOTIFICATION_LOG_MAX_LIMIT
    ) {
      return { ok: false, error: `limit must be an integer from 1 to ${NOTIFICATION_LOG_MAX_LIMIT}` };
    }
    limit = args.limit;
  }
  return {
    ok: true,
    query: {
      sinceCaptured: since.ms,
      untilCaptured: until.ms,
      postedSince: postedSince.ms,
      postedUntil: postedUntil.ms,
      packageName,
      query,
      limit,
    },
  };
}

function parseTime(
  value: unknown,
  name: string
): { ok: true; ms: number | null } | { ok: false; error: string } {
  if (value == null || value === "") return { ok: true, ms: null };
  if (typeof value === "number" && Number.isFinite(value)) return { ok: true, ms: value };
  if (typeof value !== "string") return { ok: false, error: `${name} must be epoch ms or ISO-8601` };
  const text = value.trim();
  if (/^\d{10,16}$/.test(text)) return { ok: true, ms: Number(text) };
  const ms = Date.parse(text);
  if (Number.isNaN(ms)) return { ok: false, error: `${name} must be epoch ms or ISO-8601` };
  return { ok: true, ms };
}
