import fs from "fs";
import os from "os";
import path from "path";

/**
 * Named presence places and weekly hours. Bots use this to answer
 * "is he supposed to be at work right now and is he there?"
 *
 * Default work hours: 08:00-17:00 Monday-Friday, America/Los_Angeles.
 * Church hours stay empty until Alex sets them. Place names stay empty
 * until he points home/work/church at saved PhonePi places.
 *
 * File: ~/.phonepi/presence/config.json (mode 600). Override with
 * PHONEPI_PRESENCE_CONFIG or PHONEPI_PRESENCE_DIR. Edit the file; do not
 * hard-code hours in callers.
 */

export const DEFAULT_PRESENCE_TIMEZONE = "America/Los_Angeles";
export const WEEKDAY_KEYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"] as const;
export type WeekdayKey = (typeof WEEKDAY_KEYS)[number];

const WEEKDAY_ALIASES: Record<string, WeekdayKey> = {
  sun: "sun",
  sunday: "sun",
  mon: "mon",
  monday: "mon",
  tue: "tue",
  tues: "tue",
  tuesday: "tue",
  wed: "wed",
  wednesday: "wed",
  thu: "thu",
  thur: "thu",
  thurs: "thu",
  thursday: "thu",
  fri: "fri",
  friday: "fri",
  sat: "sat",
  saturday: "sat",
};

const HHMM = /^([01]\d|2[0-3]):([0-5]\d)$/;
const ROLE_ORDER = ["work", "church", "home"];

export interface HourWindow {
  start: string;
  end: string;
}

export interface PresenceConfigData {
  places: Record<string, string>;
  timezone: string;
  hours: Record<string, Partial<Record<WeekdayKey, HourWindow[]>>>;
}

export const DEFAULT_PRESENCE_CONFIG: PresenceConfigData = {
  places: {
    home: "",
    work: "",
    church: "",
  },
  timezone: DEFAULT_PRESENCE_TIMEZONE,
  hours: {
    work: {
      mon: [{ start: "08:00", end: "17:00" }],
      tue: [{ start: "08:00", end: "17:00" }],
      wed: [{ start: "08:00", end: "17:00" }],
      thu: [{ start: "08:00", end: "17:00" }],
      fri: [{ start: "08:00", end: "17:00" }],
      sat: [],
      sun: [],
    },
    church: {},
  },
};

export function presenceConfigPath(env: NodeJS.ProcessEnv = process.env): string {
  const flagged = env.PHONEPI_PRESENCE_CONFIG?.trim();
  if (flagged) return flagged;
  const dir =
    env.PHONEPI_PRESENCE_DIR?.trim() ||
    path.join(os.homedir(), ".phonepi", "presence");
  return path.join(dir, "config.json");
}

export function openPresenceConfig(
  env: NodeJS.ProcessEnv = process.env
): PresenceConfig {
  return new PresenceConfig(presenceConfigPath(env));
}

export class PresenceConfig {
  private cached: PresenceConfigData | null = null;

  constructor(readonly file: string) {}

  data(): PresenceConfigData {
    if (this.cached) return this.cached;
    this.cached = loadOrCreate(this.file);
    return this.cached;
  }

  reload(): PresenceConfigData {
    this.cached = null;
    return this.data();
  }

  expectedPlace(nowMs: number): string | null {
    return expectedPlaceAt(this.data(), nowMs);
  }
}

export function expectedPlaceAt(
  config: PresenceConfigData,
  nowMs: number
): string | null {
  const roles = uniqueRoles(config);
  for (const role of roles) {
    const weekly = config.hours[role];
    if (!weekly) continue;
    if (!inWeeklyHours(weekly, config.timezone, nowMs)) continue;
    const named = (config.places[role] ?? "").trim();
    return named || role;
  }
  return null;
}

export function placeMatchesExpected(
  currentPlace: string | null | undefined,
  expected: string | null,
  config: PresenceConfigData
): boolean | null {
  if (expected == null || expected.trim() === "") return null;
  if (currentPlace == null || currentPlace.trim() === "") return false;
  const current = currentPlace.trim().toLowerCase();
  const want = expected.trim().toLowerCase();
  if (current === want) return true;
  if (current === "away") return false;
  for (const [role, rawName] of Object.entries(config.places)) {
    const name = rawName.trim().toLowerCase();
    const roleKey = role.trim().toLowerCase();
    const aliases = [roleKey, name].filter((part) => part.length > 0);
    const currentIsRole = aliases.includes(current);
    const expectedIsRole = want === roleKey || (name && want === name);
    if (currentIsRole && expectedIsRole) return true;
  }
  return false;
}

export function parsePresenceConfig(raw: unknown): PresenceConfigData {
  const fallback = cloneDefault();
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return fallback;
  const record = raw as Record<string, unknown>;
  const places = parsePlaces(record.places, fallback.places);
  const timezone =
    typeof record.timezone === "string" && record.timezone.trim()
      ? record.timezone.trim()
      : fallback.timezone;
  const hours = parseHours(record.hours, fallback.hours);
  return { places, timezone, hours };
}

function loadOrCreate(file: string): PresenceConfigData {
  if (!fs.existsSync(file)) {
    writeDefault(file);
    return cloneDefault();
  }
  try {
    const text = fs.readFileSync(file, "utf8");
    return parsePresenceConfig(JSON.parse(text));
  } catch {
    return cloneDefault();
  }
}

function writeDefault(file: string): void {
  const dir = path.dirname(file);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  fs.chmodSync(dir, 0o700);
  const existed = fs.existsSync(file);
  fs.writeFileSync(file, `${JSON.stringify(DEFAULT_PRESENCE_CONFIG, null, 2)}\n`, {
    encoding: "utf8",
    mode: 0o600,
  });
  if (!existed) fs.chmodSync(file, 0o600);
}

function parsePlaces(
  raw: unknown,
  fallback: Record<string, string>
): Record<string, string> {
  const places = { ...fallback };
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return places;
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    const role = key.trim().toLowerCase();
    if (!role || role.length > 40) continue;
    if (typeof value !== "string") {
      places[role] = "";
      continue;
    }
    places[role] = value.trim().slice(0, 64);
  }
  return places;
}

function parseHours(
  raw: unknown,
  fallback: PresenceConfigData["hours"]
): PresenceConfigData["hours"] {
  const hours: PresenceConfigData["hours"] = {};
  for (const [role, weekly] of Object.entries(fallback)) {
    hours[role] = { ...weekly };
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return hours;
  for (const [roleRaw, weeklyRaw] of Object.entries(raw as Record<string, unknown>)) {
    const role = roleRaw.trim().toLowerCase();
    if (!role) continue;
    hours[role] = parseWeekly(weeklyRaw);
  }
  return hours;
}

function parseWeekly(
  raw: unknown
): Partial<Record<WeekdayKey, HourWindow[]>> {
  const weekly: Partial<Record<WeekdayKey, HourWindow[]>> = {};
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return weekly;
  for (const [dayRaw, windowsRaw] of Object.entries(raw as Record<string, unknown>)) {
    const day = WEEKDAY_ALIASES[dayRaw.trim().toLowerCase()];
    if (!day) continue;
    if (!Array.isArray(windowsRaw)) {
      weekly[day] = [];
      continue;
    }
    const windows: HourWindow[] = [];
    for (const item of windowsRaw) {
      const parsed = parseWindow(item);
      if (parsed) windows.push(parsed);
    }
    weekly[day] = windows;
  }
  return weekly;
}

function parseWindow(raw: unknown): HourWindow | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const record = raw as Record<string, unknown>;
  if (typeof record.start !== "string" || typeof record.end !== "string") return null;
  const start = record.start.trim();
  const end = record.end.trim();
  if (!HHMM.test(start) || !HHMM.test(end)) return null;
  return { start, end };
}

export function inWeeklyHours(
  weekly: Partial<Record<WeekdayKey, HourWindow[]>>,
  timeZone: string,
  nowMs: number
): boolean {
  const parts = zonedParts(nowMs, timeZone);
  if (!parts) return false;
  const windows = weekly[parts.day] ?? [];
  const minutes = parts.hour * 60 + parts.minute;
  for (const window of windows) {
    if (windowCovers(window, minutes)) return true;
  }
  return false;
}

function windowCovers(window: HourWindow, minutes: number): boolean {
  const start = toMinutes(window.start);
  const end = toMinutes(window.end);
  if (start == null || end == null) return false;
  if (start === end) return false;
  if (start < end) return minutes >= start && minutes < end;
  return minutes >= start || minutes < end;
}

function toMinutes(hhmm: string): number | null {
  const match = HHMM.exec(hhmm);
  if (!match) return null;
  return Number(match[1]) * 60 + Number(match[2]);
}

function zonedParts(
  nowMs: number,
  timeZone: string
): { day: WeekdayKey; hour: number; minute: number } | null {
  try {
    const fmt = new Intl.DateTimeFormat("en-US", {
      timeZone,
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    });
    const bag: Record<string, string> = {};
    for (const part of fmt.formatToParts(new Date(nowMs))) {
      if (part.type !== "literal") bag[part.type] = part.value;
    }
    const weekday = (bag.weekday ?? "").toLowerCase();
    const day = WEEKDAY_ALIASES[weekday];
    const hour = Number(bag.hour);
    const minute = Number(bag.minute);
    if (!day || !Number.isInteger(hour) || !Number.isInteger(minute)) return null;
    return { day, hour, minute };
  } catch {
    return null;
  }
}

function uniqueRoles(config: PresenceConfigData): string[] {
  const seen = new Set<string>();
  const roles: string[] = [];
  for (const role of ROLE_ORDER) {
    if (seen.has(role)) continue;
    seen.add(role);
    roles.push(role);
  }
  for (const role of Object.keys(config.hours)) {
    const key = role.trim().toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    roles.push(key);
  }
  return roles;
}

function cloneDefault(): PresenceConfigData {
  return JSON.parse(JSON.stringify(DEFAULT_PRESENCE_CONFIG)) as PresenceConfigData;
}
