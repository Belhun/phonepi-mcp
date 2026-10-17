import assert from "node:assert/strict";
import test from "node:test";
import { formatLogLine, installTimestampedConsole } from "../dist/log.js";

test("formatLogLine prefixes an ISO-8601 timestamp", () => {
  const line = formatLogLine(
    "Phone connected",
    new Date("2026-10-17T06:00:00.000Z")
  );
  assert.equal(line, "2026-10-17T06:00:00.000Z Phone connected");
});

test("formatLogLine does not stamp a line twice", () => {
  const once = formatLogLine("hello", new Date("2026-10-17T06:00:00.000Z"));
  assert.equal(
    formatLogLine(once, new Date("2026-10-17T07:00:00.000Z")),
    once
  );
});

test("installTimestampedConsole stamps console.error and leaves extra args", () => {
  const lines = [];
  const fake = {
    error: (...args) => lines.push(args),
  };
  installTimestampedConsole(fake, () => new Date("2026-10-17T06:12:34.567Z"));
  fake.error("[WebSocket] Phone connected", { ok: true });
  assert.equal(lines[0][0], "2026-10-17T06:12:34.567Z [WebSocket] Phone connected");
  assert.deepEqual(lines[0][1], { ok: true });
});
