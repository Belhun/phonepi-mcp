import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { NotificationLog } from "../dist/notification-log.js";
import { runNotificationLogRead } from "../dist/notification-log-tools.js";
import { NotificationPoller } from "../dist/notification-poller.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";

const NOW = Date.UTC(2026, 9, 20, 14, 0, 0);
const MIN = 60_000;

function tempLog(nowRef = { t: NOW }, days = 30) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-notif-"));
  const log = new NotificationLog(dir, days, () => nowRef.t);
  return { dir, log, nowRef };
}

function n(overrides = {}) {
  return {
    key: "0|com.example|1|null|10001",
    package: "com.example",
    appName: "Example",
    title: "Hello",
    text: "World",
    postedAt: NOW - 5 * MIN,
    isActive: true,
    notificationId: 1,
    ...overrides,
  };
}

function read(log, args = {}, limiter = new CommandRateLimiter()) {
  const out = runNotificationLogRead({ log, limiter, args });
  return { status: out.status, body: out.status === 200 ? JSON.parse(out.body.text) : out.body };
}

test("stores entries with 600 files in a 700 dir and dedupes overlap re-fetches", () => {
  const { dir, log } = tempLog();
  const first = log.ingest([n(), n({ key: "k2", title: "Other", postedAt: NOW - 4 * MIN })]);
  assert.equal(first.added, 2);
  const again = log.ingest([n(), n({ key: "k2", title: "Other", postedAt: NOW - 4 * MIN })]);
  assert.equal(again.added, 0);
  assert.equal(again.duplicates, 2);
  assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
  const files = fs.readdirSync(dir);
  assert.deepEqual(files, ["2026-10.jsonl"]);
  assert.equal(fs.statSync(path.join(dir, files[0])).mode & 0o777, 0o600);
  const lines = fs.readFileSync(path.join(dir, files[0]), "utf8").trim().split("\n");
  assert.equal(lines.length, 2);
  const e = JSON.parse(lines[0]);
  assert.equal(e.package, "com.example");
  assert.equal(e.appName, "Example");
  assert.equal(e.postedAtIso, new Date(NOW - 5 * MIN).toISOString());
  assert.equal(e.removed, false);
});

test("collapses identical re-posts of the same key (Clock 'Upcoming alarm')", () => {
  const { log } = tempLog();
  const clock = (postedAt) =>
    n({ key: "0|com.google.android.deskclock|2147483645|null|10", package: "com.google.android.deskclock", title: "Upcoming alarm", text: "Tue 6:40 AM", postedAt });
  log.ingest([clock(NOW - 60 * MIN)]);
  const r = log.ingest([clock(NOW - 50 * MIN), clock(NOW - 40 * MIN)]);
  assert.equal(r.added, 0);
  assert.equal(r.collapsed, 2);
  const changed = log.ingest([n({ ...clock(NOW - 30 * MIN), text: "Wed 6:40 AM" })]);
  assert.equal(changed.added, 1);
  const { body } = read(log, { package: "com.google.android.deskclock" });
  assert.equal(body.count, 2);
  assert.equal(body.entries[0].repeatCount, 2);
  assert.equal(body.entries[0].lastPostedAt, NOW - 40 * MIN);
});

test("removed flag updates persist and survive reload", () => {
  const { dir, log, nowRef } = tempLog();
  log.ingest([n()]);
  const r = log.ingest([n({ isActive: false })]);
  assert.equal(r.updated, 1);
  const reloaded = new NotificationLog(dir, 30, () => nowRef.t);
  const { body } = read(reloaded);
  assert.equal(body.count, 1);
  assert.equal(body.entries[0].removed, true);
});

test("since cursor pages forward without gaps and nextSince advances", () => {
  const nowRef = { t: NOW };
  const { log } = tempLog(nowRef);
  log.ingest([n({ key: "a", postedAt: NOW - 3 * MIN }), n({ key: "b", postedAt: NOW - 2 * MIN }), n({ key: "c", postedAt: NOW - MIN })]);
  const page1 = read(log, { limit: 2, since: 0 }).body;
  assert.equal(page1.count, 2);
  assert.equal(page1.hasMore, true);
  assert.deepEqual(page1.entries.map((e) => e.key), ["a", "b"]);
  const page2 = read(log, { since: page1.nextSince }).body;
  assert.deepEqual(page2.entries.map((e) => e.key), ["c"]);
  const empty = read(log, { since: page2.nextSince }).body;
  assert.equal(empty.count, 0);
  assert.equal(empty.nextSince, page2.nextSince);
  nowRef.t += MIN;
  // A late backfill with an older postedAt still shows up after the cursor.
  log.ingest([n({ key: "late", postedAt: NOW - 30 * MIN })]);
  const late = read(log, { since: empty.nextSince }).body;
  assert.deepEqual(late.entries.map((e) => e.key), ["late"]);
  // No since: newest entries, newest last.
  const newest = read(log, { limit: 2 }).body;
  assert.deepEqual(newest.entries.map((e) => e.key), ["c", "late"]);
});

test("query, package, ISO since and validation", () => {
  const { log } = tempLog();
  log.ingest([n({ key: "a", title: "Package delivered", text: "Your Amazon order" }), n({ key: "b", package: "com.other", title: "x" })]);
  assert.equal(read(log, { query: "amazon DELIVERED" }).body.count, 1);
  assert.equal(read(log, { package: "com.other" }).body.count, 1);
  assert.equal(read(log, { postedSince: new Date(NOW).toISOString() }).body.count, 0);
  assert.equal(read(log, { limit: 501 }).status, 400);
  assert.equal(read(log, { since: "not a time" }).status, 400);
});

test("rate limit is 60 per minute", () => {
  const { log } = tempLog();
  const limiter = new CommandRateLimiter();
  for (let i = 0; i < 60; i++) assert.equal(read(log, {}, limiter).status, 200);
  assert.equal(read(log, {}, limiter).status, 429);
});

test("retention drops old entries on compact and rejects old ingests", () => {
  const nowRef = { t: NOW };
  const { dir, log } = tempLog(nowRef, 30);
  log.ingest([n({ key: "old", postedAt: NOW - 29 * 24 * 60 * MIN }), n({ key: "new" })]);
  assert.equal(log.size(), 2);
  nowRef.t = NOW + 2 * 24 * 60 * MIN;
  const r = log.compact();
  assert.equal(r.dropped, 1);
  assert.equal(r.kept, 1);
  const reloaded = new NotificationLog(dir, 30, () => nowRef.t);
  assert.equal(reloaded.size(), 1);
  assert.equal(log.ingest([n({ key: "ancient", postedAt: NOW - 40 * 24 * 60 * MIN })]).added, 0);
});

test("poller backfills on connect, uses since overlap, and skips while offline", async () => {
  const nowRef = { t: NOW };
  const { log } = tempLog(nowRef);
  let connected = false;
  const calls = [];
  const lines = [];
  const poller = new NotificationPoller({
    log,
    isConnected: () => connected,
    fetch: async (args) => {
      calls.push(args);
      return { status: "success", listenerConnected: true, shareAll: true, notifications: [n({ title: "SECRET-CONTENT" })] };
    },
    intervalMs: 60_000,
    now: () => nowRef.t,
    logLine: (l) => lines.push(l),
  });
  await poller.tick();
  assert.equal(calls.length, 0);
  connected = true;
  await poller.tick();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].since, 0);
  assert.equal(log.size(), 1);
  nowRef.t += 15_000;
  await poller.tick();
  assert.equal(calls.length, 1, "waits for the interval");
  nowRef.t += 60_000;
  await poller.tick();
  assert.equal(calls.length, 2);
  assert.equal(calls[1].since, NOW - 5 * MIN - 10 * MIN);
  connected = false;
  await poller.tick();
  connected = true;
  await poller.tick();
  assert.equal(calls.length, 3, "reconnect triggers an immediate backfill");
  assert.equal(lines.some((l) => l.includes("SECRET-CONTENT")), false, "never logs contents");
  assert.equal(poller.captureStatus().lastPollOk, true);
});

test("HTTP /api/tool serves get_notification_log while the phone is offline", async () => {
  const { log } = tempLog();
  log.ingest([n()]);
  const handle = await startLoopbackHttp({
    port: 0,
    wsPort: 1,
    bridge: { isPhoneConnected: () => false, callPhoneTool: async () => ({}) },
    log: () => {},
    notificationLog: log,
    locationReadLimiter: new CommandRateLimiter(),
  });
  try {
    const res = await new Promise((resolve, reject) => {
      const payload = JSON.stringify({ tool: "get_notification_log", arguments: { since: 0 } });
      const req = http.request(
        { host: "127.0.0.1", port: handle.port, path: "/api/tool", method: "POST", headers: { "Content-Type": "application/json" } },
        (r) => {
          const chunks = [];
          r.on("data", (c) => chunks.push(c));
          r.on("end", () => resolve({ status: r.statusCode, json: JSON.parse(Buffer.concat(chunks).toString("utf8")) }));
        }
      );
      req.on("error", reject);
      req.end(payload);
    });
    assert.equal(res.status, 200);
    const body = JSON.parse(res.json.text);
    assert.equal(body.count, 1);
    assert.equal(typeof body.nextSince, "number");
  } finally {
    await new Promise((r) => handle.server.close(() => r()));
  }
});
