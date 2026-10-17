#!/usr/bin/env node
/**
 * Read-only PhonePi health check.
 * Talks only to http://127.0.0.1:11043/api/health (loopback, that path).
 * Does not read the token, call /api/command, or open the phone WebSocket.
 *
 * Exit 0: phone connected
 * Exit 1: server up, phone offline
 * Exit 2: server down or unreachable
 */
import http from "node:http";
import { pathToFileURL } from "node:url";

const HEALTH_KEYS = [
  "ok",
  "phoneConnected",
  "wsPort",
  "httpPort",
  "lastConnectedAt",
  "lastDisconnectedAt",
  "lastDisconnectCode",
  "lastDisconnectReason",
  "lastPongAt",
  "phonePermissions",
  "missingPermissions",
];

export function resolveHealthUrl(raw) {
  const value =
    raw && String(raw).trim()
      ? String(raw).trim()
      : "http://127.0.0.1:11043/api/health";
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("health check URL is invalid");
  }
  if (url.protocol !== "http:") {
    throw new Error("health check only uses http on loopback");
  }
  if (url.hostname !== "127.0.0.1" && url.hostname !== "localhost") {
    throw new Error("health check only reads loopback");
  }
  if (url.pathname !== "/api/health") {
    throw new Error("health check only reads /api/health");
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error("health check URL must not carry credentials or a query");
  }
  return url;
}

export function offlineForMs(lastDisconnectedAt, nowMs) {
  if (typeof lastDisconnectedAt !== "string" || lastDisconnectedAt.trim() === "") {
    return null;
  }
  const parsed = Date.parse(lastDisconnectedAt);
  if (Number.isNaN(parsed)) return null;
  return Math.max(0, nowMs - parsed);
}

export function formatDuration(ms) {
  const sec = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(sec / 3600);
  const minutes = Math.floor((sec % 3600) / 60);
  const seconds = sec % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export function publicHealth(body) {
  const out = {};
  if (!body || typeof body !== "object") return out;
  for (const key of HEALTH_KEYS) {
    if (Object.prototype.hasOwnProperty.call(body, key)) out[key] = body[key];
  }
  return out;
}

export function evaluateHealth(body, nowMs = Date.now()) {
  if (!body || body.ok !== true || typeof body.phoneConnected !== "boolean") {
    return {
      exitCode: 2,
      line: "phonepi server unreachable",
      json: { status: "server_down" },
    };
  }
  const safe = publicHealth(body);
  if (body.phoneConnected === true) {
    return {
      exitCode: 0,
      line: "phone connected",
      json: { status: "connected", ...safe },
    };
  }
  const offlineMs = offlineForMs(body.lastDisconnectedAt, nowMs);
  const duration = offlineMs == null ? null : formatDuration(offlineMs);
  let line = "phone offline";
  if (duration) line += ` for ${duration}`;
  const detail = [];
  if (typeof body.lastDisconnectedAt === "string" && body.lastDisconnectedAt.trim()) {
    detail.push(`since ${body.lastDisconnectedAt}`);
  } else {
    detail.push("no disconnect timestamp");
  }
  if (typeof body.lastDisconnectCode === "number") {
    detail.push(`code ${body.lastDisconnectCode}`);
  }
  if (
    typeof body.lastDisconnectReason === "string" &&
    body.lastDisconnectReason.trim()
  ) {
    detail.push(body.lastDisconnectReason.trim());
  }
  line += ` (${detail.join(", ")})`;
  return {
    exitCode: 1,
    line,
    json: {
      status: "phone_offline",
      offlineForMs: offlineMs,
      offlineFor: duration,
      ...safe,
    },
  };
}

function fetchHealth(url, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        const raw = Buffer.concat(chunks).toString("utf8");
        if (res.statusCode !== 200) {
          reject(new Error(`health status ${res.statusCode}`));
          return;
        }
        try {
          resolve(JSON.parse(raw));
        } catch {
          reject(new Error("health body was not JSON"));
        }
      });
    });
    req.setTimeout(timeoutMs, () => {
      req.destroy(new Error("health check timed out"));
    });
    req.on("error", reject);
  });
}

async function main() {
  const jsonFlag = process.argv.includes("--json");
  let url;
  try {
    url = resolveHealthUrl(process.env.PHONEPI_HEALTH_URL);
  } catch {
    console.log("phonepi server unreachable");
    if (jsonFlag) console.log(JSON.stringify({ status: "server_down" }));
    process.exit(2);
  }
  let body;
  try {
    body = await fetchHealth(url);
  } catch {
    console.log("phonepi server unreachable");
    if (jsonFlag) console.log(JSON.stringify({ status: "server_down" }));
    process.exit(2);
  }
  const result = evaluateHealth(body);
  console.log(result.line);
  if (jsonFlag) console.log(JSON.stringify(result.json));
  process.exit(result.exitCode);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
