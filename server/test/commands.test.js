import assert from "node:assert/strict";
import http from "node:http";
import test from "node:test";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import {
  PHONE_COMMAND_NAMES,
  RateLimitError,
  getPhoneCommand,
  isPhoneCommand,
} from "../dist/commands.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";
import {
  authorizeCommandToken,
  tokenFromHttpRequest,
  tokenMatches,
} from "../dist/ws-token.js";

const FORBIDDEN_COMMANDS = [
  "make_call",
  "find_phone",
  "am_start",
  "shell",
  "intent",
  "get_battery_level",
  "not_a_real_tool",
];

function postCommand(port, body, headers = {}, path = "/api/command") {
  return new Promise((resolve, reject) => {
    const payload = typeof body === "string" ? body : JSON.stringify(body);
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path,
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

async function withHttp(bridge, fn, commandToken = "test-token") {
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    bridge,
    commandToken,
  });
  assert.ok(started, "HTTP API should listen");
  try {
    return await fn(started);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
}

test("command allowlist is the phone commands", () => {
  assert.deepEqual(
    [...PHONE_COMMAND_NAMES].sort(),
    [
      "clear_health_cache",
      "clear_location_history",
      "clear_presence_history",
      "clear_usage_history",
      "dismiss_alarm",
      "dismiss_timer",
      "get_photo",
      "get_send_status",
      "pause_health_sync",
      "pause_location_logging",
      "pause_presence_logging",
      "pause_usage_logging",
      "phone_add_location",
      "phone_list_locations",
      "phone_navigate",
      "phone_push_update",
      "phone_remove_location",
      "phone_set_volume",
      "request_send_text",
      "send_notification",
      "set_alarm",
      "set_timer",
    ]
  );
  for (const name of PHONE_COMMAND_NAMES) {
    assert.equal(isPhoneCommand(name), true);
    assert.equal(getPhoneCommand(name)?.phoneTool, name);
  }
  for (const name of FORBIDDEN_COMMANDS) {
    assert.equal(isPhoneCommand(name), false);
  }
  assert.equal(isPhoneCommand("get_notifications"), false);
  assert.equal(isPhoneCommand("list_notification_apps"), false);
  assert.equal(isPhoneCommand("get_volumes"), false);
  assert.equal(isPhoneCommand("get_recent_photos"), false);
  assert.equal(isPhoneCommand("list_alarms"), false);
  assert.equal(isPhoneCommand("get_screen_time"), false);
  assert.equal(getPhoneCommand("pause_usage_logging")?.maxPerMinute, 10);
  assert.equal(getPhoneCommand("clear_usage_history")?.maxPerMinute, 4);
  assert.equal(getPhoneCommand("set_alarm")?.maxPerMinute, 10);
  assert.equal(getPhoneCommand("set_timer")?.kind, "action");
  assert.equal(isPhoneCommand("get_photo"), true);
  assert.equal(getPhoneCommand("get_photo")?.kind, "read");
  assert.equal(getPhoneCommand("get_photo")?.maxPerMinute, 12);
  assert.equal(isPhoneCommand("phone_set_volume"), true);
});

test("token comparison is length-safe", () => {
  assert.equal(tokenMatches("secret", "secret"), true);
  assert.equal(tokenMatches("secret", "Secret"), false);
  assert.equal(tokenMatches("short", "much-longer-token"), false);
});

test("HTTP command token is required and must match", () => {
  assert.equal(authorizeCommandToken(null, null).ok, false);
  assert.equal(authorizeCommandToken("secret", null).status, 401);
  assert.equal(authorizeCommandToken(null, "secret").ok, false);
  assert.equal(authorizeCommandToken("secret", "secret").ok, true);
  assert.equal(authorizeCommandToken("nope", "secret").ok, false);
});

test("HTTP command token comes from Bearer, header, or query", () => {
  assert.equal(
    tokenFromHttpRequest({
      url: "/api/command",
      headers: { authorization: "Bearer abc" },
    }),
    "abc"
  );
  assert.equal(
    tokenFromHttpRequest({
      url: "/api/command",
      headers: { "x-phonepi-token": "from-header" },
    }),
    "from-header"
  );
  assert.equal(
    tokenFromHttpRequest({
      url: "/api/command?token=from-query",
      headers: {},
    }),
    "from-query"
  );
});

test("rate limiter blocks after the window fills", () => {
  let now = 1_000;
  const limiter = new CommandRateLimiter(60_000, () => now);
  assert.equal(limiter.allow("phone_navigate", 2).ok, true);
  assert.equal(limiter.allow("phone_navigate", 2).ok, true);
  const blocked = limiter.allow("phone_navigate", 2);
  assert.equal(blocked.ok, false);
  now += 60_000;
  assert.equal(limiter.allow("phone_navigate", 2).ok, true);
});

test("POST /api/command requires a token", async () => {
  const calls = [];
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push({ name, args });
        return { status: "success" };
      },
    },
    async (started) => {
      const missing = await postCommand(started.port, {
        command: "phone_list_locations",
        arguments: {},
      });
      assert.equal(missing.status, 401);
      assert.equal(calls.length, 0);

      const wrong = await postCommand(
        started.port,
        { command: "phone_list_locations", arguments: {} },
        { "X-PhonePi-Token": "wrong" }
      );
      assert.equal(wrong.status, 401);
      assert.equal(calls.length, 0);
    }
  );
});

test("POST /api/command allowlist rejects arbitrary intents and existing write tools", async () => {
  const calls = [];
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push(name);
        return { status: "success", tool: name, args };
      },
    },
    async (started) => {
      for (const command of FORBIDDEN_COMMANDS) {
        const res = await postCommand(
          started.port,
          { command, arguments: { action: "android.intent.action.VIEW" } },
          { Authorization: "Bearer test-token" }
        );
        assert.equal(res.status, 403, command);
      }
      assert.equal(calls.length, 0);

      const ok = await postCommand(
        started.port,
        {
          command: "phone_navigate",
          arguments: { name: "home" },
        },
        { Authorization: "Bearer test-token" }
      );
      assert.equal(ok.status, 200);
      assert.deepEqual(calls, ["phone_navigate"]);
    }
  );
});

test("POST /api/command does not go through the read-only /api/tool allowlist", async () => {
  const calls = [];
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async (name) => {
        calls.push(name);
        return { status: "success" };
      },
    },
    async (started) => {
      const viaTool = await postCommand(
        started.port,
        { tool: "phone_navigate", arguments: { name: "home" } },
        { Authorization: "Bearer test-token" },
        "/api/tool"
      );
      assert.equal(viaTool.status, 403);
      assert.equal(calls.length, 0);
    }
  );
});

test("POST /api/command maps RateLimitError to 429", async () => {
  await withHttp(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async () => {
        throw new RateLimitError("phone_navigate", 12);
      },
    },
    async (started) => {
      const res = await postCommand(
        started.port,
        { command: "phone_navigate", arguments: { address: "Austin" } },
        { "X-PhonePi-Token": "test-token" }
      );
      assert.equal(res.status, 429);
      assert.match(res.json.text, /Rate limited/);
    }
  );
});

test("POST /api/command returns 503 when the phone is disconnected", async () => {
  await withHttp(
    {
      isPhoneConnected: () => false,
      callPhoneTool: async () => {
        throw new Error("should not be called");
      },
    },
    async (started) => {
      const res = await postCommand(
        started.port,
        { command: "phone_list_locations" },
        { Authorization: "Bearer test-token" }
      );
      assert.equal(res.status, 503);
    }
  );
});
