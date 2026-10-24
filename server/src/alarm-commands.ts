/**
 * Argument checks for clock commands. Invalid input never reaches the phone.
 * The phone checks again. HTTP 200 from a later call still has to be parsed
 * for status; a validation failure here is HTTP 400.
 */

export const ALARM_LABEL_MAX_LENGTH = 100;
export const TIMER_MAX_SECONDS = 24 * 60 * 60;

const DAY_INDEX: Record<string, number> = {
  sunday: 1,
  sun: 1,
  monday: 2,
  mon: 2,
  tuesday: 3,
  tue: 3,
  tues: 3,
  wednesday: 4,
  wed: 4,
  thursday: 5,
  thu: 5,
  thur: 5,
  thurs: 5,
  friday: 6,
  fri: 6,
  saturday: 7,
  sat: 7,
};

const WEEKDAYS = [2, 3, 4, 5, 6];
const WEEKENDS = [1, 7];
const DAILY = [1, 2, 3, 4, 5, 6, 7];

export class CommandValidationError extends Error {
  readonly statusCode = 400;
  constructor(message: string) {
    super(message);
    this.name = "CommandValidationError";
  }
}

export type ValidatedCommandArgs =
  | { ok: true; args: Record<string, unknown> }
  | { ok: false; error: string };

export function validatePhoneCommandArgs(
  name: string,
  args: Record<string, unknown>
): ValidatedCommandArgs {
  switch (name) {
    case "set_alarm":
      return validateSetAlarm(args);
    case "set_timer":
      return validateSetTimer(args);
    case "dismiss_alarm":
      return validateDismissAlarm(args);
    case "dismiss_timer":
      return validateDismissTimer(args);
    default:
      return { ok: true, args };
  }
}

function validateSetAlarm(args: Record<string, unknown>): ValidatedCommandArgs {
  const hour = readHour(args, true);
  if (!hour.ok) return hour;
  const minute = readMinute(args);
  if (!minute.ok) return minute;
  const label = readLabel(args);
  if (!label.ok) return label;
  const days = parseDays(args.days);
  if (!days.ok) return days;
  const normalized: Record<string, unknown> = {
    hour: hour.hour,
    minute: minute.minute,
    skipUi: true,
  };
  if (label.message) normalized.message = label.message;
  if (days.days && days.days.length > 0) normalized.days = days.days;
  return { ok: true, args: normalized };
}

function validateSetTimer(args: Record<string, unknown>): ValidatedCommandArgs {
  const seconds = wholeInt(args.seconds);
  if (seconds == null || seconds < 1 || seconds > TIMER_MAX_SECONDS) {
    return {
      ok: false,
      error: "seconds must be an integer from 1 to 86400",
    };
  }
  const label = readLabel(args);
  if (!label.ok) return label;
  const normalized: Record<string, unknown> = { seconds, skipUi: true };
  if (label.message) normalized.message = label.message;
  return { ok: true, args: normalized };
}

function validateDismissAlarm(args: Record<string, unknown>): ValidatedCommandArgs {
  const searchRaw = typeof args.search === "string" ? args.search.trim().toLowerCase() : "";
  if (searchRaw !== "time" && searchRaw !== "label") {
    return { ok: false, error: "search must be time or label" };
  }
  if (searchRaw === "time") {
    const hour = readHour(args, true);
    if (!hour.ok) return hour;
    const minute = readMinute(args);
    if (!minute.ok) return minute;
    return {
      ok: true,
      args: { search: "time", hour: hour.hour, minute: minute.minute },
    };
  }
  const label = readLabel(args);
  if (!label.ok) return label;
  if (!label.message) {
    return { ok: false, error: "dismiss by label needs a message" };
  }
  return { ok: true, args: { search: "label", message: label.message } };
}

function validateDismissTimer(args: Record<string, unknown>): ValidatedCommandArgs {
  const label = readLabel(args);
  if (!label.ok) return label;
  if (!label.message) return { ok: true, args: {} };
  return { ok: true, args: { message: label.message } };
}

function readHour(
  args: Record<string, unknown>,
  allowPeriod: boolean
): { ok: true; hour: number } | { ok: false; error: string } {
  const hour = wholeInt(args.hour);
  if (hour == null || hour < 0 || hour > 23) {
    return { ok: false, error: "hour must be an integer from 0 to 23" };
  }
  if (!allowPeriod) return { ok: true, hour };
  return applyPeriod(hour, readPeriod(args));
}

function readMinute(
  args: Record<string, unknown>
): { ok: true; minute: number } | { ok: false; error: string } {
  const minute = wholeInt(args.minute ?? args.minutes);
  if (minute == null || minute < 0 || minute > 59) {
    return { ok: false, error: "minute must be an integer from 0 to 59" };
  }
  return { ok: true, minute };
}

function readPeriod(args: Record<string, unknown>): unknown {
  if ("period" in args) return args.period;
  if ("ampm" in args) return args.ampm;
  if ("amPm" in args) return args.amPm;
  return undefined;
}

function applyPeriod(
  hour: number,
  periodRaw: unknown
): { ok: true; hour: number } | { ok: false; error: string } {
  if (periodRaw == null || periodRaw === "") return { ok: true, hour };
  if (typeof periodRaw !== "string") {
    return { ok: false, error: "period must be am or pm" };
  }
  const period = periodRaw.trim().toLowerCase().replace(/\./g, "");
  if (period !== "am" && period !== "pm") {
    return { ok: false, error: "period must be am or pm" };
  }
  if (hour < 1 || hour > 12) {
    return { ok: false, error: "hour must be 1 to 12 when period is set" };
  }
  if (period === "am") return { ok: true, hour: hour === 12 ? 0 : hour };
  return { ok: true, hour: hour === 12 ? 12 : hour + 12 };
}

function readLabel(
  args: Record<string, unknown>
): { ok: true; message?: string } | { ok: false; error: string } {
  const raw = args.message !== undefined ? args.message : args.label;
  if (raw == null || raw === "") return { ok: true };
  if (typeof raw !== "string") {
    return {
      ok: false,
      error: "message must be a string of at most 100 characters",
    };
  }
  const message = raw.trim();
  if (!message) return { ok: true };
  if (message.length > ALARM_LABEL_MAX_LENGTH) {
    return { ok: false, error: "message must be at most 100 characters" };
  }
  return { ok: true, message };
}

function parseDays(
  value: unknown
): { ok: true; days?: number[] } | { ok: false; error: string } {
  if (value == null || value === "") return { ok: true };
  const error = {
    ok: false as const,
    error: "days must be weekday names, weekdays, weekends, or daily",
  };
  if (typeof value === "string") {
    const expanded = expandDayToken(value.trim().toLowerCase());
    if (!expanded) return error;
    return { ok: true, days: expanded };
  }
  if (!Array.isArray(value)) return error;
  if (value.length === 0) return { ok: true };
  const set = new Set<number>();
  for (const item of value) {
    if (typeof item === "number" && Number.isInteger(item) && item >= 1 && item <= 7) {
      set.add(item);
      continue;
    }
    if (typeof item !== "string") return error;
    const expanded = expandDayToken(item.trim().toLowerCase());
    if (!expanded) return error;
    for (const day of expanded) set.add(day);
  }
  return { ok: true, days: [...set].sort((a, b) => a - b) };
}

function expandDayToken(token: string): number[] | null {
  if (token === "weekdays" || token === "weekday") return [...WEEKDAYS];
  if (token === "weekends" || token === "weekend") return [...WEEKENDS];
  if (token === "daily" || token === "everyday" || token === "every day") {
    return [...DAILY];
  }
  const one = DAY_INDEX[token];
  return one == null ? null : [one];
}

function wholeInt(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string" && /^-?\d+$/.test(value.trim())) {
    return Number(value.trim());
  }
  return undefined;
}
