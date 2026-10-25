/**
 * Separate read token for the sensitive tier (presence, screen time, health,
 * inbound prompts, replies). Optional per-bot scoped tokens. Default-deny.
 * Constant-time compare. Logs caller id, never the token.
 */

import fs from "fs";
import path from "path";
import { tokenMatches } from "./ws-token.js";
import { atomicAppendLine, phonepiHome, resolveBoxDir } from "./atomic-file.js";

export const READ_TOKEN_ENV = "PHONEPI_READ_TOKEN";
export const READ_BOTS_ENV = "PHONEPI_READ_BOTS";
export const READ_TOKEN_HEADER = "x-phonepi-read-token";

export const SENSITIVE_READ_TOOLS = new Set<string>([
  "get_presence",
  "get_health_summary",
  "get_screen_time",
  "get_notification_replies",
  "get_inbound_prompts",
]);

export const DEFAULT_READ_BOT_ID = "default";

export interface ReadBot {
  id: string;
  token: string;
  tools: string[] | null;
  coords: boolean;
}

export interface ReadAuthState {
  bots: ReadBot[];
}

export type ReadAuthResult =
  | { ok: true; callerId: string; coords: boolean }
  | { ok: false; status: number; text: string };

export function isSensitiveReadTool(name: string): boolean {
  return SENSITIVE_READ_TOOLS.has(name);
}

export function loadReadAuth(
  env: NodeJS.ProcessEnv = process.env
): ReadAuthState {
  const bots: ReadBot[] = [];
  const envToken = env[READ_TOKEN_ENV]?.trim() || "";
  if (envToken) {
    bots.push({
      id: DEFAULT_READ_BOT_ID,
      token: envToken,
      tools: null,
      coords: true,
    });
  }
  for (const extra of loadBots(env)) {
    if (bots.some((bot) => bot.id === extra.id)) continue;
    bots.push(extra);
  }
  return { bots };
}

export function readTokenFromHttpRequest(req: {
  headers: NodeJS.Dict<string | string[]>;
}): string | null {
  const header = headerValue(req.headers[READ_TOKEN_HEADER]);
  if (header) return header;
  const auth = headerValue(req.headers.authorization);
  if (auth) {
    const match = /^Bearer\s+(\S+)/i.exec(auth);
    if (match) return match[1];
  }
  return null;
}

export function takeTokenArg(args: Record<string, unknown>): {
  token: string | null;
  rest: Record<string, unknown>;
} {
  const rest = { ...args };
  const raw = rest.token;
  delete rest.token;
  if (typeof raw === "string" && raw.trim() !== "") {
    return { token: raw.trim(), rest };
  }
  return { token: null, rest };
}

/**
 * HTTP must send the token. MCP may omit it and use PHONEPI_READ_TOKEN from
 * the process env (one MCP process = one bot). Never treats PHONEPI_WS_TOKEN
 * as a read token.
 */
export function authorizeSensitiveRead(options: {
  state: ReadAuthState;
  tool: string;
  provided: string | null;
  envProvided?: string | null;
  source: "http" | "mcp";
}): ReadAuthResult {
  if (!isSensitiveReadTool(options.tool)) {
    return { ok: true, callerId: "public", coords: false };
  }
  if (options.state.bots.length === 0) {
    return {
      ok: false,
      status: 401,
      text: "PHONEPI_READ_TOKEN is required for this read",
    };
  }
  let provided = options.provided;
  if (
    (provided == null || provided === "") &&
    options.source === "mcp" &&
    options.envProvided
  ) {
    provided = options.envProvided;
  }
  if (provided == null || provided === "") {
    return { ok: false, status: 401, text: "Missing read token" };
  }
  const bot = matchBot(options.state.bots, provided);
  if (!bot) {
    return { ok: false, status: 401, text: "Invalid read token" };
  }
  if (bot.tools && !bot.tools.includes(options.tool)) {
    return {
      ok: false,
      status: 403,
      text: `Caller ${bot.id} is not scoped for ${options.tool}`,
    };
  }
  return { ok: true, callerId: bot.id, coords: bot.coords === true };
}

export function logSensitiveRead(options: {
  callerId: string;
  tool: string;
  source: "http" | "mcp";
  log?: (line: string) => void;
  audit?: boolean;
  nowMs?: number;
}): void {
  const line = `sensitive read tool=${options.tool} caller=${options.callerId} path=${options.source}`;
  (options.log ?? defaultLog)(line);
  if (options.audit === false) return;
  try {
    appendReadAudit({
      ts: options.nowMs ?? Date.now(),
      caller: options.callerId,
      tool: options.tool,
      path: options.source,
    });
  } catch {
    // Audit is best-effort. Never throw into a read.
  }
}

export function appendReadAudit(entry: {
  ts: number;
  caller: string;
  tool: string;
  path: string;
}): void {
  const dir = resolveBoxDir("PHONEPI_AUDIT_DIR", "audit");
  const file = path.join(dir, "read.jsonl");
  atomicAppendLine(
    file,
    JSON.stringify({
      ts: entry.ts,
      caller: entry.caller,
      tool: entry.tool,
      path: entry.path,
    })
  );
}

function matchBot(bots: ReadBot[], provided: string): ReadBot | null {
  for (const bot of bots) {
    if (tokenMatches(bot.token, provided)) return bot;
  }
  return null;
}

function loadBots(env: NodeJS.ProcessEnv): ReadBot[] {
  const raw = env[READ_BOTS_ENV]?.trim();
  if (raw) return parseBotsJson(raw);
  const file = path.join(phonepiHome(env), "read-bots.json");
  if (!fs.existsSync(file)) return [];
  try {
    return parseBotsJson(fs.readFileSync(file, "utf8"));
  } catch {
    return [];
  }
}

function parseBotsJson(raw: string): ReadBot[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const bots: ReadBot[] = [];
  for (const item of parsed) {
    if (!item || typeof item !== "object" || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    const id =
      typeof record.id === "string" && record.id.trim() !== ""
        ? record.id.trim().slice(0, 64)
        : "";
    const token =
      typeof record.token === "string" && record.token.trim() !== ""
        ? record.token
        : "";
    if (!id || !token) continue;
    let tools: string[] | null = null;
    if (Array.isArray(record.tools)) {
      tools = record.tools.filter(
        (name): name is string =>
          typeof name === "string" && SENSITIVE_READ_TOOLS.has(name)
      );
    }
    bots.push({
      id,
      token,
      tools,
      coords: record.coords === true,
    });
  }
  return bots;
}

function headerValue(value: string | string[] | undefined): string | null {
  if (value == null) return null;
  const raw = Array.isArray(value) ? value[0] : value;
  const trimmed = raw?.trim();
  return trimmed ? trimmed : null;
}

function defaultLog(line: string): void {
  console.error(line);
}
