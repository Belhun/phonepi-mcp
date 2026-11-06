import { createHash, timingSafeEqual } from "crypto";
import { isLoopbackHost } from "./bind-host.js";
import { tokenContainsWeakMarker } from "./weak-token.js";

export const MIN_WS_TOKEN_CHARS = 32;
export const ALLOW_QUERY_TOKEN_ENV = "PHONEPI_ALLOW_QUERY_TOKEN";
export const INSECURE_NO_TOKEN_ENV = "PHONEPI_INSECURE_NO_TOKEN";
export const WS_SUBPROTOCOL_V1 = "phonepi.v1";
export const WS_TOKEN_PROTOCOL_PREFIX = "phonepi.token.";

const QUERY_TOKEN_LOG_EVERY_MS = 10_000;

let queryTokenUses = 0;
let lastQueryTokenLogMs = 0;

export type WsTokenParse =
  | { ok: true; token: string }
  | { ok: false; reason: "missing" | "too_short" | "weak" };

export type ListenAuth =
  | { ok: true; token: string | null }
  | { ok: false; message: string };

/**
 * Shared secret for the phone WebSocket. Whitespace-only is unset.
 * Tokens shorter than 32 characters are rejected.
 */
export function parseWsToken(
  env: NodeJS.ProcessEnv = process.env
): WsTokenParse {
  const raw = env.PHONEPI_WS_TOKEN;
  if (raw == null) return { ok: false, reason: "missing" };
  const trimmed = raw.trim();
  if (trimmed === "") return { ok: false, reason: "missing" };
  if (trimmed.length < MIN_WS_TOKEN_CHARS) {
    return { ok: false, reason: "too_short" };
  }
  if (tokenContainsWeakMarker(trimmed)) {
    return { ok: false, reason: "weak" };
  }
  return { ok: true, token: trimmed };
}

export function wsTokenFromEnv(
  env: NodeJS.ProcessEnv = process.env
): string | null {
  const parsed = parseWsToken(env);
  return parsed.ok ? parsed.token : null;
}

export function requireWsTokenForListen(
  host: string,
  env: NodeJS.ProcessEnv = process.env
): ListenAuth {
  const parsed = parseWsToken(env);
  if (parsed.ok) return { ok: true, token: parsed.token };
  if (env[INSECURE_NO_TOKEN_ENV] === "1") {
    if (!isLoopbackHost(host)) {
      return {
        ok: false,
        message:
          "PHONEPI_INSECURE_NO_TOKEN=1 is only allowed with a loopback bind (127.0.0.1, ::1, localhost)",
      };
    }
    return { ok: true, token: null };
  }
  if (parsed.reason === "too_short") {
    return {
      ok: false,
      message: "PHONEPI_WS_TOKEN must be at least 32 characters",
    };
  }
  if (parsed.reason === "weak") {
    return {
      ok: false,
      message: "PHONEPI_WS_TOKEN looks like a placeholder",
    };
  }
  return { ok: false, message: "PHONEPI_WS_TOKEN required" };
}

/** Compare two strings without leaking the secret through early length checks. */
export function tokenMatches(expected: string, provided: string): boolean {
  const left = createHash("sha256").update(expected, "utf8").digest();
  const right = createHash("sha256").update(provided, "utf8").digest();
  return timingSafeEqual(left, right);
}

function tokensEqual(expected: string, provided: string): boolean {
  return tokenMatches(expected, provided);
}

export function allowQueryToken(
  env: NodeJS.ProcessEnv = process.env
): boolean {
  return env[ALLOW_QUERY_TOKEN_ENV] !== "0";
}

export function queryTokenUseCount(): number {
  return queryTokenUses;
}

export function resetQueryTokenUseForTests(): void {
  queryTokenUses = 0;
  lastQueryTokenLogMs = 0;
}

export function noteQueryTokenUse(
  log: (line: string) => void = console.error,
  nowMs: number = Date.now()
): void {
  queryTokenUses += 1;
  if (
    queryTokenUses > 1 &&
    nowMs - lastQueryTokenLogMs < QUERY_TOKEN_LOG_EVERY_MS
  ) {
    return;
  }
  lastQueryTokenLogMs = nowMs;
  log(`query token used (deprecated) count=${queryTokenUses}`);
}

export type TokenRequest = {
  url?: string;
  headers?: NodeJS.Dict<string | string[]>;
};

export function tokenFromHttpHeaders(
  headers: NodeJS.Dict<string | string[]> | undefined
): string | null {
  if (!headers) return null;
  const header = headerValue(headers["x-phonepi-token"]);
  if (header) return header;
  const auth = headerValue(headers.authorization);
  if (auth) {
    const match = /^Bearer\s+(\S+)/i.exec(auth);
    if (match) return match[1];
  }
  return null;
}

export function tokenFromWsHeaders(
  headers: NodeJS.Dict<string | string[]> | undefined
): string | null {
  if (!headers) return null;
  const auth = headerValue(headers.authorization);
  if (auth) {
    const match = /^Bearer\s+(\S+)/i.exec(auth);
    if (match) return match[1];
  }
  const header = headerValue(headers["x-phonepi-token"]);
  if (header) return header;
  return tokenFromSubprotocol(headers["sec-websocket-protocol"]);
}

export function tokenFromSubprotocol(
  value: string | string[] | undefined
): string | null {
  const raw = headerValue(value);
  if (!raw) return null;
  for (const part of raw.split(",")) {
    const protocol = part.trim();
    if (
      protocol.startsWith(WS_TOKEN_PROTOCOL_PREFIX) &&
      protocol.length > WS_TOKEN_PROTOCOL_PREFIX.length
    ) {
      return protocol.slice(WS_TOKEN_PROTOCOL_PREFIX.length);
    }
  }
  return null;
}

export function selectWsProtocol(
  protocols: Iterable<string>
): string | false {
  for (const protocol of protocols) {
    if (protocol === WS_SUBPROTOCOL_V1) return WS_SUBPROTOCOL_V1;
  }
  return false;
}

/**
 * Token for POST /api/command. Accepts Authorization: Bearer, X-PhonePi-Token,
 * or ?token= while PHONEPI_ALLOW_QUERY_TOKEN is not 0. Never logs the value.
 */
export function tokenFromHttpRequest(
  req: TokenRequest,
  env: NodeJS.ProcessEnv = process.env,
  log: (line: string) => void = console.error
): string | null {
  const header = tokenFromHttpHeaders(req.headers);
  if (header) return header;
  return tokenFromQueryIfAllowed(req.url, env, log);
}

function tokenFromQueryIfAllowed(
  url: string | undefined,
  env: NodeJS.ProcessEnv,
  log: (line: string) => void
): string | null {
  const provided = tokenFromRequestUrl(url);
  if (provided == null) return null;
  if (!allowQueryToken(env)) return null;
  noteQueryTokenUse(log);
  return provided;
}

export function authorizeCommandToken(
  provided: string | null,
  expected: string | null
): { ok: true } | { ok: false; status: number; text: string } {
  if (expected == null) {
    return {
      ok: false,
      status: 401,
      text: "PHONEPI_WS_TOKEN is required for phone commands over HTTP",
    };
  }
  if (provided == null || provided === "") {
    return { ok: false, status: 401, text: "Missing command token" };
  }
  if (!tokenMatches(expected, provided)) {
    return { ok: false, status: 401, text: "Invalid command token" };
  }
  return { ok: true };
}

function headerValue(value: string | string[] | undefined): string | null {
  if (value == null) return null;
  const raw = Array.isArray(value) ? value[0] : value;
  const trimmed = raw?.trim();
  return trimmed ? trimmed : null;
}

export function authorizeWsConnection(
  req: TokenRequest | string | undefined,
  expectedToken: string | null,
  env: NodeJS.ProcessEnv = process.env,
  log: (line: string) => void = console.error
): boolean {
  if (expectedToken == null) {
    return env[INSECURE_NO_TOKEN_ENV] === "1";
  }
  const request: TokenRequest =
    typeof req === "string" || req == null ? { url: req } : req;
  const fromHeader = tokenFromWsHeaders(request.headers);
  if (fromHeader) return tokensEqual(expectedToken, fromHeader);
  const fromQuery = tokenFromQueryIfAllowed(request.url, env, log);
  if (fromQuery == null || fromQuery === "") return false;
  return tokensEqual(expectedToken, fromQuery);
}

export function createPhoneVerifyClient(
  log: (line: string) => void = console.error,
  env: NodeJS.ProcessEnv = process.env
): (info: {
  req: {
    url?: string;
    headers?: NodeJS.Dict<string | string[]>;
    socket: { remoteAddress?: string | null };
  };
}) => boolean {
  return (info) => {
    const address = info.req.socket.remoteAddress;
    log(`Connection attempt from ${address || "unknown"}`);
    if (!authorizeWsConnection(info.req, wsTokenFromEnv(env), env, log)) {
      log("WebSocket connection rejected");
      return false;
    }
    return true;
  };
}

function tokenFromRequestUrl(url: string | undefined): string | null {
  if (!url) return null;
  try {
    return new URL(url, "http://127.0.0.1").searchParams.get("token");
  } catch {
    return null;
  }
}
