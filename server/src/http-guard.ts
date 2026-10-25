/**
 * Loopback HTTP Host / Origin / Content-Type hardening.
 * Default Host allowlist is 127.0.0.1, localhost, and [::1] on the bound port.
 * PHONEPI_HTTP_HOSTS can add Tailscale Serve / failover names that work today.
 */

import type { IncomingMessage } from "http";

export const DEFAULT_HTTP_HOSTS = ["127.0.0.1", "localhost", "[::1]"];

export type HttpGuardResult =
  | { ok: true }
  | { ok: false; status: number; text: string };

export function parseHttpHostAllowlist(
  env: NodeJS.ProcessEnv = process.env
): string[] {
  const raw = env.PHONEPI_HTTP_HOSTS?.trim();
  if (!raw) return [...DEFAULT_HTTP_HOSTS];
  const hosts = raw
    .split(",")
    .map((part) => part.trim().toLowerCase())
    .filter(Boolean);
  return hosts.length > 0 ? hosts : [...DEFAULT_HTTP_HOSTS];
}

export function guardLoopbackRequest(
  req: IncomingMessage,
  boundPort: number,
  options: {
    allowlist?: string[];
    requireJson?: boolean;
  } = {}
): HttpGuardResult {
  const allowlist = options.allowlist ?? parseHttpHostAllowlist();
  const host = headerValue(req.headers.host);
  if (!hostAllowed(host, boundPort, allowlist)) {
    return { ok: false, status: 403, text: "Forbidden host" };
  }
  const origin = headerValue(req.headers.origin);
  if (origin) {
    return { ok: false, status: 403, text: "Forbidden origin" };
  }
  const fetchSite = headerValue(req.headers["sec-fetch-site"]);
  if (fetchSite && fetchSite.toLowerCase() === "cross-site") {
    return { ok: false, status: 403, text: "Forbidden cross-site request" };
  }
  if (options.requireJson !== false && isPost(req)) {
    const contentType = headerValue(req.headers["content-type"]);
    if (!isJsonContentType(contentType)) {
      return {
        ok: false,
        status: 415,
        text: "Content-Type must be application/json",
      };
    }
  }
  return { ok: true };
}

export function hostAllowed(
  hostHeader: string | null,
  boundPort: number,
  allowlist: string[]
): boolean {
  if (!hostHeader) return false;
  const parsed = parseHostHeader(hostHeader);
  if (!parsed) return false;
  const hostname = parsed.hostname.toLowerCase();
  for (const entry of allowlist) {
    const allowed = parseAllowlistEntry(entry, boundPort);
    if (!allowed) continue;
    if (allowed.hostname !== hostname) continue;
    if (parsed.port !== allowed.port) continue;
    return true;
  }
  return false;
}

function parseHostHeader(
  raw: string
): { hostname: string; port: number } | null {
  const text = raw.trim();
  if (!text) return null;
  if (text.startsWith("[")) {
    const close = text.indexOf("]");
    if (close < 0) return null;
    const hostname = text.slice(0, close + 1).toLowerCase();
    const rest = text.slice(close + 1);
    if (rest === "") return { hostname, port: 80 };
    if (!rest.startsWith(":")) return null;
    const port = Number(rest.slice(1));
    if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
    return { hostname, port };
  }
  const colon = text.lastIndexOf(":");
  if (colon < 0) return { hostname: text.toLowerCase(), port: 80 };
  const hostname = text.slice(0, colon).toLowerCase();
  const port = Number(text.slice(colon + 1));
  if (!hostname || !Number.isInteger(port) || port < 1 || port > 65535) {
    return null;
  }
  return { hostname, port };
}

function parseAllowlistEntry(
  entry: string,
  boundPort: number
): { hostname: string; port: number } | null {
  const trimmed = entry.trim().toLowerCase();
  if (!trimmed) return null;
  if (trimmed.startsWith("[") && /\]:\d+$/.test(trimmed)) {
    return parseHostHeader(trimmed);
  }
  if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
    return { hostname: trimmed, port: boundPort };
  }
  if (/:\d+$/.test(trimmed)) return parseHostHeader(trimmed);
  return { hostname: trimmed, port: boundPort };
}

function isPost(req: IncomingMessage): boolean {
  return (req.method || "GET").toUpperCase() === "POST";
}

function isJsonContentType(value: string | null): boolean {
  if (!value) return false;
  const media = value.split(";")[0]?.trim().toLowerCase();
  return media === "application/json";
}

function headerValue(value: string | string[] | undefined): string | null {
  if (value == null) return null;
  const raw = Array.isArray(value) ? value[0] : value;
  const trimmed = raw?.trim();
  return trimmed ? trimmed : null;
}
