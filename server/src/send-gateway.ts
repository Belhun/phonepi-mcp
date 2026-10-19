/**
 * PhonePi send gateway: the ONLY path that can send a text through Alex's
 * Google Messages bridge. Listens on 127.0.0.1:11044 and forwards just
 * send_message to the read-only bridge on 127.0.0.1:11042, adding the
 * bridge send key (~/.phonepi/bridge-send.key) that unlocks that one tool.
 *
 * Policy (enforced here, documented in /workspace/phonepi-linux/SEND-GATEWAY.md):
 *  - caller presents the gateway token (~/.phonepi/send-gateway.token)
 *  - one recipient per call, resolved from an EXISTING 1:1 conversation
 *  - authorized_by "alex" + authorization_quote + requested_by + mode
 *  - attributed mode gets ATTRIBUTION_PREFIX added server-side
 *  - 5 sends/min and 30 sends/rolling 24h
 *  - append-only JSONL log (~/.phonepi/sent-texts.jsonl, channel "gmessages-gateway")
 *  - dry_run validates and resolves without sending
 *
 * All bots run as the same Unix user, so the token is a speed bump, not isolation.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { ATTRIBUTION_PREFIX } from "./send-text.js";

export const GATEWAY_CHANNEL = "gmessages-gateway";
export const DEFAULT_GATEWAY_PORT = 11044;
export const GATEWAY_MESSAGE_MAX = 1000;
export const GATEWAY_PER_MINUTE = 5;
export const GATEWAY_PER_DAY = 30;
const MINUTE_MS = 60_000;
const DAY_MS = 24 * 60 * MINUTE_MS;
const BODY_MAX = 16 * 1024;
export const BRIDGE_KEY_HEADER = "X-PhonePi-Send-Key";

export const GATEWAY_FIELDS = [
  "conversation_id",
  "phone_number",
  "contact_name",
  "message",
  "mode",
  "authorized_by",
  "authorization_quote",
  "requested_by",
  "dry_run",
] as const;

export type GatewayMode = "as_alex" | "attributed";

export type GatewayStatus =
  | "dry_run_ok"
  | "delivered"
  | "not_delivered"
  | "needs_clarification"
  | "rejected"
  | "rate_limited"
  | "unauthorized"
  | "bridge_error";

export interface GatewayResult {
  ok: boolean;
  status: GatewayStatus;
  error?: string;
  candidates?: Array<{ conversation_id: string; name: string; number: string }>;
  recipient?: { conversation_id: string; name: string; number: string };
  mode?: GatewayMode;
  final_message?: string;
  dry_run?: boolean;
  bridge_text?: string;
  remaining?: { minute: number; day: number };
  note?: string;
}

export interface Conversation {
  conversationId: string;
  name: string;
  isGroup: boolean;
  lastMessageTs: number;
  /** Non-me participants. */
  others: Array<{ name: string; number: string }>;
}

export interface BridgeClient {
  listConversations(): Promise<Conversation[]>;
  sendMessage(args: {
    conversation_id: string;
    phone_number: string;
    message: string;
  }): Promise<{ httpStatus: number; text: string; isError: boolean }>;
}

export interface GatewayPaths {
  tokenFile: string;
  bridgeKeyFile: string;
  logFile: string;
}

export function defaultGatewayPaths(): GatewayPaths {
  const dir = path.join(os.homedir(), ".phonepi");
  return {
    tokenFile: process.env.PHONEPI_SEND_GATEWAY_TOKEN_FILE?.trim() || path.join(dir, "send-gateway.token"),
    bridgeKeyFile: path.join(dir, "bridge-send.key"),
    logFile: process.env.PHONEPI_SEND_LOG?.trim() || path.join(dir, "sent-texts.jsonl"),
  };
}

/* ----------------------------- helpers ----------------------------- */

export function normalizeNumber(raw: string): string {
  const digits = String(raw ?? "").replace(/\D/g, "");
  if (digits.length === 10) return "1" + digits;
  return digits;
}

export function maskNumber(number: string): string {
  const d = String(number ?? "").replace(/\D/g, "");
  if (d.length <= 4) return "*".repeat(d.length || 4);
  return "+" + "*".repeat(d.length - 4) + d.slice(-4);
}

function readSecret(file: string): string {
  try {
    const st = fs.lstatSync(file);
    if (!st.isFile() || (st.mode & 0o077) !== 0) return "";
    const v = fs.readFileSync(file, "utf8").trim();
    return v.length >= 32 ? v : "";
  } catch {
    return "";
  }
}

export function tokenMatches(presented: string | undefined, tokenFile: string): boolean {
  const want = readSecret(tokenFile);
  if (!want || !presented) return false;
  const a = crypto.createHash("sha256").update(presented).digest();
  const b = crypto.createHash("sha256").update(want).digest();
  return crypto.timingSafeEqual(a, b);
}

export function parseConversations(raw: unknown): Conversation[] {
  if (!Array.isArray(raw)) return [];
  const out: Conversation[] = [];
  for (const c of raw as Array<Record<string, unknown>>) {
    if (!c || typeof c.ConversationID !== "string") continue;
    let parts: Array<Record<string, unknown>> = [];
    try {
      const p = typeof c.Participants === "string" ? JSON.parse(c.Participants) : c.Participants;
      if (Array.isArray(p)) parts = p;
    } catch {
      parts = [];
    }
    const seen = new Set<string>();
    const others: Array<{ name: string; number: string }> = [];
    for (const p of parts) {
      if (!p || p.is_me) continue;
      const number = typeof p.number === "string" ? p.number : "";
      const key = normalizeNumber(number) || String(p.name ?? "");
      if (!key || seen.has(key)) continue;
      seen.add(key);
      others.push({ name: String(p.name ?? ""), number });
    }
    out.push({
      conversationId: c.ConversationID,
      name: String(c.Name ?? ""),
      isGroup: Boolean(c.IsGroup),
      lastMessageTs: Number(c.LastMessageTS ?? 0) || 0,
      others,
    });
  }
  return out;
}

function isOneToOne(c: Conversation): boolean {
  return !c.isGroup && c.others.length === 1 && normalizeNumber(c.others[0].number).length >= 7;
}

function candidate(c: Conversation) {
  return {
    conversation_id: c.conversationId,
    name: c.name || c.others[0]?.name || "",
    number: maskNumber(c.others[0]?.number ?? ""),
  };
}

export type Resolution =
  | { ok: true; conv: Conversation; note?: string }
  | { ok: false; status: "needs_clarification" | "rejected"; error: string; candidates?: GatewayResult["candidates"] };

export function resolveRecipient(
  convs: Conversation[],
  sel: { conversation_id?: string; phone_number?: string; contact_name?: string }
): Resolution {
  const byRecent = (a: Conversation, b: Conversation) => b.lastMessageTs - a.lastMessageTs;
  if (sel.conversation_id) {
    const c = convs.find((x) => x.conversationId === sel.conversation_id);
    if (!c) return { ok: false, status: "needs_clarification", error: "conversation_id not found among existing conversations" };
    if (!isOneToOne(c)) {
      return { ok: false, status: "rejected", error: "conversation is a group or has no single phone-number participant; one recipient per call" };
    }
    return { ok: true, conv: c };
  }
  if (sel.phone_number) {
    const want = normalizeNumber(sel.phone_number);
    if (want.length < 7) return { ok: false, status: "needs_clarification", error: "phone_number is not a valid number" };
    const matches = convs.filter((c) => isOneToOne(c) && normalizeNumber(c.others[0].number) === want).sort(byRecent);
    if (matches.length === 0) {
      return {
        ok: false,
        status: "needs_clarification",
        error: "phone_number does not exactly match a participant of any existing 1:1 conversation. Ask Alex which contact he means; the gateway never texts new numbers.",
      };
    }
    return { ok: true, conv: matches[0], note: matches.length > 1 ? `${matches.length} conversations with this number; used the most recent` : undefined };
  }
  if (sel.contact_name) {
    const q = sel.contact_name.trim().toLowerCase();
    const oneToOne = convs.filter(isOneToOne);
    const exact = oneToOne
      .filter((c) => c.name.trim().toLowerCase() === q || c.others[0].name.trim().toLowerCase() === q)
      .sort(byRecent);
    const numbers = new Set(exact.map((c) => normalizeNumber(c.others[0].number)));
    if (exact.length > 0 && numbers.size === 1) {
      return { ok: true, conv: exact[0], note: exact.length > 1 ? `${exact.length} conversations for this contact; used the most recent` : undefined };
    }
    const pool = exact.length > 0 ? exact : oneToOne.filter((c) => c.name.toLowerCase().includes(q) || c.others[0].name.toLowerCase().includes(q)).sort(byRecent);
    return {
      ok: false,
      status: "needs_clarification",
      error: exact.length > 0 ? "contact_name matches more than one person; pick a conversation_id" : "no existing 1:1 conversation has exactly this contact name; pick a conversation_id",
      candidates: pool.slice(0, 8).map(candidate),
    };
  }
  return { ok: false, status: "rejected", error: "one of conversation_id, phone_number, contact_name is required" };
}

/* ----------------------------- validation ----------------------------- */

export interface ValidRequest {
  selector: { conversation_id?: string; phone_number?: string; contact_name?: string };
  message: string;
  mode: GatewayMode;
  authorizationQuote: string;
  requestedBy: string;
  dryRun: boolean;
}

// eslint-disable-next-line no-control-regex
const BAD_CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;

export function validateRequest(body: unknown): { ok: true; value: ValidRequest } | { ok: false; error: string } {
  if (!body || typeof body !== "object" || Array.isArray(body)) return { ok: false, error: "body must be a JSON object" };
  const b = body as Record<string, unknown>;
  const unknown = Object.keys(b).filter((k) => !(GATEWAY_FIELDS as readonly string[]).includes(k));
  if (unknown.length) return { ok: false, error: `unknown field(s): ${unknown.join(", ")}. Allowed: ${GATEWAY_FIELDS.join(", ")}` };

  const sel: ValidRequest["selector"] = {};
  let count = 0;
  for (const k of ["conversation_id", "phone_number", "contact_name"] as const) {
    const v = b[k];
    if (v === undefined || v === null || v === "") continue;
    if (typeof v !== "string") return { ok: false, error: `${k} must be a single string (one recipient per call)` };
    if (k === "phone_number" && /[,;/&]|\band\b/i.test(v)) return { ok: false, error: "phone_number must be ONE number (one recipient per call)" };
    if (v.length > 200) return { ok: false, error: `${k} too long` };
    sel[k] = v.trim();
    count++;
  }
  if (count !== 1) return { ok: false, error: "give exactly one of conversation_id, phone_number, contact_name (one recipient per call)" };

  if (typeof b.message !== "string" || !b.message.trim()) return { ok: false, error: "message is required" };
  if (b.message.length > GATEWAY_MESSAGE_MAX) return { ok: false, error: `message longer than ${GATEWAY_MESSAGE_MAX} characters` };
  if (BAD_CONTROL.test(b.message)) return { ok: false, error: "message contains control characters" };

  if (b.mode !== "as_alex" && b.mode !== "attributed") return { ok: false, error: 'mode must be "as_alex" or "attributed"' };
  if (b.authorized_by !== "alex") return { ok: false, error: 'authorized_by must be "alex" (only Alex can authorize a text)' };
  if (typeof b.authorization_quote !== "string" || b.authorization_quote.trim().length < 8) {
    return { ok: false, error: "authorization_quote is required: Alex's own words naming the person and the message" };
  }
  if (b.authorization_quote.length > 2000) return { ok: false, error: "authorization_quote too long" };
  if (typeof b.requested_by !== "string" || !b.requested_by.trim() || b.requested_by.length > 80) {
    return { ok: false, error: "requested_by is required (name of the bot asking)" };
  }
  if (b.dry_run !== undefined && typeof b.dry_run !== "boolean") return { ok: false, error: "dry_run must be true or false" };

  return {
    ok: true,
    value: {
      selector: sel,
      message: b.message,
      mode: b.mode,
      authorizationQuote: b.authorization_quote.trim(),
      requestedBy: b.requested_by.trim(),
      dryRun: b.dry_run === true,
    },
  };
}

export function finalText(mode: GatewayMode, message: string): string {
  if (mode !== "attributed") return message;
  return message.startsWith(ATTRIBUTION_PREFIX) ? message : ATTRIBUTION_PREFIX + message;
}

/* ----------------------------- rate limit + log ----------------------------- */

export class GatewayRateLimiter {
  private stamps: number[] = [];
  constructor(private perMinute = GATEWAY_PER_MINUTE, private perDay = GATEWAY_PER_DAY, private now = () => Date.now()) {}
  seed(stamps: number[]): void {
    const cut = this.now() - DAY_MS;
    this.stamps = stamps.filter((t) => t > cut).sort((a, b) => a - b);
  }
  private prune(): void {
    const cut = this.now() - DAY_MS;
    this.stamps = this.stamps.filter((t) => t > cut);
  }
  remaining(): { minute: number; day: number } {
    this.prune();
    const t = this.now();
    const minute = this.stamps.filter((s) => s > t - MINUTE_MS).length;
    return { minute: Math.max(0, this.perMinute - minute), day: Math.max(0, this.perDay - this.stamps.length) };
  }
  /** Reserve one send; false if over budget. */
  take(): boolean {
    const r = this.remaining();
    if (r.minute <= 0 || r.day <= 0) return false;
    this.stamps.push(this.now());
    return true;
  }
}

export interface GatewayLogEntry {
  time: string;
  channel: string;
  requested_by: string;
  recipient: string;
  recipient_number: string;
  conversation_id: string;
  mode: string;
  message_sha256: string;
  message_preview: string;
  authorization_quote: string;
  dry_run: boolean;
  attempted: boolean;
  result: GatewayStatus;
  detail?: string;
}

export function appendGatewayLog(entry: GatewayLogEntry, file: string): void {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const line = JSON.stringify(entry).replace(/[\u2028\u2029]/g, " ") + "\n";
  const fd = fs.openSync(file, "a", 0o600);
  try {
    fs.writeSync(fd, line);
  } finally {
    fs.closeSync(fd);
  }
}

/** Real send attempts from the log (for rate-limit state across restarts). */
export function attemptStampsFromLog(file: string): number[] {
  let text = "";
  try {
    text = fs.readFileSync(file, "utf8");
  } catch {
    return [];
  }
  const out: number[] = [];
  for (const line of text.split("\n")) {
    if (!line.includes(GATEWAY_CHANNEL)) continue;
    try {
      const e = JSON.parse(line);
      if (e.channel === GATEWAY_CHANNEL && e.attempted === true) {
        const t = Date.parse(e.time);
        if (Number.isFinite(t)) out.push(t);
      }
    } catch {
      /* skip */
    }
  }
  return out;
}

/* ----------------------------- core ----------------------------- */

export class SendGateway {
  private queue: Promise<unknown> = Promise.resolve();
  constructor(
    private bridge: BridgeClient,
    private paths: GatewayPaths,
    private limiter = new GatewayRateLimiter()
  ) {
    limiter.seed(attemptStampsFromLog(paths.logFile));
  }

  /** Serialized so the rate limit and the log are exact. */
  handle(token: string | undefined, body: unknown): Promise<GatewayResult> {
    const run = this.queue.then(() => this.handleOne(token, body));
    this.queue = run.catch(() => undefined);
    return run;
  }

  private log(partial: Partial<GatewayLogEntry> & { result: GatewayStatus }, message = ""): void {
    try {
      appendGatewayLog(
        {
          time: new Date().toISOString(),
          channel: GATEWAY_CHANNEL,
          requested_by: "",
          recipient: "",
          recipient_number: "",
          conversation_id: "",
          mode: "",
          message_sha256: message ? crypto.createHash("sha256").update(message).digest("hex") : "",
          message_preview: message.slice(0, 40),
          authorization_quote: "",
          dry_run: false,
          attempted: false,
          ...partial,
        },
        this.paths.logFile
      );
    } catch {
      /* logging must not crash the gateway */
    }
  }

  private async handleOne(token: string | undefined, body: unknown): Promise<GatewayResult> {
    if (!tokenMatches(token, this.paths.tokenFile)) {
      this.log({ result: "unauthorized" });
      return { ok: false, status: "unauthorized", error: "missing or wrong gateway token" };
    }
    const v = validateRequest(body);
    const rb = body && typeof body === "object" ? (body as Record<string, unknown>) : {};
    if (!v.ok) {
      this.log({
        result: "rejected",
        detail: v.error,
        requested_by: typeof rb.requested_by === "string" ? rb.requested_by.slice(0, 80) : "",
        dry_run: rb.dry_run === true,
      }, typeof rb.message === "string" ? rb.message : "");
      return { ok: false, status: "rejected", error: v.error };
    }
    const req = v.value;
    const text = finalText(req.mode, req.message);
    const base = {
      requested_by: req.requestedBy,
      mode: req.mode,
      authorization_quote: req.authorizationQuote.slice(0, 500),
      dry_run: req.dryRun,
    };

    let convs: Conversation[];
    try {
      convs = await this.bridge.listConversations();
    } catch (err) {
      const e = `bridge unreachable: ${(err as Error).message}`;
      this.log({ ...base, result: "bridge_error", detail: e }, text);
      return { ok: false, status: "bridge_error", error: e };
    }
    const r = resolveRecipient(convs, req.selector);
    if (!r.ok) {
      this.log({ ...base, result: r.status, detail: r.error }, text);
      return { ok: false, status: r.status, error: r.error, candidates: r.candidates };
    }
    const other = r.conv.others[0];
    const recipient = { conversation_id: r.conv.conversationId, name: r.conv.name || other.name, number: maskNumber(other.number) };
    const where = { recipient: recipient.name, recipient_number: recipient.number, conversation_id: recipient.conversation_id };

    if (req.dryRun) {
      this.log({ ...base, ...where, result: "dry_run_ok", detail: r.note }, text);
      return { ok: true, status: "dry_run_ok", dry_run: true, recipient, mode: req.mode, final_message: text, remaining: this.limiter.remaining(), ...(r.note ? { note: r.note } : {}) };
    }

    if (!this.limiter.take()) {
      this.log({ ...base, ...where, result: "rate_limited" }, text);
      return { ok: false, status: "rate_limited", error: `limit is ${GATEWAY_PER_MINUTE}/minute and ${GATEWAY_PER_DAY}/24h`, remaining: this.limiter.remaining() };
    }

    let res: { httpStatus: number; text: string; isError: boolean };
    try {
      res = await this.bridge.sendMessage({ conversation_id: recipient.conversation_id, phone_number: other.number, message: text });
    } catch (err) {
      const e = `bridge send failed: ${(err as Error).message}`;
      this.log({ ...base, ...where, attempted: true, result: "bridge_error", detail: e }, text);
      return { ok: false, status: "bridge_error", error: e, recipient };
    }
    const delivered = res.httpStatus === 200 && !res.isError && res.text.startsWith("DELIVERED");
    const status: GatewayStatus = delivered ? "delivered" : res.httpStatus === 403 ? "bridge_error" : "not_delivered";
    const detail = res.text.split("\n")[0].slice(0, 300);
    this.log({ ...base, ...where, attempted: true, result: status, detail }, text);
    return {
      ok: delivered,
      status,
      recipient,
      mode: req.mode,
      final_message: text,
      bridge_text: detail,
      remaining: this.limiter.remaining(),
      ...(delivered ? {} : { error: res.httpStatus === 403 ? "bridge refused the send key (check ~/.phonepi/bridge-send.key and that the patched bridge is running)" : "Google Messages did not accept the send" }),
    };
  }
}

/* ----------------------------- bridge client ----------------------------- */

function httpJson(
  opts: http.RequestOptions,
  payload?: string,
  timeoutMs = 60_000
): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request(opts, (res) => {
      const chunks: Buffer[] = [];
      let size = 0;
      res.on("data", (c: Buffer) => {
        size += c.length;
        if (size <= 32 * 1024 * 1024) chunks.push(c);
      });
      res.on("end", () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks).toString("utf8") }));
    });
    req.setTimeout(timeoutMs, () => req.destroy(new Error("timeout")));
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

export function httpBridgeClient(bridgeKeyFile: string, host = "127.0.0.1", port = Number(process.env.OPENMESSAGES_PORT || 11042)): BridgeClient {
  return {
    async listConversations() {
      const r = await httpJson({ host, port, path: "/api/conversations?limit=100000", method: "GET" }, undefined, 15_000);
      if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
      return parseConversations(JSON.parse(r.body));
    },
    async sendMessage(args) {
      const key = readSecret(bridgeKeyFile);
      if (!key) throw new Error("bridge send key missing or has loose permissions");
      const payload = JSON.stringify({ tool: "send_message", arguments: args });
      const r = await httpJson(
        {
          host,
          port,
          path: "/api/tool",
          method: "POST",
          headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload), [BRIDGE_KEY_HEADER]: key },
        },
        payload
      );
      let text = r.body;
      let isError = r.status !== 200;
      try {
        const j = JSON.parse(r.body);
        text = String(j.text ?? j.error ?? r.body);
        isError = isError || Boolean(j.isError);
      } catch {
        /* keep raw */
      }
      return { httpStatus: r.status, text, isError };
    },
  };
}

/* ----------------------------- HTTP server ----------------------------- */

const HTTP_CODE: Record<GatewayStatus, number> = {
  dry_run_ok: 200,
  delivered: 200,
  not_delivered: 502,
  needs_clarification: 422,
  rejected: 400,
  rate_limited: 429,
  unauthorized: 401,
  bridge_error: 502,
};

function presentedToken(req: http.IncomingMessage): string | undefined {
  const auth = req.headers.authorization;
  if (typeof auth === "string" && auth.startsWith("Bearer ")) return auth.slice(7).trim();
  const h = req.headers["x-phonepi-send-token"];
  return typeof h === "string" ? h.trim() : undefined;
}

export function startSendGateway(gw: SendGateway, port = DEFAULT_GATEWAY_PORT, host = "127.0.0.1"): Promise<http.Server> {
  const server = http.createServer((req, res) => {
    const send = (code: number, obj: unknown) => {
      res.writeHead(code, { "Content-Type": "application/json" });
      res.end(JSON.stringify(obj));
    };
    if (req.method === "GET" && req.url === "/health") return send(200, { ok: true, service: "phonepi-send-gateway" });
    if (req.method !== "POST" || req.url !== "/send") return send(404, { ok: false, status: "rejected", error: "POST /send only" });
    const chunks: Buffer[] = [];
    let size = 0;
    let tooBig = false;
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > BODY_MAX) tooBig = true;
      else chunks.push(c);
    });
    req.on("end", async () => {
      if (tooBig) return send(413, { ok: false, status: "rejected", error: "body too large" });
      let body: unknown;
      try {
        body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      } catch {
        return send(400, { ok: false, status: "rejected", error: "invalid JSON" });
      }
      try {
        const r = await gw.handle(presentedToken(req), body);
        send(HTTP_CODE[r.status] ?? 500, r);
      } catch (err) {
        send(500, { ok: false, status: "bridge_error", error: (err as Error).message });
      }
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => resolve(server));
  });
}
