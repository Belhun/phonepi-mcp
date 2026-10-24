import fs from "fs";
import os from "os";
import path from "path";

/**
 * Last-known PhonePi Clock snapshot on the box.
 * The phone is the writer. Reads work offline with stale:true.
 * File mode 600. Directory mode 700.
 * A secret field is dropped and never written.
 */

const SECRET_KEYS = new Set([
  "token",
  "wstoken",
  "authorization",
  "password",
  "secret",
  "ts_authkey",
]);
const MAX_BYTES = 512 * 1024;

export interface ClockSnapshotFile {
  alarms: unknown[];
  nextAlarm: unknown;
  health: Record<string, unknown> | null;
  updatedAt: string | null;
  savedAt: string;
}

export function openClockSnapshot(
  env: NodeJS.ProcessEnv = process.env
): ClockSnapshotStore {
  const dir =
    env.PHONEPI_CLOCK_DIR?.trim() ||
    path.join(os.homedir(), ".phonepi", "clock");
  return new ClockSnapshotStore(dir);
}

export class ClockSnapshotStore {
  constructor(readonly dir: string) {}

  filePath(): string {
    return path.join(this.dir, "snapshot.json");
  }

  load(): ClockSnapshotFile | null {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath(), "utf8"));
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
      return parsed as ClockSnapshotFile;
    } catch {
      return null;
    }
  }

  /** Short status for /api/health. Never the full health object. */
  healthStatus(): string {
    const status = this.load()?.health?.status;
    if (
      status === "ok" ||
      status === "degraded" ||
      status === "watching" ||
      status === "unknown"
    ) {
      return status;
    }
    return "unknown";
  }

  saveFromPhone(result: unknown): boolean {
    if (!result || typeof result !== "object") return false;
    const record = result as Record<string, unknown>;
    if (record.snapshot && typeof record.snapshot === "object") {
      return this.saveFrame(record.snapshot);
    }
    if (Array.isArray(record.alarms) || record.health) return this.saveFrame(record);
    return false;
  }

  saveFrame(snapshot: unknown): boolean {
    const cleaned = cleanSnapshot(snapshot);
    if (!cleaned) return false;
    const text = JSON.stringify(cleaned);
    if (Buffer.byteLength(text) > MAX_BYTES) return false;
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    try {
      fs.chmodSync(this.dir, 0o700);
    } catch {
      // A restrictive umask already covers the directory.
    }
    const tmp = path.join(this.dir, `.snapshot.${process.pid}.${Date.now()}.tmp`);
    fs.writeFileSync(tmp, text, { mode: 0o600 });
    fs.renameSync(tmp, this.filePath());
    try {
      fs.chmodSync(this.filePath(), 0o600);
    } catch {
      // The write mode is already 600.
    }
    return true;
  }
}

function cleanSnapshot(raw: unknown): ClockSnapshotFile | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const obj = raw as Record<string, unknown>;
  const healthRaw = obj.health;
  const health =
    healthRaw && typeof healthRaw === "object" && !Array.isArray(healthRaw)
      ? (stripSecrets(healthRaw) as Record<string, unknown>)
      : null;
  return {
    alarms: Array.isArray(obj.alarms) ? (stripSecrets(obj.alarms) as unknown[]) : [],
    nextAlarm: obj.nextAlarm == null ? null : stripSecrets(obj.nextAlarm),
    health,
    updatedAt: typeof obj.updatedAt === "string" ? obj.updatedAt : null,
    savedAt: new Date().toISOString(),
  };
}

function stripSecrets(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripSecrets);
  if (!value || typeof value !== "object") return value;
  const out: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
    if (SECRET_KEYS.has(key.toLowerCase())) continue;
    out[key] = stripSecrets(child);
  }
  return out;
}
