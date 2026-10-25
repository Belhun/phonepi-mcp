/**
 * Box copy of assistant inbound prompts and notification replies.
 * The phone is the writer (WebSocket frames). Bots only read.
 * Files are mode 600. The directory is mode 700.
 * A secret field is rejected and never written. Server logs are counts only.
 */

import fs from "fs";
import path from "path";
import {
  atomicAppendLine,
  atomicWriteFile,
  ensureBoxDir,
  recoverSwapDir,
  removeFile,
  resolveBoxDir,
  safeReadFile,
  swapRewriteDir,
} from "./atomic-file.js";
import { CommandRateLimiter } from "./command-rate-limit.js";
import { INGEST_PER_MINUTE, ingestRateLimiter } from "./ingest-limit.js";
import { parseIngestTs } from "./ingest-time.js";

export const DEFAULT_ASSISTANT_RETENTION_DAYS = 30;
export const ASSISTANT_MAX_ENTRIES = 200;
export const ASSISTANT_DEFAULT_LIMIT = 50;
export const PROMPT_TEXT_MAX = 1000;
export const ACTION_MAX = 32;
export const TITLE_MAX = 120;
export const BODY_MAX = 1000;
export const ACTIONS_MAX = 3;
export const URGENT_TITLE_MAX = 80;
export const URGENT_BODY_MAX = 200;
export const URGENT_TITLE_PREFIX = "From assistant:";

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

export interface IngestRejection {
  id: string;
  error: string;
  retryable?: boolean;
  retryAfterSec?: number;
}

export interface IngestResult {
  accepted: string[];
  rejected: IngestRejection[];
  duplicate: boolean;
  droppedOldest?: number;
}

type ParseResult<T> =
  | { ok: true; value: T }
  | { ok: false; id: string; error: string };

export function openAssistantStore(
  env: NodeJS.ProcessEnv = process.env,
  now: () => number = Date.now
): AssistantStore {
  const dir = resolveBoxDir("PHONEPI_ASSISTANT_DIR", "assistant", env);
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
    private readonly now: () => number = Date.now,
    private readonly limiter: CommandRateLimiter = ingestRateLimiter
  ) {}

  ingestReply(raw: unknown): IngestResult {
    return this.ingest("replies", raw, (value) => {
      const parsed = parseReply(value, this.now());
      if (!parsed.ok) return parsed;
      return { ok: true, value: parsed.reply };
    });
  }

  ingestPrompt(raw: unknown): IngestResult {
    return this.ingest("prompts", raw, (value) => {
      const parsed = parsePrompt(value, this.now());
      if (!parsed.ok) return parsed;
      return { ok: true, value: parsed.prompt };
    });
  }

  clear(): void {
    for (const kind of ["replies", "prompts"] as const) {
      const dir = this.kindDir(kind);
      removeSwapDirs(dir);
      if (fs.existsSync(dir)) {
        fs.rmSync(dir, { recursive: true, force: true });
      }
    }
    removeFile(this.readIdsFile());
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
    const rows = newestLast(all, limit);
    this.markRead("replies", rows);
    return rows;
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
    const rows = newestLast(all, limit);
    this.markRead("prompts", rows);
    return rows;
  }

  private ingest<T extends { id: string; ts: number }>(
    kind: "replies" | "prompts",
    raw: unknown,
    parse: (raw: unknown) => ParseResult<T>
  ): IngestResult {
    this.ensureDir(kind);
    this.prune(kind);
    const parsed = parse(raw);
    const parsedId =
      parsed.ok
        ? parsed.value.id
        : parsed.id ||
          (raw &&
          typeof raw === "object" &&
          !Array.isArray(raw) &&
          typeof (raw as { id?: unknown }).id === "string"
            ? (raw as { id: string }).id
            : "");
    if (parsed.ok) {
      const existing = this.findById<T>(kind, parsed.value.id);
      if (existing) {
        return { accepted: [parsed.value.id], rejected: [], duplicate: true };
      }
    }
    const budget = this.limiter.allow(
      `ingest:assistant:${kind}`,
      INGEST_PER_MINUTE.assistant
    );
    if (!budget.ok) {
      return {
        accepted: [],
        rejected: [
          {
            id: parsedId,
            error: "rate limited",
            retryable: true,
            retryAfterSec: budget.retryAfterSec,
          },
        ],
        duplicate: false,
      };
    }
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
    const droppedOldest = this.dropOldestIfNeeded<T>(kind);
    this.append(kind, parsed.value);
    return {
      accepted: [parsed.value.id],
      rejected: [],
      duplicate: false,
      droppedOldest,
    };
  }

  private dropOldestIfNeeded<T extends { id: string; ts: number }>(
    kind: "replies" | "prompts"
  ): number {
    const all = this.readAll<T>(kind);
    if (all.length < ASSISTANT_MAX_ENTRIES) return 0;
    const drop = all.length - ASSISTANT_MAX_ENTRIES + 1;
    const toDrop = pickRowsToDrop(all, drop, this.readIds(kind));
    const dropIds = new Set(toDrop.map((row) => row.id));
    const keep = all.filter((row) => !dropIds.has(row.id));
    this.forgetReadIds(kind, dropIds);
    this.rewriteKind(kind, keep);
    console.error(
      `assistant ${kind} dropped_oldest=${drop} cap=${ASSISTANT_MAX_ENTRIES}`
    );
    return drop;
  }

  private rewriteKind<T extends { ts: number }>(
    kind: "replies" | "prompts",
    rows: T[]
  ): void {
    const files = new Map<string, string[]>();
    for (const row of rows) {
      const name = monthName(row.ts);
      const list = files.get(name) ?? [];
      list.push(JSON.stringify(row));
      files.set(name, list);
    }
    swapRewriteDir(
      this.kindDir(kind),
      [...files.entries()].map(([name, lines]) => [name, `${lines.join("\n")}\n`])
    );
  }

  private markRead(kind: "replies" | "prompts", rows: { id: string }[]): void {
    if (rows.length === 0) return;
    const ids = this.readIds(kind);
    for (const row of rows) ids.add(row.id);
    this.writeReadIds(kind, ids);
  }

  private forgetReadIds(kind: "replies" | "prompts", dropped: Set<string>): void {
    const ids = this.readIds(kind);
    for (const id of dropped) ids.delete(id);
    this.writeReadIds(kind, ids);
  }

  private readIds(kind: "replies" | "prompts"): Set<string> {
    const raw = safeReadFile(this.readIdsFile());
    if (!raw.trim()) return new Set();
    try {
      const parsed = JSON.parse(raw) as { prompts?: unknown; replies?: unknown };
      const list = parsed[kind];
      if (!Array.isArray(list)) return new Set();
      return new Set(list.filter((id): id is string => typeof id === "string"));
    } catch {
      return new Set();
    }
  }

  private writeReadIds(kind: "replies" | "prompts", ids: Set<string>): void {
    let prompts: string[] = [];
    let replies: string[] = [];
    if (kind === "prompts") {
      prompts = [...ids];
      replies = [...this.readIds("replies")];
    } else {
      replies = [...ids];
      prompts = [...this.readIds("prompts")];
    }
    atomicWriteFile(
      this.readIdsFile(),
      JSON.stringify({ prompts, replies })
    );
  }

  private readIdsFile(): string {
    return path.join(this.dir, "read-ids.json");
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
    atomicAppendLine(file, JSON.stringify(row));
  }

  private prune(kind: "replies" | "prompts"): void {
    const dir = this.kindDir(kind);
    recoverSwapDir(dir);
    if (!fs.existsSync(dir)) return;
    const cutoff = this.cutoffMs();
    for (const file of this.listFiles(kind)) {
      const kept: string[] = [];
      const text = safeReadFile(file);
      for (const line of text.split("\n")) {
        if (!line.trim()) continue;
        try {
          const row = JSON.parse(line) as { ts?: number };
          if (typeof row.ts === "number" && row.ts >= cutoff) kept.push(line);
        } catch {
          continue;
        }
      }
      if (kept.length === 0) {
        removeFile(file);
      } else {
        atomicWriteFile(file, `${kept.join("\n")}\n`);
      }
    }
  }

  private cutoffMs(): number {
    return this.now() - this.retentionDays * 24 * 60 * 60 * 1000;
  }

  private ensureDir(kind: "replies" | "prompts"): void {
    ensureBoxDir(this.dir);
    recoverSwapDir(this.kindDir(kind));
    ensureBoxDir(this.kindDir(kind));
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

export function parseReply(raw: unknown, nowMs: number = Date.now()): ParsedReply {
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
    const ts = parseIngestTs(
      record.ts ?? record.timestamp,
      nowMs
    );
    if (!ts.ok) {
      return { ok: false, id, error: ts.error };
    }
    return { ok: true, value: { id, messageId, action, replyText, ts: ts.ts } };
  });
  if (!mapped.ok) return mapped;
  return { ok: true, reply: mapped.value };
}

export function parsePrompt(raw: unknown, nowMs: number = Date.now()): ParsedPrompt {
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
    const ts = parseIngestTs(
      record.ts ?? record.timestamp,
      nowMs
    );
    if (!ts.ok) {
      return { ok: false, id, error: ts.error };
    }
    return { ok: true, value: { id, text, source, ts: ts.ts } };
  });
  if (!mapped.ok) return mapped;
  return { ok: true, prompt: mapped.value };
}

export function validateClearAssistantArgs(
  args: Record<string, unknown>
): { ok: true; args: Record<string, unknown> } | { ok: false; error: string } {
  if (args.confirm !== "delete") {
    return { ok: false, error: 'confirm must be "delete"' };
  }
  return { ok: true, args: { confirm: "delete" } };
}

export function validateSendNotificationArgs(
  args: Record<string, unknown>
): { ok: true; args: Record<string, unknown> } | { ok: false; error: string } {
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
  const urgent = priority === "urgent";
  const titleMax = urgent ? URGENT_TITLE_MAX : TITLE_MAX;
  const bodyMax = urgent ? URGENT_BODY_MAX : BODY_MAX;
  let title = optionalText(args.title, titleMax);
  if (title === "bad" || !title) {
    return {
      ok: false,
      error: urgent
        ? `urgent title must be 1 to ${URGENT_TITLE_MAX} characters`
        : "title must be 1 to 120 characters",
    };
  }
  const body = optionalText(args.body, bodyMax);
  if (body === "bad" || !body) {
    return {
      ok: false,
      error: urgent
        ? `urgent body must be 1 to ${URGENT_BODY_MAX} characters`
        : "body must be 1 to 1000 characters",
    };
  }
  const unsafe = forbiddenAssistantText(`${title} ${body}`);
  if (unsafe) {
    return { ok: false, error: unsafe };
  }
  if (!title.startsWith(URGENT_TITLE_PREFIX)) {
    title = `${URGENT_TITLE_PREFIX} ${title}`.slice(0, titleMax);
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

export function pickRowsToDrop<T extends { id: string; ts: number }>(
  all: T[],
  drop: number,
  readIds: Set<string>
): T[] {
  if (drop <= 0) return [];
  const byTs = (a: T, b: T) => a.ts - b.ts || a.id.localeCompare(b.id);
  const read = all.filter((row) => readIds.has(row.id)).sort(byTs);
  const unread = all.filter((row) => !readIds.has(row.id)).sort(byTs);
  return [...read, ...unread].slice(0, drop);
}

function removeSwapDirs(dir: string): void {
  for (const suffix of [".next", ".bak"] as const) {
    const extra = `${dir}${suffix}`;
    if (fs.existsSync(extra)) {
      fs.rmSync(extra, { recursive: true, force: true });
    }
  }
}

function newestLast<T>(all: T[], limit: number): T[] {
  if (all.length <= limit) return all;
  return all.slice(all.length - limit);
}

function monthName(ms: number): string {
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) {
    throw new Error("invalid timestamp for filename");
  }
  const month = String(d.getUTCMonth() + 1).padStart(2, "0");
  return `${d.getUTCFullYear()}-${month}.jsonl`;
}

const LINK_RE = /https?:\/\/|www\.|\bbit\.ly\b|\btinyurl\b|\bgoo\.gl\b/i;
const CREDENTIAL_RE =
  /\b(password|passwd|passcode|pin\b|api[_\s-]?key|secret|token|credential|ssn|social security)\b/i;

export function forbiddenAssistantText(text: string): string | null {
  if (LINK_RE.test(text)) {
    return "text must not contain links";
  }
  if (CREDENTIAL_RE.test(text)) {
    return "text must not ask for credentials";
  }
  return null;
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
