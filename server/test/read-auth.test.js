import assert from "node:assert/strict";
import test from "node:test";
import {
  OPEN_READ_TOOLS_ENV,
  REQUIRE_READ_TOKEN_ALL_ENV,
  authorizeSensitiveRead,
  isSensitiveReadTool,
  loadReadAuth,
  openReadTools,
  requireReadTokenAll,
} from "../dist/read-auth.js";
import { TEST_READ_AUTH, TEST_READ_TOKEN } from "./read-auth-fixture.js";

test("strict reads are on by default", () => {
  assert.equal(requireReadTokenAll({}), true);
  assert.equal(isSensitiveReadTool("get_notifications"), true);
  assert.equal(isSensitiveReadTool("get_contacts"), true);
  assert.equal(isSensitiveReadTool("get_recent_calls"), true);
  assert.equal(isSensitiveReadTool("get_recent_photos"), true);
  assert.equal(isSensitiveReadTool("get_notification_log"), true);
  assert.equal(isSensitiveReadTool("get_all_snippets"), true);
  assert.equal(isSensitiveReadTool("get_snippet_by_id"), true);
  assert.equal(isSensitiveReadTool("get_snippets_by_type"), true);
  assert.equal(isSensitiveReadTool("search_snippets"), true);
  assert.equal(isSensitiveReadTool("get_battery_level"), false);
  assert.equal(isSensitiveReadTool("get_volumes"), false);
  assert.equal(isSensitiveReadTool("list_alarms"), false);
  assert.equal(isSensitiveReadTool("get_presence"), true);
});

test("snippet tools need a read token unless OPEN_READ_TOOLS lists them", () => {
  const denied = authorizeSensitiveRead({
    state: TEST_READ_AUTH,
    tool: "get_all_snippets",
    provided: null,
    source: "http",
    env: {},
  });
  assert.equal(denied.ok, false);
  assert.equal(denied.status, 401);
  const allowed = authorizeSensitiveRead({
    state: TEST_READ_AUTH,
    tool: "search_snippets",
    provided: TEST_READ_TOKEN,
    source: "http",
    env: {},
  });
  assert.equal(allowed.ok, true);
  const open = authorizeSensitiveRead({
    state: TEST_READ_AUTH,
    tool: "get_all_snippets",
    provided: null,
    source: "http",
    env: { [OPEN_READ_TOOLS_ENV]: "get_all_snippets" },
  });
  assert.equal(open.ok, true);
});

test("PHONEPI_REQUIRE_READ_TOKEN_ALL=0 restores the legacy open reads", () => {
  const env = { [REQUIRE_READ_TOKEN_ALL_ENV]: "0" };
  assert.equal(requireReadTokenAll(env), false);
  assert.equal(isSensitiveReadTool("get_notifications", env), false);
  assert.equal(isSensitiveReadTool("get_presence", env), true);
});

test("PHONEPI_OPEN_READ_TOOLS exemption works", () => {
  const env = { [OPEN_READ_TOOLS_ENV]: "get_notifications, list_notification_apps" };
  assert.deepEqual(
    [...openReadTools(env)].sort(),
    ["get_notifications", "list_notification_apps"]
  );
  assert.equal(isSensitiveReadTool("get_notifications", env), false);
  assert.equal(isSensitiveReadTool("get_contacts", env), true);
  const denied = authorizeSensitiveRead({
    state: TEST_READ_AUTH,
    tool: "get_contacts",
    provided: null,
    source: "http",
    env,
  });
  assert.equal(denied.ok, false);
  assert.equal(denied.status, 401);
  const open = authorizeSensitiveRead({
    state: TEST_READ_AUTH,
    tool: "get_notifications",
    provided: null,
    source: "http",
    env,
  });
  assert.equal(open.ok, true);
  const allowed = authorizeSensitiveRead({
    state: TEST_READ_AUTH,
    tool: "get_contacts",
    provided: TEST_READ_TOKEN,
    source: "http",
    env,
  });
  assert.equal(allowed.ok, true);
});

test("empty PHONEPI_OPEN_READ_TOOLS keeps strict reads closed", () => {
  assert.equal(openReadTools({}).size, 0);
  assert.equal(openReadTools({ [OPEN_READ_TOOLS_ENV]: "" }).size, 0);
  const auth = loadReadAuth({ PHONEPI_READ_TOKEN: "a".repeat(32) });
  const denied = authorizeSensitiveRead({
    state: auth,
    tool: "get_notification_log",
    provided: null,
    source: "http",
    env: {},
  });
  assert.equal(denied.ok, false);
});
