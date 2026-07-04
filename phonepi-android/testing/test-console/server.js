/**
 * PhonePi desktop test console.
 * - WebSocket server for the phone (port 11041)
 * - Web UI for picking tools, editing params, and viewing responses (port 8765)
 *
 * Run: node phonepi-android/testing/test-console/server.js
 * Open: http://127.0.0.1:8765
 */

const http = require("http");
const fs = require("fs");
const path = require("path");
const { TOOLS } = require("./tools-schema");

const WS_PORT = Number(process.env.PHONEPI_WS_PORT || 11041);
const UI_PORT = Number(process.env.PHONEPI_UI_PORT || 8765);
const PUBLIC_DIR = path.join(__dirname, "public");

function loadWs() {
  try {
    return require("ws");
  } catch {
    const serverModules = path.resolve(__dirname, "..", "..", "..", "server", "node_modules");
    return require(path.join(serverModules, "ws"));
  }
}

const { WebSocketServer, WebSocket } = loadWs();

/** @typedef {{ id: string, label: string, group: string, description?: string, danger?: boolean, fields: FieldDef[] }} ToolDef */
/** @typedef {{ key: string, label: string, type: string, required?: boolean, default?: unknown, options?: string[], min?: number }} FieldDef */

let phoneSocket = null;
let requestId = 0;
const pending = new Map();
const sseClients = new Set();
const logEntries = [];
const MAX_LOG = 200;

function broadcast(event) {
  logEntries.unshift(event);
  if (logEntries.length > MAX_LOG) logEntries.length = MAX_LOG;
  const data = `data: ${JSON.stringify(event)}\n\n`;
  for (const res of sseClients) {
    res.write(data);
  }
}

function log(type, message, extra = {}) {
  const detail = extra.detail ?? formatResponseDetail(extra.tool, extra.data);
  const entry = { ts: Date.now(), type, message, detail, ...extra };
  console.log(`[${type}] ${message}`, detail ? `\n  → ${detail}` : "");
  broadcast(entry);
}

function formatResponseDetail(tool, data) {
  if (!data) return "";
  if (data.status === "error") return data.error || "Unknown error";
  if (tool === "set_timer") {
    if (data.via === "alarm_manager") {
      return data.handoffDetail || data.message || "Used PhonePi fallback instead of Clock";
    }
    if (data.via === "clock_app") return "Clock app accepted the timer";
  }
  if (data.handoffDetail) return data.handoffDetail;
  return "";
}

function responseLogType(tool, data) {
  if (data.status === "error") return "fail";
  if (tool === "set_timer" && data.via === "alarm_manager") return "warn";
  return "pass";
}

function phoneStatus() {
  return {
    connected: phoneSocket?.readyState === WebSocket.OPEN,
    host: "0.0.0.0",
    wsPort: WS_PORT,
    uiPort: UI_PORT,
  };
}

function sendToPhone(tool, params) {
  if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
    throw new Error("Phone not connected — open PhonePi on the device and tap Connect.");
  }
  const id = `req-${++requestId}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`Timeout waiting for ${tool}`));
    }, 30000);
    pending.set(id, { tool, resolve, reject, timer });
    phoneSocket.send(JSON.stringify({ type: "request", requestId: id, tool, params }));
    log("send", `Sent ${tool}`, { tool, requestId: id, params });
  });
}

// --- WebSocket server (phone connects here) ---
const wss = new WebSocketServer({ host: "0.0.0.0", port: WS_PORT });

wss.on("connection", (ws) => {
  if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN) {
    phoneSocket.close(1000, "replaced");
  }
  phoneSocket = ws;
  log("connection", "Phone connected");
  ws.send(JSON.stringify({ type: "ping" }));

  const ping = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "ping" }));
  }, 15000);

  ws.on("message", (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.type === "pong") return;
    if (msg.type === "response") {
      const entry = pending.get(msg.requestId);
      const data = msg.data ?? {};
      const tool = entry?.tool ?? "tool";
      const logType = responseLogType(tool, data);
      const detail = formatResponseDetail(tool, data);
      log(logType, `${tool} response`, {
        tool,
        requestId: msg.requestId,
        data,
        detail,
      });
      if (entry) {
        clearTimeout(entry.timer);
        pending.delete(msg.requestId);
        entry.resolve(data);
      }
      return;
    }
    log("info", "Phone message", { detail: msg });
  });

  ws.on("close", () => {
    clearInterval(ping);
    if (phoneSocket === ws) phoneSocket = null;
    log("connection", "Phone disconnected");
  });
});

// --- HTTP UI + API ---
function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}

function serveStatic(req, res) {
  let filePath = req.url === "/" ? "/index.html" : req.url.split("?")[0];
  const abs = path.normalize(path.join(PUBLIC_DIR, filePath));
  if (!abs.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }
  if (!fs.existsSync(abs) || fs.statSync(abs).isDirectory()) {
    res.writeHead(404);
    res.end("Not found");
    return;
  }
  const ext = path.extname(abs);
  const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" };
  res.writeHead(200, { "Content-Type": types[ext] || "application/octet-stream" });
  fs.createReadStream(abs).pipe(res);
}

const server = http.createServer(async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  if (req.method === "OPTIONS") {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.url === "/api/tools" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(TOOLS));
    return;
  }

  if (req.url === "/api/status" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(phoneStatus()));
    return;
  }

  if (req.url === "/api/log" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(logEntries));
    return;
  }

  if (req.url === "/api/events" && req.method === "GET") {
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    res.write("\n");
    res.write(`data: ${JSON.stringify({ type: "snapshot", entries: logEntries })}\n\n`);
    sseClients.add(res);
    req.on("close", () => sseClients.delete(res));
    return;
  }

  if (req.url === "/api/run" && req.method === "POST") {
    try {
      const body = await readBody(req);
      const data = await sendToPhone(body.tool, body.params ?? {});
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, data }));
    } catch (e) {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: e.message }));
    }
    return;
  }

  if (req.url === "/api/run-suite" && req.method === "POST") {
    try {
      const body = await readBody(req);
      const delayMs = body.delayMs ?? 800;
      const skipDanger = body.skipDanger !== false;
      const tools = body.tools ?? TOOLS.filter((t) => !skipDanger || !t.danger).map((t) => t.id);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, started: tools.length }));
      (async () => {
        for (const toolId of tools) {
          const def = TOOLS.find((t) => t.id === toolId);
          if (!def) continue;
          const params = buildDefaultParams(def);
          try {
            await sendToPhone(toolId, params);
          } catch (e) {
            log("fail", e.message, { tool: toolId });
          }
          await sleep(delayMs);
        }
        log("info", "Suite finished");
      })();
    } catch (e) {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: false, error: e.message }));
    }
    return;
  }

  if (req.method === "GET") {
    serveStatic(req, res);
    return;
  }

  res.writeHead(404);
  res.end("Not found");
});

function buildDefaultParams(def) {
  const params = {};
  for (const field of def.fields) {
    if (field.default === undefined) continue;
    if (field.key === "responseOptions" && typeof field.default === "string" && field.default === "") continue;
    if (field.type === "json") params[field.key] = field.default;
    else params[field.key] = field.default;
  }
  return params;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

server.listen(UI_PORT, "127.0.0.1", () => {
  console.log(`PhonePi test console`);
  console.log(`  UI:  http://127.0.0.1:${UI_PORT}`);
  console.log(`  WS:  ws://0.0.0.0:${WS_PORT} (phone connects here)`);
  console.log(`  adb reverse tcp:${WS_PORT} tcp:${WS_PORT}`);
});

log("info", `Web UI ready on http://127.0.0.1:${UI_PORT}`);
