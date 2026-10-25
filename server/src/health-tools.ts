import { CommandRateLimiter } from "./command-rate-limit.js";
import type { ValidatedCommandArgs } from "./alarm-commands.js";
import {
  HEALTH_READ_PER_MINUTE,
  HealthLog,
  HealthSummary,
  SOURCE_LAG_NOTE,
} from "./health-log.js";

export const HEALTH_READ_TOOL = "get_health_summary";

export function isHealthReadTool(name: string): boolean {
  return name === HEALTH_READ_TOOL;
}

export const HEALTH_TOOL_SCHEMAS = [
  {
    name: HEALTH_READ_TOOL,
    description:
      "Read last night's sleep (start, end, total, stages), steps today and the last 7 days, resting and average heart rate, and recent workouts from the box copy of PhonePi health data. Daily totals only; never raw heart-rate streams. Works when the phone is offline (stale true). lastNightSleepEndMs / lastNightSleepEndIso are the sleep end for later Clock bedtime/wake features. Samsung Health can lag minutes to hours; syncedAtIso is when PhonePi last read Health Connect. Read-only.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
  },
];

export interface HealthReadOutcome {
  status: number;
  body: Record<string, unknown>;
}

export function runHealthRead(options: {
  log: HealthLog;
  limiter: CommandRateLimiter;
  phoneConnected: boolean;
  activityLogged: boolean;
}): HealthReadOutcome {
  const budget = options.limiter.allow(HEALTH_READ_TOOL, HEALTH_READ_PER_MINUTE);
  if (!budget.ok) {
    return {
      status: 429,
      body: {
        text: `Rate limited: ${HEALTH_READ_TOOL}. Try again in ${budget.retryAfterSec}s`,
        isError: true,
      },
    };
  }
  const stored = options.log.readLatest();
  const summary: HealthSummary = stored ?? options.log.emptySummary();
  const payload = {
    status: "success",
    stale: !options.phoneConnected,
    phoneConnected: options.phoneConnected,
    activityLogged: options.activityLogged,
    syncedAtMs: stored ? summary.syncedAtMs : null,
    syncedAtIso: stored ? summary.syncedAtIso : null,
    timezone: summary.timezone,
    enabled: summary.enabled,
    paused: summary.paused,
    permissions: summary.permissions,
    lastNightSleep: summary.lastNightSleep,
    lastNightSleepEndMs: summary.lastNightSleepEndMs,
    lastNightSleepEndIso: summary.lastNightSleepEndIso,
    steps: summary.steps,
    heartRate: summary.heartRate,
    workouts: summary.workouts,
    oxygenSaturationPct: summary.oxygenSaturationPct,
    sourceLagNote: SOURCE_LAG_NOTE,
    retentionDays: options.log.retentionDays,
  };
  return {
    status: 200,
    body: {
      text: JSON.stringify(payload, null, 2),
      isError: false,
    },
  };
}

/**
 * Token commands that pause sync or wipe the cache. Returns null when [name]
 * is unrelated so other validators still run.
 */
export function validateHealthAdminArgs(
  name: string,
  args: Record<string, unknown>
): ValidatedCommandArgs | null {
  if (name === "pause_health_sync") {
    if (typeof args.paused !== "boolean") {
      return { ok: false, error: "paused must be true or false" };
    }
    return { ok: true, args: { paused: args.paused } };
  }
  if (name === "clear_health_cache") {
    if (args.confirm !== "delete") {
      return { ok: false, error: 'confirm must be "delete"' };
    }
    return { ok: true, args: { confirm: "delete" } };
  }
  return null;
}
