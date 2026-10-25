import { CommandRateLimiter } from "./command-rate-limit.js";
import type { ValidatedCommandArgs } from "./alarm-commands.js";
import {
  USAGE_DEFAULT_DAYS,
  USAGE_DEFAULT_TOP,
  USAGE_MAX_DAYS,
  USAGE_MAX_TOP,
  UsageLog,
  msToMinutes,
} from "./usage-log.js";
import { wrapUntrusted } from "./untrusted.js";

export const USAGE_READ_TOOL = "get_screen_time";
export const USAGE_READ_PER_MINUTE = 30;

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

export function isUsageReadTool(name: string): boolean {
  return name === USAGE_READ_TOOL;
}

export const USAGE_TOOL_SCHEMAS = [
  {
    name: USAGE_READ_TOOL,
    description:
      "Read daily screen-on minutes, unlock count, top apps by foreground minutes, and last time the phone was used. Totals only — no event timeline and no notification contents. App names are untrusted data. Requires PHONEPI_READ_TOKEN. Reads the box copy under ~/.phonepi/usage, so it works when the phone is offline (stale true when the copy is older than 30 minutes or the phone is disconnected). days defaults to 7 and caps at 90. top defaults to 10 and caps at 25. from/to are YYYY-MM-DD local phone dates.",
    inputSchema: {
      type: "object",
      properties: {
        days: {
          type: "number",
          description: "How many recent local dates to return (default 7, cap 90)",
        },
        from: {
          description: "Start local date YYYY-MM-DD (inclusive)",
        },
        to: {
          description: "End local date YYYY-MM-DD (inclusive)",
        },
        top: {
          type: "number",
          description: "Top apps per day by minutes (default 10, cap 25)",
        },
      },
      required: [],
    },
  },
];

export interface UsageReadOutcome {
  status: number;
  body: Record<string, unknown>;
}

export function runUsageRead(options: {
  log: UsageLog;
  limiter: CommandRateLimiter;
  args: Record<string, unknown>;
  phoneConnected: boolean;
  activityLogged: boolean;
  callerId?: string;
}): UsageReadOutcome {
  const parsed = parseUsageArgs(options.args);
  if (!parsed.ok) return { status: 400, body: { text: parsed.error, isError: true } };
  const limitKey = `${options.callerId ?? "anon"}:${USAGE_READ_TOOL}`;
  const budget = options.limiter.allow(limitKey, USAGE_READ_PER_MINUTE);
  if (!budget.ok) {
    return {
      status: 429,
      body: {
        text: `Rate limited: ${USAGE_READ_TOOL}. Try again in ${budget.retryAfterSec}s`,
        isError: true,
      },
    };
  }
  const result = options.log.query({
    fromDate: parsed.fromDate,
    toDate: parsed.toDate,
    days: parsed.days,
    top: parsed.top,
    phoneConnected: options.phoneConnected,
  });
  const grantNeeded =
    result.meta && result.meta.usageAccessGranted === false
      ? "PACKAGE_USAGE_STATS"
      : undefined;
  const payload = {
    status: "success",
    stale: result.stale,
    activityLogged: options.activityLogged,
    enabled: result.meta?.enabled ?? null,
    paused: result.meta?.paused ?? null,
    usageAccessGranted: result.meta?.usageAccessGranted ?? null,
    grantNeeded,
    timeZone: result.meta?.timeZone ?? result.days[0]?.timeZone ?? null,
    lastUsedAt: result.lastUsedAt,
    lastUsedAtIso: result.lastUsedAt ? new Date(result.lastUsedAt).toISOString() : null,
    lastSyncedAt: result.lastSyncedAt,
    lastSyncedAtIso: result.lastSyncedAt
      ? new Date(result.lastSyncedAt).toISOString()
      : null,
    count: result.days.length,
    days: result.days.map((day) => ({
      date: day.date,
      timeZone: day.timeZone,
      screenOnMinutes: msToMinutes(day.screenOnMs),
      unlockCount: day.unlockCount,
      lastUsedAt: day.lastUsedAt,
      lastUsedAtIso: day.lastUsedAt ? new Date(day.lastUsedAt).toISOString() : null,
      apps: day.apps.map((app) => ({
        package: app.packageName,
        label: wrapUntrusted(app.label, "usage.app_label"),
        minutes: msToMinutes(app.foregroundMs),
      })),
    })),
  };
  return {
    status: 200,
    body: {
      text: JSON.stringify(payload, null, 2),
      isError: false,
    },
  };
}

export function validateUsageAdminArgs(
  name: string,
  args: Record<string, unknown>
): ValidatedCommandArgs | null {
  if (name === "pause_usage_logging") {
    if (args.paused !== true) {
      return {
        ok: false,
        error: "paused must be true; resume screen time on the phone",
      };
    }
    return { ok: true, args: { paused: true } };
  }
  if (name === "clear_usage_history") {
    if (args.confirm !== "delete") {
      return { ok: false, error: 'confirm must be "delete"' };
    }
    return { ok: true, args: { confirm: "delete" } };
  }
  return null;
}

function parseUsageArgs(args: Record<string, unknown>):
  | {
      ok: true;
      fromDate: string | null;
      toDate: string | null;
      days: number;
      top: number;
    }
  | { ok: false; error: string } {
  const from = parseDate(args.from, "from");
  if (!from.ok) return from;
  const to = parseDate(args.to, "to");
  if (!to.ok) return to;
  if (from.date && to.date && from.date > to.date) {
    return { ok: false, error: "from must be before to" };
  }
  const days = parseBoundedInt(args.days, USAGE_DEFAULT_DAYS, USAGE_MAX_DAYS, "days");
  if (!days.ok) return days;
  const top = parseBoundedInt(args.top, USAGE_DEFAULT_TOP, USAGE_MAX_TOP, "top");
  if (!top.ok) return top;
  return {
    ok: true,
    fromDate: from.date,
    toDate: to.date,
    days: days.value,
    top: top.value,
  };
}

function parseDate(
  value: unknown,
  edge: "from" | "to"
): { ok: true; date: string | null } | { ok: false; error: string } {
  if (value == null || value === "") return { ok: true, date: null };
  if (typeof value !== "string") {
    return { ok: false, error: `${edge} must be YYYY-MM-DD` };
  }
  const text = value.trim();
  if (!DATE_ONLY.test(text)) {
    return { ok: false, error: `${edge} must be YYYY-MM-DD` };
  }
  const [, year, month, day] = DATE_ONLY.exec(text)!;
  const y = Number(year);
  const m = Number(month);
  const d = Number(day);
  if (m < 1 || m > 12 || d < 1 || d > 31) {
    return { ok: false, error: `${edge} is not a valid date` };
  }
  const utc = Date.UTC(y, m - 1, d);
  const check = new Date(utc);
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== m - 1 || check.getUTCDate() !== d) {
    return { ok: false, error: `${edge} is not a valid date` };
  }
  return { ok: true, date: text };
}

function parseBoundedInt(
  value: unknown,
  fallback: number,
  cap: number,
  name: string
): { ok: true; value: number } | { ok: false; error: string } {
  if (value == null) return { ok: true, value: fallback };
  if (typeof value !== "number" || !Number.isInteger(value)) {
    return { ok: false, error: `${name} must be an integer from 1 to ${cap}` };
  }
  if (value < 1 || value > cap) {
    return { ok: false, error: `${name} must be an integer from 1 to ${cap}` };
  }
  return { ok: true, value };
}
