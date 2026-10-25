/**
 * Box copy of assistant inbound prompts and notification replies.
 * The phone is the writer (WebSocket frames). Bots only read.
 * Files are mode 600. The directory is mode 700.
 * A secret field is rejected and never written. Server logs are counts only.
 */

import fs from "fs";
import os from "os";
import path from "path";

export const DEFAULT_ASSISTANT_RETENTION_DAYS = 30;
export const ASSISTANT_MAX_ENTRIES = 200;
export const ASSISTANT_DEFAULT_LIMIT = 50;
export const PROMPT_TEXT_MAX = 1000;
export const ACTION_MAX = 32;
export const TITLE_MAX = 120;
export const BODY_MAX = 1000;
export const ACTIONS_MAX = 3;

const ID_RE = /^[A-Za-z0-9._-]{8,80}$/;
const FILE_RE = /^(\d{4})-(\d{2})\.jsonl$/;
const SECRET_KEYS = ["token", "wstoken", "authorization", "password", "secret"];
const PROMPT_SOURCES = [
  "qs_tile",
  "shortcut",
  "quick_tap",
  "watch_relay",
  "app",
] as const;

export type PromptSource = (typeof PROMPT_SOURCES)[number];

export interface NotificationReply {
  id: string;
  messageId: string;
  action: string;
  replyText: string | null;
  ts: number;
}

export interface InboundPrompt {
  id: string;
  text: string;
  source: PromptSource;
  ts: number;
}

export type ParsedReply =
  | { ok: true; reply: NotificationReply }
  | { ok: false; id: string; error: string };

export type ParsedPrompt =
  | { ok: true; prompt: InboundPrompt }
  | { ok: false; id: string; error: string };

export interface IngestResult {
  accepted: string[];
  rejected: { id: string; error: string }[];
  duplicate: boolean;
}

type ParseResult<T> =
  | { ok: true; value: T }
  | { ok: false; id: string; error: string };

export function openAssistantStore(
  env: NodeJS.ProcessEnv = process.env,
  now: () => number = Date.now
): AssistantStore {
  const dir =
    env.PHONEPI_ASSISTANT_DIR?.trim() ||
    path.join(os.homedir(), ".phonepi", "assistant");
  const raw = env.PHONEPI_ASSISTANT_RETENTION_DAYS;
  let days = DEFAULT_ASSISTANT_RETENTION_DAYS;
  if (raw != null && raw.trim() !== "") {
    const parsed = Number(raw);
    if (Number.isInteger(parsed) && parsed >= 1 && parsed <= 365) {
      days = parsed;
    }
  }
  return new AssistantStore(dir, days, now);
}

export class AssistantStore {
  constructor(
    readonly dir: string,
    readonly retentionDays: number = DEFAULT_ASSISTANT_RETENTION_DAYS,
    private readonly now: () => number = Date.now
  ) {}

  ingestReply(raw: unknown): IngestResult {
    return this.ingest("replies", raw, (value) => {
      const parsed = parseReply(value);
      if (!parsed.ok) return parsed;
      return { ok: true, value: parsed.reply };
    });
  }

  ingestPrompt(raw: unknown): IngestResult {
    return this.ingest("prompts", raw, (value) => {
      const parsed = parsePrompt(value);
      if (!parsed.ok) return parsed;
      return { ok: true, value: parsed.prompt };
    });
  }

  queryReplies(filter: {
    sinceMs?: number | null;
    untilMs?: number | null;
    messageId?: string | null;
    limit?: number;
  }): NotificationReply[] {
    const since = filter.sinceMs ?? 0;
    const until = filter.untilMs ?? Number.MAX_SAFE_INTEGER;
    const limit = filter.limit ?? ASSISTANT_DEFAULT_LIMIT;
    const messageId = filter.messageId ?? null;
    const all = this.readAll<NotificationReply>("replies").filter((row) => {
      if (row.ts <= since || row.ts > until) return false;
      if (messageId && row.messageId !== messageId) return false;
      return true;
    });
    return newestLast(all, limit);
  }

  queryPrompts(filter: {
    sinceMs?: number | null;
    untilMs?: number | null;
    limit?: number;
  }): InboundPrompt[] {
    const since = filter.sinceMs ?? 0;
    const until = filter.untilMs ?? Number.MAX_SAFE_INTEGER;
    const limit = filter.limit ?? ASSISTANT_DEFAULT_LIMIT;
    const all = this.readAll<InboundPrompt>("prompts").filter((row) => {
      return row.ts > since && row.ts <= until;
    });
    return newestLast(all, limit);
  }

  private ingest<T extends { id: string; ts: number }>(
    kind: "replies" | "prompts",
    raw: unknown,
    parse: (raw: unknown) => ParseResult<T>
  ): IngestResult {
    this.ensureDir(kind);
    this.prune(kind);
    const parsed = parse(raw);
    if (!parsed.ok) {
      return {
        accepted: [],
        rejected: [{ id: parsed.id, error: parsed.error }],
        duplicate: false,
      };
    }
    if (parsed.value.ts < this.cutoffMs()) {
      return {
        accepted: [],
        rejected: [{ id: parsed.value.id, error: "outside retention window" }],
        duplicate: false,
      };
    }
    const existing = this.findById<T>(kind, parsed.value.id);
    if (existing) {
      return { accepted: [parsed.value.id], rejected: [], duplicate: true };
    }
    this.append(kind, parsed.value);
    return { accepted: [parsed.value.id], rejected: [], duplicate: false };
  }

  private findById<T extends { id: string }>(
    kind: "replies" | "prompts",
    id: string
  ): T | null {
    return this.readAll<T>(kind).find((row) => row.id === id) ?? null;
  }

  private readAll<T>(kind: "replies" | "prompts"): T[] {
    const dir = this.kindDir(kind);
    if (!fs.existsSync(dir)) return [];
    const byId = new Map<string, T>();
    for (const file of this.listFiles(kind)) {
      const text = fs.readFileSync(file, "utf8");
      for (const line of text.split("\n")) {
        if (!line.trim()) continue;
        try {
          const row = JSON.parse(line) as T & { id?: string };
          if (row && typeof row.id === "string") byId.set(row.id, row);
        } catch {
          continue;
        }
      }
    }
    return [...byId.values()].sort((a, b) => {
      const at = (a as { ts?: number }).ts ?? 0;
      const bt = (b as { ts?: number }).ts ?? 0;
      return at - bt;
    });
  }

  private append(kind: "replies" | "prompts", row: { ts: number }): void {
    this.ensureDir(kind);
    const file = path.join(this.kindDir(kind), monthName(row.ts));
    const existed = fs.existsSync(file);
    fs.appendFileSync(file, `${JSON.stringify(row)}\n`, {
      encoding: "utf8",
      mode: 0o600,
    });
    if (!existed) fs.chmodSync(file, 0o600);
  }

  private prune(kind: "replies" | "prompts"): void {
    const dir = this.kindDir(kind);
    if (!fs.existsSync(dir)) return;
    const cutoff = this.cutoffMs();
    for (const file of this.listFiles(kind)) {
      const match = FILE_RE.exec(path.basename(file));
      if (!match) continue;
      const year = Number(match[1]);
      const month = Number(match[2]);
      const endOfMonth = Date.UTC(year, month, 0, 23, 59, 59, 999);
      if (endOfMonth < cutoff) fs.rmSync(file, { force: true });
    }
  }

  private cutoffMs(): number {
    return this.now() - this.retentionDays * 24 * 60 * 60 * 1000;
  }

  private ensureDir(kind: "replies" | "prompts"): void {
    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    fs.chmodSync(this.dir, 0o700);
    const child = this.kindDir(kind);
    fs.mkdirSync(child, { recursive: true, mode: 0o700 });
    fs.chmodSync(child, 0o700);
  }

  private kindDir(kind: "replies" | "prompts"): string {
    return path.join(this.dir, kind);
  }

  private listFiles(kind: "replies" | "prompts"): string[] {
    const dir = this.kindDir(kind);
    if (!fs.existsSync(dir)) return [];
    return fs
      .readdirSync(dir)
      .filter((name) => FILE_RE.test(name))
      .map((name) => path.join(dir, name));
  }
}

export function parseReply(raw: unknown): ParsedReply {
  const mapped = parseObject(raw, (record, id) => {
    const messageId =
      typeof record.messageId === "string" && ID_RE.test(record.messageId)
        ? record.messageId
        : id;
    const action = optionalText(record.action, ACTION_MAX);
    if (action === "bad" || !action) {
      return { ok: false, id, error: "action must be 1 to 32 characters" };
    }
    let replyText: string | null = null;
    if (record.replyText != null && record.replyText !== "") {
      const text = optionalText(record.replyText, PROMPT_TEXT_MAX);
      if (text === "bad") {
        return { ok: false, id, error: "replyText is too long" };
      }
      replyText = text;
    }
    const ts = finiteNumber(record.ts) ?? finiteNumber(record.timestamp);
    if (ts == null || ts < 0) {
      return { ok: false, id, error: "ts must be a positive number" };
    }
    return { ok: true, value: { id, messageId, action, replyText, ts } };
  });
  if (!mapped.ok) return mapped;
  return { ok: true, reply: mapped.value };
}

export function parsePrompt(raw: unknown): ParsedPrompt {
  const mapped = parseObject(raw, (record, id) => {
    const text = optionalText(record.text, PROMPT_TEXT_MAX);
    if (text === "bad" || !text) {
      return { ok: false, id, error: "text must be 1 to 1000 characters" };
    }
    const sourceRaw =
      typeof record.source === "string" ? record.source.trim() : "app";
    const source = (PROMPT_SOURCES as readonly string[]).includes(sourceRaw)
      ? (sourceRaw as PromptSource)
      : "app";
    const ts = finiteNumber(record.ts) ?? finiteNumber(record.timestamp);
    if (ts == null || ts < 0) {
      return { ok: false, id, error: "ts must be a positive number" };
    }
    return { ok: true, value: { id, text, source, ts } };
  });
  if (!mapped.ok) return mapped;
  return { ok: true, prompt: mapped.value };
}

export function validateSendNotificationArgs(
  args: Record<string, unknown>
): { ok: true; args: Record<string, unknown> } | { ok: false; error: string } {
  const title = optionalText(args.title, TITLE_MAX);
  if (title === "bad" || !title) {
    return { ok: false, error: "title must be 1 to 120 characters" };
  }
  const body = optionalText(args.body, BODY_MAX);
  if (body === "bad" || !body) {
    return { ok: false, error: "body must be 1 to 1000 characters" };
  }
  let requestId: string | undefined;
  if (args.requestId != null && args.requestId !== "") {
    if (typeof args.requestId !== "string" || !ID_RE.test(args.requestId)) {
      return {
        ok: false,
        error: "requestId must be 8-80 letters, digits, _ . or -",
      };
    }
    requestId = args.requestId;
  }
  const actions = parseActions(args.actions ?? args.responseOptions);
  if (!actions.ok) return actions;
  let priority = "high";
  if (args.priority != null && args.priority !== "") {
    if (typeof args.priority !== "string") {
      return { ok: false, error: "priority must be default, high, or urgent" };
    }
    const p = args.priority.trim().toLowerCase();
    if (p !== "default" && p !== "high" && p !== "urgent") {
      return { ok: false, error: "priority must be default, high, or urgent" };
    }
    priority = p;
  }
  const out: Record<string, unknown> = {
    title,
    body,
    priority,
    actions: actions.actions,
  };
  if (requestId) out.requestId = requestId;
  return { ok: true, args: out };
}

function parseObject<T>(
  raw: unknown,
  build: (record: Record<string, unknown>, id: string) => ParseResult<T>
): ParseResult<T> {
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
    return {
      ok: false,
      id: id.slice(0, 80),
      error: "id must be 8-80 letters, digits, _ . or -",
    };
  }
  return build(record, id);
}

function parseActions(
  raw: unknown
): { ok: true; actions: string[] } | { ok: false; error: string } {
  if (raw == null) return { ok: true, actions: [] };
  let list: unknown[] = [];
  if (Array.isArray(raw)) list = raw;
  else if (typeof raw === "string") {
    const text = raw.trim();
    if (text === "") return { ok: true, actions: [] };
    list = text.split(",").map((part) => part.trim());
  } else {
    return { ok: false, error: "actions must be an array of up to 3 labels" };
  }
  const actions: string[] = [];
  for (const item of list) {
    if (typeof item !== "string") {
      return { ok: false, error: "actions must be an array of up to 3 labels" };
    }
    const label = item.trim();
    if (!label) continue;
    if (label.length > ACTION_MAX) {
      return {
        ok: false,
        error: `action labels must be at most ${ACTION_MAX} characters`,
      };
    }
    actions.push(label);
    if (actions.length > ACTIONS_MAX) {
      return { ok: false, error: `at most ${ACTIONS_MAX} action buttons` };
    }
  }
  return { ok: true, actions };
}

function newestLast<T>(all: T[], limit: number): T[] {
  if (all.length <= limit) return all;
  return all.slice(all.length - limit);
}

function monthName(ms: number): string {
  const d = new Date(ms);
  const month = String(d.getUTCMonth() + 1).padStart(2, "0");
  return `${d.getUTCFullYear()}-${month}.jsonl`;
}

function finiteNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && /^-?\d+(\.\d+)?$/.test(value.trim())) {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function optionalText(value: unknown, max: number): string | null | "bad" {
  if (value == null) return null;
  if (typeof value !== "string") return "bad";
  const text = value.trim();
  if (text === "") return null;
  if (text.length > max) return "bad";
  return text;
}
