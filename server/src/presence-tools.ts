import { CommandRateLimiter } from "./command-rate-limit.js";
import type { ValidatedCommandArgs } from "./alarm-commands.js";
import {
  PresenceConfig,
  PresenceConfigData,
  expectedPlaceAt,
  placeMatchesExpected,
} from "./presence-config.js";
import {
  PresenceLog,
  PresenceSnapshot,
  presenceIsFresh,
  presenceIsStale,
} from "./presence-log.js";

export const PRESENCE_READ_TOOL = "get_presence";
export const PRESENCE_READ_PER_MINUTE = 30;

export function isPresenceReadTool(name: string): boolean {
  return name === PRESENCE_READ_TOOL;
}

export const PRESENCE_TOOL_SCHEMAS = [
  {
    name: PRESENCE_READ_TOOL,
    description:
      "Read where Alex is right now from the box copy of PhonePi presence (place label, minutes there, driving/moving, system DND, in-app PhonePi quiet). Default is place + status. Raw coordinates only if this bot is scoped for them. Coordinates are always null when presence is off, paused, or stale (TTL). Requires PHONEPI_READ_TOKEN. Works when the phone is offline. capturedAtMs is when the phone last pushed a snapshot. expected_place and matches_expected use ~/.phonepi/presence/config.json. Read-only.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
  },
];

export interface PresenceReadOutcome {
  status: number;
  body: Record<string, unknown>;
}

export function runPresenceRead(options: {
  log: PresenceLog;
  config: PresenceConfig | PresenceConfigData;
  limiter: CommandRateLimiter;
  tool: string;
  nowMs?: number;
  activityLogged: boolean;
  includeCoords?: boolean;
  callerId?: string;
}): PresenceReadOutcome {
  if (options.tool !== PRESENCE_READ_TOOL) {
    return {
      status: 403,
      body: { text: `Tool "${options.tool}" is not a presence read`, isError: true },
    };
  }
  const limitKey = `${options.callerId ?? "anon"}:${PRESENCE_READ_TOOL}`;
  const budget = options.limiter.allow(limitKey, PRESENCE_READ_PER_MINUTE);
  if (!budget.ok) {
    return {
      status: 429,
      body: {
        text: `Rate limited: ${PRESENCE_READ_TOOL}. Try again in ${budget.retryAfterSec}s`,
        isError: true,
      },
    };
  }
  const nowMs = options.nowMs ?? Date.now();
  const config =
    options.config instanceof PresenceConfig ? options.config.data() : options.config;
  const snapshot = options.log.current();
  const expected = expectedPlaceAt(config, nowMs);
  const payload = snapshot
    ? successFromSnapshot(
        snapshot,
        expected,
        config,
        nowMs,
        options.activityLogged,
        options.includeCoords === true,
        options.log.ttlMs
      )
    : emptySuccess(expected, nowMs, options.activityLogged);
  return {
    status: 200,
    body: {
      text: JSON.stringify(payload, null, 2),
      isError: false,
    },
  };
}

/**
 * Token commands that change presence capture. Returns null when [name]
 * is unrelated so the clock validator still runs.
 */
export function validatePresenceAdminArgs(
  name: string,
  args: Record<string, unknown>
): ValidatedCommandArgs | null {
  if (name === "pause_presence_logging") {
    if (args.paused !== true) {
      return {
        ok: false,
        error: "paused must be true; resume presence on the phone",
      };
    }
    return { ok: true, args: { paused: true } };
  }
  if (name === "clear_presence_history") {
    if (args.confirm !== "delete") {
      return { ok: false, error: 'confirm must be "delete"' };
    }
    return { ok: true, args: { confirm: "delete" } };
  }
  return null;
}

function successFromSnapshot(
  snapshot: PresenceSnapshot,
  expected: string | null,
  config: PresenceConfigData,
  nowMs: number,
  activityLogged: boolean,
  includeCoords: boolean,
  ttlMs: number
): Record<string, unknown> {
  const fresh = presenceIsFresh(snapshot, nowMs, ttlMs);
  const stale = presenceIsStale(snapshot, nowMs, ttlMs);
  const coordsOk = includeCoords && fresh;
  return {
    status: "success",
    place: snapshot.place,
    minutesAtPlace: snapshot.minutesAtPlace,
    driving: snapshot.driving,
    moving: snapshot.moving,
    activity: snapshot.activity,
    dnd: snapshot.dnd,
    pi_quiet: snapshot.piQuiet === true,
    latitude: coordsOk ? snapshot.latitude : null,
    longitude: coordsOk ? snapshot.longitude : null,
    accuracyMeters: coordsOk ? snapshot.accuracyMeters : null,
    coordsScoped: includeCoords,
    expected_place: expected,
    matches_expected: placeMatchesExpected(snapshot.place, expected, config),
    capturedAtMs: snapshot.capturedAtMs,
    capturedAtIso: new Date(snapshot.capturedAtMs).toISOString(),
    ageMs: Math.max(0, nowMs - snapshot.capturedAtMs),
    arrivedAtMs: snapshot.arrivedAtMs,
    lastEvent: snapshot.lastEvent,
    lastEventPlace: snapshot.lastEventPlace,
    lastEventMs: snapshot.lastEventMs,
    enabled: snapshot.enabled,
    paused: snapshot.paused,
    fresh,
    stale,
    source: snapshot.source,
    activityLogged,
  };
}

function emptySuccess(
  expected: string | null,
  nowMs: number,
  activityLogged: boolean
): Record<string, unknown> {
  return {
    status: "success",
    place: null,
    minutesAtPlace: 0,
    driving: false,
    moving: false,
    activity: null,
    dnd: null,
    pi_quiet: false,
    latitude: null,
    longitude: null,
    accuracyMeters: null,
    expected_place: expected,
    matches_expected: expected == null ? null : false,
    capturedAtMs: null,
    capturedAtIso: null,
    ageMs: null,
    arrivedAtMs: null,
    lastEvent: null,
    lastEventPlace: null,
    lastEventMs: null,
    enabled: null,
    paused: null,
    source: null,
    reason: "no_snapshot",
    activityLogged,
    nowMs,
  };
}
