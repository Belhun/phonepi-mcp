import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { validatePhoneCommandArgs } from "../dist/commands.js";
import { HealthLog, SOURCE_LAG_NOTE } from "../dist/health-log.js";
import { isHealthReadTool } from "../dist/health-tools.js";
import { isReadOnlyHttpTool } from "../dist/read-only-tools.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";

const NOW = Date.UTC(2026, 9, 25, 15, 0, 0);

function tempLog() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-health-"));
  const log = new HealthLog(dir, 30, () => NOW);
  return { dir, log };
}

function summary(overrides = {}) {
  return {
    syncedAtMs: Date.UTC(2026, 9, 25, 14, 5, 0),
    timezone: "America/Los_Angeles",
    enabled: true,
    paused: false,
    permissions: {
      sleep: true,
      steps: true,
      heartRate: true,
      restingHeartRate: true,
      exercise: true,
      oxygenSaturation: true,
      background: true,
      history: true,
    },
    lastNightSleep: {
      startMs: Date.UTC(2026, 9, 25, 6, 12, 0),
      endMs: Date.UTC(2026, 9, 25, 13, 4, 0),
      totalMinutes: 412,
      stages: {
        awakeMinutes: 22,
        lightMinutes: 180,
        deepMinutes: 90,
        remMinutes: 80,
        unspecifiedMinutes: 40,
      },
    },
    steps: {
      today: 8432,
      last7Days: [
        { date: "2026-10-19", count: 7100 },
        { date: "2026-10-20", count: 8200 },
        { date: "2026-10-21", count: 6400 },
        { date: "2026-10-22", count: 9100 },
        { date: "2026-10-23", count: 7800 },
        { date: "2026-10-24", count: 8600 },
        { date: "2026-10-25", count: 8432 },
      ],
      last7DaysTotal: 55632,
    },
    heartRate: {
      restingBpm: 58,
      averageBpm: 72,
    },
    workouts: [
      {
        startMs: Date.UTC(2026, 9, 24, 23, 0, 0),
        endMs: Date.UTC(2026, 9, 25, 0, 5, 0),
        durationMinutes: 65,
        type: "RUNNING",
        title: "Evening run",
      },
    ],
    oxygenSaturationPct: 97,
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

test("ingest rejects secrets, raw heart-rate streams, and writes mode 700/600", () => {
  const { dir, log } = tempLog();
  const secret = log.ingest(summary({ token: "super-secret" }));
  assert.equal(secret.ok, false);
  assert.match(secret.error, /secret/);
  assert.equal(fs.readdirSync(dir).length, 0);
  const dumped = fs
    .readdirSync(dir)
    .map((name) => fs.readFileSync(path.join(dir, name), "utf8"))
    .join("");
  assert.equal(dumped.includes("super-secret"), false);

  const samples = log.ingest(
    summary({ heartRateSamples: [{ bpm: 88, at: NOW }] })
  );
  assert.equal(samples.ok, false);
  assert.match(samples.error, /raw|stream|sample/i);
  assert.equal(fs.readdirSync(dir).length, 0);

  const ok = log.ingest(summary());
  assert.equal(ok.ok, true);
  assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
  const latest = path.join(dir, "latest.json");
  assert.equal(fs.existsSync(latest), true);
  assert.equal(fs.statSync(latest).mode & 0o777, 0o600);
  const dayFile = path.join(dir, "2026-10-25.json");
  assert.equal(fs.existsSync(dayFile), true);
  assert.equal(fs.statSync(dayFile).mode & 0o777, 0o600);
  const stored = JSON.parse(fs.readFileSync(latest, "utf8"));
  assert.equal(stored.steps.today, 8432);
  assert.equal(Object.hasOwn(stored, "heartRateSamples"), false);
});

test("retention drops daily snapshots older than the window and clear wipes the dir", () => {
  const { dir, log } = tempLog();
  log.ingest(
    summary({
      syncedAtMs: Date.UTC(2026, 8, 20, 12, 0, 0),
      steps: { today: 1, last7Days: [], last7DaysTotal: 1 },
    })
  );
  assert.equal(fs.existsSync(path.join(dir, "2026-09-20.json")), true);
  const pruned = new HealthLog(dir, 30, () => NOW);
  pruned.prune();
  assert.equal(fs.existsSync(path.join(dir, "2026-09-20.json")), false);
  log.ingest(summary());
  log.clear();
  assert.equal(fs.existsSync(path.join(dir, "latest.json")), false);
  assert.equal(fs.readdirSync(dir).filter((name) => name.endsWith(".json")).length, 0);
});

test("get_health_summary is allowlisted, served offline with stale, and rate limited", async () => {
  const { log } = tempLog();
  log.ingest(summary());
  let phoneCalls = 0;
  const limiter = new CommandRateLimiter();
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    commandToken: "test-token",
    healthLog: log,
    locationReadLimiter: limiter,
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
    assert.equal(isReadOnlyHttpTool("get_health_summary"), true);
    assert.equal(isHealthReadTool("get_health_summary"), true);
    assert.equal(isReadOnlyHttpTool("pause_health_sync"), false);
    assert.equal(isReadOnlyHttpTool("clear_health_cache"), false);

    const res = await post(started.port, "/api/tool", {
      tool: "get_health_summary",
      arguments: {},
    });
    assert.equal(res.status, 200);
    const body = JSON.parse(res.json.text);
    assert.equal(body.status, "success");
    assert.equal(body.stale, true);
    assert.equal(body.phoneConnected, false);
    assert.equal(body.lastNightSleep.totalMinutes, 412);
    assert.equal(body.lastNightSleepEndMs, Date.UTC(2026, 9, 25, 13, 4, 0));
    assert.equal(typeof body.lastNightSleepEndIso, "string");
    assert.equal(body.steps.today, 8432);
    assert.equal(body.steps.last7DaysTotal, 55632);
    assert.equal(body.heartRate.restingBpm, 58);
    assert.equal(body.heartRate.averageBpm, 72);
    assert.equal(body.workouts[0].type, "RUNNING");
    assert.equal(body.sourceLagNote, SOURCE_LAG_NOTE);
    assert.equal(typeof body.syncedAtIso, "string");
    assert.equal(Object.hasOwn(body, "heartRateSamples"), false);
    assert.equal(phoneCalls, 0);

    for (let i = 0; i < 40; i += 1) {
      await post(started.port, "/api/tool", {
        tool: "get_health_summary",
        arguments: {},
      });
    }
    const limited = await post(started.port, "/api/tool", {
      tool: "get_health_summary",
      arguments: {},
    });
    assert.equal(limited.status, 429);
    assert.equal(phoneCalls, 0);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("empty cache still returns a summary shape with stale true", async () => {
  const { log } = tempLog();
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    healthLog: log,
    bridge: {
      isPhoneConnected: () => false,
      callPhoneTool: async () => ({ leaked: true }),
    },
  });
  assert.ok(started);
  try {
    const res = await post(started.port, "/api/tool", {
      tool: "get_health_summary",
      arguments: {},
    });
    assert.equal(res.status, 200);
    const body = JSON.parse(res.json.text);
    assert.equal(body.status, "success");
    assert.equal(body.stale, true);
    assert.equal(body.lastNightSleep, null);
    assert.equal(body.lastNightSleepEndMs, null);
    assert.equal(body.steps.today, 0);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("pause and clear are token commands and invalid args never call the phone", async () => {
  assert.equal(validatePhoneCommandArgs("pause_health_sync", { paused: true }).ok, true);
  assert.match(validatePhoneCommandArgs("pause_health_sync", {}).error, /paused/);
  assert.equal(
    validatePhoneCommandArgs("clear_health_cache", { confirm: "delete" }).ok,
    true
  );
  assert.match(
    validatePhoneCommandArgs("clear_health_cache", { confirm: "yes" }).error,
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
      command: "clear_health_cache",
      arguments: { confirm: "delete" },
    });
    assert.equal(noToken.status, 401);
    assert.equal(phoneCalls, 0);

    const asTool = await post(started.port, "/api/tool", {
      tool: "pause_health_sync",
      arguments: { paused: true },
    });
    assert.equal(asTool.status, 403);
    assert.equal(phoneCalls, 0);

    const bad = await post(
      started.port,
      "/api/command",
      { command: "clear_health_cache", arguments: { confirm: "please" } },
      { "X-PhonePi-Token": "test-token" }
    );
    assert.equal(bad.status, 400);
    assert.equal(phoneCalls, 0);

    const pause = await post(
      started.port,
      "/api/command",
      { command: "pause_health_sync", arguments: { paused: true } },
      { Authorization: "Bearer test-token" }
    );
    assert.equal(pause.status, 200);
    assert.equal(phoneCalls, 1);
    assert.equal(JSON.parse(pause.json.text).args.paused, true);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});
