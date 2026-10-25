import assert from "node:assert/strict";
import fs from "node:fs";
import os from "os";
import path from "path";
import test from "node:test";
import { McpError } from "@modelcontextprotocol/sdk/types.js";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { LocationLog } from "../dist/location-log.js";
import { PresenceLog } from "../dist/presence-log.js";
import { DEFAULT_PRESENCE_CONFIG } from "../dist/presence-config.js";
import { UsageLog } from "../dist/usage-log.js";
import { HealthLog } from "../dist/health-log.js";
import { NotificationLog } from "../dist/notification-log.js";
import { AssistantStore } from "../dist/assistant-events.js";
import {
  GET_LOCATION_SCHEMA,
  LIVE_LOCATION_TOOL,
  MCP_ALLOWED_TOOLS,
  SAVED_PLACES_TOOL,
  handleMcpToolCall,
  isMcpAllowedTool,
  mcpSchemasWithTokenArg,
  prepareMcpToolCall,
  redactLocationPayload,
  withMcpTokenArg,
} from "../dist/mcp-call.js";
import { LOCATION_TOOL_SCHEMAS } from "../dist/location-tools.js";
import { loadReadAuth } from "../dist/read-auth.js";

function tempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function stubCtx(overrides = {}) {
  const phoneCalls = [];
  const ctx = {
    readAuth: loadReadAuth({ PHONEPI_READ_TOKEN: "mcp-read-token-32-chars-minimum-x" }),
    env: { PHONEPI_READ_TOKEN: "mcp-read-token-32-chars-minimum-x" },
    limiter: new CommandRateLimiter(60_000, () => 1),
    locationLog: new LocationLog(tempDir("mcp-loc-"), 12, () => Date.now()),
    presenceLog: new PresenceLog(tempDir("mcp-pres-"), 15 * 60_000, () => Date.now()),
    presenceConfig: DEFAULT_PRESENCE_CONFIG,
    usageLog: new UsageLog(tempDir("mcp-use-"), 90, () => Date.now()),
    healthLog: new HealthLog(tempDir("mcp-hlth-"), 7, () => Date.now()),
    notificationLog: new NotificationLog(tempDir("mcp-nlog-"), 30, () => Date.now()),
    assistantStore: new AssistantStore(tempDir("mcp-asst-"), 30, () => Date.now()),
    isPhoneOpen: () => true,
    callPhoneTool: async (name, args, callerId, options) => {
      phoneCalls.push({ name, args, callerId, options });
      if (name === LIVE_LOCATION_TOOL) {
        return { latitude: 33.2, longitude: -117.2, address: "1 Main St", mapsUrl: "https://maps" };
      }
      if (name === SAVED_PLACES_TOOL) {
        return {
          count: 1,
          locations: [
            { name: "Home", address: "1 Main St", latitude: 33.2, longitude: -117.2 },
          ],
        };
      }
      return { status: "success", tool: name };
    },
    notePhoneActivity: () => false,
    notificationCaptureStatus: () => ({ enabled: false }),
    callGmTool: async () => ({ isError: false, text: "{}" }),
    phoneCalls,
    ...overrides,
  };
  if (!overrides.callPhoneTool) {
    ctx.callPhoneTool = async (name, args, callerId, options) => {
      phoneCalls.push({ name, args, callerId, options });
      if (name === LIVE_LOCATION_TOOL) {
        return { latitude: 33.2, longitude: -117.2, address: "1 Main St", mapsUrl: "https://maps" };
      }
      if (name === SAVED_PLACES_TOOL) {
        return {
          count: 1,
          locations: [
            { name: "Home", address: "1 Main St", latitude: 33.2, longitude: -117.2 },
          ],
        };
      }
      return { status: "success", tool: name };
    };
  }
  return ctx;
}

test("MCP allowlist includes current bot tools and excludes commented SMS/photo capture", () => {
  assert.equal(isMcpAllowedTool("get_notifications"), true);
  assert.equal(isMcpAllowedTool("send_message"), true);
  assert.equal(isMcpAllowedTool("gm_send_message"), true);
  assert.equal(isMcpAllowedTool("get_notification_log"), true);
  assert.equal(isMcpAllowedTool(LIVE_LOCATION_TOOL), true);
  assert.equal(isMcpAllowedTool(SAVED_PLACES_TOOL), true);
  assert.equal(isMcpAllowedTool("send_sms"), false);
  assert.equal(isMcpAllowedTool("get_sms_messages"), false);
  assert.equal(isMcpAllowedTool("take_photo"), false);
  assert.equal(MCP_ALLOWED_TOOLS.has("not_a_real_tool"), false);
});

test("sensitive MCP schemas declare the token argument", () => {
  const [history] = mcpSchemasWithTokenArg(LOCATION_TOOL_SCHEMAS);
  assert.equal(history.inputSchema.properties.token.type, "string");
  assert.equal(GET_LOCATION_SCHEMA.inputSchema.properties.token.type, "string");
  const battery = withMcpTokenArg({
    name: "get_battery_level",
    inputSchema: { type: "object", properties: {}, required: [] },
  });
  assert.equal(battery.inputSchema.properties.token, undefined);
});

test("prepareMcpToolCall is deny-all for unlisted names", () => {
  const denied = prepareMcpToolCall({
    name: "send_sms",
    args: {},
    readAuth: loadReadAuth({ PHONEPI_READ_TOKEN: "mcp-read-token-32-chars-minimum-x" }),
    env: { PHONEPI_READ_TOKEN: "mcp-read-token-32-chars-minimum-x" },
  });
  assert.equal(denied.ok, false);
  assert.equal(denied.status, 403);
});

test("MCP request handler denies unlisted tools without calling the phone", async () => {
  const ctx = stubCtx();
  await assert.rejects(
    () => handleMcpToolCall({ params: { name: "send_sms", arguments: {} } }, ctx),
    (err) => {
      assert.ok(err instanceof McpError);
      assert.match(err.message, /allowlist/);
      return true;
    }
  );
  assert.equal(ctx.phoneCalls.length, 0);
});

test("MCP request handler requires a read token for live location and saved places", async () => {
  const ctx = stubCtx();
  await assert.rejects(
    () =>
      handleMcpToolCall(
        { params: { name: LIVE_LOCATION_TOOL, arguments: {} } },
        ctx
      ),
    (err) => {
      assert.ok(err instanceof McpError);
      assert.match(err.message, /token/i);
      return true;
    }
  );
  await assert.rejects(
    () =>
      handleMcpToolCall(
        { params: { name: SAVED_PLACES_TOOL, arguments: {} } },
        ctx
      ),
    /token/i
  );
  assert.equal(ctx.phoneCalls.length, 0);

  const labeled = await handleMcpToolCall(
    {
      params: {
        name: LIVE_LOCATION_TOOL,
        arguments: { token: "mcp-read-token-32-chars-minimum-x" },
      },
    },
    ctx
  );
  const body = JSON.parse(labeled.content[0].text);
  assert.equal(body.latitude, null);
  assert.equal(body.address, null);
  assert.equal(ctx.phoneCalls.length, 1);
  assert.equal(Object.hasOwn(ctx.phoneCalls[0].args, "token"), false);
});

test("MCP request handler still forwards non-sensitive bot tools without a read token", async () => {
  const ctx = stubCtx();
  const result = await handleMcpToolCall(
    { params: { name: "get_notifications", arguments: { limit: 5 } } },
    ctx
  );
  assert.equal(ctx.phoneCalls[0].name, "get_notifications");
  assert.equal(JSON.parse(result.content[0].text).status, "success");
});

test("MCP clear_assistant_events rate-limits once and skips the phone limiter", async () => {
  const limiter = new CommandRateLimiter(60_000, () => 1);
  const ctx = stubCtx({ limiter });
  const first = await handleMcpToolCall(
    {
      params: {
        name: "clear_assistant_events",
        arguments: { confirm: "delete" },
      },
    },
    ctx
  );
  assert.equal(JSON.parse(first.content[0].text).cleared, true);
  assert.equal(ctx.phoneCalls[0].options.skipRateLimit, true);
  for (let i = 0; i < 3; i += 1) {
    await handleMcpToolCall(
      {
        params: {
          name: "clear_assistant_events",
          arguments: { confirm: "delete" },
        },
      },
      ctx
    );
  }
  await assert.rejects(
    () =>
      handleMcpToolCall(
        {
          params: {
            name: "clear_assistant_events",
            arguments: { confirm: "delete" },
          },
        },
        ctx
      ),
    /Rate limited/
  );
  assert.equal(ctx.phoneCalls.length, 4);
});

test("redactLocationPayload drops street address when coords are withheld", () => {
  const redacted = redactLocationPayload(
    { placeName: "Cafe", address: "1 Main St", latitude: 1, longitude: 2 },
    false
  );
  assert.equal(redacted.placeName, "Cafe");
  assert.equal(redacted.address, null);
  assert.equal(redacted.latitude, null);
  const street = redactLocationPayload(
    { name: "100 Main St", address: "100 Main St", latitude: 1, longitude: 2 },
    false
  );
  assert.equal(street.name, "Saved place");
  assert.equal(street.address, null);
});

test("MCP live notifications wrap text; gm reads keep string bodies", async () => {
  const ctx = stubCtx({
    callPhoneTool: async () => ({
      status: "success",
      notifications: [
        { key: "n1", title: "Hi", text: "Secret", appName: "Gmail" },
      ],
    }),
    callGmTool: async (name) => {
      if (name === "gm_get_messages") {
        return {
          isError: false,
          text: JSON.stringify({
            messages: [{ id: "m9", text: "On my way", phone_number: "+1" }],
          }),
        };
      }
      if (name === "gm_send_message") {
        return { isError: false, text: "DELIVERED to +1" };
      }
      return { isError: false, text: "{}" };
    },
  });
  const notes = await handleMcpToolCall(
    { params: { name: "get_notifications", arguments: { limit: 5 } } },
    ctx
  );
  const body = JSON.parse(notes.content[0].text);
  assert.equal(body.notifications[0].text.untrusted, true);
  assert.match(body.notifications[0].text.provenance, /get_notifications:n1:text/);
  assert.equal(typeof body.notifications[0].text.text, "string");

  const gm = await handleMcpToolCall(
    { params: { name: "gm_get_messages", arguments: {} } },
    ctx
  );
  const gmBody = JSON.parse(gm.content[0].text);
  assert.equal(gmBody.messages[0].text, "On my way");
  assert.equal(gmBody.messages[0].untrusted, true);
  assert.equal(gmBody.messages[0].provenance, "gm_get_messages:m9");

  const sent = await handleMcpToolCall(
    {
      params: {
        name: "gm_send_message",
        arguments: { phone_number: "+1", message: "Hi" },
      },
    },
    ctx
  );
  assert.equal(sent.content[0].text, "DELIVERED to +1");
});
