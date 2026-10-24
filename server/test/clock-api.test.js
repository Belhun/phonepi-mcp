import assert from "node:assert/strict";
import fs from "fs";
import http from "node:http";
import os from "os";
import path from "node:path";
import test from "node:test";
import { isPhoneCommand, getPhoneCommand } from "../dist/commands.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";
import { ClockSnapshotStore } from "../dist/clock-snapshot.js";

const REQUEST_ID = "11111111-1111-4111-8111-111111111111";

function post(port, urlPath, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body);
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: urlPath,
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
          resolve({ status: res.statusCode, json: JSON.parse(raw), raw });
        });
      }
    );
    req.on("error", reject);
    req.end(payload);
  });
}

function get(port, urlPath) {
  return new Promise((resolve, reject) => {
    http
      .get({ host: "127.0.0.1", port, path: urlPath }, (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          resolve({ status: res.statusCode, json: JSON.parse(raw), raw });
        });
      })
      .on("error", reject);
  });
}

async function withClock(bridge, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-clock-"));
  const store = new ClockSnapshotStore(dir);
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    bridge,
    commandToken: "test-token",
    clockSnapshot: store,
  });
  assert.ok(started, "HTTP API should listen");
  try {
    return await fn(started, store);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test("clock writes are commands and clock reads stay off that list", () => {
  assert.equal(isPhoneCommand("clock_create_alarm"), true);
  assert.equal(getPhoneCommand("clock_create_alarm")?.maxPerMinute, 20);
  assert.equal(isPhoneCommand("clock_list_alarms"), false);
  assert.equal(isPhoneCommand("set_alarm"), true);
  assert.equal(getPhoneCommand("set_alarm")?.maxPerMinute, 10);
});

test("a clock write without requestId is 400 and does not call the phone", async () => {
  let calls = 0;
  await withClock(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async () => {
        calls += 1;
        return { status: "success" };
      },
    },
    async ({ port }) => {
      const res = await post(
        port,
        "/api/command",
        { command: "clock_create_alarm", arguments: { hour: 7, minute: 30 } },
        { Authorization: "Bearer test-token" }
      );
      assert.equal(res.status, 400);
      assert.match(res.json.text, /requestId/);
      assert.equal(calls, 0);
    }
  );
});

test("the server does not replay a clock write", async () => {
  const calls = [];
  await withClock(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push({ name, args });
        return {
          status: "success",
          replayed: false,
          snapshot: { alarms: [{ id: "a1", hour: args.hour }], health: { status: "ok" } },
        };
      },
    },
    async ({ port }) => {
      const body = {
        command: "clock_create_alarm",
        arguments: {
          requestId: REQUEST_ID,
          hour: 7,
          minute: 45,
          days: "weekdays",
          label: "Work",
        },
      };
      const headers = { Authorization: "Bearer test-token" };
      const first = await post(port, "/api/command", body, headers);
      const second = await post(port, "/api/command", body, headers);
      assert.equal(first.status, 200);
      assert.equal(second.status, 200);
      assert.equal(calls.length, 2);
      assert.equal(calls[0].name, "clock_create_alarm");
      assert.equal(calls[0].args.requestId, REQUEST_ID);
      assert.equal(calls[0].args.days, 31);
      assert.equal(calls[1].args.requestId, REQUEST_ID);
      assert.equal(JSON.parse(second.json.text).replayed, false);
    }
  );
});

test("an offline clock write is 503 and is not queued", async () => {
  let calls = 0;
  await withClock(
    {
      isPhoneConnected: () => false,
      callPhoneTool: async () => {
        calls += 1;
        return { status: "success" };
      },
    },
    async ({ port }) => {
      const res = await post(
        port,
        "/api/command",
        {
          command: "clock_dismiss",
          arguments: { requestId: REQUEST_ID },
        },
        { Authorization: "Bearer test-token" }
      );
      assert.equal(res.status, 503);
      assert.match(res.json.text, /not connected/i);
      assert.equal(calls, 0);
    }
  );
});

test("an offline clock read returns the last snapshot with stale true", async () => {
  await withClock(
    {
      isPhoneConnected: () => false,
      callPhoneTool: async () => {
        throw new Error("phone should not be called");
      },
    },
    async ({ port }, store) => {
      store.saveFrame({
        alarms: [{ id: "sat", hour: 8, minute: 5, label: "Saturday" }],
        nextAlarm: { id: "sat", at: "2026-10-24T08:05:00-07:00", label: "Saturday" },
        health: { status: "watching", batteryOptimized: true },
        updatedAt: "2026-10-24T07:00:00-07:00",
        token: "should-not-persist",
      });
      const list = await post(port, "/api/tool", { tool: "clock_list_alarms", arguments: {} });
      assert.equal(list.status, 200);
      const body = JSON.parse(list.json.text);
      assert.equal(body.stale, true);
      assert.equal(body.alarms[0].id, "sat");
      assert.equal(body.health.status, "watching");
      assert.equal(JSON.stringify(body).includes("should-not-persist"), false);

      const health = await get(port, "/api/health");
      assert.equal(health.json.clockHealth, "watching");
      assert.doesNotMatch(health.raw, /battery|should-not-persist/i);

      const file = fs.readFileSync(store.filePath(), "utf8");
      assert.equal(file.includes("should-not-persist"), false);
      assert.equal(file.includes("batteryOptimized"), true);
      assert.equal(fs.statSync(store.filePath()).mode & 0o777, 0o600);
    }
  );
});

test("REST aliases map onto the same clock tools", async () => {
  const calls = [];
  await withClock(
    {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push({ name, args });
        return { status: "success", name, args };
      },
    },
    async ({ port }) => {
      const missing = await post(port, "/api/clock/alarms", {
        requestId: REQUEST_ID,
        hour: 6,
        minute: 15,
      });
      assert.equal(missing.status, 401);
      assert.equal(calls.length, 0);

      const created = await post(
        port,
        "/api/clock/alarms",
        { requestId: REQUEST_ID, hour: 6, minute: 15, days: ["sat"] },
        { Authorization: "Bearer test-token" }
      );
      assert.equal(created.status, 200);
      assert.equal(calls[0].name, "clock_create_alarm");
      assert.equal(calls[0].args.days, 32);

      const legacy = await post(
        port,
        "/api/command",
        { command: "set_alarm", arguments: { hour: 8, minute: 0, days: "weekdays" } },
        { Authorization: "Bearer test-token" }
      );
      assert.equal(legacy.status, 200);
      assert.equal(calls[1].name, "set_alarm");
    }
  );
});
