import assert from "node:assert/strict";
import http from "node:http";
import { spawn } from "node:child_process";
import test from "node:test";
import {
  evaluateHealth,
  formatDuration,
  offlineForMs,
  resolveHealthUrl,
} from "../../scripts/phonepi-health-check.mjs";

const SCRIPT = new URL("../../scripts/phonepi-health-check.mjs", import.meta.url);

test("duration formatting and offline age", () => {
  assert.equal(formatDuration(0), "0s");
  assert.equal(formatDuration(1500), "1s");
  assert.equal(formatDuration(90_000), "1m 30s");
  assert.equal(formatDuration(3_700_000), "1h 1m");
  const now = Date.parse("2026-10-17T06:10:00.000Z");
  assert.equal(offlineForMs("2026-10-17T06:00:00.000Z", now), 600_000);
  assert.equal(offlineForMs(null, now), null);
});

test("evaluateHealth exit codes do not echo a token", () => {
  const connected = evaluateHealth(
    {
      ok: true,
      phoneConnected: true,
      token: "super-secret-token",
      wsPort: 11041,
      httpPort: 11043,
    },
    Date.parse("2026-10-17T06:10:00.000Z")
  );
  assert.equal(connected.exitCode, 0);
  assert.equal(connected.line, "phone connected");
  assert.equal(JSON.stringify(connected.json).includes("secret"), false);

  const offline = evaluateHealth(
    {
      ok: true,
      phoneConnected: false,
      lastDisconnectedAt: "2026-10-17T04:00:00.000Z",
      lastDisconnectCode: 1006,
      lastDisconnectReason: "abnormal closure",
      token: "super-secret-token",
    },
    Date.parse("2026-10-17T06:10:00.000Z")
  );
  assert.equal(offline.exitCode, 1);
  assert.match(offline.line, /phone offline for 2h 10m/);
  assert.match(offline.line, /code 1006/);
  assert.equal(offline.json.offlineForMs, 2 * 60 * 60 * 1000 + 10 * 60 * 1000);
  assert.equal(JSON.stringify(offline).includes("secret"), false);

  const down = evaluateHealth(null);
  assert.equal(down.exitCode, 2);
  assert.match(down.line, /unreachable/);
});

test("resolveHealthUrl refuses the command API, the websocket port path, and non-loopback hosts", () => {
  assert.equal(
    resolveHealthUrl("http://127.0.0.1:11043/api/health").pathname,
    "/api/health"
  );
  assert.throws(() => resolveHealthUrl("http://127.0.0.1:11043/api/command"));
  assert.throws(() => resolveHealthUrl("http://127.0.0.1:11041/phonepi"));
  assert.throws(() => resolveHealthUrl("http://10.0.0.8:11043/api/health"));
  assert.throws(() =>
    resolveHealthUrl("http://127.0.0.1:11043/api/health?token=abc")
  );
});

function listen(handler) {
  const server = http.createServer(handler);
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

function runCheck(url, extraArgs = []) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [SCRIPT.pathname, ...extraArgs], {
      env: { ...process.env, PHONEPI_HEALTH_URL: url },
    });
    let stdout = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.on("close", (code) => resolve({ code, stdout }));
  });
}

test("the script exits 0, 1, and 2 against a loopback health server", async () => {
  const bodies = [
    {
      ok: true,
      phoneConnected: true,
      wsPort: 11041,
      httpPort: 11043,
      token: "super-secret-token",
    },
  ];
  const server = await listen((_req, res) => {
    const body = bodies[0];
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  });
  try {
    const port = server.address().port;
    const url = `http://127.0.0.1:${port}/api/health`;
    const up = await runCheck(url);
    assert.equal(up.code, 0);
    assert.match(up.stdout, /^phone connected/);
    assert.equal(up.stdout.includes("secret"), false);

    bodies[0] = {
      ok: true,
      phoneConnected: false,
      lastDisconnectedAt: new Date(Date.now() - 125_000).toISOString(),
      lastDisconnectCode: 1006,
      lastDisconnectReason: "abnormal closure",
    };
    const offline = await runCheck(url, ["--json"]);
    assert.equal(offline.code, 1);
    assert.match(offline.stdout, /phone offline for/);
    assert.match(offline.stdout, /"status":"phone_offline"/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }

  const down = await runCheck("http://127.0.0.1:1/api/health");
  assert.equal(down.code, 2);
  assert.match(down.stdout, /unreachable/);

  const refused = await runCheck("http://127.0.0.1:9/api/command");
  assert.equal(refused.code, 2);
});
