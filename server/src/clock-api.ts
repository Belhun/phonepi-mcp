import { CommandRateLimiter, commandRateLimiter } from "./command-rate-limit.js";
import { RateLimitError } from "./commands.js";
import {
  isClockRead,
  isClockWrite,
  validateClockReadArgs,
  validateClockWriteArgs,
} from "./clock-commands.js";
import { ClockSnapshotStore } from "./clock-snapshot.js";

const ID_RE = /^[A-Za-z0-9_-]{1,80}$/;

export interface ClockRoute {
  tool: string;
  id?: string;
  write: boolean;
}

export interface ClockDispatchInput {
  tool: string;
  args: Record<string, unknown>;
  connected: boolean;
  callPhone: (name: string, args: Record<string, unknown>) => Promise<unknown>;
  store: ClockSnapshotStore;
  limiter?: CommandRateLimiter;
}

export interface ClockHttpResult {
  status: number;
  body: { text: string; isError: boolean };
}

export function matchClockRoute(method: string, pathname: string): ClockRoute | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts[0] !== "api" || parts[1] !== "clock") return null;
  const verb = method.toUpperCase();
  if (parts.length === 3 && parts[2] === "health" && verb === "GET") {
    return { tool: "clock_health", write: false };
  }
  if (parts.length === 3 && parts[2] === "history" && verb === "GET") {
    return { tool: "clock_history", write: false };
  }
  if (parts.length === 3 && parts[2] === "alarms" && verb === "GET") {
    return { tool: "clock_list_alarms", write: false };
  }
  if (parts.length === 3 && parts[2] === "alarms" && verb === "POST") {
    return { tool: "clock_create_alarm", write: true };
  }
  if (parts.length === 4 && parts[2] === "ringing" && verb === "POST") {
    if (parts[3] === "snooze") return { tool: "clock_snooze", write: true };
    if (parts[3] === "dismiss") return { tool: "clock_dismiss", write: true };
  }
  if (parts[2] !== "alarms" || !parts[3] || !ID_RE.test(parts[3])) return null;
  const id = parts[3];
  if (parts.length === 4 && verb === "GET") return { tool: "clock_get_alarm", id, write: false };
  if (parts.length === 4 && verb === "PATCH") return { tool: "clock_update_alarm", id, write: true };
  if (parts.length === 4 && verb === "DELETE") return { tool: "clock_delete_alarm", id, write: true };
  if (parts.length === 5 && verb === "POST") {
    const tool = {
      "skip-next": "clock_skip_next",
      unskip: "clock_unskip",
      enable: "clock_enable_alarm",
      disable: "clock_disable_alarm",
    }[parts[4]];
    if (tool) return { tool, id, write: true };
  }
  return null;
}

/**
 * Reads try the phone, then the last snapshot (stale:true).
 * Writes return 503 when the phone is offline and are not queued.
 * A repeated requestId still calls the phone. The phone decides replay.
 */
export async function dispatchClock(input: ClockDispatchInput): Promise<ClockHttpResult> {
  const write = isClockWrite(input.tool);
  const read = isClockRead(input.tool);
  if (!write && !read) {
    return jsonResult(404, "Unknown clock tool", true);
  }
  const validated = write
    ? validateClockWriteArgs(input.tool, input.args)
    : validateClockReadArgs(input.tool, input.args);
  if (!validated.ok) return jsonResult(400, validated.error, true);

  if (!input.connected) {
    if (write) return jsonResult(503, "Phone not connected", true);
    const limited = limitRead(input);
    if (limited) return limited;
    return staleRead(input.store, input.tool, validated.args);
  }

  try {
    const result = await input.callPhone(input.tool, validated.args);
    input.store.saveFromPhone(result);
    return {
      status: 200,
      body: { text: JSON.stringify(result, null, 2), isError: false },
    };
  } catch (err) {
    if (err instanceof RateLimitError) return jsonResult(429, err.message, true);
    if (read && unavailable(err)) return staleRead(input.store, input.tool, validated.args);
    const message = err instanceof Error ? err.message : String(err);
    return jsonResult(unavailable(err) ? 503 : 502, message, true);
  }
}

export function staleRead(
  store: ClockSnapshotStore,
  tool: string,
  args: Record<string, unknown>
): ClockHttpResult {
  const snap = store.load();
  const updatedAt = snap?.updatedAt ?? null;
  if (tool === "clock_get_alarm") {
    const id = String(args.id ?? "");
    const alarms = Array.isArray(snap?.alarms) ? snap.alarms : [];
    const alarm = alarms.find(
      (item) => item && typeof item === "object" && (item as { id?: unknown }).id === id
    );
    if (!alarm) {
      return {
        status: 200,
        body: {
          text: JSON.stringify(
            { status: "error", error: "alarm not found", stale: true, updatedAt },
            null,
            2
          ),
          isError: true,
        },
      };
    }
    return {
      status: 200,
      body: {
        text: JSON.stringify(
          { status: "success", alarm, stale: true, updatedAt },
          null,
          2
        ),
        isError: false,
      },
    };
  }
  if (tool === "clock_health") {
    return {
      status: 200,
      body: {
        text: JSON.stringify(
          {
            status: "success",
            health: snap?.health ?? { status: "unknown" },
            stale: true,
            updatedAt,
          },
          null,
          2
        ),
        isError: false,
      },
    };
  }
  if (tool === "clock_history") {
    return {
      status: 200,
      body: {
        text: JSON.stringify(
          { status: "success", events: [], stale: true, updatedAt },
          null,
          2
        ),
        isError: false,
      },
    };
  }
  return {
    status: 200,
    body: {
      text: JSON.stringify(
        {
          status: "success",
          alarms: snap?.alarms ?? [],
          nextAlarm: snap?.nextAlarm ?? null,
          health: snap?.health ?? { status: "unknown" },
          stale: true,
          updatedAt,
        },
        null,
        2
      ),
      isError: false,
    },
  };
}

function limitRead(input: ClockDispatchInput): ClockHttpResult | null {
  const limiter = input.limiter ?? commandRateLimiter;
  const budget = limiter.allow(input.tool, 60);
  if (budget.ok) return null;
  return jsonResult(
    429,
    `Rate limited: ${input.tool}. Try again in ${budget.retryAfterSec}s`,
    true
  );
}

function unavailable(err: unknown): boolean {
  const message = err instanceof Error ? err.message : String(err);
  return /phone not connected|connection is not open|connection not ready/i.test(message);
}

function jsonResult(status: number, text: string, isError: boolean): ClockHttpResult {
  return { status, body: { text, isError } };
}
