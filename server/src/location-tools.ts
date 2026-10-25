import { CommandRateLimiter } from "./command-rate-limit.js";
import {
  HISTORY_MAX_ENTRIES,
  LocationLog,
  SEARCH_MAX_PLACES,
} from "./location-log.js";
import type { ValidatedCommandArgs } from "./alarm-commands.js";

export const LOCATION_READ_TOOLS = ["get_location_history", "search_places"] as const;
export const LOCATION_READ_PER_MINUTE = 30;

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

export function isLocationReadTool(name: string): boolean {
  return (LOCATION_READ_TOOLS as readonly string[]).includes(name);
}

export const LOCATION_TOOL_SCHEMAS = [
  {
    name: "get_location_history",
    description:
      "Read visits and drives from the box copy of PhonePi location history. Requires PHONEPI_READ_TOKEN. Place labels always; raw coordinates only if this bot is scoped coords:true. Works when the phone is offline. from and to are epoch milliseconds or ISO-8601 (a YYYY-MM-DD value is a UTC day). max defaults to 50 and caps at 200. kind is visit or drive. Read-only. Location history stays on the phone and this box.",
    inputSchema: {
      type: "object",
      properties: {
        from: {
          description: "Start of the range (inclusive). Epoch ms, ISO-8601, or YYYY-MM-DD (UTC)",
        },
        to: {
          description: "End of the range (inclusive). Epoch ms, ISO-8601, or YYYY-MM-DD (UTC end of day)",
        },
        max: {
          type: "number",
          description: "Max entries (default 50, cap 200)",
        },
        kind: {
          type: "string",
          description: "visit or drive. Omit for both.",
        },
      },
      required: [],
    },
  },
  {
    name: "search_places",
    description:
      "Find visits whose place name or address contains every word in query. Requires PHONEPI_READ_TOKEN. Place labels always; raw coordinates only if this bot is scoped coords:true. Returns the address and visit dates from the box copy, so it works when the phone is offline. Example: query \"pizza vista\". max defaults to 20 and caps at 50.",
    inputSchema: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "Words to match against place name and address",
        },
        max: {
          type: "number",
          description: "Max places (default 20, cap 50)",
        },
      },
      required: ["query"],
    },
  },
];

export interface LocationReadOutcome {
  status: number;
  body: Record<string, unknown>;
}

export function runLocationRead(options: {
  log: LocationLog;
  limiter: CommandRateLimiter;
  tool: string;
  args: Record<string, unknown>;
  activityLogged: boolean;
  includeCoords?: boolean;
  callerId?: string;
}): LocationReadOutcome {
  const includeCoords = options.includeCoords === true;
  const limitKey = `${options.callerId ?? "anon"}:${options.tool}`;
  if (options.tool === "get_location_history") {
    const parsed = parseHistoryArgs(options.args);
    if (!parsed.ok) return { status: 400, body: { text: parsed.error, isError: true } };
    const budget = options.limiter.allow(limitKey, LOCATION_READ_PER_MINUTE);
    if (!budget.ok) {
      return {
        status: 429,
        body: {
          text: `Rate limited: ${options.tool}. Try again in ${budget.retryAfterSec}s`,
          isError: true,
        },
      };
    }
    const entries = options.log.query(parsed).map((entry) =>
      redactLocationEvent(entry, includeCoords)
    );
    return {
      status: 200,
      body: {
        text: JSON.stringify(
          {
            status: "success",
            from: parsed.fromMs,
            to: parsed.toMs,
            count: entries.length,
            activityLogged: options.activityLogged,
            coordsScoped: includeCoords,
            entries,
          },
          null,
          2
        ),
        isError: false,
      },
    };
  }
  if (options.tool === "search_places") {
    const parsed = parseSearchArgs(options.args);
    if (!parsed.ok) return { status: 400, body: { text: parsed.error, isError: true } };
    const budget = options.limiter.allow(limitKey, LOCATION_READ_PER_MINUTE);
    if (!budget.ok) {
      return {
        status: 429,
        body: {
          text: `Rate limited: ${options.tool}. Try again in ${budget.retryAfterSec}s`,
          isError: true,
        },
      };
    }
    const places = options.log
      .search(parsed.query, parsed.max)
      .map((place) => redactPlace(place, includeCoords));
    return {
      status: 200,
      body: {
        text: JSON.stringify(
          {
            status: "success",
            query: parsed.query,
            count: places.length,
            activityLogged: options.activityLogged,
            coordsScoped: includeCoords,
            places,
          },
          null,
          2
        ),
        isError: false,
      },
    };
  }
  return {
    status: 403,
    body: { text: `Tool "${options.tool}" is not a location history read`, isError: true },
  };
}

/**
 * Token commands that change logging. Returns null when [name] is unrelated
 * so the clock validator still runs.
 */
export function validateLocationAdminArgs(
  name: string,
  args: Record<string, unknown>
): ValidatedCommandArgs | null {
  if (name === "pause_location_logging") {
    if (args.paused !== true) {
      return {
        ok: false,
        error: "paused must be true; resume location on the phone",
      };
    }
    return { ok: true, args: { paused: true } };
  }
  if (name === "clear_location_history") {
    if (args.confirm !== "delete") {
      return { ok: false, error: 'confirm must be "delete"' };
    }
    return { ok: true, args: { confirm: "delete" } };
  }
  return null;
}

function redactLocationEvent(
  event: {
    latitude: number;
    longitude: number;
    endLatitude: number | null;
    endLongitude: number | null;
  },
  includeCoords: boolean
) {
  if (includeCoords) return event;
  return {
    ...event,
    latitude: null,
    longitude: null,
    endLatitude: null,
    endLongitude: null,
  };
}

function redactPlace(
  place: { latitude: number; longitude: number },
  includeCoords: boolean
) {
  if (includeCoords) return place;
  return { ...place, latitude: null, longitude: null };
}

function parseHistoryArgs(args: Record<string, unknown>):
  | {
      ok: true;
      fromMs: number | null;
      toMs: number | null;
      kind: "visit" | "drive" | null;
      max: number;
    }
  | { ok: false; error: string } {
  const from = parseBound(args.from, "from");
  if (!from.ok) return from;
  const to = parseBound(args.to, "to");
  if (!to.ok) return to;
  if (from.ms != null && to.ms != null && from.ms > to.ms) {
    return { ok: false, error: "from must be before to" };
  }
  let kind: "visit" | "drive" | null = null;
  if (args.kind != null) {
    if (args.kind !== "visit" && args.kind !== "drive") {
      return { ok: false, error: "kind must be visit or drive" };
    }
    kind = args.kind;
  }
  const max = parseMax(args.max, 50, HISTORY_MAX_ENTRIES);
  if (!max.ok) return max;
  return { ok: true, fromMs: from.ms, toMs: to.ms, kind, max: max.max };
}

function parseSearchArgs(args: Record<string, unknown>):
  | { ok: true; query: string; max: number }
  | { ok: false; error: string } {
  if (typeof args.query !== "string") {
    return { ok: false, error: "query must be a string" };
  }
  const query = args.query.trim();
  if (query.length < 2 || query.length > 80) {
    return { ok: false, error: "query must be 2 to 80 characters" };
  }
  const tokens = query
    .split(/\s+/)
    .map((part) => part.trim())
    .filter((part) => part.length >= 2);
  if (tokens.length === 0) {
    return { ok: false, error: "query must be 2 to 80 characters" };
  }
  const max = parseMax(args.max, 20, SEARCH_MAX_PLACES);
  if (!max.ok) return max;
  return { ok: true, query, max: max.max };
}

function parseMax(
  value: unknown,
  fallback: number,
  cap: number
): { ok: true; max: number } | { ok: false; error: string } {
  if (value == null) return { ok: true, max: fallback };
  if (typeof value !== "number" || !Number.isInteger(value)) {
    return { ok: false, error: `max must be an integer from 1 to ${cap}` };
  }
  if (value < 1 || value > cap) {
    return { ok: false, error: `max must be an integer from 1 to ${cap}` };
  }
  return { ok: true, max: value };
}

function parseBound(
  value: unknown,
  edge: "from" | "to"
): { ok: true; ms: number | null } | { ok: false; error: string } {
  if (value == null || value === "") return { ok: true, ms: null };
  if (typeof value === "number" && Number.isFinite(value)) {
    return { ok: true, ms: value };
  }
  if (typeof value !== "string") {
    return { ok: false, error: `${edge} must be epoch ms or an ISO-8601 time` };
  }
  const text = value.trim();
  if (/^\d{11,16}$/.test(text)) {
    return { ok: true, ms: Number(text) };
  }
  const dateOnly = DATE_ONLY.exec(text);
  if (dateOnly) {
    const year = Number(dateOnly[1]);
    const month = Number(dateOnly[2]);
    const day = Number(dateOnly[3]);
    if (month < 1 || month > 12 || day < 1 || day > 31) {
      return { ok: false, error: `${edge} is not a valid date` };
    }
    const start = Date.UTC(year, month - 1, day, 0, 0, 0, 0);
    if (edge === "from") return { ok: true, ms: start };
    return { ok: true, ms: Date.UTC(year, month - 1, day, 23, 59, 59, 999) };
  }
  const parsed = Date.parse(text);
  if (Number.isNaN(parsed)) {
    return { ok: false, error: `${edge} must be epoch ms or an ISO-8601 time` };
  }
  return { ok: true, ms: parsed };
}
