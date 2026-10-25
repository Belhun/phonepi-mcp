import fs from "fs";
import path from "path";
import {
  atomicAppendLine,
  atomicWriteFile,
  ensureBoxDir,
  removeFile,
  resolveBoxDir,
  safeReadFile,
} from "./atomic-file.js";
import { parseIngestTs } from "./ingest-time.js";

/**
 * On-box copy of PhonePi location history.
 * One JSON object per line under ~/.phonepi/location/YYYY-MM.jsonl.
 * The phone is the writer. Bots only read this directory.
 * Files are mode 600. The directory is mode 700.
 * A secret field on an event is rejected and never written.
 */

export const DEFAULT_RETENTION_MONTHS = 12;
export const HISTORY_MAX_ENTRIES = 200;
export const SEARCH_MAX_PLACES = 50;
export const VISITS_PER_PLACE = 30;
export const SYNC_BATCH_LIMIT = 40;

const ID_RE = /^[A-Za-z0-9_-]{8,80}$/;
const FILE_RE = /^(\d{4})-(\d{2})\.jsonl$/;
const SECRET_KEYS = ["token", "wstoken", "authorization", "password", "secret"];

export interface LocationEvent {
  id: string;
  kind: "visit" | "drive";
  startMs: number;
  endMs: number | null;
  latitude: number;
  longitude: number;
  endLatitude: number | null;
  endLongitude: number | null;
  placeName: string | null;
  address: string | null;
  distanceMeters: number | null;
  source: "phonepi" | "timeline";
  updatedAtMs: number;
}

export interface PlaceVisit {
  startMs: number;
  endMs: number | null;
  dateUtc: string;
}

export interface PlaceGroup {
  placeName: string | null;
  address: string | null;
  latitude: number;
  longitude: number;
  visitCount: number;
  visits: PlaceVisit[];
}

export type ParsedEvent =
  | { ok: true; event: LocationEvent }
  | { ok: false; id: string; error: string };

export interface IngestResult {
  accepted: string[];
  rejected: { id: string; error: string }[];
}

export function openLocationLog(
  env: NodeJS.ProcessEnv = process.env,
  now: () => number = Date.now
): LocationLog {
  const dir = resolveBoxDir("PHONEPI_LOCATION_DIR", "location", env);
  const raw = env.PHONEPI_LOCATION_RETENTION_MONTHS;
  let months = DEFAULT_RETENTION_MONTHS;
  if (raw != null && raw.trim() !== "") {
    const parsed = Number(raw);
    if (Number.isInteger(parsed) && parsed >= 1 && parsed <= 120) {
      months = parsed;
    }
  }
  return new LocationLog(dir, months, now);
}

export class LocationLog {
  constructor(
    readonly dir: string,
    readonly retentionMonths: number = DEFAULT_RETENTION_MONTHS,
    private readonly now: () => number = Date.now
  ) {}

  ingest(rawEvents: unknown): IngestResult {
    this.ensureDir();
    this.prune();
    if (!Array.isArray(rawEvents)) {
      return {
        accepted: [],
        rejected: [{ id: "", error: "events must be an array" }],
      };
    }
    const accepted: string[] = [];
    const rejected: { id: string; error: string }[] = [];
    const limited = rawEvents.slice(0, SYNC_BATCH_LIMIT);
    if (rawEvents.length > SYNC_BATCH_LIMIT) {
      rejected.push({
        id: "",
        error: `batch limit is ${SYNC_BATCH_LIMIT}`,
      });
    }
    for (const raw of limited) {
      const parsed = parseLocationEvent(raw, this.now());
      if (!parsed.ok) {
        rejected.push({ id: parsed.id, error: parsed.error });
        continue;
      }
      if (parsed.event.startMs < this.cutoffMs()) {
        rejected.push({ id: parsed.event.id, error: "outside retention window" });
        continue;
      }
      const current = this.findById(parsed.event.id);
      if (current && current.updatedAtMs >= parsed.event.updatedAtMs) {
        accepted.push(parsed.event.id);
        continue;
      }
      this.append(parsed.event);
      accepted.push(parsed.event.id);
    }
    return { accepted, rejected };
  }

  clear(): void {
    this.ensureDir();
    for (const file of this.listFiles()) {
      fs.rmSync(file, { force: true });
    }
  }

  query(options: {
    fromMs: number | null;
    toMs: number | null;
    kind: "visit" | "drive" | null;
    max: number;
  }): LocationEvent[] {
    this.prune();
    const cutoff = this.cutoffMs();
    const fromMs = Math.max(options.fromMs ?? cutoff, cutoff);
    const toMs = options.toMs ?? Number.MAX_SAFE_INTEGER;
    const events = this.readCollapsed(fromMs, toMs).filter((event) => {
      if (event.startMs < fromMs || event.startMs > toMs) return false;
      if (options.kind && event.kind !== options.kind) return false;
      return true;
    });
    events.sort((a, b) => b.startMs - a.startMs || b.updatedAtMs - a.updatedAtMs);
    return events.slice(0, options.max);
  }

  search(query: string, maxPlaces: number): PlaceGroup[] {
    this.prune();
    const tokens = query
      .toLowerCase()
      .split(/\s+/)
      .map((part) => part.trim())
      .filter((part) => part.length >= 2);
    const cutoff = this.cutoffMs();
    const visits = this.readCollapsed(cutoff, Number.MAX_SAFE_INTEGER).filter(
      (event) => event.kind === "visit" && event.startMs >= cutoff
    );
    const groups = new Map<string, PlaceGroup>();
    for (const event of visits) {
      const hay = `${event.placeName ?? ""} ${event.address ?? ""}`.toLowerCase();
      if (!tokens.every((token) => hay.includes(token))) continue;
      const key = placeKey(event);
      const existing = groups.get(key);
      const visit: PlaceVisit = {
        startMs: event.startMs,
        endMs: event.endMs,
        dateUtc: utcDate(event.startMs),
      };
      if (!existing) {
        groups.set(key, {
          placeName: event.placeName,
          address: event.address,
          latitude: event.latitude,
          longitude: event.longitude,
          visitCount: 1,
          visits: [visit],
        });
        continue;
      }
      existing.visitCount += 1;
      if (event.placeName && !existing.placeName) existing.placeName = event.placeName;
      if (event.address && !existing.address) existing.address = event.address;
      existing.visits.push(visit);
    }
    const places = [...groups.values()];
    for (const place of places) {
      place.visits.sort((a, b) => b.startMs - a.startMs);
      if (place.visits.length > VISITS_PER_PLACE) {
        place.visits = place.visits.slice(0, VISITS_PER_PLACE);
      }
    }
    places.sort((a, b) => (b.visits[0]?.startMs ?? 0) - (a.visits[0]?.startMs ?? 0));
    return places.slice(0, maxPlaces);
  }

  prune(): void {
    if (!fs.existsSync(this.dir)) return;
    const cutoff = this.cutoffMs();
    for (const file of this.listFiles()) {
      const kept: string[] = [];
      const text = safeReadFile(file);
      for (const line of text.split("\n")) {
        if (!line.trim()) continue;
        try {
          const row = JSON.parse(line) as { startMs?: number; updatedAtMs?: number };
          const ts = row.startMs ?? row.updatedAtMs;
          if (typeof ts === "number" && ts >= cutoff) kept.push(line);
        } catch {
          continue;
        }
      }
      if (kept.length === 0) removeFile(file);
      else atomicWriteFile(file, `${kept.join("\n")}\n`);
    }
  }

  private cutoffMs(): number {
    const d = new Date(this.now());
    const utc = new Date(
      Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate())
    );
    utc.setUTCMonth(utc.getUTCMonth() - this.retentionMonths);
    return utc.getTime();
  }

  private ensureDir(): void {
    ensureBoxDir(this.dir);
  }

  private listFiles(): string[] {
    if (!fs.existsSync(this.dir)) return [];
    return fs
      .readdirSync(this.dir)
      .filter((name) => FILE_RE.test(name))
      .map((name) => path.join(this.dir, name));
  }

  private append(event: LocationEvent): void {
    this.ensureDir();
    const file = path.join(this.dir, monthName(event.startMs));
    atomicAppendLine(file, JSON.stringify(event));
  }

  private findById(id: string): LocationEvent | null {
    const all = this.readCollapsed(0, Number.MAX_SAFE_INTEGER);
    return all.find((event) => event.id === id) ?? null;
  }

  private readCollapsed(fromMs: number, toMs: number): LocationEvent[] {
    const byId = new Map<string, LocationEvent>();
    for (const file of this.listFiles()) {
      const month = monthStartFromName(path.basename(file));
      if (month == null) continue;
      const monthEnd = Date.UTC(
        new Date(month).getUTCFullYear(),
        new Date(month).getUTCMonth() + 1,
        0,
        23,
        59,
        59,
        999
      );
      if (monthEnd < fromMs || month > toMs) continue;
      const text = safeReadFile(file);
      for (const line of text.split("\n")) {
        if (!line.trim()) continue;
        let raw: unknown;
        try {
          raw = JSON.parse(line);
        } catch {
          continue;
        }
        const parsed = parseLocationEvent(raw, this.now());
        if (!parsed.ok) continue;
        const prev = byId.get(parsed.event.id);
        if (!prev || parsed.event.updatedAtMs >= prev.updatedAtMs) {
          byId.set(parsed.event.id, parsed.event);
        }
      }
    }
    return [...byId.values()];
  }
}

export function parseLocationEvent(
  raw: unknown,
  nowMs: number = Date.now()
): ParsedEvent {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, id: "", error: "event must be an object" };
  }
  const record = raw as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (SECRET_KEYS.includes(key.toLowerCase())) {
      return {
        ok: false,
        id: typeof record.id === "string" ? record.id.slice(0, 80) : "",
        error: "refusing event that carries a secret field",
      };
    }
  }
  const id = typeof record.id === "string" ? record.id : "";
  if (!ID_RE.test(id)) {
    return { ok: false, id: id.slice(0, 80), error: "id must be 8-80 letters, digits, _ or -" };
  }
  if (record.kind !== "visit" && record.kind !== "drive") {
    return { ok: false, id, error: "kind must be visit or drive" };
  }
  const started = parseIngestTs(record.startMs, nowMs);
  if (!started.ok) {
    return { ok: false, id, error: started.error };
  }
  const startMs = started.ts;
  let endMs: number | null = null;
  if (record.endMs != null) {
    endMs = finiteNumber(record.endMs);
    if (endMs == null || endMs < startMs) {
      return { ok: false, id, error: "endMs must be null or >= startMs" };
    }
  }
  const latitude = finiteNumber(record.latitude);
  const longitude = finiteNumber(record.longitude);
  if (latitude == null || longitude == null || !validLatLng(latitude, longitude)) {
    return { ok: false, id, error: "latitude/longitude out of range" };
  }
  const endLatitude = optionalCoord(record.endLatitude);
  const endLongitude = optionalCoord(record.endLongitude);
  if (endLatitude === "bad" || endLongitude === "bad") {
    return { ok: false, id, error: "end coordinates out of range" };
  }
  if ((endLatitude == null) !== (endLongitude == null)) {
    return { ok: false, id, error: "end coordinates must be a pair" };
  }
  if (
    endLatitude != null &&
    endLongitude != null &&
    !validLatLng(endLatitude, endLongitude)
  ) {
    return { ok: false, id, error: "end coordinates out of range" };
  }
  const placeName = optionalText(record.placeName, 120);
  const address = optionalText(record.address, 300);
  if (placeName === "bad" || address === "bad") {
    return { ok: false, id, error: "placeName or address is too long" };
  }
  let distanceMeters: number | null = null;
  if (record.distanceMeters != null) {
    distanceMeters = finiteNumber(record.distanceMeters);
    if (distanceMeters == null || distanceMeters < 0 || distanceMeters > 100_000_000) {
      return { ok: false, id, error: "distanceMeters must be a positive number" };
    }
  }
  if (record.source !== "phonepi" && record.source !== "timeline") {
    return { ok: false, id, error: "source must be phonepi or timeline" };
  }
  const updatedAtMs = finiteNumber(record.updatedAtMs);
  if (updatedAtMs == null || updatedAtMs < 0) {
    return { ok: false, id, error: "updatedAtMs must be a positive number" };
  }
  return {
    ok: true,
    event: {
      id,
      kind: record.kind,
      startMs,
      endMs,
      latitude,
      longitude,
      endLatitude,
      endLongitude,
      placeName,
      address,
      distanceMeters,
      source: record.source,
      updatedAtMs,
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

function monthName(startMs: number): string {
  const d = new Date(startMs);
  if (Number.isNaN(d.getTime())) {
    throw new Error("invalid timestamp for filename");
  }
  const month = String(d.getUTCMonth() + 1).padStart(2, "0");
  return `${d.getUTCFullYear()}-${month}.jsonl`;
}

function monthStartFromName(name: string): number | null {
  const match = FILE_RE.exec(name);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (month < 1 || month > 12) return null;
  return Date.UTC(year, month - 1, 1);
}

function placeKey(event: LocationEvent): string {
  const address = event.address?.trim().toLowerCase() ?? "";
  if (address.length >= 4) return `a:${address}`;
  const name = event.placeName?.trim().toLowerCase() ?? "";
  if (name.length >= 2) return `n:${name}`;
  return `c:${event.latitude.toFixed(3)},${event.longitude.toFixed(3)}`;
}

function utcDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}
