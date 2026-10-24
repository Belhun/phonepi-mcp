/**
 * Argument checks for PhonePi Clock.
 * Day bits match the phone: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64.
 * Every write needs a UUID requestId. The phone ledger stores the first result.
 * This process passes the id through and does not replay.
 */

export const CLOCK_WRITE_NAMES = [
  "clock_create_alarm",
  "clock_update_alarm",
  "clock_delete_alarm",
  "clock_enable_alarm",
  "clock_disable_alarm",
  "clock_skip_next",
  "clock_unskip",
  "clock_snooze",
  "clock_dismiss",
] as const;

export const CLOCK_READ_NAMES = [
  "clock_list_alarms",
  "clock_get_alarm",
  "clock_health",
  "clock_history",
] as const;

const WRITE_SET = new Set<string>(CLOCK_WRITE_NAMES);
const READ_SET = new Set<string>(CLOCK_READ_NAMES);
const UUID_RE =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const SOUND_TYPES = new Set(["default", "ringtone", "file", "silent"]);

const DAY_BITS: Record<string, number> = {
  monday: 1,
  mon: 1,
  tuesday: 2,
  tue: 2,
  tues: 2,
  wednesday: 4,
  wed: 4,
  thursday: 8,
  thu: 8,
  thur: 8,
  thurs: 8,
  friday: 16,
  fri: 16,
  saturday: 32,
  sat: 32,
  sunday: 64,
  sun: 64,
};

export type ClockArgsResult =
  | { ok: true; args: Record<string, unknown> }
  | { ok: false; error: string };

export function isClockWrite(name: string): boolean {
  return WRITE_SET.has(name);
}

export function isClockRead(name: string): boolean {
  return READ_SET.has(name);
}

export function validateClockWriteArgs(
  name: string,
  args: Record<string, unknown>
): ClockArgsResult {
  if (!isClockWrite(name)) return { ok: false, error: "Unknown clock command" };
  const requestId = typeof args.requestId === "string" ? args.requestId.trim() : "";
  if (!UUID_RE.test(requestId)) {
    return { ok: false, error: "requestId must be a uuid" };
  }
  const idResult = readId(args.id, idRequired(name));
  if (!idResult.ok) return idResult;

  const out: Record<string, unknown> = { requestId };
  if (idResult.id) out.id = idResult.id;

  if (name === "clock_create_alarm" || name === "clock_update_alarm") {
    const time = readTime(args, name === "clock_create_alarm");
    if (!time.ok) return time;
    if (time.hour != null) out.hour = time.hour;
    if (time.minute != null) out.minute = time.minute;
    if (name === "clock_create_alarm" && args.days === undefined) {
      out.days = 0;
    } else if (args.days !== undefined) {
      const days = parseDays(args.days);
      if (!days.ok) return days;
      out.days = days.mask;
    }
    const copied = copyAlarmFields(args);
    if (!copied.ok) return copied;
    Object.assign(out, copied.fields);
  }

  return { ok: true, args: out };
}

export function validateClockReadArgs(
  name: string,
  args: Record<string, unknown>
): ClockArgsResult {
  if (!isClockRead(name)) return { ok: false, error: "Unknown clock read" };
  const out: Record<string, unknown> = {};
  if (name === "clock_get_alarm") {
    const id = readId(args.id, true);
    if (!id.ok) return id;
    out.id = id.id;
  }
  if (name === "clock_history" && args.limit !== undefined) {
    const limit = wholeInt(args.limit);
    if (limit == null || limit < 1 || limit > 200) {
      return { ok: false, error: "limit must be an integer from 1 to 200" };
    }
    out.limit = limit;
  }
  return { ok: true, args: out };
}

export const CLOCK_READ_SCHEMAS = [
  {
    name: "clock_list_alarms",
    description:
      "List PhonePi Clock alarms from the phone, or the last box copy when the phone is offline (stale:true). This is not Google Clock. Days are a bitmask: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64.",
    inputSchema: { type: "object", properties: {}, required: [] },
  },
  {
    name: "clock_get_alarm",
    description:
      "Read one PhonePi Clock alarm by id, plus its recent instances. Offline answers use the last box copy and set stale:true.",
    inputSchema: {
      type: "object",
      properties: { id: { type: "string", description: "Alarm id" } },
      required: ["id"],
    },
  },
  {
    name: "clock_health",
    description:
      "PhonePi Clock self-test. status is ok, degraded, watching, or unknown. watching means another app has an earlier alarm. degraded means exact alarms are blocked or the next alarm does not match.",
    inputSchema: { type: "object", properties: {}, required: [] },
  },
  {
    name: "clock_history",
    description:
      "Recent PhonePi Clock events (created, rang, snoozed, dismissed, missed). The box copy does not keep this log, so an offline read returns an empty list with stale:true. limit is 1 to 200.",
    inputSchema: {
      type: "object",
      properties: {
        limit: { type: "number", description: "Max events, default 50, cap 200" },
      },
      required: [],
    },
  },
];

function idRequired(name: string): boolean {
  return (
    name !== "clock_create_alarm" &&
    name !== "clock_snooze" &&
    name !== "clock_dismiss"
  );
}

function readId(
  value: unknown,
  required: boolean
): { ok: true; id: string } | { ok: false; error: string } {
  if (value == null || value === "") {
    if (required) return { ok: false, error: "id is required" };
    return { ok: true, id: "" };
  }
  if (typeof value !== "string" || value.trim() === "" || value.trim().length > 80) {
    return { ok: false, error: "id must be a string" };
  }
  return { ok: true, id: value.trim() };
}

function readTime(
  args: Record<string, unknown>,
  required: boolean
): { ok: true; hour: number | null; minute: number | null } | { ok: false; error: string } {
  const hourPresent = args.hour !== undefined;
  const minutePresent = args.minute !== undefined || args.minutes !== undefined;
  if (required && (!hourPresent || !minutePresent)) {
    return { ok: false, error: "hour and minute are required" };
  }
  let hour: number | null = null;
  let minute: number | null = null;
  if (hourPresent) {
    hour = wholeInt(args.hour);
    if (hour == null || hour < 0 || hour > 23) {
      return { ok: false, error: "hour must be an integer from 0 to 23" };
    }
  }
  if (minutePresent) {
    minute = wholeInt(args.minute !== undefined ? args.minute : args.minutes);
    if (minute == null || minute < 0 || minute > 59) {
      return { ok: false, error: "minute must be an integer from 0 to 59" };
    }
  }
  return { ok: true, hour, minute };
}

function copyAlarmFields(
  args: Record<string, unknown>
): { ok: true; fields: Record<string, unknown> } | { ok: false; error: string } {
  const fields: Record<string, unknown> = {};
  if (args.label !== undefined || args.message !== undefined) {
    const raw = args.label !== undefined ? args.label : args.message;
    if (typeof raw !== "string") return { ok: false, error: "label must be a string" };
    if (raw.length > 100) return { ok: false, error: "label must be at most 100 characters" };
    fields.label = raw;
  }
  if (args.enabled !== undefined) {
    if (typeof args.enabled !== "boolean") return { ok: false, error: "enabled must be a boolean" };
    fields.enabled = args.enabled;
  }
  if (args.vibrate !== undefined) {
    if (typeof args.vibrate !== "boolean") return { ok: false, error: "vibrate must be a boolean" };
    fields.vibrate = args.vibrate;
  }
  if (args.deleteAfterRing !== undefined) {
    if (typeof args.deleteAfterRing !== "boolean") {
      return { ok: false, error: "deleteAfterRing must be a boolean" };
    }
    fields.deleteAfterRing = args.deleteAfterRing;
  }
  const bounds: Array<[string, number, number]> = [
    ["rampSeconds", 0, 600],
    ["snoozeMinutes", 1, 120],
    ["maxSnoozes", 0, 20],
    ["silenceAfterMinutes", 0, 120],
    ["preAlarmMinutes", 0, 180],
  ];
  for (const [key, min, max] of bounds) {
    if (args[key] === undefined) continue;
    const n = wholeInt(args[key]);
    if (n == null || n < min || n > max) {
      return { ok: false, error: `${key} must be an integer from ${min} to ${max}` };
    }
    fields[key] = n;
  }
  if (args.ifVersion !== undefined) {
    const version = wholeInt(args.ifVersion);
    if (version == null || version < 1) {
      return { ok: false, error: "ifVersion must be a positive integer" };
    }
    fields.ifVersion = version;
  }
  const sound = readSound(args);
  if (!sound.ok) return sound;
  if (sound.type) fields.soundType = sound.type;
  if (sound.uri != null) fields.soundUri = sound.uri;
  if (sound.name != null) fields.soundName = sound.name;
  return { ok: true, fields };
}

function readSound(
  args: Record<string, unknown>
):
  | { ok: true; type?: string; uri: string | null; name: string | null }
  | { ok: false; error: string } {
  const sound = args.sound;
  let type = typeof args.soundType === "string" ? args.soundType : undefined;
  let uri = typeof args.soundUri === "string" ? args.soundUri : null;
  let name = typeof args.soundName === "string" ? args.soundName : null;
  if (sound && typeof sound === "object" && !Array.isArray(sound)) {
    const record = sound as Record<string, unknown>;
    if (typeof record.type === "string") type = record.type;
    if (typeof record.uri === "string") uri = record.uri;
    if (typeof record.name === "string") name = record.name;
  } else if (sound !== undefined) {
    return { ok: false, error: "sound must be an object" };
  }
  if (type !== undefined && !SOUND_TYPES.has(type)) {
    return { ok: false, error: "sound type must be default, ringtone, file, or silent" };
  }
  if (uri != null && uri.length > 500) return { ok: false, error: "sound uri is too long" };
  if (name != null && name.length > 100) return { ok: false, error: "sound name is too long" };
  return { ok: true, type, uri, name };
}

function parseDays(
  value: unknown
): { ok: true; mask: number } | { ok: false; error: string } {
  if (value == null || value === "") return { ok: true, mask: 0 };
  if (typeof value === "number") {
    if (!Number.isInteger(value) || value < 0 || value > 127) {
      return { ok: false, error: "days must be a bitmask from 0 to 127" };
    }
    return { ok: true, mask: value };
  }
  if (typeof value === "string") {
    const text = value.trim().toLowerCase();
    if (!text) return { ok: true, mask: 0 };
    if (/^\d+$/.test(text)) return parseDays(Number(text));
    if (text === "weekdays") return { ok: true, mask: 31 };
    if (text === "weekends") return { ok: true, mask: 96 };
    if (text === "daily" || text === "everyday" || text === "every day") {
      return { ok: true, mask: 127 };
    }
    let mask = 0;
    for (const part of text.split(/[,\s+]+/).filter(Boolean)) {
      const bit = DAY_BITS[part];
      if (bit == null) {
        return {
          ok: false,
          error: "days must be a bitmask, weekday names, weekdays, weekends, or daily",
        };
      }
      mask |= bit;
    }
    return { ok: true, mask };
  }
  if (Array.isArray(value)) {
    let mask = 0;
    for (const item of value) {
      const parsed = parseDays(typeof item === "string" ? item : item);
      if (!parsed.ok) return parsed;
      if (typeof item === "number" && item >= 1 && item <= 64 && (item & (item - 1)) === 0) {
        mask |= item;
        continue;
      }
      mask |= parsed.mask;
    }
    return { ok: true, mask };
  }
  return {
    ok: false,
    error: "days must be a bitmask, weekday names, weekdays, weekends, or daily",
  };
}

function wholeInt(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isInteger(value)) return null;
  return value;
}
