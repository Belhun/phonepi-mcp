/**
 * Outbound wake hook. Posts a tiny JSON body when Alex replies to a
 * notification or submits an inbound prompt, so a bot does not wait for
 * its next poll. Disabled when PHONEPI_WAKE_HOOK_URL is unset.
 *
 * Never log or return the URL or the shared secret.
 */

import http from "http";
import https from "https";
import { URL } from "url";

export const WAKE_EVENTS = ["inbound_prompt", "notification_reply"] as const;
export type WakeEventKind = (typeof WAKE_EVENTS)[number];

export const WAKE_HOOK_URL_ENV = "PHONEPI_WAKE_HOOK_URL";
export const WAKE_HOOK_SECRET_ENV = "PHONEPI_WAKE_HOOK_SECRET";
export const WAKE_HOOK_TIMEOUT_ENV = "PHONEPI_WAKE_HOOK_TIMEOUT_MS";

export const DEFAULT_WAKE_TIMEOUT_MS = 2_000;
export const DEFAULT_WAKE_ATTEMPTS = 3;
export const DEFAULT_WAKE_BACKOFF_MS = [200, 800] as const;

export interface WakePayload {
  event: WakeEventKind;
  id: string;
  ts: number;
}

export interface WakeHookConfig {
  url: string | null;
  secret: string | null;
  timeoutMs: number;
  maxAttempts: number;
  backoffMs: readonly number[];
}

export interface WakeHookStatus {
  wakeHookEnabled: boolean;
  hookFailed: number;
}

export interface WakePostRequest {
  url: string;
  secret: string | null;
  body: WakePayload;
  headers: Record<string, string>;
  timeoutMs?: number;
}

export type WakePoster = (req: WakePostRequest) => Promise<{ ok: boolean }>;

const ID_RE = /^[A-Za-z0-9._-]{8,80}$/;

export function readWakeHookConfig(
  env: NodeJS.ProcessEnv = process.env
): WakeHookConfig {
  const url = parseUrl(env[WAKE_HOOK_URL_ENV]);
  const secretRaw = env[WAKE_HOOK_SECRET_ENV];
  const secret =
    typeof secretRaw === "string" && secretRaw.trim() !== ""
      ? secretRaw
      : null;
  return {
    url,
    secret,
    timeoutMs: parseTimeout(env[WAKE_HOOK_TIMEOUT_ENV]),
    maxAttempts: DEFAULT_WAKE_ATTEMPTS,
    backoffMs: DEFAULT_WAKE_BACKOFF_MS,
  };
}

export function isWakeEventKind(value: unknown): value is WakeEventKind {
  return value === "inbound_prompt" || value === "notification_reply";
}

export function wakeHeaders(secret: string | null): Record<string, string> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (secret) headers["X-PhonePi-Wake-Secret"] = secret;
  return headers;
}

export class WakeHook {
  private hookFailed = 0;
  private readonly inFlight = new Set<string>();
  private readonly succeeded = new Set<string>();
  private idleWaiters: Array<() => void> = [];
  private running = 0;

  constructor(
    private readonly config: WakeHookConfig = readWakeHookConfig(),
    private readonly post: WakePoster = postWake,
    private readonly sleep: (ms: number) => Promise<void> = delay,
    private readonly log: (line: string) => void = defaultLog
  ) {}

  get enabled(): boolean {
    return this.config.url != null;
  }

  status(): WakeHookStatus {
    return {
      wakeHookEnabled: this.enabled,
      hookFailed: this.hookFailed,
    };
  }

  /**
   * Fire-and-forget. Never throws. A hook failure does not fail the caller's
   * request. Duplicate ids that already succeeded, or are in flight, are skipped.
   */
  notify(event: WakeEventKind, id: string, ts: number): void {
    if (!this.enabled) return;
    if (!isWakeEventKind(event) || !ID_RE.test(id)) return;
    if (this.succeeded.has(id) || this.inFlight.has(id)) return;
    this.inFlight.add(id);
    this.running += 1;
    void this.run({ event, id, ts: finiteTs(ts) }).finally(() => {
      this.inFlight.delete(id);
      this.running -= 1;
      if (this.running === 0) {
        const waiters = this.idleWaiters;
        this.idleWaiters = [];
        for (const done of waiters) done();
      }
    });
  }

  waitForIdle(): Promise<void> {
    if (this.running === 0) return Promise.resolve();
    return new Promise((resolve) => this.idleWaiters.push(resolve));
  }

  private async run(payload: WakePayload): Promise<void> {
    const url = this.config.url;
    if (!url) return;
    const req: WakePostRequest = {
      url,
      secret: this.config.secret,
      body: payload,
      headers: wakeHeaders(this.config.secret),
      timeoutMs: this.config.timeoutMs,
    };
    for (let attempt = 1; attempt <= this.config.maxAttempts; attempt++) {
      try {
        const result = await withTimeout(
          this.post(req),
          this.config.timeoutMs
        );
        if (result.ok) {
          this.rememberSuccess(payload.id);
          return;
        }
      } catch {
        // Timeouts and network errors are retries. Never log the URL or secret.
      }
      if (attempt < this.config.maxAttempts) {
        const wait = this.config.backoffMs[attempt - 1] ?? 800;
        await this.sleep(wait);
      }
    }
    this.hookFailed += 1;
    this.log("wake hook failed");
  }

  private rememberSuccess(id: string): void {
    this.succeeded.add(id);
    if (this.succeeded.size > 1_000) {
      const first = this.succeeded.values().next().value;
      if (first) this.succeeded.delete(first);
    }
  }
}

function parseUrl(raw: string | undefined): string | null {
  if (raw == null) return null;
  const text = raw.trim();
  if (text === "") return null;
  try {
    const parsed = new URL(text);
    if (parsed.username || parsed.password) return null;
    if (!parsed.hostname) return null;
    if (!wakeHostAllowed(parsed)) return null;
    return parsed.toString();
  } catch {
    return null;
  }
}

function wakeHostAllowed(parsed: URL): boolean {
  const hostname = parsed.hostname.toLowerCase();
  if (hostname === "metadata.google.internal" || hostname.endsWith(".internal")) {
    return false;
  }
  const ip = literalIp(hostname);
  if (ip) {
    if (isBlockedIp(ip)) return false;
    if (isLoopbackIp(ip) || isTailnetIp(ip)) {
      return parsed.protocol === "http:" || parsed.protocol === "https:";
    }
    return parsed.protocol === "https:";
  }
  if (hostname === "localhost" || hostname.endsWith(".localhost")) {
    return parsed.protocol === "http:" || parsed.protocol === "https:";
  }
  if (hostname.endsWith(".ts.net")) {
    return parsed.protocol === "http:" || parsed.protocol === "https:";
  }
  return parsed.protocol === "https:";
}

function literalIp(hostname: string): string | null {
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(hostname)) return hostname;
  if (hostname.startsWith("[") && hostname.endsWith("]")) {
    return hostname.slice(1, -1);
  }
  if (hostname.includes(":")) return hostname;
  return null;
}

function ipv4ToInt(ip: string): number | null {
  const parts = ip.split(".");
  if (parts.length !== 4) return null;
  let n = 0;
  for (const part of parts) {
    if (!/^\d+$/.test(part)) return null;
    const octet = Number(part);
    if (octet < 0 || octet > 255) return null;
    n = (n << 8) + octet;
  }
  return n >>> 0;
}

function inCidr(ip: string, base: string, bits: number): boolean {
  const a = ipv4ToInt(ip);
  const b = ipv4ToInt(base);
  if (a == null || b == null) return false;
  const mask = bits === 0 ? 0 : (~0 << (32 - bits)) >>> 0;
  return (a & mask) === (b & mask);
}

function isLoopbackIp(ip: string): boolean {
  return inCidr(ip, "127.0.0.0", 8) || ip === "::1" || ip === "0:0:0:0:0:0:0:1";
}

function isTailnetIp(ip: string): boolean {
  return inCidr(ip, "100.64.0.0", 10);
}

function isPrivateLanIp(ip: string): boolean {
  if (inCidr(ip, "10.0.0.0", 8)) return true;
  if (inCidr(ip, "172.16.0.0", 12)) return true;
  if (inCidr(ip, "192.168.0.0", 16)) return true;
  const lower = ip.toLowerCase();
  if (lower.startsWith("fc") || lower.startsWith("fd")) return true;
  return false;
}

function isBlockedIp(ip: string): boolean {
  if (inCidr(ip, "169.254.0.0", 16)) return true;
  if (inCidr(ip, "0.0.0.0", 8)) return true;
  if (ip === "10.10.0.3") return true;
  if (ip.toLowerCase() === "fd00:ec2::254") return true;
  if (ip.toLowerCase().startsWith("fe80:")) return true;
  if (isPrivateLanIp(ip) && !isTailnetIp(ip) && !isLoopbackIp(ip)) return true;
  return false;
}

function parseTimeout(raw: string | undefined): number {
  if (raw == null || raw.trim() === "") return DEFAULT_WAKE_TIMEOUT_MS;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 200 || n > 10_000) return DEFAULT_WAKE_TIMEOUT_MS;
  return n;
}

function finiteTs(ts: number): number {
  return Number.isFinite(ts) ? ts : Date.now();
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function withTimeout<T>(work: Promise<T>, timeoutMs: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timeout")), timeoutMs);
    work.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}

export { wakeHostAllowed };

function defaultLog(line: string): void {
  console.error(line);
}

export function postWake(req: WakePostRequest): Promise<{ ok: boolean }> {
  return new Promise((resolve) => {
    let parsed: URL;
    try {
      parsed = new URL(req.url);
    } catch {
      resolve({ ok: false });
      return;
    }
    if (!wakeHostAllowed(parsed) || parsed.username || parsed.password) {
      resolve({ ok: false });
      return;
    }
    const lib = parsed.protocol === "https:" ? https : http;
    const body = JSON.stringify(req.body);
    const headers = {
      ...req.headers,
      "Content-Length": String(Buffer.byteLength(body)),
    };
    let settled = false;
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      resolve({ ok });
    };
    const request = lib.request(
      {
        protocol: parsed.protocol,
        hostname: parsed.hostname,
        port: parsed.port,
        path: `${parsed.pathname}${parsed.search}`,
        method: "POST",
        headers,
      },
      (res) => {
        res.resume();
        const status = res.statusCode ?? 0;
        finish(status >= 200 && status < 300);
      }
    );
    request.setTimeout(req.timeoutMs ?? DEFAULT_WAKE_TIMEOUT_MS, () => {
      request.destroy();
      finish(false);
    });
    request.on("error", () => {
      request.destroy();
      finish(false);
    });
    request.end(body);
  });
}
