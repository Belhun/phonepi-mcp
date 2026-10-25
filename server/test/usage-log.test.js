import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "os";
import path from "path";
import test from "node:test";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { validatePhoneCommandArgs } from "../dist/commands.js";
import { isReadOnlyHttpTool, READ_ONLY_TOOL_RATE_LIMITS } from "../dist/read-only-tools.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";
import { TEST_READ_AUTH, readHeaders } from "./read-auth-fixture.js";
import { UsageLog, msToMinutes } from "../dist/usage-log.js";

const NOW = Date.UTC(2026, 9, 24, 20, 0, 0);

function tempLog(now = NOW) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-usage-"));
  const log = new UsageLog(dir, 90, () => now);
  return { dir, log };
}

function day(overrides = {}) {
  return {
    date: "2026-10-24",
    timeZone: "America/Los_Angeles",
    screenOnMs: 3_600_000,
    unlockCount: 12,
    lastUsedAt: Date.UTC(2026, 9, 24, 19, 30, 0),
    updatedAtMs: NOW,
    apps: [
      {
        package: "com.google.android.youtube",
        label: "YouTube",
        foregroundMs: 1_800_000,
        lastUsedAt: Date.UTC(2026, 9, 24, 19, 0, 0),
      },
      {
        package: "com.android.chrome",
        label: "Chrome",
        foregroundMs: 600_000,
        lastUsedAt: Date.UTC(2026, 9, 24, 18, 0, 0),
      },
    ],
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
          resolve({ status: res.statusCode, json: JSON.parse(raw), raw });
        });
      }
    );
    req.on("error", reject);
    req.end(payload);
  });
}

test("ingest rejects secrets, bad dates, and stale updates", () => {
  const { dir, log } = tempLog();
  const secret = log.ingest([day({ date: "2026-10-24", token: "super-secret" })]);
  assert.equal(secret.accepted.length, 0);
  assert.match(secret.rejected[0].error, /secret/);
  assert.equal(fs.readdirSync(dir).filter((name) => name.endsWith(".json")).length, 0);
  const dumped = fs
    .readdirSync(dir)
    .map((name) => fs.readFileSync(path.join(dir, name), "utf8"))
    .join("");
  assert.equal(dumped.includes("super-secret"), false);

  const bad = log.ingest([day({ date: "2026-13-99" })]);
  assert.match(bad.rejected[0].error, /date/);

  const ok = log.ingest([day()], {
    enabled: true,
    paused: false,
    usageAccessGranted: true,
    timeZone: "America/Los_Angeles",
    lastUsedAt: Date.UTC(2026, 9, 24, 19, 30, 0),
    updatedAtMs: NOW,
  });
  assert.deepEqual(ok.accepted, ["2026-10-24"]);
  const older = log.ingest([day({ unlockCount: 1, updatedAtMs: NOW - 1000 })]);
  assert.deepEqual(older.accepted, ["2026-10-24"]);
  const newer = log.ingest([day({ unlockCount: 20, updatedAtMs: NOW + 1000 })]);
  assert.deepEqual(newer.accepted, ["2026-10-24"]);
  const rows = log.query({
    fromDate: null,
    toDate: null,
    days: 7,
    top: 10,
    phoneConnected: true,
  });
  assert.equal(rows.days.length, 1);
  assert.equal(rows.days[0].unlockCount, 20);
  const file = path.join(dir, "2026-10-24.json");
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
  assert.equal(fs.statSync(path.join(dir, "_meta.json")).mode & 0o777, 0o600);
});

test("retention drops days before the window", () => {
  const { dir, log } = tempLog();
  const kept = log.ingest([day({ date: "2026-08-01", updatedAtMs: 1 })]);
  assert.equal(kept.accepted.length, 1);
  const dropped = log.ingest([day({ date: "2026-07-20", updatedAtMs: 1 })]);
  assert.match(dropped.rejected[0].error, /retention/);
  assert.equal(fs.existsSync(path.join(dir, "2026-08-01.json")), true);
  assert.equal(fs.existsSync(path.join(dir, "2026-07-20.json")), false);
});

test("query serves top apps, from/to local dates, and a stale flag", () => {
  const { log } = tempLog();
  log.ingest(
    [
      day({ date: "2026-10-22", unlockCount: 4, screenOnMs: 600_000 }),
      day({ date: "2026-10-23", unlockCount: 8, screenOnMs: 1_200_000 }),
      day(),
    ],
    {
      enabled: true,
      paused: false,
      usageAccessGranted: true,
      timeZone: "America/Los_Angeles",
      lastUsedAt: Date.UTC(2026, 9, 24, 19, 30, 0),
      updatedAtMs: NOW,
    }
  );
  const range = log.query({
    fromDate: "2026-10-23",
    toDate: "2026-10-24",
    days: 7,
    top: 1,
    phoneConnected: true,
  });
  assert.equal(range.days.length, 2);
  assert.equal(range.days[1].apps.length, 1);
  assert.equal(range.days[1].apps[0].packageName, "com.google.android.youtube");
  assert.equal(range.stale, false);
  assert.equal(msToMinutes(90_000), 2);

  const staleLog = new UsageLog(log.dir, 90, () => NOW + 31 * 60 * 1000);
  const stale = staleLog.query({
    fromDate: null,
    toDate: null,
    days: 7,
    top: 10,
    phoneConnected: true,
  });
  assert.equal(stale.stale, true);

  const offline = log.query({
    fromDate: null,
    toDate: null,
    days: 7,
    top: 10,
    phoneConnected: false,
  });
  assert.equal(offline.stale, true);
});

test("clear removes daily files and meta", () => {
  const { dir, log } = tempLog();
  log.ingest([day()], {
    enabled: true,
    paused: false,
    usageAccessGranted: true,
    timeZone: "UTC",
    lastUsedAt: NOW,
    updatedAtMs: NOW,
  });
  assert.equal(fs.readdirSync(dir).length >= 2, true);
  log.clear();
  assert.equal(fs.readdirSync(dir).length, 0);
});

test("get_screen_time is allowlisted, served offline, and rate limited", async () => {
  const { log } = tempLog();
  log.ingest([day()], {
    enabled: true,
    paused: false,
    usageAccessGranted: true,
    timeZone: "America/Los_Angeles",
    lastUsedAt: Date.UTC(2026, 9, 24, 19, 30, 0),
    updatedAtMs: NOW,
  });
  let phoneCalls = 0;
  const limiter = new CommandRateLimiter();
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    commandToken: "test-token",
    usageLog: log,
    locationReadLimiter: limiter,
    readAuth: TEST_READ_AUTH,
    bridge: {
      isPhoneConnected: () => false,
      callPhoneTool: async () => {
        phoneCalls += 1;
        return { leaked: true };
      },
    },
  });
  assert.ok(started);
  try {
    assert.equal(isReadOnlyHttpTool("get_screen_time"), true);
    assert.equal(READ_ONLY_TOOL_RATE_LIMITS.get_screen_time, 30);
    assert.equal(isReadOnlyHttpTool("pause_usage_logging"), false);
    assert.equal(isReadOnlyHttpTool("clear_usage_history"), false);

    const read = await post(
      started.port,
      "/api/tool",
      {
        tool: "get_screen_time",
        arguments: { days: 7, top: 10 },
      },
      readHeaders()
    );
    assert.equal(read.status, 200);
    const body = JSON.parse(read.json.text);
    assert.equal(body.status, "success");
    assert.equal(body.stale, true);
    assert.equal(body.activityLogged, false);
    assert.equal(body.count, 1);
    assert.equal(body.days[0].screenOnMinutes, 60);
    assert.equal(body.days[0].unlockCount, 12);
    assert.equal(body.days[0].apps[0].label.untrusted, true);
    assert.match(body.days[0].apps[0].label.text, /YouTube/);
    assert.equal(body.days[0].apps[0].minutes, 30);
    assert.equal(phoneCalls, 0);

    const bad = await post(
      started.port,
      "/api/tool",
      {
        tool: "get_screen_time",
        arguments: { days: 500 },
      },
      readHeaders()
    );
    assert.equal(bad.status, 400);
    assert.match(bad.json.text, /days/);

    const badDate = await post(
      started.port,
      "/api/tool",
      {
        tool: "get_screen_time",
        arguments: { from: "2026-10-24T12:00:00-07:00" },
      },
      readHeaders()
    );
    assert.equal(badDate.status, 400);

    const paused = await post(started.port, "/api/tool", {
      tool: "pause_usage_logging",
      arguments: { paused: true },
    });
    assert.equal(paused.status, 403);

    for (let i = 0; i < 29; i += 1) {
      await post(
        started.port,
        "/api/tool",
        {
          tool: "get_screen_time",
          arguments: {},
        },
        readHeaders()
      );
    }
    const limited = await post(
      started.port,
      "/api/tool",
      {
        tool: "get_screen_time",
        arguments: {},
      },
      readHeaders()
    );
    assert.equal(limited.status, 429);
    assert.equal(phoneCalls, 0);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("pause and clear usage are token commands and invalid args never call the phone", async () => {
  assert.equal(validatePhoneCommandArgs("pause_usage_logging", { paused: true }).ok, true);
  assert.match(validatePhoneCommandArgs("pause_usage_logging", {}).error, /paused/);
  assert.equal(
    validatePhoneCommandArgs("clear_usage_history", { confirm: "delete" }).ok,
    true
  );
  assert.match(
    validatePhoneCommandArgs("clear_usage_history", { confirm: "yes" }).error,
    /delete/
  );

  let phoneCalls = 0;
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    commandToken: "test-token",
    bridge: {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        phoneCalls += 1;
        return { name, args, status: "success" };
      },
    },
  });
  assert.ok(started);
  try {
    const noToken = await post(started.port, "/api/command", {
      command: "clear_usage_history",
      arguments: { confirm: "delete" },
    });
    assert.equal(noToken.status, 401);
    assert.equal(phoneCalls, 0);

    const bad = await post(
      started.port,
      "/api/command",
      { command: "clear_usage_history", arguments: { confirm: "please" } },
      { "X-PhonePi-Token": "test-token" }
    );
    assert.equal(bad.status, 400);
    assert.equal(phoneCalls, 0);

    const pause = await post(
      started.port,
      "/api/command",
      { command: "pause_usage_logging", arguments: { paused: true } },
      { Authorization: "Bearer test-token" }
    );
    assert.equal(pause.status, 200);
    assert.equal(phoneCalls, 1);
    assert.equal(JSON.parse(pause.json.text).args.paused, true);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});
