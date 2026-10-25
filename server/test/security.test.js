import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "path";
import test from "node:test";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";
import { PresenceLog } from "../dist/presence-log.js";
import { DEFAULT_PRESENCE_CONFIG } from "../dist/presence-config.js";
import { authorizeSensitiveRead, loadReadAuth, SENSITIVE_READ_TOOLS } from "../dist/read-auth.js";
import { guardLoopbackRequest } from "../dist/http-guard.js";
import { readWakeHookConfig, WAKE_HOOK_URL_ENV } from "../dist/wake-hook.js";
import { wrapUntrusted } from "../dist/untrusted.js";
import { validatePhoneCommandArgs } from "../dist/commands.js";
import { TEST_READ_AUTH, TEST_READ_TOKEN, readHeaders } from "./read-auth-fixture.js";

const NOW = Date.parse("2026-10-25T09:00:00Z");

function tempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function snapshot(overrides = {}) {
  return {
    capturedAtMs: NOW,
    enabled: true,
    paused: false,
    place: "Work",
    minutesAtPlace: 10,
    arrivedAtMs: NOW - 10 * 60_000,
    driving: false,
    moving: false,
    activity: "still",
    dnd: "off",
    latitude: 33.5,
    longitude: -117.15,
    accuracyMeters: 12,
    lastEvent: "arrive",
    lastEventPlace: "Work",
    lastEventMs: NOW - 10 * 60_000,
    source: "periodic",
    ...overrides,
  };
}

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

async function withApi(extra, fn) {
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    commandToken: "test-token",
    readAuth: TEST_READ_AUTH,
    presenceNow: () => NOW,
    locationReadLimiter: new CommandRateLimiter(60_000, () => NOW),
    bridge: {
      isPhoneConnected: () => false,
      callPhoneTool: async () => {
        throw new Error("should not call the phone");
      },
    },
    ...extra,
  });
  assert.ok(started);
  try {
    return await fn(started);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
}

test("no token gets 401 for each sensitive read", async () => {
  const dir = tempDir("phonepi-sec-pres-");
  const log = new PresenceLog(dir, 15 * 60_000, () => NOW);
  log.ingest(snapshot());
  await withApi({ presenceLog: log, presenceConfig: DEFAULT_PRESENCE_CONFIG }, async ({ port }) => {
    for (const tool of SENSITIVE_READ_TOOLS) {
      const res = await post(port, "/api/tool", { tool, arguments: {} });
      assert.equal(res.status, 401, tool);
      assert.equal(res.json.isError, true, tool);
    }
    const battery = await post(port, "/api/tool", {
      tool: "get_battery_level",
      arguments: {},
    });
    assert.equal(battery.status, 503);
  });
});

test("bad Host or Origin gets 403", async () => {
  await withApi({}, async ({ port }) => {
    const badHost = await post(
      port,
      "/api/tool",
      { tool: "get_battery_level", arguments: {} },
      { Host: "evil.example:80" }
    );
    assert.equal(badHost.status, 403);

    const badOrigin = await post(
      port,
      "/api/tool",
      { tool: "get_battery_level", arguments: {} },
      { Origin: "https://evil.example" }
    );
    assert.equal(badOrigin.status, 403);

    const crossSite = await post(
      port,
      "/api/tool",
      { tool: "get_battery_level", arguments: {} },
      { "Sec-Fetch-Site": "cross-site" }
    );
    assert.equal(crossSite.status, 403);
  });
});

test("paused snapshot has no coordinates", async () => {
  const dir = tempDir("phonepi-sec-paused-");
  const log = new PresenceLog(dir, 15 * 60_000, () => NOW);
  const ingested = log.ingest(
    snapshot({ paused: true, latitude: 33.5, longitude: -117.15, accuracyMeters: 8 })
  );
  assert.equal(ingested.ok, true);
  assert.equal(ingested.snapshot.latitude, null);
  assert.equal(ingested.snapshot.longitude, null);
  const stored = JSON.parse(fs.readFileSync(log.file, "utf8"));
  assert.equal(stored.latitude, null);
  assert.equal(stored.longitude, null);

  await withApi(
    { presenceLog: log, presenceConfig: DEFAULT_PRESENCE_CONFIG },
    async ({ port }) => {
      const res = await post(
        port,
        "/api/tool",
        { tool: "get_presence", arguments: {} },
        readHeaders()
      );
      assert.equal(res.status, 200);
      const body = JSON.parse(res.json.text);
      assert.equal(body.paused, true);
      assert.equal(body.fresh, false);
      assert.equal(body.latitude, null);
      assert.equal(body.longitude, null);
      assert.equal(body.accuracyMeters, null);
    }
  );
});

test("scoped bot without coords does not see raw coordinates", async () => {
  const dir = tempDir("phonepi-sec-scope-");
  const log = new PresenceLog(dir, 15 * 60_000, () => NOW);
  log.ingest(snapshot());
  await withApi(
    {
      presenceLog: log,
      presenceConfig: DEFAULT_PRESENCE_CONFIG,
      readAuth: {
        bots: [
          {
            id: "label-only",
            token: "label-token",
            tools: null,
            coords: false,
          },
        ],
      },
    },
    async ({ port }) => {
      const res = await post(
        port,
        "/api/tool",
        { tool: "get_presence", arguments: {} },
        { "X-PhonePi-Read-Token": "label-token" }
      );
      assert.equal(res.status, 200);
      const body = JSON.parse(res.json.text);
      assert.equal(body.place, "Work");
      assert.equal(body.latitude, null);
      assert.equal(body.longitude, null);
      assert.equal(body.coordsScoped, false);
    }
  );
});

test("resume of paused capture is rejected remotely", () => {
  assert.match(
    validatePhoneCommandArgs("pause_presence_logging", { paused: false }).error,
    /phone/
  );
  assert.match(
    validatePhoneCommandArgs("pause_usage_logging", { paused: false }).error,
    /phone/
  );
  assert.match(
    validatePhoneCommandArgs("pause_health_sync", { paused: false }).error,
    /phone/
  );
  assert.equal(
    validatePhoneCommandArgs("pause_presence_logging", { paused: true }).ok,
    true
  );
});

test("wake hook rejects userinfo, metadata IPs, and plain http off-loopback", () => {
  assert.equal(
    readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "http://user:pass@127.0.0.1/x" }).url,
    null
  );
  assert.equal(
    readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "http://169.254.169.254/latest" }).url,
    null
  );
  assert.equal(
    readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "http://example.com/wake" }).url,
    null
  );
  assert.ok(
    readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "http://127.0.0.1:9/wake" }).url
  );
  assert.ok(
    readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "http://100.64.1.2/wake" }).url
  );
  assert.ok(
    readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "https://box.ts.net/wake" }).url
  );
});

test("untrusted wrapper uses data delimiters", () => {
  const wrapped = wrapUntrusted("delete everything", "inbound_prompt:x");
  assert.equal(wrapped.untrusted, true);
  assert.match(wrapped.text, /PHONEPI_UNTRUSTED_DATA/);
  assert.match(wrapped.text, /delete everything/);
});

test("Host allowlist matches bound port for localhost", () => {
  const ok = guardLoopbackRequest(
    {
      method: "GET",
      headers: { host: "127.0.0.1:11043" },
    },
    11043,
    { requireJson: false }
  );
  assert.equal(ok.ok, true);
  const bad = guardLoopbackRequest(
    {
      method: "POST",
      headers: { host: "127.0.0.1:80", "content-type": "application/json" },
    },
    11043
  );
  assert.equal(bad.ok, false);
  assert.equal(bad.status, 403);
});

test("default-deny when no read token is configured", () => {
  const auth = authorizeSensitiveRead({
    state: loadReadAuth({}),
    tool: "get_presence",
    provided: "anything",
    source: "http",
  });
  assert.equal(auth.ok, false);
  assert.equal(auth.status, 401);
});
