import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "path";
import test from "node:test";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { validatePhoneCommandArgs } from "../dist/commands.js";
import {
  DEFAULT_PRESENCE_CONFIG,
  PresenceConfig,
  expectedPlaceAt,
  parsePresenceConfig,
  placeMatchesExpected,
} from "../dist/presence-config.js";
import { PresenceLog, parsePresenceSnapshot } from "../dist/presence-log.js";
import { isReadOnlyHttpTool } from "../dist/read-only-tools.js";
import { isPresenceReadTool, runPresenceRead } from "../dist/presence-tools.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";

const TUE_10AM_PDT = Date.parse("2026-10-20T10:00:00-07:00");
const TUE_5PM_PDT = Date.parse("2026-10-20T17:00:00-07:00");
const TUE_7PM_PDT = Date.parse("2026-10-20T19:00:00-07:00");
const SAT_10AM_PDT = Date.parse("2026-10-24T10:00:00-07:00");
const SUN_6PM_PDT = Date.parse("2026-10-25T18:00:00-07:00");
const MON_8AM_PDT = Date.parse("2026-10-19T08:00:00-07:00");
const MON_759AM_PDT = Date.parse("2026-10-19T07:59:00-07:00");

function tempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function snapshot(overrides = {}) {
  return {
    capturedAtMs: TUE_10AM_PDT,
    enabled: true,
    paused: false,
    place: "Work",
    minutesAtPlace: 42,
    arrivedAtMs: TUE_10AM_PDT - 42 * 60_000,
    driving: false,
    moving: false,
    activity: "still",
    dnd: "off",
    latitude: 33.5,
    longitude: -117.15,
    accuracyMeters: 18,
    lastEvent: "arrive",
    lastEventPlace: "Work",
    lastEventMs: TUE_10AM_PDT - 42 * 60_000,
    source: "geofence",
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

test("missing config file is created with default work hours so Alex can edit it", () => {
  const dir = tempDir("phonepi-pres-cfg-");
  const file = path.join(dir, "config.json");
  const cfg = new PresenceConfig(file);
  const data = cfg.data();
  assert.equal(data.timezone, "America/Los_Angeles");
  assert.equal(expectedPlaceAt(data, TUE_10AM_PDT), "work");
  const written = JSON.parse(fs.readFileSync(file, "utf8"));
  assert.deepEqual(written.hours.work.mon, [{ start: "08:00", end: "17:00" }]);
  assert.deepEqual(written.hours.church, {});
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
});

test("default config is 8-5 weekdays Pacific, empty church hours, empty place names", () => {
  assert.equal(DEFAULT_PRESENCE_CONFIG.timezone, "America/Los_Angeles");
  assert.equal(DEFAULT_PRESENCE_CONFIG.places.home, "");
  assert.equal(DEFAULT_PRESENCE_CONFIG.places.work, "");
  assert.equal(DEFAULT_PRESENCE_CONFIG.places.church, "");
  assert.deepEqual(DEFAULT_PRESENCE_CONFIG.hours.work.mon, [
    { start: "08:00", end: "17:00" },
  ]);
  assert.deepEqual(DEFAULT_PRESENCE_CONFIG.hours.work.fri, [
    { start: "08:00", end: "17:00" },
  ]);
  assert.deepEqual(DEFAULT_PRESENCE_CONFIG.hours.work.sat, []);
  assert.deepEqual(DEFAULT_PRESENCE_CONFIG.hours.church, {});
  assert.equal(expectedPlaceAt(DEFAULT_PRESENCE_CONFIG, TUE_10AM_PDT), "work");
  assert.equal(expectedPlaceAt(DEFAULT_PRESENCE_CONFIG, MON_8AM_PDT), "work");
  assert.equal(expectedPlaceAt(DEFAULT_PRESENCE_CONFIG, MON_759AM_PDT), null);
  assert.equal(expectedPlaceAt(DEFAULT_PRESENCE_CONFIG, TUE_5PM_PDT), null);
  assert.equal(expectedPlaceAt(DEFAULT_PRESENCE_CONFIG, TUE_7PM_PDT), null);
  assert.equal(expectedPlaceAt(DEFAULT_PRESENCE_CONFIG, SAT_10AM_PDT), null);
  assert.equal(expectedPlaceAt(DEFAULT_PRESENCE_CONFIG, SUN_6PM_PDT), null);
});

test("work hours come from config, not hard-coded matching", () => {
  const custom = parsePresenceConfig({
    places: { work: "Shop" },
    timezone: "America/Los_Angeles",
    hours: {
      work: {
        mon: [{ start: "10:00", end: "12:00" }],
      },
      church: {
        sun: [{ start: "17:00", end: "20:00" }],
      },
    },
  });
  assert.equal(expectedPlaceAt(custom, TUE_10AM_PDT), null);
  assert.equal(expectedPlaceAt(custom, Date.parse("2026-10-19T10:30:00-07:00")), "Shop");
  assert.equal(expectedPlaceAt(custom, SUN_6PM_PDT), "church");
});

test("matches_expected compares the live place to expected_place and mapped names", () => {
  const config = parsePresenceConfig({
    places: { work: "Shop", home: "Home" },
    timezone: "America/Los_Angeles",
    hours: DEFAULT_PRESENCE_CONFIG.hours,
  });
  assert.equal(placeMatchesExpected("Shop", "Shop", config), true);
  assert.equal(placeMatchesExpected("work", "Shop", config), true);
  assert.equal(placeMatchesExpected("Home", "Shop", config), false);
  assert.equal(placeMatchesExpected("away", "Shop", config), false);
  assert.equal(placeMatchesExpected("Work", "work", DEFAULT_PRESENCE_CONFIG), true);
  assert.equal(placeMatchesExpected("away", null, config), null);
});

test("snapshot ingest rejects secrets and bad coordinates, keeps mode 600", () => {
  const dir = tempDir("phonepi-pres-");
  const log = new PresenceLog(dir);
  const secret = log.ingest(snapshot({ token: "super-secret" }));
  assert.equal(secret.ok, false);
  assert.match(secret.error, /secret/);
  assert.equal(fs.existsSync(path.join(dir, "current.json")), false);

  const bad = log.ingest(snapshot({ latitude: 120 }));
  assert.match(bad.error, /latitude/);

  const ok = log.ingest(snapshot());
  assert.equal(ok.ok, true);
  const stored = log.current();
  assert.equal(stored.place, "Work");
  assert.equal(stored.latitude, 33.5);
  const file = path.join(dir, "current.json");
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
  const dumped = fs.readFileSync(file, "utf8");
  assert.equal(dumped.includes("super-secret"), false);
  log.clear();
  assert.equal(log.current(), null);
});

test("parsePresenceSnapshot rejects a secret field even before write", () => {
  const parsed = parsePresenceSnapshot(snapshot({ authorization: "Bearer x" }));
  assert.equal(parsed.ok, false);
  assert.match(parsed.error, /secret/);
});

test("get_presence is a read-only box copy and does not call the phone", async () => {
  const dir = tempDir("phonepi-pres-http-");
  const log = new PresenceLog(dir);
  log.ingest(snapshot());
  const limiter = new CommandRateLimiter(60_000, () => TUE_10AM_PDT);
  let phoneCalls = 0;
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    commandToken: "test-token",
    presenceLog: log,
    presenceConfig: DEFAULT_PRESENCE_CONFIG,
    presenceNow: () => TUE_10AM_PDT,
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
    assert.equal(isReadOnlyHttpTool("get_presence"), true);
    assert.equal(isReadOnlyHttpTool("pause_presence_logging"), false);
    assert.equal(isReadOnlyHttpTool("clear_presence_history"), false);
    assert.equal(isPresenceReadTool("get_presence"), true);

    const res = await post(started.port, "/api/tool", {
      tool: "get_presence",
      arguments: {},
    });
    assert.equal(res.status, 200);
    const body = JSON.parse(res.json.text);
    assert.equal(body.status, "success");
    assert.equal(body.place, "Work");
    assert.equal(body.minutesAtPlace, 42);
    assert.equal(body.driving, false);
    assert.equal(body.moving, false);
    assert.equal(body.dnd, "off");
    assert.equal(body.latitude, 33.5);
    assert.equal(body.longitude, -117.15);
    assert.equal(body.accuracyMeters, 18);
    assert.equal(body.expected_place, "work");
    assert.equal(body.matches_expected, true);
    assert.equal(body.capturedAtMs, TUE_10AM_PDT);
    assert.equal(typeof body.ageMs, "number");
    assert.equal(body.activityLogged, false);
    assert.equal(phoneCalls, 0);

    const paused = await post(started.port, "/api/tool", {
      tool: "pause_presence_logging",
      arguments: { paused: true },
    });
    assert.equal(paused.status, 403);
    const cleared = await post(started.port, "/api/tool", {
      tool: "clear_presence_history",
      arguments: { confirm: "delete" },
    });
    assert.equal(cleared.status, 403);
    assert.equal(phoneCalls, 0);

    for (let i = 0; i < 40; i += 1) {
      await post(started.port, "/api/tool", { tool: "get_presence", arguments: {} });
    }
    const limited = await post(started.port, "/api/tool", {
      tool: "get_presence",
      arguments: {},
    });
    assert.equal(limited.status, 429);
    assert.equal(phoneCalls, 0);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("get_presence without a snapshot still reports expected_place from config", () => {
  const dir = tempDir("phonepi-pres-empty-");
  const outcome = runPresenceRead({
    log: new PresenceLog(dir),
    config: DEFAULT_PRESENCE_CONFIG,
    limiter: new CommandRateLimiter(60_000, () => TUE_10AM_PDT),
    tool: "get_presence",
    nowMs: TUE_10AM_PDT,
    activityLogged: false,
  });
  assert.equal(outcome.status, 200);
  const body = JSON.parse(outcome.body.text);
  assert.equal(body.place, null);
  assert.equal(body.reason, "no_snapshot");
  assert.equal(body.expected_place, "work");
  assert.equal(body.matches_expected, false);
});

test("pause and clear presence are token commands and invalid args never call the phone", async () => {
  assert.equal(
    validatePhoneCommandArgs("pause_presence_logging", { paused: true }).ok,
    true
  );
  assert.match(
    validatePhoneCommandArgs("pause_presence_logging", {}).error,
    /paused/
  );
  assert.equal(
    validatePhoneCommandArgs("clear_presence_history", { confirm: "delete" }).ok,
    true
  );
  assert.match(
    validatePhoneCommandArgs("clear_presence_history", { confirm: "yes" }).error,
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
      command: "clear_presence_history",
      arguments: { confirm: "delete" },
    });
    assert.equal(noToken.status, 401);
    assert.equal(phoneCalls, 0);

    const bad = await post(
      started.port,
      "/api/command",
      { command: "clear_presence_history", arguments: { confirm: "please" } },
      { "X-PhonePi-Token": "test-token" }
    );
    assert.equal(bad.status, 400);
    assert.equal(phoneCalls, 0);

    const pause = await post(
      started.port,
      "/api/command",
      { command: "pause_presence_logging", arguments: { paused: true } },
      { Authorization: "Bearer test-token" }
    );
    assert.equal(pause.status, 200);
    assert.equal(phoneCalls, 1);
    assert.equal(JSON.parse(pause.json.text).args.paused, true);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});
