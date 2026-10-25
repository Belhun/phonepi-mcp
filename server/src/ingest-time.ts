/**
 * Shared ingest timestamp checks. Reject NaN, dates before 2020, and
 * timestamps more than ~10 minutes in the future.
 */

export const INGEST_MIN_TS = Date.UTC(2020, 0, 1);
export const INGEST_FUTURE_SLACK_MS = 10 * 60 * 1000;

export type IngestTimeResult =
  | { ok: true; ts: number }
  | { ok: false; error: string };

export function parseIngestTs(
  value: unknown,
  nowMs: number = Date.now()
): IngestTimeResult {
  const ts = finiteNumber(value);
  if (ts == null) {
    return { ok: false, error: "timestamp must be a finite number" };
  }
  return validateIngestTs(ts, nowMs);
}

export function validateIngestTs(
  ts: number,
  nowMs: number = Date.now()
): IngestTimeResult {
  if (!Number.isFinite(ts)) {
    return { ok: false, error: "timestamp must be a finite number" };
  }
  if (ts < INGEST_MIN_TS) {
    return { ok: false, error: "timestamp is before 2020" };
  }
  if (ts > nowMs + INGEST_FUTURE_SLACK_MS) {
    return { ok: false, error: "timestamp is too far in the future" };
  }
  return { ok: true, ts };
}

function finiteNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && /^-?\d+(\.\d+)?$/.test(value.trim())) {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
