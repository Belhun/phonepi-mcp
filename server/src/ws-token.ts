import { createHash, timingSafeEqual } from "crypto";

/**
 * Shared secret for the phone WebSocket. Empty or unset means accept every
 * client, which is the historical behavior.
 */
export function wsTokenFromEnv(
  env: NodeJS.ProcessEnv = process.env
): string | null {
  const raw = env.PHONEPI_WS_TOKEN;
  if (raw == null || raw === "") return null;
  return raw;
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

/**
 * Token for POST /api/command. Accepts Authorization: Bearer, X-PhonePi-Token,
 * or ?token= on the request URL. Never logs the value.
 */
export function tokenFromHttpRequest(req: {
  url?: string;
  headers: NodeJS.Dict<string | string[]>;
}): string | null {
  const header = headerValue(req.headers["x-phonepi-token"]);
  if (header) return header;
  const auth = headerValue(req.headers.authorization);
  if (auth) {
    const match = /^Bearer\s+(\S+)/i.exec(auth);
    if (match) return match[1];
  }
  return tokenFromRequestUrl(req.url);
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
  url: string | undefined,
  expectedToken: string | null
): boolean {
  if (expectedToken == null) return true;
  const provided = tokenFromRequestUrl(url);
  if (provided == null || provided === "") return false;
  return tokensEqual(expectedToken, provided);
}

export function createPhoneVerifyClient(
  log: (line: string) => void = console.error
): (info: {
  req: { url?: string; socket: { remoteAddress?: string | null } };
}) => boolean {
  return (info) => {
    const address = info.req.socket.remoteAddress;
    log(`Connection attempt from ${address || "unknown"}`);
    if (!authorizeWsConnection(info.req.url, wsTokenFromEnv())) {
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
