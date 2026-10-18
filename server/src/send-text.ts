/**
 * Safe text requests. The server validates, rate-limits, and records.
 * It never talks to a carrier. The phone adds the attribution prefix,
 * confirms as_alex, and is the only place that calls SmsManager.
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export const ATTRIBUTION_PREFIX = "[Alex's assistant Grok]: ";
export const SEND_TEXT_MESSAGE_MAX = 1000;
export const SEND_TEXT_TTL_MS = 5 * 60 * 1000;
export const AS_ALEX_PER_MINUTE = 5;
export const AS_ALEX_PER_DAY = 30;
export const ATTRIBUTED_PER_MINUTE = 5;
export const ATTRIBUTED_PER_DAY = 20;
const DAY_MS = 24 * 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;

export const SEND_TEXT_MODES = ["as_alex", "attributed"] as const;
export type SendTextMode = (typeof SEND_TEXT_MODES)[number];

export const SEND_TEXT_STATUSES = [
  "pending",
  "sent",
  "cancelled",
  "expired",
  "failed",
  "needs_clarification",
  "rejected",
] as const;
export type SendTextStatus = (typeof SEND_TEXT_STATUSES)[number];

/** Bot fields that must never change confirmation or the prefix. */
export const SEND_TEXT_BYPASS_KEYS = [
  "confirmed",
  "confirm",
  "skipConfirm",
  "skipConfirmation",
  "alreadyConfirmed",
  "requiresConfirmation",
  "prefix",
  "attributionPrefix",
  "sent",
  "confirmMethod",
] as const;

const FINAL_STATUSES = new Set<SendTextStatus>([
  "sent",
  "cancelled",
  "expired",
  "failed",
  "needs_clarification",
  "rejected",
]);

export interface PreparedSendText {
  recipient: string;
  message: string;
  mode: SendTextMode;
  requiresConfirmation: boolean;
}

export function validateSendTextArgs(
  name: string,
  args: Record<string, unknown>
): { ok: true; args: Record<string, unknown> } | { ok: false; error: string } {
  if (name === "get_send_status") return validateStatusArgs(args);
  if (name !== "request_send_text") return { ok: true, args };
  const prepared = prepareSendText(args);
  if (!prepared.ok) return prepared;
  return {
    ok: true,
    args: {
      recipient: prepared.value.recipient,
      message: prepared.value.message,
      mode: prepared.value.mode,
    },
  };
}

export function prepareSendText(
  args: Record<string, unknown>
): { ok: true; value: PreparedSendText } | { ok: false; error: string } {
  const bypass = SEND_TEXT_BYPASS_KEYS.find((key) => key in args);
  if (bypass) {
    return {
      ok: false,
      error: `${bypass} cannot be set by the bot. The phone decides confirmation and the attribution prefix.`,
    };
  }
  const recipient = readRecipient(args.recipient);
  if (!recipient.ok) return recipient;
  const message = readMessage(args.message);
  if (!message.ok) return message;
  const mode = readMode(args.mode);
  if (!mode.ok) return mode;
  return {
    ok: true,
    value: {
      recipient: recipient.recipient,
      message: message.message,
      mode: mode.mode,
      requiresConfirmation: mode.mode === "as_alex",
    },
  };
}

/**
 * Contract the phone applies. The server does not write this onto the
 * request it forwards. Tests use it to prove attributed text always
 * starts with the prefix and as_alex text is unchanged.
 */
export function expectedCarrierText(mode: SendTextMode, message: string): string {
  if (mode !== "attributed") return message;
  return message.startsWith(ATTRIBUTION_PREFIX)
    ? message
    : `${ATTRIBUTION_PREFIX}${message}`;
}

export function maskPhone(number: string): string {
  const digits = number.replace(/\D/g, "");
  if (!digits) return "****";
  if (digits.length <= 4) return "*".repeat(digits.length);
  const last4 = digits.slice(-4);
  const stars = "*".repeat(digits.length - 4);
  return number.trim().startsWith("+") ? `+${stars}${last4}` : `${stars}${last4}`;
}

export interface SendTextRecord {
  id: string;
  mode: SendTextMode;
  recipientName: string;
  numberMasked: string;
  message: string;
  status: SendTextStatus;
  confirmMethod: string | null;
  createdAt: number;
  expiresAt: number;
  sent: boolean;
}

export type SendTextAction =
  | { type: "confirm"; method: "tap" | "spoken" }
  | { type: "cancel" }
  | { type: "expire" }
  | { type: "attributed_send"; carrierText: string }
  | { type: "fail"; error?: string };

export interface LedgerResult {
  record: SendTextRecord;
  send: boolean;
  carrierText?: string;
}

/**
 * Pending-to-final rules. request() never sends. as_alex becomes sent
 * only after tap or spoken confirm, before expiry. attributed becomes
 * sent only when the carrier text already has the prefix. Cancel and
 * expiry are final.
 */
export class SendTextLedger {
  private readonly records = new Map<string, SendTextRecord>();

  request(input: {
    id: string;
    mode: SendTextMode;
    recipientName: string;
    numberMasked: string;
    message: string;
    now: number;
    status?: Extract<SendTextStatus, "pending" | "needs_clarification" | "rejected" | "failed">;
  }): LedgerResult {
    const status = input.status ?? "pending";
    const record: SendTextRecord = {
      id: input.id,
      mode: input.mode,
      recipientName: input.recipientName,
      numberMasked: input.numberMasked,
      message: input.message,
      status,
      confirmMethod: null,
      createdAt: input.now,
      expiresAt: input.now + SEND_TEXT_TTL_MS,
      sent: false,
    };
    this.records.set(record.id, record);
    return { record: clone(record), send: false };
  }

  get(id: string, now: number): SendTextRecord | undefined {
    const record = this.records.get(id);
    if (!record) return undefined;
    if (record.status === "pending" && now >= record.expiresAt) {
      record.status = "expired";
      record.sent = false;
    }
    return clone(record);
  }

  apply(id: string, action: SendTextAction, now: number): LedgerResult | { error: string } {
    const record = this.records.get(id);
    if (!record) return { error: "unknown send" };
    if (record.status === "pending" && now >= record.expiresAt && action.type !== "expire") {
      record.status = "expired";
      record.sent = false;
      return { record: clone(record), send: false };
    }
    if (FINAL_STATUSES.has(record.status)) {
      return { record: clone(record), send: false };
    }
    if (action.type === "cancel") {
      record.status = "cancelled";
      record.confirmMethod = "cancel";
      record.sent = false;
      return { record: clone(record), send: false };
    }
    if (action.type === "expire") {
      record.status = "expired";
      record.sent = false;
      return { record: clone(record), send: false };
    }
    if (action.type === "fail") {
      record.status = "failed";
      record.sent = false;
      return { record: clone(record), send: false };
    }
    if (action.type === "confirm") {
      if (record.mode !== "as_alex") {
        return { record: clone(record), send: false };
      }
      if (action.method !== "tap" && action.method !== "spoken") {
        return { record: clone(record), send: false };
      }
      record.status = "sent";
      record.confirmMethod = action.method;
      record.sent = true;
      record.message = expectedCarrierText(record.mode, record.message);
      return { record: clone(record), send: true, carrierText: record.message };
    }
    if (record.mode !== "attributed") {
      return { record: clone(record), send: false };
    }
    if (!action.carrierText.startsWith(ATTRIBUTION_PREFIX)) {
      record.status = "failed";
      record.sent = false;
      return { record: clone(record), send: false };
    }
    record.status = "sent";
    record.confirmMethod = "attributed";
    record.sent = true;
    record.message = action.carrierText;
    return { record: clone(record), send: true, carrierText: action.carrierText };
  }
}

export interface SendBudget {
  ok: true;
}
export interface SendBudgetDenied {
  ok: false;
  retryAfterSec: number;
  scope: "minute" | "day";
  mode: SendTextMode;
}

export class SendTextRateLimiter {
  private readonly hits = new Map<SendTextMode, number[]>();

  constructor(
    private readonly now: () => number = Date.now,
    private readonly persist: ((hits: Map<SendTextMode, number[]>) => void) | null = null,
    initial?: Map<SendTextMode, number[]>
  ) {
    if (initial) {
      for (const [mode, list] of initial) this.hits.set(mode, [...list]);
    }
  }

  allow(mode: SendTextMode): SendBudget | SendBudgetDenied {
    const limits =
      mode === "attributed"
        ? { perMinute: ATTRIBUTED_PER_MINUTE, perDay: ATTRIBUTED_PER_DAY }
        : { perMinute: AS_ALEX_PER_MINUTE, perDay: AS_ALEX_PER_DAY };
    const t = this.now();
    const recent = (this.hits.get(mode) ?? []).filter((ts) => ts > t - DAY_MS);
    const minuteHits = recent.filter((ts) => ts > t - MINUTE_MS);
    if (minuteHits.length >= limits.perMinute) {
      this.hits.set(mode, recent);
      const oldest = minuteHits[0] ?? t;
      return {
        ok: false,
        retryAfterSec: Math.max(1, Math.ceil((oldest + MINUTE_MS - t) / 1000)),
        scope: "minute",
        mode,
      };
    }
    if (recent.length >= limits.perDay) {
      this.hits.set(mode, recent);
      const oldest = recent[0] ?? t;
      return {
        ok: false,
        retryAfterSec: Math.max(1, Math.ceil((oldest + DAY_MS - t) / 1000)),
        scope: "day",
        mode,
      };
    }
    recent.push(t);
    this.hits.set(mode, recent);
    this.persist?.(this.hits);
    return { ok: true };
  }
}

function budgetFilePath(): string {
  return (
    process.env.PHONEPI_SEND_TEXT_BUDGET?.trim() ||
    path.join(os.homedir(), ".phonepi", "send-text-budget.json")
  );
}

function loadHits(file: string): Map<SendTextMode, number[]> {
  try {
    const raw = JSON.parse(fs.readFileSync(file, "utf8")) as {
      as_alex?: unknown;
      attributed?: unknown;
    };
    const map = new Map<SendTextMode, number[]>();
    for (const mode of SEND_TEXT_MODES) {
      const list = raw[mode];
      if (!Array.isArray(list)) continue;
      map.set(
        mode,
        list.filter((item): item is number => typeof item === "number" && Number.isFinite(item))
      );
    }
    return map;
  } catch {
    return new Map();
  }
}

function saveHits(file: string, hits: Map<SendTextMode, number[]>): void {
  try {
    const dir = path.dirname(file);
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    const body = JSON.stringify({
      as_alex: hits.get("as_alex") ?? [],
      attributed: hits.get("attributed") ?? [],
    });
    fs.writeFileSync(file, body, { mode: 0o600 });
    fs.chmodSync(file, 0o600);
  } catch {
    // A full disk must not turn into a free send. The in-memory window still applies.
  }
}

/** Process-wide budget so MCP and HTTP share one minute and day cap per mode. */
export const sendTextRateLimiter = new SendTextRateLimiter(
  Date.now,
  (hits) => saveHits(budgetFilePath(), hits),
  loadHits(budgetFilePath())
);

export interface SendLogEntry {
  timestamp: string;
  id?: string;
  recipientName?: string;
  numberMasked?: string;
  message?: string;
  outcome: string;
  confirmMethod?: string | null;
  mode?: string;
  candidates?: { name: string; numberMasked: string }[];
  error?: string;
}

const SECRET_KEYS = new Set([
  "token",
  "wsToken",
  "authorization",
  "phonepi_ws_token",
  "secret",
  "password",
]);

export function sanitizeLogValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((item) => sanitizeLogValue(item));
  if (!value || typeof value !== "object") return value;
  const out: Record<string, unknown> = {};
  for (const [key, inner] of Object.entries(value as Record<string, unknown>)) {
    if (SECRET_KEYS.has(key) || SECRET_KEYS.has(key.toLowerCase())) continue;
    if (key === "number" || key === "phone" || key === "phoneNumber") {
      out.numberMasked = maskPhone(typeof inner === "string" ? inner : "");
      continue;
    }
    out[key] = sanitizeLogValue(inner);
  }
  return out;
}

export function defaultSendLogPath(): string {
  return (
    process.env.PHONEPI_SEND_LOG?.trim() ||
    path.join(os.homedir(), ".phonepi", "sent-texts.jsonl")
  );
}

export function appendSendLog(entry: SendLogEntry, filePath = defaultSendLogPath()): void {
  const clean = sanitizeLogValue(entry) as SendLogEntry;
  if (!clean.timestamp) clean.timestamp = new Date().toISOString();
  const dir = path.dirname(filePath);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  fs.appendFileSync(filePath, `${JSON.stringify(clean)}\n`, { mode: 0o600 });
  try {
    fs.chmodSync(filePath, 0o600);
  } catch {
    // chmod is best-effort on platforms that ignore the mode flag
  }
}

export function sendLogFromRejection(
  args: Record<string, unknown>,
  outcome: string,
  error: string
): SendLogEntry {
  const recipient = typeof args.recipient === "string" ? args.recipient.trim() : undefined;
  const message = typeof args.message === "string" ? args.message : undefined;
  const mode = args.mode === "attributed" ? "attributed" : args.mode === "as_alex" ? "as_alex" : undefined;
  return {
    timestamp: new Date().toISOString(),
    recipientName: recipient,
    message,
    outcome,
    mode,
    error,
    confirmMethod: null,
  };
}

export function sendLogFromPhone(message: Record<string, unknown>): SendLogEntry | null {
  const outcome = typeof message.outcome === "string" ? message.outcome.trim() : "";
  if (!outcome) return null;
  const clean = sanitizeLogValue(message) as Record<string, unknown>;
  const candidates = Array.isArray(clean.candidates)
    ? clean.candidates
        .map((item) => {
          if (!item || typeof item !== "object") return null;
          const row = item as Record<string, unknown>;
          const name = typeof row.name === "string" ? row.name : "";
          const numberMasked =
            typeof row.numberMasked === "string" ? row.numberMasked : "";
          if (!name && !numberMasked) return null;
          return { name, numberMasked };
        })
        .filter((item): item is { name: string; numberMasked: string } => item != null)
    : undefined;
  return {
    timestamp: new Date().toISOString(),
    id: typeof clean.id === "string" ? clean.id : undefined,
    recipientName:
      typeof clean.recipientName === "string" ? clean.recipientName : undefined,
    numberMasked:
      typeof clean.numberMasked === "string" ? clean.numberMasked : undefined,
    message: typeof clean.message === "string" ? clean.message : undefined,
    outcome,
    confirmMethod:
      clean.confirmMethod == null
        ? null
        : typeof clean.confirmMethod === "string"
          ? clean.confirmMethod
          : null,
    mode: typeof clean.mode === "string" ? clean.mode : undefined,
    candidates,
    error: typeof clean.error === "string" ? clean.error : undefined,
  };
}

function readRecipient(
  value: unknown
): { ok: true; recipient: string } | { ok: false; error: string } {
  if (typeof value !== "string") {
    return { ok: false, error: "recipient must be one contact name or one phone number" };
  }
  const recipient = value.trim();
  if (!recipient) {
    return { ok: false, error: "recipient must be one contact name or one phone number" };
  }
  if (recipient.length > 200) {
    return { ok: false, error: "recipient must be at most 200 characters" };
  }
  if (/[,;\n]/.test(recipient) || /\band\b/i.test(recipient)) {
    return {
      ok: false,
      error: "one recipient only. No groups, lists, or multiple numbers.",
    };
  }
  return { ok: true, recipient };
}

function readMessage(
  value: unknown
): { ok: true; message: string } | { ok: false; error: string } {
  if (typeof value !== "string") {
    return { ok: false, error: "message must be a string of at most 1000 characters" };
  }
  if (!value.trim()) {
    return { ok: false, error: "message must not be empty" };
  }
  if (value.length > SEND_TEXT_MESSAGE_MAX) {
    return { ok: false, error: "message must be at most 1000 characters" };
  }
  return { ok: true, message: value };
}

function readMode(
  value: unknown
): { ok: true; mode: SendTextMode } | { ok: false; error: string } {
  if (value == null || value === "") return { ok: true, mode: "as_alex" };
  if (typeof value !== "string") {
    return { ok: false, error: "mode must be as_alex or attributed" };
  }
  const mode = value.trim().toLowerCase();
  if (mode !== "as_alex" && mode !== "attributed") {
    return { ok: false, error: "mode must be as_alex or attributed" };
  }
  return { ok: true, mode };
}

function validateStatusArgs(
  args: Record<string, unknown>
): { ok: true; args: Record<string, unknown> } | { ok: false; error: string } {
  const bypass = SEND_TEXT_BYPASS_KEYS.find((key) => key in args);
  if (bypass) {
    return { ok: false, error: `${bypass} cannot be set by the bot` };
  }
  if (typeof args.id !== "string" || !/^[A-Za-z0-9._:-]{8,80}$/.test(args.id.trim())) {
    return { ok: false, error: "id must be the pending send id" };
  }
  return { ok: true, args: { id: args.id.trim() } };
}

function clone(record: SendTextRecord): SendTextRecord {
  return { ...record };
}
