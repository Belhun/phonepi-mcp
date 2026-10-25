/**
 * MCP CallTool gate and dispatcher. Default-deny: only listed tools run.
 * Sensitive reads need a per-call token argument. Unlisted names never
 * reach the phone.
 */

import { ErrorCode, McpError } from "@modelcontextprotocol/sdk/types.js";
import { CommandRateLimiter } from "./command-rate-limit.js";
import {
  CommandValidationError,
  RateLimitError,
  PHONE_COMMAND_NAMES,
  getPhoneCommand,
  validatePhoneCommandArgs,
} from "./commands.js";
import { GM_TOOL_NAMES, isGmTool } from "./gmessages/index.js";
import { displayPlaceName, LocationLog } from "./location-log.js";
import { annotateGmReadText, markPhoneToolResponse } from "./untrusted.js";
import { isLocationReadTool, LOCATION_TOOL_SCHEMAS, runLocationRead } from "./location-tools.js";
import { PresenceConfig, PresenceConfigData } from "./presence-config.js";
import { PresenceLog } from "./presence-log.js";
import { isPresenceReadTool, PRESENCE_TOOL_SCHEMAS, runPresenceRead } from "./presence-tools.js";
import { UsageLog } from "./usage-log.js";
import { isUsageReadTool, USAGE_TOOL_SCHEMAS, runUsageRead } from "./usage-tools.js";
import { HealthLog } from "./health-log.js";
import { HEALTH_TOOL_SCHEMAS, isHealthReadTool, runHealthRead } from "./health-tools.js";
import { NotificationLog } from "./notification-log.js";
import {
  NOTIFICATION_LOG_TOOL_SCHEMAS,
  isNotificationLogTool,
  runNotificationLogRead,
} from "./notification-log-tools.js";
import { AssistantStore } from "./assistant-events.js";
import {
  ASSISTANT_TOOL_SCHEMAS,
  isAssistantReadTool,
  runAssistantRead,
} from "./assistant-tools.js";
import {
  ALARM_TOOL_SCHEMAS,
  NOTIFICATION_TOOL_SCHEMAS,
  PHOTO_TOOL_SCHEMAS,
  VOLUME_TOOL_SCHEMAS,
} from "./read-only-tools.js";
import {
  ReadAuthState,
  authorizeSensitiveRead,
  isSensitiveReadTool,
  logSensitiveRead,
  matchReadBot,
  takeTokenArg,
} from "./read-auth.js";

export const LIVE_LOCATION_TOOL = "get_location";
export const SAVED_PLACES_TOOL = "phone_list_locations";

export const MCP_TOKEN_PROPERTY = {
  token: {
    type: "string",
    description:
      "PHONEPI_READ_TOKEN for this call. Required for sensitive reads. Stripped before the tool runs. HTTP clients send Authorization: Bearer or X-PhonePi-Read-Token instead.",
  },
};

export const GET_LOCATION_SCHEMA = {
  name: LIVE_LOCATION_TOOL,
  description:
    "Read the phone's current GPS fix. Requires PHONEPI_READ_TOKEN. Place/status only unless this bot is scoped coords:true (raw lat/lng, accuracy, mapsUrl). Token is a top-level argument named token.",
  inputSchema: {
    type: "object",
    properties: {
      ...MCP_TOKEN_PROPERTY,
    },
    required: [],
  },
};

/** Tools declared inline in the MCP list (not imported schema arrays). */
export const MCP_INLINE_TOOLS = [
  "get_battery_level",
  "add_snippet",
  "get_all_snippets",
  "get_snippet_by_id",
  "get_snippets_by_type",
  "search_snippets",
  "update_snippet",
  "delete_snippet",
  "make_call",
  "get_recent_calls",
  "find_phone",
  "copy_to_clipboard",
  "get_contacts",
  "search_contacts",
  "get_contact_by_id",
  "add_contact",
  "update_contact",
  "delete_contact",
  "share_snippet",
  "send_message",
  "get_message_response",
  LIVE_LOCATION_TOOL,
] as const;

const SCHEMA_TOOL_NAMES = [
  ...NOTIFICATION_TOOL_SCHEMAS,
  ...PHOTO_TOOL_SCHEMAS,
  ...VOLUME_TOOL_SCHEMAS,
  ...ALARM_TOOL_SCHEMAS,
  ...LOCATION_TOOL_SCHEMAS,
  ...PRESENCE_TOOL_SCHEMAS,
  ...USAGE_TOOL_SCHEMAS,
  ...HEALTH_TOOL_SCHEMAS,
  ...NOTIFICATION_LOG_TOOL_SCHEMAS,
  ...ASSISTANT_TOOL_SCHEMAS,
].map((schema) => schema.name);

export const MCP_ALLOWED_TOOLS = new Set<string>([
  ...MCP_INLINE_TOOLS,
  ...SCHEMA_TOOL_NAMES,
  ...PHONE_COMMAND_NAMES,
  ...GM_TOOL_NAMES,
]);

export function isMcpAllowedTool(name: string): boolean {
  return MCP_ALLOWED_TOOLS.has(name);
}

export function withMcpTokenArg<T extends { name: string; inputSchema?: Record<string, unknown> }>(
  schema: T
): T {
  if (!isSensitiveReadTool(schema.name)) return schema;
  const input = (schema.inputSchema ?? { type: "object", properties: {}, required: [] }) as {
    type?: string;
    properties?: Record<string, unknown>;
    required?: readonly string[];
  };
  return {
    ...schema,
    inputSchema: {
      ...input,
      type: "object",
      properties: {
        ...(input.properties ?? {}),
        ...MCP_TOKEN_PROPERTY,
      },
      required: input.required ?? [],
    },
  };
}

export function mcpSchemasWithTokenArg<T extends { name: string }>(schemas: T[]): T[] {
  return schemas.map((schema) => withMcpTokenArg(schema as T & { inputSchema?: Record<string, unknown> }));
}

export type PreparedMcpCall =
  | {
      ok: true;
      name: string;
      rest: Record<string, unknown>;
      auth: { callerId: string; coords: boolean };
    }
  | { ok: false; status: number; text: string };

export function prepareMcpToolCall(options: {
  name: string;
  args: Record<string, unknown>;
  readAuth: ReadAuthState;
  env?: NodeJS.ProcessEnv;
}): PreparedMcpCall {
  if (!isMcpAllowedTool(options.name)) {
    return {
      ok: false,
      status: 403,
      text: `Tool "${options.name}" is not on the MCP allowlist`,
    };
  }
  const stripped = takeTokenArg(options.args);
  if (!isSensitiveReadTool(options.name, options.env)) {
    const bot = matchReadBot(options.readAuth, stripped.token);
    return {
      ok: true,
      name: options.name,
      rest: stripped.rest,
      auth: {
        callerId: bot?.id ?? "mcp",
        coords: bot?.coords === true,
      },
    };
  }
  const auth = authorizeSensitiveRead({
    state: options.readAuth,
    tool: options.name,
    provided: stripped.token,
    envProvided: (options.env ?? process.env).PHONEPI_READ_TOKEN ?? null,
    source: "mcp",
    env: options.env,
  });
  if (!auth.ok) {
    return { ok: false, status: auth.status, text: auth.text };
  }
  logSensitiveRead({
    callerId: auth.callerId,
    tool: options.name,
    source: "mcp",
  });
  return {
    ok: true,
    name: options.name,
    rest: stripped.rest,
    auth,
  };
}

const LOCATION_REDACT_KEYS = new Set([
  "latitude",
  "longitude",
  "endLatitude",
  "endLongitude",
  "address",
  "mapsUrl",
  "accuracyMeters",
]);

const PLACE_NAME_KEYS = new Set(["placeName", "name"]);

export function redactLocationPayload(
  value: unknown,
  includeCoords: boolean
): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => redactLocationPayload(item, includeCoords));
  }
  if (!value || typeof value !== "object") return value;
  const copy: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
    if (!includeCoords && LOCATION_REDACT_KEYS.has(key)) {
      copy[key] = null;
      continue;
    }
    if (PLACE_NAME_KEYS.has(key) && typeof entry === "string") {
      copy[key] = displayPlaceName(entry, includeCoords);
      continue;
    }
    copy[key] = redactLocationPayload(entry, includeCoords);
  }
  return copy;
}

export interface McpCallContext {
  readAuth: ReadAuthState;
  env?: NodeJS.ProcessEnv;
  limiter: CommandRateLimiter;
  locationLog: LocationLog;
  presenceLog: PresenceLog;
  presenceConfig: PresenceConfig | PresenceConfigData;
  usageLog: UsageLog;
  healthLog: HealthLog;
  notificationLog: NotificationLog;
  assistantStore: AssistantStore;
  isPhoneOpen: () => boolean;
  callPhoneTool: (
    name: string,
    args: Record<string, unknown>,
    callerId?: string,
    options?: { skipRateLimit?: boolean }
  ) => Promise<unknown>;
  notePhoneActivity: (entry: { title: string; detail: string }) => boolean;
  notificationCaptureStatus: () => Record<string, unknown>;
  callGmTool: (
    name: string,
    args: Record<string, unknown>
  ) => Promise<{ isError: boolean; text: string }>;
}

export async function handleMcpToolCall(
  request: { params: { name: string; arguments?: Record<string, unknown> | null } },
  ctx: McpCallContext
): Promise<{ content: Array<Record<string, unknown>> }> {
  const prepared = prepareMcpToolCall({
    name: request.params.name,
    args: (request.params.arguments || {}) as Record<string, unknown>,
    readAuth: ctx.readAuth,
    env: ctx.env,
  });
  if (!prepared.ok) {
    throw new McpError(ErrorCode.InvalidRequest, prepared.text);
  }
  const { name, rest, auth } = prepared;

  if (isLocationReadTool(name)) {
    return boxRead(
      runLocationRead({
        log: ctx.locationLog,
        limiter: ctx.limiter,
        tool: name,
        args: rest,
        activityLogged: false,
        includeCoords: auth.coords,
        callerId: auth.callerId,
      }),
      name,
      "read box location history",
      ctx
    );
  }
  if (isPresenceReadTool(name)) {
    return boxRead(
      runPresenceRead({
        log: ctx.presenceLog,
        config: ctx.presenceConfig,
        limiter: ctx.limiter,
        tool: name,
        activityLogged: false,
        includeCoords: auth.coords,
        callerId: auth.callerId,
      }),
      name,
      "read box presence",
      ctx
    );
  }
  if (isUsageReadTool(name)) {
    return boxRead(
      runUsageRead({
        log: ctx.usageLog,
        limiter: ctx.limiter,
        args: rest,
        phoneConnected: ctx.isPhoneOpen(),
        activityLogged: false,
        callerId: auth.callerId,
      }),
      name,
      "read box screen time",
      ctx
    );
  }
  if (isHealthReadTool(name)) {
    return boxRead(
      runHealthRead({
        log: ctx.healthLog,
        limiter: ctx.limiter,
        phoneConnected: ctx.isPhoneOpen(),
        activityLogged: false,
        callerId: auth.callerId,
      }),
      name,
      "read box health summary",
      ctx
    );
  }
  if (isNotificationLogTool(name)) {
    const outcome = runNotificationLogRead({
      log: ctx.notificationLog,
      limiter: ctx.limiter,
      args: rest,
      capture: () => ctx.notificationCaptureStatus(),
      wrapUntrusted: true,
      callerId: auth.callerId,
    });
    if (outcome.status === 400) {
      throw new McpError(ErrorCode.InvalidParams, outcome.body.text);
    }
    if (outcome.status === 429) {
      throw new McpError(ErrorCode.InvalidRequest, outcome.body.text);
    }
    return { content: [{ type: "text", text: outcome.body.text }] };
  }
  if (isAssistantReadTool(name)) {
    return boxRead(
      runAssistantRead({
        store: ctx.assistantStore,
        limiter: ctx.limiter,
        tool: name,
        args: rest,
        activityLogged: false,
        callerId: auth.callerId,
      }),
      name,
      "read box assistant events",
      ctx
    );
  }
  if (name === "clear_assistant_events") {
    const validated = validatePhoneCommandArgs(name, rest);
    if (!validated.ok) {
      throw new McpError(ErrorCode.InvalidParams, validated.error);
    }
    const spec = getPhoneCommand(name);
    if (spec) {
      const budget = ctx.limiter.allow(
        `${auth.callerId}:${spec.name}`,
        spec.maxPerMinute
      );
      if (!budget.ok) {
        throw new McpError(
          ErrorCode.InvalidRequest,
          `Rate limited: ${spec.name}. Try again in ${budget.retryAfterSec}s`
        );
      }
    }
    ctx.assistantStore.clear();
    let phoneCleared = false;
    if (ctx.isPhoneOpen()) {
      try {
        await ctx.callPhoneTool(name, validated.args, auth.callerId, {
          skipRateLimit: true,
        });
        phoneCleared = true;
      } catch {
        phoneCleared = false;
      }
    }
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            { status: "success", cleared: true, phoneCleared },
            null,
            2
          ),
        },
      ],
    };
  }
  if (isGmTool(name)) {
    const result = await ctx.callGmTool(name, rest);
    if (result.isError) {
      throw new McpError(ErrorCode.InternalError, result.text);
    }
    return {
      content: [{ type: "text", text: annotateGmReadText(name, result.text) }],
    };
  }
  if (!ctx.isPhoneOpen()) {
    throw new McpError(
      ErrorCode.InvalidParams,
      "Phone not connected - please ensure the phone app is running and connected"
    );
  }
  try {
    let response = await ctx.callPhoneTool(name, rest, auth.callerId);
    if (
      (name === LIVE_LOCATION_TOOL || name === SAVED_PLACES_TOOL) &&
      !auth.coords
    ) {
      response = redactLocationPayload(response, false);
    }
    response = markPhoneToolResponse(name, response);
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(response, null, 2),
        },
      ],
    };
  } catch (error: unknown) {
    if (error instanceof RateLimitError) {
      throw new McpError(ErrorCode.InvalidRequest, error.message);
    }
    if (error instanceof CommandValidationError) {
      throw new McpError(ErrorCode.InvalidParams, error.message);
    }
    throw new McpError(
      ErrorCode.InternalError,
      `Failed to execute ${name}: ${
        error instanceof Error ? error.message : "Unknown error"
      }`
    );
  }
}

function boxRead(
  outcome: { status: number; body: { text?: unknown } },
  name: string,
  detail: string,
  ctx: McpCallContext
): { content: Array<Record<string, unknown>> } {
  if (outcome.status === 400) {
    throw new McpError(ErrorCode.InvalidParams, String(outcome.body.text));
  }
  if (outcome.status === 429) {
    throw new McpError(ErrorCode.InvalidRequest, String(outcome.body.text));
  }
  let text = String(outcome.body.text ?? "");
  if (ctx.notePhoneActivity({ title: name, detail })) {
    try {
      const payload = JSON.parse(text);
      payload.activityLogged = true;
      text = JSON.stringify(payload, null, 2);
    } catch {
      // Keep the original success text.
    }
  }
  return { content: [{ type: "text", text }] };
}
