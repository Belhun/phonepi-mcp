import assert from "node:assert/strict";
import http from "node:http";
import net from "node:net";
import test from "node:test";
import { WebSocket } from "ws";
import { RateLimitError } from "../dist/commands.js";
import {
  READ_ONLY_HTTP_TOOLS,
  READ_ONLY_TOOL_RATE_LIMITS,
} from "../dist/read-only-tools.js";
import {
  authorizeWsConnection,
  createPhoneVerifyClient,
  wsTokenFromEnv,
} from "../dist/ws-token.js";
import { requestPhoneTool } from "../dist/phone-request.js";
import { resolveHttpPort, startLoopbackHttp } from "../dist/loopback-http.js";
import { createPhoneWebSocketServer } from "../dist/phone-ws.js";

const FORBIDDEN = [
  "make_call",
  "find_phone",
  "set_timer",
  "copy_to_clipboard",
  "add_snippet",
  "update_snippet",
  "delete_snippet",
  "add_contact",
  "update_contact",
  "delete_contact",
  "share_snippet",
  "send_message",
  "send_notification",
  "get_message_response",
  "gm_send_message",
  "gm_get_messages",
  "gm_list_conversations",
  "phone_navigate",
  "phone_add_location",
  "not_a_real_tool",
];

function postTool(port, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const payload = typeof body === "string" ? body : JSON.stringify(body);
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: "/api/tool",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(payload),
          ...headers,
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          let json = null;
          try {
            json = JSON.parse(raw);
          } catch {
            json = null;
          }
          resolve({ status: res.statusCode, json, raw });
        });
      }
    );
    req.on("error", reject);
    req.end(payload);
  });
}

function getHealth(port) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: "127.0.0.1", port, path: "/api/health", method: "GET" },
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
    req.end();
  });
}

async function withHttp(bridge, fn) {
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    bridge,
  });
  assert.ok(started, "HTTP API should listen");
  try {
    return await fn(started);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
}

test("read-only allowlist is exactly the twelve phone read tools", () => {
  assert.deepEqual(
    [...READ_ONLY_HTTP_TOOLS].sort(),
    [
      "get_all_snippets",
      "get_battery_level",
      "get_contact_by_id",
      "get_contacts",
      "get_notifications",
      "get_recent_calls",
      "get_snippet_by_id",
      "get_snippets_by_type",
      "get_volumes",
      "list_notification_apps",
      "search_contacts",
      "search_snippets",
    ]
  );
});

test("POST /api/tool rejects everything outside the allowlist with 403 and does not call the phone", async () => {
  let calls = 0;
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async () => {
        calls += 1;
        return { leaked: true };
      },
    },
    async ({ port }) => {
      for (const tool of FORBIDDEN) {
        const res = await postTool(port, { tool, arguments: { to: "555" } });
        assert.equal(res.status, 403, tool);
        assert.equal(res.json.isError, true, tool);
        assert.match(res.json.text, /allowlist/i, tool);
        assert.equal(Object.hasOwn(res.json, "leaked"), false);
      }
      assert.equal(calls, 0);
    }
  );
});

test("POST /api/tool returns the phone result through the shared request path", async () => {
  const pending = new Map();
  const phone = {
    readyState: WebSocket.OPEN,
    send(raw) {
      const message = JSON.parse(raw);
      assert.equal(message.type, "request");
      assert.equal(message.tool, "get_battery_level");
      assert.deepEqual(message.params, {});
      const resolve = pending.get(message.requestId);
      resolve({ level: 81, charging: false });
    },
  };
  let requestId = 0;

  await withHttp(
    {
      isPhoneConnected: () => phone.readyState === WebSocket.OPEN,
      callPhoneTool: (name, args) =>
        requestPhoneTool({
          connection: phone,
          pending,
          requestId: `req-${++requestId}`,
          tool: name,
          params: args,
        }),
    },
    async ({ port }) => {
      const res = await postTool(port, {
        tool: "make_call",
        arguments: { to: "555" },
      });
      assert.equal(res.status, 403);

      const ok = await postTool(port, { tool: "get_battery_level" });
      assert.equal(ok.status, 200);
      assert.equal(ok.json.isError, false);
      assert.deepEqual(JSON.parse(ok.json.text), { level: 81, charging: false });
    }
  );
});

test("each allowlisted tool can be called when the phone answers", async () => {
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => ({ name, args }),
    },
    async ({ port }) => {
      for (const tool of READ_ONLY_HTTP_TOOLS) {
        const res = await postTool(port, { tool, arguments: { query: "x" } });
        assert.equal(res.status, 200, tool);
        assert.equal(res.json.isError, false, tool);
        assert.equal(JSON.parse(res.json.text).name, tool);
      }
    }
  );
});

test("phone not connected returns 503 and does not send a phone request", async () => {
  let calls = 0;
  await withHttp(
    {
      isPhoneConnected: () => false,
      callPhoneTool: async () => {
        calls += 1;
        return {};
      },
    },
    async ({ port }) => {
      const res = await postTool(port, { tool: "get_contacts" });
      assert.equal(res.status, 503);
      assert.equal(res.json.isError, true);
      assert.match(res.json.text, /not connected/i);
      assert.equal(calls, 0);
    }
  );
});

test("GET /api/health reports ports, phone state, and empty history", async () => {
  await withHttp(
    {
      isPhoneConnected: () => false,
      callPhoneTool: async () => ({}),
    },
    async ({ port }) => {
      const res = await getHealth(port);
      assert.equal(res.status, 200);
      assert.deepEqual(Object.keys(res.json).sort(), [
        "httpPort",
        "lastConnectedAt",
        "lastDisconnectCode",
        "lastDisconnectReason",
        "lastDisconnectedAt",
        "lastPongAt",
        "missingPermissions",
        "ok",
        "phoneConnected",
        "phonePermissions",
        "wsPort",
      ]);
      assert.equal(res.json.ok, true);
      assert.equal(res.json.phoneConnected, false);
      assert.equal(res.json.wsPort, 11041);
      assert.equal(res.json.httpPort, port);
      assert.equal(res.json.lastConnectedAt, null);
      assert.equal(res.json.lastDisconnectedAt, null);
      assert.equal(res.json.lastDisconnectCode, null);
      assert.equal(res.json.lastDisconnectReason, null);
      assert.equal(res.json.lastPongAt, null);
      assert.equal(res.json.phonePermissions, null);
      assert.deepEqual(res.json.missingPermissions, []);
      assert.doesNotMatch(res.raw, /contact|snippet|token|battery/i);
    }
  );
});

test("GET /api/health copies permission flags and drops token fields", async () => {
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async () => ({}),
      healthDetails: () => ({
        lastConnectedAt: "2026-10-17T06:00:00.000Z",
        lastDisconnectedAt: "2026-10-17T05:00:00.000Z",
        lastDisconnectCode: 1006,
        lastDisconnectReason: "abnormal closure",
        lastPongAt: "2026-10-17T06:04:00.000Z",
        phonePermissions: {
          overlay: true,
          post_notifications: true,
          full_screen_intent: false,
          notification_listener: false,
          dnd_access: true,
          exact_alarms: true,
          token: "super-secret-token",
        },
        missingPermissions: ["full_screen_intent", "notification_listener", "token"],
        token: "super-secret-token",
      }),
    },
    async ({ port }) => {
      const res = await getHealth(port);
      assert.equal(res.status, 200);
      assert.equal(res.json.phoneConnected, true);
      assert.equal(res.json.lastDisconnectCode, 1006);
      assert.equal(res.json.lastDisconnectReason, "abnormal closure");
      assert.equal(res.json.phonePermissions.overlay, true);
      assert.equal(res.json.phonePermissions.full_screen_intent, false);
      assert.equal(res.json.phonePermissions.token, undefined);
      assert.deepEqual(res.json.missingPermissions, [
        "full_screen_intent",
        "notification_listener",
      ]);
      assert.equal(JSON.stringify(res.json).includes("secret"), false);
      assert.equal(Object.hasOwn(res.json, "token"), false);
    }
  );
});

test("bad tool requests return 400", async () => {
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async () => ({}),
    },
    async ({ port }) => {
      const invalid = await postTool(port, "{");
      assert.equal(invalid.status, 400);
      assert.equal(invalid.json.isError, true);

      const missing = await postTool(port, { arguments: {} });
      assert.equal(missing.status, 400);

      const badArgs = await postTool(port, {
        tool: "get_contacts",
        arguments: ["nope"],
      });
      assert.equal(badArgs.status, 400);
    }
  );
});

test("HTTP API binds loopback only and survives a taken port", async () => {
  const blocker = http.createServer();
  await new Promise((resolve) => blocker.listen(0, "127.0.0.1", resolve));
  const taken = blocker.address().port;
  const logs = [];
  const started = await startLoopbackHttp({
    port: taken,
    wsPort: 11041,
    bridge: {
      isPhoneConnected: () => false,
      callPhoneTool: async () => ({}),
    },
    log: (line) => logs.push(line),
  });
  assert.equal(started, null);
  assert.match(logs.join("\n"), /already in use/i);
  await new Promise((resolve) => blocker.close(resolve));

  const live = await startLoopbackHttp({
    port: 0,
    wsPort: 1,
    bridge: {
      isPhoneConnected: () => true,
      callPhoneTool: async () => ({ ok: true }),
    },
  });
  assert.equal(live.server.address().address, "127.0.0.1");
  await new Promise((resolve) => live.server.close(resolve));
});

test("resolveHttpPort prefers the flag, then PHONEPI_HTTP_PORT, then 11043", () => {
  const notes = [];
  const log = (line) => notes.push(line);
  assert.equal(resolveHttpPort([], {}, log), 11043);
  assert.equal(
    resolveHttpPort([], { PHONEPI_HTTP_PORT: "12001" }, log),
    12001
  );
  assert.equal(
    resolveHttpPort(
      ["--stdio", "--http-port", "13002"],
      { PHONEPI_HTTP_PORT: "12001" },
      log
    ),
    13002
  );
  assert.equal(
    resolveHttpPort([], { PHONEPI_HTTP_PORT: "" }, log),
    11043
  );
  assert.equal(
    resolveHttpPort([], { PHONEPI_HTTP_PORT: "nope" }, log),
    11043
  );
  assert.match(notes.join("\n"), /PHONEPI_HTTP_PORT/);
  assert.equal(
    resolveHttpPort(["--http-port", "0"], {}, log),
    11043
  );
});

test("ws token check is off when unset and constant-time when set", () => {
  const previous = process.env.PHONEPI_WS_TOKEN;
  delete process.env.PHONEPI_WS_TOKEN;
  assert.equal(wsTokenFromEnv(), null);
  process.env.PHONEPI_WS_TOKEN = "";
  assert.equal(wsTokenFromEnv(), null);
  assert.equal(authorizeWsConnection("/phonepi", null), true);
  assert.equal(authorizeWsConnection(undefined, null), true);

  process.env.PHONEPI_WS_TOKEN = "s3cret-value";
  assert.equal(wsTokenFromEnv(), "s3cret-value");
  assert.equal(authorizeWsConnection("/phonepi", "s3cret-value"), false);
  assert.equal(
    authorizeWsConnection("/phonepi?token=nope", "s3cret-value"),
    false
  );
  assert.equal(
    authorizeWsConnection("/phonepi?token=s3cret-value", "s3cret-value"),
    true
  );
  assert.equal(
    authorizeWsConnection("/phonepi?token=s3cret-value&x=1", "s3cret-value"),
    true
  );
  assert.equal(authorizeWsConnection("/?token=s3cret-value", "s3cret-value"), true);
  assert.equal(authorizeWsConnection("/phonepi?token=", "s3cret-value"), false);
  assert.equal(authorizeWsConnection(undefined, "s3cret-value"), false);
  assert.equal(
    authorizeWsConnection("/phonepi?token=short", "much-longer-secret"),
    false
  );

  if (previous === undefined) delete process.env.PHONEPI_WS_TOKEN;
  else process.env.PHONEPI_WS_TOKEN = previous;
});

function onceMessage(ws) {
  return new Promise((resolve, reject) => {
    ws.once("message", (data) => resolve(String(data)));
    ws.once("error", reject);
    ws.once("unexpected-response", (_req, res) => {
      reject(new Error(`unexpected ${res.statusCode}`));
    });
  });
}

function startUpgradeProxy(targetPort) {
  const proxy = http.createServer((_req, res) => {
    res.writeHead(404);
    res.end();
  });
  proxy.on("upgrade", (req, clientSocket, head) => {
    const srv = net.connect(targetPort, "127.0.0.1", () => {
      const lines = Object.entries(req.headers).map(([key, value]) => {
        const text = Array.isArray(value) ? value.join(", ") : value;
        return `${key}: ${text}`;
      });
      srv.write(`GET ${req.url} HTTP/1.1\r\n${lines.join("\r\n")}\r\n\r\n`);
      if (head.length) srv.write(head);
      srv.pipe(clientSocket);
      clientSocket.pipe(srv);
    });
    srv.on("error", () => clientSocket.destroy());
    clientSocket.on("error", () => srv.destroy());
  });
  return new Promise((resolve) => {
    proxy.listen(0, "127.0.0.1", () => resolve(proxy));
  });
}

test("phone websocket accepts /phonepi upgrades behind a reverse proxy", async () => {
  const previous = process.env.PHONEPI_WS_TOKEN;
  process.env.PHONEPI_WS_TOKEN = "proxy-token";
  const logs = [];
  const wss = createPhoneWebSocketServer({
    port: 0,
    host: "127.0.0.1",
    verifyClient: createPhoneVerifyClient((line) => logs.push(line)),
  });
  await new Promise((resolve) => wss.once("listening", resolve));
  const backendPort = wss.address().port;
  wss.on("connection", (ws) => {
    ws.send(JSON.stringify({ type: "ping" }));
  });

  const proxy = await startUpgradeProxy(backendPort);
  const proxyPort = proxy.address().port;

  const rejected = new WebSocket(
    `ws://127.0.0.1:${proxyPort}/phonepi?token=wrong`
  );
  const rejectedCode = await new Promise((resolve) => {
    rejected.on("unexpected-response", (_req, res) => resolve(res.statusCode));
    rejected.on("open", () => resolve("open"));
  });
  assert.equal(rejectedCode, 401);

  const accepted = new WebSocket(
    `ws://127.0.0.1:${proxyPort}/phonepi?token=proxy-token`
  );
  const ping = await onceMessage(accepted);
  assert.match(ping, /ping/);
  accepted.close();

  const direct = new WebSocket(
    `ws://127.0.0.1:${backendPort}/phonepi?token=proxy-token`
  );
  const directPing = await onceMessage(direct);
  assert.match(directPing, /ping/);
  direct.close();

  const joined = logs.join("\n");
  assert.match(joined, /rejected/);
  assert.equal(joined.includes("proxy-token"), false);

  await new Promise((resolve) => proxy.close(resolve));
  await new Promise((resolve) => wss.close(resolve));
  if (previous === undefined) delete process.env.PHONEPI_WS_TOKEN;
  else process.env.PHONEPI_WS_TOKEN = previous;
});

test("starting the HTTP API does not write to stdout", async () => {
  const chunks = [];
  const original = process.stdout.write.bind(process.stdout);
  process.stdout.write = (chunk, encoding, cb) => {
    chunks.push(Buffer.isBuffer(chunk) ? chunk.toString("utf8") : String(chunk));
    return original(chunk, encoding, cb);
  };
  try {
    const live = await startLoopbackHttp({
      port: 0,
      wsPort: 11041,
      bridge: {
        isPhoneConnected: () => false,
        callPhoneTool: async () => ({}),
      },
    });
    await new Promise((resolve) => live.server.close(resolve));
  } finally {
    process.stdout.write = original;
  }
  const noise = chunks.join("");
  assert.equal(noise.includes("HTTP"), false);
  assert.equal(noise.includes("11043"), false);
});

test("notification read tools are on the HTTP allowlist and rate-limited", async () => {
  assert.equal(READ_ONLY_HTTP_TOOLS.has("get_notifications"), true);
  assert.equal(READ_ONLY_HTTP_TOOLS.has("list_notification_apps"), true);
  assert.equal(READ_ONLY_HTTP_TOOLS.has("get_volumes"), true);
  assert.equal(READ_ONLY_TOOL_RATE_LIMITS.get_notifications, 60);
  assert.equal(READ_ONLY_TOOL_RATE_LIMITS.list_notification_apps, 60);
  assert.equal(READ_ONLY_TOOL_RATE_LIMITS.get_volumes, 60);

  const calls = [];
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push({ name, args });
        return { status: "success", tool: name };
      },
    },
    async ({ port }) => {
      const listed = await postTool(port, {
        tool: "list_notification_apps",
        arguments: {},
      });
      assert.equal(listed.status, 200);
      const notes = await postTool(port, {
        tool: "get_notifications",
        arguments: { package: "com.google.android.gm", limit: 10 },
      });
      assert.equal(notes.status, 200);
      assert.deepEqual(calls, [
        { name: "list_notification_apps", args: {} },
        {
          name: "get_notifications",
          args: { package: "com.google.android.gm", limit: 10 },
        },
      ]);
    }
  );

  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async () => {
        throw new RateLimitError("get_notifications", 7);
      },
    },
    async ({ port }) => {
      const res = await postTool(port, {
        tool: "get_notifications",
        arguments: {},
      });
      assert.equal(res.status, 429);
      assert.match(res.json.text, /Rate limited/);
    }
  );
});
