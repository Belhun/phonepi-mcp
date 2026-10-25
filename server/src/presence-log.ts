import fs from "fs";
import os from "os";
import path from "path";

/**
 * Box copy of the latest PhonePi presence snapshot.
 * One JSON object at ~/.phonepi/presence/current.json (mode 600).
 * The phone is the writer. Bots only read this file.
 * A secret field is rejected and never written.
 */

const SECRET_KEYS = ["token", "wstoken", "authorization", "password", "secret"];
const ACTIVITIES = new Set(["still", "walking", "running", "cycling", "driving", "unknown"]);
const EVENTS = new Set(["arrive", "leave"]);
const SOURCES = new Set(["geofence", "periodic", "activity", "manual"]);

export interface PresenceSnapshot {
  capturedAtMs: number;
  enabled: boolean;
  paused: boolean;
  place: string;
  minutesAtPlace: number;
  arrivedAtMs: number | null;
  driving: boolean;
  moving: boolean;
  activity: string;
  dnd: string;
  latitude: number | null;
  longitude: number | null;
  accuracyMeters: number | null;
  lastEvent: string | null;
  lastEventPlace: string | null;
  lastEventMs: number | null;
  source: string;
}

export type ParsedSnapshot =
  | { ok: true; snapshot: PresenceSnapshot }
  | { ok: false; error: string };

export function presenceLogDir(env: NodeJS.ProcessEnv = process.env): string {
  return (
    env.PHONEPI_PRESENCE_DIR?.trim() ||
    path.join(os.homedir(), ".phonepi", "presence")
  );
}

export function openPresenceLog(
  env: NodeJS.ProcessEnv = process.env
): PresenceLog {
  return new PresenceLog(presenceLogDir(env));
}

export class PresenceLog {
  constructor(readonly dir: string) {}

  get file(): string {
    return path.join(this.dir, "current.json");
  }

  ingest(raw: unknown): ParsedSnapshot {
    const parsed = parsePresenceSnapshot(raw);
    if (!parsed.ok) return parsed;
    this.write(parsed.snapshot);
    return parsed;
  }

  current(): PresenceSnapshot | null {
    if (!fs.existsSync(this.file)) return null;
    try {
      const raw = JSON.parse(fs.readFileSync(this.file, "utf8"));
      const parsed = parsePresenceSnapshot(raw);
      return parsed.ok ? parsed.snapshot : null;
    } catch {
      return null;
    }
  }

  clear(): void {
    if (fs.existsSync(this.file)) fs.rmSync(this.file, { force: true });
  }

  private write(snapshot: PresenceSnapshot): void {
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    fs.chmodSync(this.dir, 0o700);
    const existed = fs.existsSync(this.file);
    fs.writeFileSync(this.file, `${JSON.stringify(snapshot)}\n`, {
      encoding: "utf8",
      mode: 0o600,
    });
    if (!existed) fs.chmodSync(this.file, 0o600);
  }
}

export function parsePresenceSnapshot(raw: unknown): ParsedSnapshot {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, error: "snapshot must be an object" };
  }
  const record = raw as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (SECRET_KEYS.includes(key.toLowerCase())) {
      return { ok: false, error: "refusing snapshot that carries a secret field" };
    }
  }
  const capturedAtMs = finiteNumber(record.capturedAtMs);
  if (capturedAtMs == null || capturedAtMs < 0) {
    return { ok: false, error: "capturedAtMs must be a positive number" };
  }
  if (typeof record.enabled !== "boolean") {
    return { ok: false, error: "enabled must be a boolean" };
  }
  if (typeof record.paused !== "boolean") {
    return { ok: false, error: "paused must be a boolean" };
  }
  const place = optionalText(record.place, 64);
  if (place === "bad" || !place) {
    return { ok: false, error: "place is required" };
  }
  const minutesAtPlace = finiteNumber(record.minutesAtPlace);
  if (minutesAtPlace == null || minutesAtPlace < 0 || minutesAtPlace > 1_000_000) {
    return { ok: false, error: "minutesAtPlace must be a positive number" };
  }
  let arrivedAtMs: number | null = null;
  if (record.arrivedAtMs != null) {
    arrivedAtMs = finiteNumber(record.arrivedAtMs);
    if (arrivedAtMs == null || arrivedAtMs < 0) {
      return { ok: false, error: "arrivedAtMs must be null or a positive number" };
    }
  }
  if (typeof record.driving !== "boolean") {
    return { ok: false, error: "driving must be a boolean" };
  }
  if (typeof record.moving !== "boolean") {
    return { ok: false, error: "moving must be a boolean" };
  }
  const activity = optionalText(record.activity, 32);
  if (activity === "bad" || (activity && !ACTIVITIES.has(activity))) {
    return { ok: false, error: "activity is invalid" };
  }
  const dnd = optionalText(record.dnd, 32);
  if (dnd === "bad") {
    return { ok: false, error: "dnd is invalid" };
  }
  const latitude = optionalCoord(record.latitude);
  const longitude = optionalCoord(record.longitude);
  if (latitude === "bad" || longitude === "bad") {
    return { ok: false, error: "latitude/longitude out of range" };
  }
  if ((latitude == null) !== (longitude == null)) {
    return { ok: false, error: "latitude and longitude must be a pair" };
  }
  if (latitude != null && longitude != null && !validLatLng(latitude, longitude)) {
    return { ok: false, error: "latitude/longitude out of range" };
  }
  let accuracyMeters: number | null = null;
  if (record.accuracyMeters != null) {
    accuracyMeters = finiteNumber(record.accuracyMeters);
    if (accuracyMeters == null || accuracyMeters < 0 || accuracyMeters > 100_000) {
      return { ok: false, error: "accuracyMeters must be a positive number" };
    }
  }
  const lastEvent = optionalText(record.lastEvent, 16);
  if (lastEvent === "bad" || (lastEvent && !EVENTS.has(lastEvent))) {
    return { ok: false, error: "lastEvent must be arrive or leave" };
  }
  const lastEventPlace = optionalText(record.lastEventPlace, 64);
  if (lastEventPlace === "bad") {
    return { ok: false, error: "lastEventPlace is too long" };
  }
  let lastEventMs: number | null = null;
  if (record.lastEventMs != null) {
    lastEventMs = finiteNumber(record.lastEventMs);
    if (lastEventMs == null || lastEventMs < 0) {
      return { ok: false, error: "lastEventMs must be null or a positive number" };
    }
  }
  const source = optionalText(record.source, 16);
  if (source === "bad" || (source && !SOURCES.has(source))) {
    return { ok: false, error: "source is invalid" };
  }
  return {
    ok: true,
    snapshot: {
      capturedAtMs,
      enabled: record.enabled,
      paused: record.paused,
      place,
      minutesAtPlace: Math.floor(minutesAtPlace),
      arrivedAtMs,
      driving: record.driving,
      moving: record.moving,
      activity: activity ?? "unknown",
      dnd: dnd ?? "unknown",
      latitude,
      longitude,
      accuracyMeters,
      lastEvent,
      lastEventPlace,
      lastEventMs,
      source: source ?? "periodic",
    },
  };
}

function finiteNumber(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return value;
}

function validLatLng(lat: number, lng: number): boolean {
  return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
}

function optionalCoord(value: unknown): number | null | "bad" {
  if (value == null) return null;
  const n = finiteNumber(value);
  if (n == null) return "bad";
  return n;
}

function optionalText(value: unknown, max: number): string | null | "bad" {
  if (value == null) return null;
  if (typeof value !== "string") return "bad";
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (trimmed.length > max) return "bad";
  return trimmed;
}
