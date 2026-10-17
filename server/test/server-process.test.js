import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import net from "node:net";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { WebSocket } from "ws";

const serverDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function freePort() {
  return new Promise((resolve, reject) => {
    const socket = net.createServer();
    socket.once("error", reject);
    socket.listen(0, "127.0.0.1", () => {
      const port = socket.address().port;
      socket.close(() => resolve(port));
    });
  });
}

function request(port, method, requestPath, body) {
  return new Promise((resolve, reject) => {
    const payload = body == null ? null : JSON.stringify(body);
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: requestPath,
        method,
        headers: payload
          ? {
              "Content-Type": "application/json",
              "Content-Length": Buffer.byteLength(payload),
            }
          : {},
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          resolve({ status: res.statusCode, json: JSON.parse(raw), raw });
        });
      }
    );
    req.on("error", reject);
    if (payload) req.end(payload);
    else req.end();
  });
}

function startServer({ wsPort, httpPort, token }) {
  const env = { ...process.env };
  if (token) env.PHONEPI_WS_TOKEN = token;
  else delete env.PHONEPI_WS_TOKEN;

  const child = spawn(
    process.execPath,
    [
      "dist/index.js",
      "--stdio",
      "--port",
      String(wsPort),
      "--http-port",
      String(httpPort),
    ],
    {
      cwd: serverDir,
      env,
      stdio: ["pipe", "pipe", "pipe"],
    }
  );

  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    stdout += chunk;
  });
  child.stderr.on("data", (chunk) => {
    stderr += chunk;
  });

  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`server did not listen\n${stderr}`));
    }, 8000);
    child.stderr.on("data", () => {
      if (stderr.includes(`http://127.0.0.1:${httpPort}`)) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`server exited ${code}\n${stderr}`));
    });
  });

  return {
    child,
    ready,
    output: () => ({ stdout, stderr }),
  };
}

async function stopServer(child) {
  if (child.exitCode != null || child.signalCode != null) return;
  child.kill("SIGTERM");
  await new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      resolve();
    }, 2000);
    child.once("exit", () => {
      clearTimeout(timer);
      resolve();
    });
  });
}

test("running server exposes loopback HTTP, keeps stdout clean, and upgrades /phonepi", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const started = startServer({ wsPort, httpPort });
  try {
    await started.ready;
    const health = await request(httpPort, "GET", "/api/health");
    assert.equal(health.json.ok, true);
    assert.equal(health.json.phoneConnected, false);
    assert.equal(health.json.wsPort, wsPort);
    assert.equal(health.json.httpPort, httpPort);
    assert.equal(health.json.lastConnectedAt, null);
    assert.equal(health.json.lastPongAt, null);
    assert.deepEqual(health.json.missingPermissions, []);

    const denied = await request(httpPort, "POST", "/api/tool", {
      tool: "make_call",
      arguments: { to: "555" },
    });
    assert.equal(denied.status, 403);
    assert.equal(denied.json.isError, true);

    const offline = await request(httpPort, "POST", "/api/tool", {
      tool: "get_battery_level",
      arguments: {},
    });
    assert.equal(offline.status, 503);
    assert.match(offline.json.text, /not connected/i);

    const phone = new WebSocket(`ws://127.0.0.1:${wsPort}/phonepi`);
    phone.on("message", (data) => {
      const message = JSON.parse(String(data));
      if (message.type === "ping") {
        phone.send(JSON.stringify({ type: "pong" }));
        return;
      }
      if (message.type === "request" && message.tool === "get_battery_level") {
        phone.send(
          JSON.stringify({
            type: "response",
            requestId: message.requestId,
            data: { level: 42 },
          })
        );
      }
    });
    await new Promise((resolve, reject) => {
      phone.once("open", resolve);
      phone.once("error", reject);
      phone.once("unexpected-response", (_req, res) => {
        reject(new Error(`upgrade failed ${res.statusCode}`));
      });
    });

    const connected = await request(httpPort, "GET", "/api/health");
    assert.equal(connected.json.phoneConnected, true);
    assert.match(connected.json.lastConnectedAt, /^\d{4}-\d{2}-\d{2}T/);
    assert.match(connected.json.lastPongAt, /^\d{4}-\d{2}-\d{2}T/);

    phone.send(
      JSON.stringify({
        type: "permissions",
        token: "super-secret-token",
        permissions: {
          overlay: true,
          post_notifications: true,
          full_screen_intent: false,
          notification_listener: false,
          dnd_access: true,
          exact_alarms: true,
          token: "super-secret-token",
        },
      })
    );
    let withPerms = null;
    for (let i = 0; i < 20; i++) {
      withPerms = await request(httpPort, "GET", "/api/health");
      if (withPerms.json.phonePermissions) break;
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    assert.equal(withPerms.json.phonePermissions.overlay, true);
    assert.ok(withPerms.json.missingPermissions.includes("full_screen_intent"));
    assert.ok(withPerms.json.missingPermissions.includes("notification_listener"));
    assert.equal(JSON.stringify(withPerms.json).includes("secret"), false);

    const battery = await request(httpPort, "POST", "/api/tool", {
      tool: "get_battery_level",
      arguments: {},
    });
    assert.equal(battery.status, 200);
    assert.equal(battery.json.isError, false);
    assert.deepEqual(JSON.parse(battery.json.text), { level: 42 });

    const replacement = new WebSocket(`ws://127.0.0.1:${wsPort}/phonepi`);
    replacement.on("message", (data) => {
      const message = JSON.parse(String(data));
      if (message.type === "ping") {
        replacement.send(JSON.stringify({ type: "pong" }));
      }
    });
    await new Promise((resolve, reject) => {
      replacement.once("open", resolve);
      replacement.once("error", reject);
    });
    await new Promise((resolve) => setTimeout(resolve, 50));
    const afterReplace = await request(httpPort, "GET", "/api/health");
    assert.equal(afterReplace.json.phoneConnected, true);
    replacement.close();
    phone.close();

    const { stdout, stderr } = started.output();
    assert.equal(stdout, "");
    assert.match(stderr, /Local HTTP API listening/);
    assert.match(stderr, /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z /);
  } finally {
    await stopServer(started.child);
  }
});

test("running server requires the phone token and does not log it", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const token = "phonepi-test-token-do-not-log";
  const started = startServer({ wsPort, httpPort, token });
  try {
    await started.ready;

    const rejected = new WebSocket(`ws://127.0.0.1:${wsPort}/phonepi`);
    const status = await new Promise((resolve, reject) => {
      rejected.once("unexpected-response", (_req, res) => resolve(res.statusCode));
      rejected.once("open", () => resolve("open"));
      rejected.once("error", reject);
    });
    assert.equal(status, 401);

    const accepted = new WebSocket(
      `ws://127.0.0.1:${wsPort}/phonepi?token=${encodeURIComponent(token)}`
    );
    await new Promise((resolve, reject) => {
      accepted.once("open", resolve);
      accepted.once("unexpected-response", (_req, res) => {
        reject(new Error(`upgrade failed ${res.statusCode}`));
      });
      accepted.once("error", reject);
    });
    accepted.close();

    const { stdout, stderr } = started.output();
    assert.equal(stdout, "");
    assert.equal(stderr.includes(token), false);
    assert.match(stderr, /WebSocket connection rejected/);
  } finally {
    await stopServer(started.child);
  }
});
