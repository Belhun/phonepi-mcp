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
function tokensEqual(expected: string, provided: string): boolean {
  const left = createHash("sha256").update(expected, "utf8").digest();
  const right = createHash("sha256").update(provided, "utf8").digest();
  return timingSafeEqual(left, right);
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
