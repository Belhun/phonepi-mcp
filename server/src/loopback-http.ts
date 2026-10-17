import http, { IncomingMessage, Server, ServerResponse } from "http";
import { RateLimitError, getPhoneCommand, isPhoneCommand } from "./commands.js";
import {
  PhoneHealthDetails,
  emptyHealthDetails,
} from "./phone-session.js";
import { isReadOnlyHttpTool } from "./read-only-tools.js";
import {
  authorizeCommandToken,
  tokenFromHttpRequest,
  wsTokenFromEnv,
} from "./ws-token.js";

export const DEFAULT_HTTP_PORT = 11043;
const LOOPBACK_HOST = "127.0.0.1";
const MAX_BODY_BYTES = 1_000_000;

export interface PhoneHttpBridge {
  isPhoneConnected(): boolean;
  callPhoneTool(
    name: string,
    args: Record<string, unknown>
  ): Promise<unknown>;
  /** Optional. Health copies only the known fields; it never spreads extras. */
  healthDetails?: () => PhoneHealthDetails;
}

export interface LoopbackHttpHandle {
  server: Server;
  port: number;
}

export interface StartLoopbackHttpOptions {
  port: number;
  wsPort: number;
  bridge: PhoneHttpBridge;
  log?: (line: string) => void;
  /** Defaults to PHONEPI_WS_TOKEN. Commands over HTTP always require it. */
  commandToken?: string | null;
}

type ToolBody = {
  text: string;
  isError: boolean;
};

export function resolveHttpPort(
  argv: string[],
  env: NodeJS.ProcessEnv,
  log: (line: string) => void = console.error
): number {
  const flagged = readFlag(argv, "--http-port");
  if (flagged.present) {
    const parsed = parsePort(flagged.value);
    if (parsed == null) {
      log(
        `Invalid --http-port. Using default port ${DEFAULT_HTTP_PORT}.`
      );
      return DEFAULT_HTTP_PORT;
    }
    return parsed;
  }

  const fromEnv = env.PHONEPI_HTTP_PORT;
  if (fromEnv == null || fromEnv === "") return DEFAULT_HTTP_PORT;
  const parsed = parsePort(fromEnv);
  if (parsed == null) {
    log(
      `Invalid PHONEPI_HTTP_PORT. Using default port ${DEFAULT_HTTP_PORT}.`
    );
    return DEFAULT_HTTP_PORT;
  }
  return parsed;
}

/**
 * Loopback HTTP API in the same process as the phone WebSocket.
 * Binds 127.0.0.1 only. If the port is taken, logs and returns null so the
 * MCP server keeps running.
 */
export function startLoopbackHttp(
  options: StartLoopbackHttpOptions
): Promise<LoopbackHttpHandle | null> {
  const log = options.log ?? ((line: string) => console.error(line));
  const server = http.createServer((req, res) => {
    void handleRequest(req, res, server, options).catch((err) => {
      log(
        `Local HTTP API error: ${
          err instanceof Error ? err.message : String(err)
        }`
      );
      if (!res.headersSent) {
        sendJson(res, 500, { text: "Internal error", isError: true });
      }
    });
  });

  return new Promise((resolve) => {
    const onError = (err: NodeJS.ErrnoException) => {
      if (err.code === "EADDRINUSE") {
        log(
          `Local HTTP API port ${options.port} is already in use. Continuing without the HTTP API.`
        );
      } else {
        log(
          `Local HTTP API failed to start (${err.code || "error"}). Continuing without the HTTP API.`
        );
      }
      resolve(null);
    };

    server.once("error", onError);
    try {
      server.listen(options.port, LOOPBACK_HOST, () => {
        server.removeListener("error", onError);
        server.on("error", (err) => {
          log(
            `Local HTTP API error: ${
              err instanceof Error ? err.message : String(err)
            }`
          );
        });
        const bound = boundPort(server, options.port);
        log(
          `Local HTTP API listening on http://${LOOPBACK_HOST}:${bound} (read-only /api/tool, commands /api/command)`
        );
        resolve({ server, port: bound });
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log(
        `Local HTTP API failed to start (${message}). Continuing without the HTTP API.`
      );
      resolve(null);
    }
  });
}

async function handleRequest(
  req: IncomingMessage,
  res: ServerResponse,
  server: Server,
  options: StartLoopbackHttpOptions
): Promise<void> {
  const path = requestPath(req);
  if (path === "/api/health") {
    if (req.method !== "GET") {
      sendJson(res, 405, { text: "Method not allowed", isError: true });
      return;
    }
    const details = healthDetailsFrom(options.bridge);
    sendJson(res, 200, {
      ok: true,
      phoneConnected: options.bridge.isPhoneConnected(),
      wsPort: options.wsPort,
      httpPort: boundPort(server, options.port),
      lastConnectedAt: details.lastConnectedAt,
      lastDisconnectedAt: details.lastDisconnectedAt,
      lastDisconnectCode: details.lastDisconnectCode,
      lastDisconnectReason: details.lastDisconnectReason,
      lastPongAt: details.lastPongAt,
      phonePermissions: details.phonePermissions,
      missingPermissions: details.missingPermissions,
    });
    return;
  }

  if (path === "/api/tool") {
    if (req.method !== "POST") {
      sendJson(res, 405, { text: "Method not allowed", isError: true });
      return;
    }
    await handleTool(req, res, options.bridge);
    return;
  }

  if (path === "/api/command") {
    if (req.method !== "POST") {
      sendJson(res, 405, { text: "Method not allowed", isError: true });
      return;
    }
    await handleCommand(req, res, options);
    return;
  }

  sendJson(res, 404, { text: "Not found", isError: true });
}

async function handleTool(
  req: IncomingMessage,
  res: ServerResponse,
  bridge: PhoneHttpBridge
): Promise<void> {
  let parsed: unknown;
  try {
    const raw = await readBody(req);
    if (!raw.trim()) {
      sendJson(res, 400, { text: "Expected a JSON body", isError: true });
      return;
    }
    parsed = JSON.parse(raw);
  } catch (err) {
    const status = (err as { statusCode?: number }).statusCode === 413 ? 413 : 400;
    sendJson(res, status, {
      text: status === 413 ? "Request body too large" : "Invalid JSON",
      isError: true,
    });
    return;
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    sendJson(res, 400, { text: "Expected a JSON object", isError: true });
    return;
  }

  const body = parsed as { tool?: unknown; arguments?: unknown };
  if (typeof body.tool !== "string" || body.tool.trim() === "") {
    sendJson(res, 400, { text: "Expected a tool name", isError: true });
    return;
  }

  if (
    body.arguments !== undefined &&
    (body.arguments === null ||
      typeof body.arguments !== "object" ||
      Array.isArray(body.arguments))
  ) {
    sendJson(res, 400, {
      text: "Expected arguments to be an object",
      isError: true,
    });
    return;
  }

  const args = (body.arguments ?? {}) as Record<string, unknown>;
  if (!isReadOnlyHttpTool(body.tool)) {
    sendJson(res, 403, {
      text: `Tool "${body.tool}" is not on the read-only HTTP allowlist`,
      isError: true,
    });
    return;
  }

  if (!bridge.isPhoneConnected()) {
    sendJson(res, 503, { text: "Phone not connected", isError: true });
    return;
  }

  try {
    const result = await bridge.callPhoneTool(body.tool, args);
    sendJson(res, 200, {
      text: JSON.stringify(result, null, 2),
      isError: false,
    });
  } catch (err) {
    if (err instanceof RateLimitError) {
      sendJson(res, 429, { text: err.message, isError: true });
      return;
    }
    const message = err instanceof Error ? err.message : String(err);
    const unavailable =
      /phone not connected|connection is not open|connection not ready/i.test(
        message
      );
    sendJson(res, unavailable ? 503 : 502, { text: message, isError: true });
  }
}

async function handleCommand(
  req: IncomingMessage,
  res: ServerResponse,
  options: StartLoopbackHttpOptions
): Promise<void> {
  const expected =
    options.commandToken !== undefined
      ? options.commandToken
      : wsTokenFromEnv();
  const auth = authorizeCommandToken(tokenFromHttpRequest(req), expected);
  if (!auth.ok) {
    sendJson(res, auth.status, { text: auth.text, isError: true });
    return;
  }

  let parsed: unknown;
  try {
    const raw = await readBody(req);
    if (!raw.trim()) {
      sendJson(res, 400, { text: "Expected a JSON body", isError: true });
      return;
    }
    parsed = JSON.parse(raw);
  } catch (err) {
    const status = (err as { statusCode?: number }).statusCode === 413 ? 413 : 400;
    sendJson(res, status, {
      text: status === 413 ? "Request body too large" : "Invalid JSON",
      isError: true,
    });
    return;
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    sendJson(res, 400, { text: "Expected a JSON object", isError: true });
    return;
  }

  const body = parsed as { command?: unknown; tool?: unknown; arguments?: unknown };
  const commandName =
    typeof body.command === "string"
      ? body.command
      : typeof body.tool === "string"
        ? body.tool
        : "";
  if (!commandName.trim()) {
    sendJson(res, 400, { text: "Expected a command name", isError: true });
    return;
  }

  if (
    body.arguments !== undefined &&
    (body.arguments === null ||
      typeof body.arguments !== "object" ||
      Array.isArray(body.arguments))
  ) {
    sendJson(res, 400, {
      text: "Expected arguments to be an object",
      isError: true,
    });
    return;
  }

  if (!isPhoneCommand(commandName)) {
    sendJson(res, 403, {
      text: `Command "${commandName}" is not on the phone command allowlist`,
      isError: true,
    });
    return;
  }

  if (!options.bridge.isPhoneConnected()) {
    sendJson(res, 503, { text: "Phone not connected", isError: true });
    return;
  }

  const spec = getPhoneCommand(commandName);
  const args = (body.arguments ?? {}) as Record<string, unknown>;
  try {
    const result = await options.bridge.callPhoneTool(
      spec?.phoneTool ?? commandName,
      args
    );
    sendJson(res, 200, {
      text: JSON.stringify(result, null, 2),
      isError: false,
    });
  } catch (err) {
    if (err instanceof RateLimitError) {
      sendJson(res, 429, { text: err.message, isError: true });
      return;
    }
    const message = err instanceof Error ? err.message : String(err);
    const unavailable =
      /phone not connected|connection is not open|connection not ready/i.test(
        message
      );
    sendJson(res, unavailable ? 503 : 502, { text: message, isError: true });
  }
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let settled = false;
    const finish = (err?: Error & { statusCode?: number }) => {
      if (settled) return;
      settled = true;
      if (err) reject(err);
      else resolve(Buffer.concat(chunks).toString("utf8"));
    };

    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        const err = Object.assign(new Error("Request body too large"), {
          statusCode: 413,
        });
        finish(err);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => finish());
    req.on("error", (err) => finish(err));
    req.on("close", () => {
      if (!req.complete) {
        finish(Object.assign(new Error("Request aborted"), { statusCode: 400 }));
      }
    });
  });
}

function healthDetailsFrom(bridge: PhoneHttpBridge): PhoneHealthDetails {
  const raw = bridge.healthDetails?.() ?? emptyHealthDetails();
  const permissions = sanitizePermissions(raw.phonePermissions);
  const missing =
    permissions == null
      ? []
      : (Object.keys(permissions) as (keyof typeof permissions)[]).filter(
          (name) => permissions[name] !== true
        );
  return {
    lastConnectedAt: isoOrNull(raw.lastConnectedAt),
    lastDisconnectedAt: isoOrNull(raw.lastDisconnectedAt),
    lastDisconnectCode:
      typeof raw.lastDisconnectCode === "number" &&
      Number.isFinite(raw.lastDisconnectCode)
        ? raw.lastDisconnectCode
        : null,
    lastDisconnectReason:
      typeof raw.lastDisconnectReason === "string" &&
      raw.lastDisconnectReason.trim().length > 0
        ? raw.lastDisconnectReason.trim()
        : null,
    lastPongAt: isoOrNull(raw.lastPongAt),
    phonePermissions: permissions,
    missingPermissions: missing,
  };
}

function sanitizePermissions(
  value: PhoneHealthDetails["phonePermissions"]
): PhoneHealthDetails["phonePermissions"] {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const source = value as unknown as Record<string, unknown>;
  const keys = [
    "overlay",
    "post_notifications",
    "full_screen_intent",
    "notification_listener",
    "dnd_access",
    "exact_alarms",
  ] as const;
  const out = {} as NonNullable<PhoneHealthDetails["phonePermissions"]>;
  for (const key of keys) {
    out[key] = source[key] === true;
  }
  return out;
}

function isoOrNull(value: unknown): string | null {
  if (typeof value !== "string" || value.trim() === "") return null;
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) return null;
  return new Date(parsed).toISOString();
}

function sendJson(res: ServerResponse, status: number, body: ToolBody | Record<string, unknown>) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
    "Cache-Control": "no-store",
  });
  res.end(payload);
}

function requestPath(req: IncomingMessage): string {
  try {
    return new URL(req.url || "/", "http://127.0.0.1").pathname;
  } catch {
    return "/";
  }
}

function boundPort(server: Server, fallback: number): number {
  const address = server.address();
  if (address && typeof address === "object") return address.port;
  return fallback;
}

function readFlag(
  argv: string[],
  name: string
): { present: boolean; value?: string } {
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] !== name) continue;
    return { present: true, value: argv[i + 1] };
  }
  return { present: false };
}

function parsePort(value: string | undefined): number | null {
  if (value == null || !/^\d+$/.test(value)) return null;
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  return port;
}
