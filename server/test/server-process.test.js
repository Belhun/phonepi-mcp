import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import net from "node:net";
import os from "node:os";
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

function startServer({ wsPort, httpPort, token, extraEnv = {} }) {
  const env = { ...process.env };
  if (token) {
    env.PHONEPI_WS_TOKEN = token;
    delete env.PHONEPI_INSECURE_NO_TOKEN;
  } else {
    delete env.PHONEPI_WS_TOKEN;
    env.PHONEPI_INSECURE_NO_TOKEN = "1";
  }

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
      env: { ...env, ...extraEnv },
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
      if (
        stderr.includes(`http://127.0.0.1:${httpPort}`) &&
        stderr.includes("WebSocket server is listening")
      ) {
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
    assert.equal(health.json.wsListening, true);
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
    assert.match(stderr, /listening on 127\.0\.0\.1:/);
    assert.match(stderr, /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z /);
  } finally {
    await stopServer(started.child);
  }
});

test("running server requires the phone token and does not log it", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const token = "phonepi-test-token-do-not-log-xx";
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

    const accepted = new WebSocket(`ws://127.0.0.1:${wsPort}/phonepi`, {
      headers: { Authorization: `Bearer ${token}` },
    });
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
    assert.equal(stderr.includes("Bearer"), false);
  } finally {
    await stopServer(started.child);
  }
});

test("server exits when PHONEPI_WS_TOKEN is missing", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const env = { ...process.env };
  delete env.PHONEPI_WS_TOKEN;
  delete env.PHONEPI_INSECURE_NO_TOKEN;
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
    { cwd: serverDir, env, stdio: ["pipe", "pipe", "pipe"] }
  );
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => {
    stderr += chunk;
  });
  const code = await new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      resolve("timeout");
    }, 8000);
    child.once("exit", (exitCode) => {
      clearTimeout(timer);
      resolve(exitCode);
    });
  });
  assert.equal(code, 1);
  assert.match(stderr, /PHONEPI_WS_TOKEN required/);
  assert.equal(stderr.includes("token="), false);
});

test("server refuses 0.0.0.0 without PHONEPI_ALLOW_WIDE_BIND", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const env = { ...process.env };
  env.PHONEPI_WS_TOKEN = "phonepi-test-token-do-not-log-xx";
  env.PHONEPI_WS_HOST = "0.0.0.0";
  delete env.PHONEPI_ALLOW_WIDE_BIND;
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
    { cwd: serverDir, env, stdio: ["pipe", "pipe", "pipe"] }
  );
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => {
    stderr += chunk;
  });
  const code = await new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      resolve("timeout");
    }, 8000);
    child.once("exit", (exitCode) => {
      clearTimeout(timer);
      resolve(exitCode);
    });
  });
  assert.equal(code, 1);
  assert.match(stderr, /PHONEPI_ALLOW_WIDE_BIND=1/);
  assert.equal(stderr.includes(env.PHONEPI_WS_TOKEN), false);
});

test("second server on a busy phone socket exits 1", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const first = startServer({ wsPort, httpPort });
  await first.ready;
  try {
    const httpPort2 = await freePort();
    const env = { ...process.env };
    env.PHONEPI_INSECURE_NO_TOKEN = "1";
    delete env.PHONEPI_WS_TOKEN;
    const child = spawn(
      process.execPath,
      [
        "dist/index.js",
        "--stdio",
        "--port",
        String(wsPort),
        "--http-port",
        String(httpPort2),
      ],
      { cwd: serverDir, env, stdio: ["pipe", "pipe", "pipe"] }
    );
    let stderr = "";
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    const code = await new Promise((resolve) => {
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        resolve("timeout");
      }, 5000);
      child.once("exit", (exitCode) => {
        clearTimeout(timer);
        resolve(exitCode);
      });
    });
    assert.equal(code, 1);
    assert.match(stderr, /failed to listen/i);
    assert.equal(stderr.includes("token="), false);
  } finally {
    await stopServer(first.child);
  }
});

test("server rejects the README example WS token", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const env = { ...process.env };
  env.PHONEPI_WS_TOKEN = "choose-a-long-secret-at-least-32-chars";
  delete env.PHONEPI_INSECURE_NO_TOKEN;
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
    { cwd: serverDir, env, stdio: ["pipe", "pipe", "pipe"] }
  );
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => {
    stderr += chunk;
  });
  const code = await new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      resolve("timeout");
    }, 8000);
    child.once("exit", (exitCode) => {
      clearTimeout(timer);
      resolve(exitCode);
    });
  });
  assert.equal(code, 1);
  assert.match(stderr, /placeholder/i);
  assert.equal(stderr.includes(env.PHONEPI_WS_TOKEN), false);
});

test("loopback bind and a header-token WS upgrade returns 101", async () => {
  const wsPort = await freePort();
  const httpPort = await freePort();
  const token = "phonepi-test-token-do-not-log-xx";
  const started = startServer({ wsPort, httpPort, token });
  try {
    await started.ready;
    const { stderr } = started.output();
    assert.match(stderr, /listening on 127\.0\.0\.1:/);

    const status = await new Promise((resolve, reject) => {
      const req = http.request(
        {
          host: "127.0.0.1",
          port: wsPort,
          path: "/phonepi",
          method: "GET",
          headers: {
            Connection: "Upgrade",
            Upgrade: "websocket",
            "Sec-WebSocket-Version": "13",
            "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==",
            Authorization: `Bearer ${token}`,
          },
        },
        (res) => resolve(res.statusCode)
      );
      req.on("upgrade", (_res) => resolve(101));
      req.on("error", reject);
      req.end();
    });
    assert.equal(status, 101);
    assert.equal(started.output().stderr.includes(token), false);
  } finally {
    await stopServer(started.child);
  }
});

test("phonepi-env-check.sh rejects a placeholder WS token without printing it", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-env-check-"));
  const envFile = path.join(dir, "env");
  const placeholder = "choose-a-long-secret-at-least-32-chars";
  fs.writeFileSync(envFile, `PHONEPI_WS_TOKEN=${placeholder}\n`);
  const script = path.resolve(serverDir, "../scripts/phonepi-env-check.sh");
  const child = spawn("bash", [script], {
    env: { ...process.env, PHONEPI_ENV_FILE: envFile },
    stdio: ["ignore", "pipe", "pipe"],
  });
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
  const code = await new Promise((resolve) => {
    child.once("exit", (exitCode) => resolve(exitCode));
  });
  fs.rmSync(dir, { recursive: true, force: true });
  assert.equal(code, 1);
  assert.match(stderr, /placeholder/i);
  assert.equal(stderr.includes(placeholder), false);
  assert.equal(stdout.includes(placeholder), false);
});
