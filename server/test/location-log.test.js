import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { validatePhoneCommandArgs } from "../dist/commands.js";
import { LocationLog } from "../dist/location-log.js";
import { isReadOnlyHttpTool } from "../dist/read-only-tools.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";

const NOW = Date.UTC(2026, 9, 18, 12, 0, 0);

function tempLog() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-loc-"));
  const log = new LocationLog(dir, 12, () => NOW);
  return { dir, log };
}

function event(overrides = {}) {
  return {
    id: "visit-aaaaaaaa",
    kind: "visit",
    startMs: Date.UTC(2026, 9, 13, 19, 0, 0),
    endMs: Date.UTC(2026, 9, 13, 20, 30, 0),
    latitude: 33.2,
    longitude: -117.24,
    endLatitude: null,
    endLongitude: null,
    placeName: "Pizza Place",
    address: "100 Main St, Vista, CA 92084",
    distanceMeters: null,
    source: "phonepi",
    updatedAtMs: 10,
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

test("ingest rejects secrets, bad coordinates, and stale updates", () => {
  const { dir, log } = tempLog();
  const secret = log.ingest([event({ id: "visit-secret01", token: "super-secret" })]);
  assert.equal(secret.accepted.length, 0);
  assert.match(secret.rejected[0].error, /secret/);
  assert.equal(fs.readdirSync(dir).length, 0);
  const dumped = fs.readdirSync(dir).map((name) => fs.readFileSync(path.join(dir, name), "utf8")).join("");
  assert.equal(dumped.includes("super-secret"), false);

  const bad = log.ingest([event({ id: "visit-badcoord", latitude: 120 })]);
  assert.match(bad.rejected[0].error, /latitude/);

  const ok = log.ingest([event()]);
  assert.deepEqual(ok.accepted, ["visit-aaaaaaaa"]);
  const older = log.ingest([event({ placeName: "Old Name", updatedAtMs: 5 })]);
  assert.deepEqual(older.accepted, ["visit-aaaaaaaa"]);
  const newer = log.ingest([
    event({ placeName: "Pizza Place Vista", updatedAtMs: 20, address: "100 Main St, Vista, CA 92084" }),
  ]);
  assert.deepEqual(newer.accepted, ["visit-aaaaaaaa"]);
  const rows = log.query({ fromMs: null, toMs: null, kind: null, max: 10 });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].placeName, "Pizza Place Vista");
  const file = fs.readdirSync(dir)[0];
  const mode = fs.statSync(path.join(dir, file)).mode & 0o777;
  assert.equal(mode, 0o600);
  assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
});

test("retention drops months that ended before the window", () => {
  const { dir, log } = tempLog();
  const kept = log.ingest([
    event({
      id: "visit-kept0001",
      startMs: Date.UTC(2025, 9, 20, 15, 0, 0),
      endMs: Date.UTC(2025, 9, 20, 16, 0, 0),
    }),
  ]);
  assert.equal(kept.accepted.length, 1);
  const dropped = log.ingest([
    event({
      id: "visit-old00001",
      startMs: Date.UTC(2025, 8, 1, 12, 0, 0),
      endMs: Date.UTC(2025, 8, 1, 13, 0, 0),
    }),
  ]);
  assert.match(dropped.rejected[0].error, /retention/);
  assert.equal(fs.existsSync(path.join(dir, "2025-09.jsonl")), false);
  assert.equal(fs.existsSync(path.join(dir, "2025-10.jsonl")), true);
  const earlyOctober = log.ingest([
    event({
      id: "visit-oct1edge",
      startMs: Date.UTC(2025, 9, 1, 12, 0, 0),
      endMs: Date.UTC(2025, 9, 1, 13, 0, 0),
    }),
  ]);
  assert.match(earlyOctober.rejected[0].error, /retention/);
});

test("query and search read the collapsed log", () => {
  const { log } = tempLog();
  log.ingest([
    event(),
    event({
      id: "drive-bbbbbbbb",
      kind: "drive",
      startMs: Date.UTC(2026, 9, 13, 20, 0, 0),
      endMs: Date.UTC(2026, 9, 13, 20, 25, 0),
      endLatitude: 33.21,
      endLongitude: -117.25,
      placeName: null,
      address: null,
      distanceMeters: 3200,
    }),
    event({
      id: "visit-home0001",
      placeName: "Home",
      address: "338 E Fallbrook St, Fallbrook, CA",
      startMs: Date.UTC(2026, 9, 12, 2, 0, 0),
      endMs: Date.UTC(2026, 9, 12, 14, 0, 0),
      latitude: 33.38,
      longitude: -117.25,
    }),
  ]);
  const afternoon = log.query({
    fromMs: Date.parse("2026-10-13T12:00:00-07:00"),
    toMs: Date.parse("2026-10-13T18:00:00-07:00"),
    kind: null,
    max: 20,
  });
  assert.equal(afternoon.length, 2);
  assert.equal(afternoon.filter((row) => row.kind === "visit").length, 1);
  assert.equal(afternoon.filter((row) => row.kind === "drive").length, 1);
  const drives = log.query({ fromMs: null, toMs: null, kind: "drive", max: 20 });
  assert.equal(drives.length, 1);
  assert.equal(drives[0].distanceMeters, 3200);
  const places = log.search("pizza vista", 10);
  assert.equal(places.length, 1);
  assert.equal(places[0].address, "100 Main St, Vista, CA 92084");
  assert.equal(places[0].visits[0].dateUtc, "2026-10-13");
  assert.equal(log.search("fallbrook", 10)[0].placeName, "Home");
  assert.equal(log.search("no-such-place", 10).length, 0);
});

test("clear removes the jsonl files", () => {
  const { dir, log } = tempLog();
  log.ingest([event()]);
  assert.equal(fs.readdirSync(dir).length, 1);
  log.clear();
  assert.equal(fs.readdirSync(dir).length, 0);
  assert.equal(log.query({ fromMs: null, toMs: null, kind: null, max: 10 }).length, 0);
});

test("location reads are allowlisted, served offline, and rate limited", async () => {
  const { log } = tempLog();
  log.ingest([event()]);
  let phoneCalls = 0;
  const limiter = new CommandRateLimiter();
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    commandToken: "test-token",
    locationLog: log,
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
    assert.equal(isReadOnlyHttpTool("get_location_history"), true);
    assert.equal(isReadOnlyHttpTool("search_places"), true);
    assert.equal(isReadOnlyHttpTool("pause_location_logging"), false);
    assert.equal(isReadOnlyHttpTool("clear_location_history"), false);

    const history = await post(started.port, "/api/tool", {
      tool: "get_location_history",
      arguments: {
        from: "2026-10-13T12:00:00-07:00",
        to: "2026-10-13T18:00:00-07:00",
        max: 20,
      },
    });
    assert.equal(history.status, 200);
    const historyBody = JSON.parse(history.json.text);
    assert.equal(historyBody.count, 1);
    assert.equal(historyBody.entries[0].placeName, "Pizza Place");
    assert.equal(historyBody.activityLogged, false);
    assert.equal(phoneCalls, 0);

    const search = await post(started.port, "/api/tool", {
      tool: "search_places",
      arguments: { query: "pizza vista" },
    });
    assert.equal(search.status, 200);
    const searchBody = JSON.parse(search.json.text);
    assert.equal(searchBody.places[0].address.includes("Vista"), true);
    assert.equal(phoneCalls, 0);

    const bad = await post(started.port, "/api/tool", {
      tool: "get_location_history",
      arguments: { max: 500 },
    });
    assert.equal(bad.status, 400);
    assert.match(bad.json.text, /max/);

    const shortQuery = await post(started.port, "/api/tool", {
      tool: "search_places",
      arguments: { query: "p" },
    });
    assert.equal(shortQuery.status, 400);

    const paused = await post(started.port, "/api/tool", {
      tool: "pause_location_logging",
      arguments: { paused: true },
    });
    assert.equal(paused.status, 403);
    const cleared = await post(started.port, "/api/tool", {
      tool: "clear_location_history",
      arguments: { confirm: "delete" },
    });
    assert.equal(cleared.status, 403);
    assert.equal(phoneCalls, 0);

    for (let i = 0; i < 40; i += 1) {
      await post(started.port, "/api/tool", {
        tool: "search_places",
        arguments: { query: "pizza" },
      });
    }
    const limited = await post(started.port, "/api/tool", {
      tool: "search_places",
      arguments: { query: "pizza" },
    });
    assert.equal(limited.status, 429);
    assert.equal(phoneCalls, 0);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("pause and clear are token commands and invalid args never call the phone", async () => {
  assert.equal(validatePhoneCommandArgs("pause_location_logging", { paused: true }).ok, true);
  assert.match(
    validatePhoneCommandArgs("pause_location_logging", {}).error,
    /paused/
  );
  assert.equal(
    validatePhoneCommandArgs("clear_location_history", { confirm: "delete" }).ok,
    true
  );
  assert.match(
    validatePhoneCommandArgs("clear_location_history", { confirm: "yes" }).error,
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
      command: "clear_location_history",
      arguments: { confirm: "delete" },
    });
    assert.equal(noToken.status, 401);
    assert.equal(phoneCalls, 0);

    const bad = await post(
      started.port,
      "/api/command",
      { command: "clear_location_history", arguments: { confirm: "please" } },
      { "X-PhonePi-Token": "test-token" }
    );
    assert.equal(bad.status, 400);
    assert.equal(phoneCalls, 0);

    const pause = await post(
      started.port,
      "/api/command",
      { command: "pause_location_logging", arguments: { paused: true } },
      { Authorization: "Bearer test-token" }
    );
    assert.equal(pause.status, 200);
    assert.equal(phoneCalls, 1);
    assert.equal(JSON.parse(pause.json.text).args.paused, true);

    const offline = await startLoopbackHttp({
      port: 0,
      wsPort: 11041,
      commandToken: "test-token",
      bridge: {
        isPhoneConnected: () => false,
        callPhoneTool: async () => {
          phoneCalls += 1;
          return {};
        },
      },
    });
    assert.ok(offline);
    try {
      const down = await post(
        offline.port,
        "/api/command",
        { command: "pause_location_logging", arguments: { paused: false } },
        { "X-PhonePi-Token": "test-token" }
      );
      assert.equal(down.status, 503);
    } finally {
      await new Promise((resolve) => offline.server.close(resolve));
    }
    assert.equal(phoneCalls, 1);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});
