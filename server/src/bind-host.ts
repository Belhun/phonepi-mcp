/**
 * Phone WebSocket bind address. Default is loopback. Tailscale addresses
 * are allowed. Anything else (0.0.0.0, LAN) needs PHONEPI_ALLOW_WIDE_BIND=1.
 */

import { BlockList, isIP } from "node:net";

export const DEFAULT_WS_HOST = "127.0.0.1";
export const ALLOW_WIDE_BIND_ENV = "PHONEPI_ALLOW_WIDE_BIND";
export const WS_HOST_ENV = "PHONEPI_WS_HOST";

const TAILSCALE_V4 = new BlockList();
TAILSCALE_V4.addSubnet("100.64.0.0", 10, "ipv4");

const TAILSCALE_V6 = new BlockList();
TAILSCALE_V6.addSubnet("fd7a:115c:a1e0::", 48, "ipv6");

export class BindHostError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BindHostError";
  }
}

export function hostFromArgs(args: string[]): string | undefined {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--host" && i + 1 < args.length) {
      return args[i + 1].trim();
    }
  }
  return undefined;
}

export function resolveWsHost(
  args: string[] = [],
  env: NodeJS.ProcessEnv = process.env
): string {
  const fromArgs = hostFromArgs(args);
  const fromEnv = env[WS_HOST_ENV]?.trim();
  const host =
    (fromArgs && fromArgs.length > 0 ? fromArgs : fromEnv) || DEFAULT_WS_HOST;
  assertBindAllowed(host, env);
  return host;
}

export function assertBindAllowed(
  host: string,
  env: NodeJS.ProcessEnv = process.env
): void {
  if (isNarrowHost(host)) return;
  if (env[ALLOW_WIDE_BIND_ENV] === "1") {
    console.error(
      `WARNING: ${ALLOW_WIDE_BIND_ENV}=1; binding ${host} (not loopback or Tailscale). The phone WebSocket is reachable on this address.`
    );
    return;
  }
  throw new BindHostError(
    `PHONEPI_WS_HOST=${host} is not loopback or Tailscale. Set ${ALLOW_WIDE_BIND_ENV}=1 to bind a wide address (0.0.0.0, LAN), or use 127.0.0.1 / a 100.x Tailscale address.`
  );
}

export function isLoopbackHost(host: string): boolean {
  const value = stripBrackets(host).toLowerCase();
  return (
    value === "127.0.0.1" ||
    value === "::1" ||
    value === "localhost" ||
    value === "0:0:0:0:0:0:0:1"
  );
}

export function isTailscaleHost(host: string): boolean {
  const value = stripBrackets(host);
  const version = isIP(value);
  if (version === 4) return TAILSCALE_V4.check(value, "ipv4");
  if (version === 6) return TAILSCALE_V6.check(value, "ipv6");
  const mapped = ipv4Mapped(value);
  if (mapped) return TAILSCALE_V4.check(mapped, "ipv4");
  return false;
}

export function isNarrowHost(host: string): boolean {
  return isLoopbackHost(host) || isTailscaleHost(host);
}

function stripBrackets(host: string): string {
  const trimmed = host.trim();
  if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function ipv4Mapped(host: string): string | null {
  const lower = host.toLowerCase();
  const prefix = "::ffff:";
  if (!lower.startsWith(prefix)) return null;
  const rest = host.slice(prefix.length);
  return isIP(rest) === 4 ? rest : null;
}
