#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ErrorCode,
  ListToolsRequestSchema,
  McpError,
} from "@modelcontextprotocol/sdk/types.js";
import { WebSocket, WebSocketServer } from "ws";
import {
  GM_TOOL_SCHEMAS,
  ensureGmessagesSidecar,
  gmessagesClient,
  isGmTool,
  stopGmessagesSidecar,
} from "./gmessages/index.js";
import { commandRateLimiter } from "./command-rate-limit.js";
import { openLocationLog } from "./location-log.js";
import { isLocationReadTool, LOCATION_TOOL_SCHEMAS, runLocationRead } from "./location-tools.js";
import { openPresenceConfig } from "./presence-config.js";
import { openPresenceLog } from "./presence-log.js";
import {
  PRESENCE_TOOL_SCHEMAS,
  isPresenceReadTool,
  runPresenceRead,
} from "./presence-tools.js";
import { openUsageLog } from "./usage-log.js";
import { isUsageReadTool, USAGE_TOOL_SCHEMAS, runUsageRead } from "./usage-tools.js";
import { openHealthLog } from "./health-log.js";
import { HEALTH_TOOL_SCHEMAS, isHealthReadTool, runHealthRead } from "./health-tools.js";
import { openNotificationLog } from "./notification-log.js";
import {
  NOTIFICATION_LOG_TOOL_SCHEMAS,
  isNotificationLogTool,
  runNotificationLogRead,
} from "./notification-log-tools.js";
import { openAssistantStore } from "./assistant-events.js";
import {
  ASSISTANT_TOOL_SCHEMAS,
  isAssistantReadTool,
  runAssistantRead,
} from "./assistant-tools.js";
import { WakeHook } from "./wake-hook.js";
import {
  NotificationPoller,
  captureEnabledFromEnv,
  pollIntervalFromEnv,
} from "./notification-poller.js";
import {
  appendSendLog,
  sendLogFromPhone,
  sendLogFromRejection,
  sendTextRateLimiter,
} from "./send-text.js";
import {
  PHONE_COMMAND_SCHEMAS,
  CommandValidationError,
  RateLimitError,
  getPhoneCommand,
  isPhoneCommand,
  validatePhoneCommandArgs,
} from "./commands.js";
import {
  ALARM_TOOL_SCHEMAS,
  NOTIFICATION_TOOL_SCHEMAS,
  PHOTO_TOOL_SCHEMAS,
  VOLUME_TOOL_SCHEMAS,
  readOnlyToolRateLimit,
} from "./read-only-tools.js";
import {
  LoopbackHttpHandle,
  resolveHttpPort,
  startLoopbackHttp,
} from "./loopback-http.js";
import { installTimestampedConsole } from "./log.js";
import { requestPhoneTool } from "./phone-request.js";
import { PhoneSession, bindPhoneConnection } from "./phone-session.js";
import { createPhoneWebSocketServer } from "./phone-ws.js";
import { createPhoneVerifyClient } from "./ws-token.js";
import {
  authorizeSensitiveRead,
  isSensitiveReadTool,
  loadReadAuth,
  logSensitiveRead,
  matchReadBot,
  requireReadTokenAll,
  takeTokenArg,
} from "./read-auth.js";
import { allowIngest } from "./ingest-limit.js";

function redactWsDebug(message: Record<string, unknown>): Record<string, unknown> {
  const copy: Record<string, unknown> = { ...message };
  for (const key of ["text", "replyText", "token", "secret", "password"]) {
    if (key in copy) {
      const value = copy[key];
      copy[key] =
        typeof value === "string" ? `<redacted ${value.length} chars>` : "<redacted>";
    }
  }
  return copy;
}

installTimestampedConsole();

// Toggle this to true to enable debug logging for websocket
const DEBUG_WEBSOCKET = false;

// Parse command line arguments
const args = process.argv.slice(2);
let port = 11041; // Default port
let useStdio = false;
const httpPort = resolveHttpPort(args, process.env);

// Process arguments
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--port" && i + 1 < args.length) {
    port = parseInt(args[i + 1], 10);
    i++; // Skip the next argument which is the port number

    if (isNaN(port)) {
      console.error("Invalid port number specified. Using default port 11041.");
      port = 11041;
    }
  } else if (args[i] === "--stdio") {
    useStdio = true;
  }
}

class PhonePiMCPServer {
  private server: Server;
  private wss: WebSocketServer | null = null;
  private session = new PhoneSession<WebSocket>();
  private pendingRequests: Map<string, (response: any) => void> = new Map();
  private requestCounter = 0;
  private _pingIntervalId: NodeJS.Timeout | null = null;
  private httpApi: Promise<LoopbackHttpHandle | null> | null = null;
  private locationLog = openLocationLog();
  private presenceLog = openPresenceLog();
  private presenceConfig = openPresenceConfig();
  private usageLog = openUsageLog();
  private healthLog = openHealthLog();
  private notificationLog = openNotificationLog();
  private notificationPoller: NotificationPoller | null = null;
  private assistantStore = openAssistantStore();
  private wakeHook = new WakeHook();
  private readAuth = loadReadAuth();

  constructor(
    private serverPort: number = 11041,
    private httpApiPort: number = 11043
  ) {
    // Initialize MCP Server
    this.server = new Server(
      {
        name: "phone-mcp-server",
        version: "0.1.0",
      },
      {
        capabilities: {
          tools: {},
        },
      }
    );

    // Try to initialize WebSocket Server, or connect to existing one
    this.initializeWebSocketServer();
    this.startLocalHttpApi();
    this.setupMcpHandlers();
  }

  /** Only the process that owns the phone WebSocket server captures. */
  private startNotificationCapture() {
    if (this.notificationPoller) return;
    if (!captureEnabledFromEnv()) {
      console.error("[notification-log] capture disabled by PHONEPI_NOTIFICATION_CAPTURE");
      return;
    }
    this.notificationPoller = new NotificationPoller({
      log: this.notificationLog,
      isConnected: () => this.isPhoneOpen(),
      fetch: (args) => this.callPhoneTool("get_notifications", args),
      intervalMs: pollIntervalFromEnv(),
    });
    this.notificationPoller.start();
  }

  private notificationCaptureStatus(): Record<string, unknown> {
    if (!this.notificationPoller) return { enabled: false };
    return { ...this.notificationPoller.captureStatus() };
  }

  private startLocalHttpApi() {
    if (this.httpApiPort === this.serverPort) {
      console.error(
        `Local HTTP API port ${this.httpApiPort} matches the phone WebSocket port. Continuing without the HTTP API.`
      );
      return;
    }
    this.httpApi = startLoopbackHttp({
      port: this.httpApiPort,
      wsPort: this.serverPort,
      bridge: {
        isPhoneConnected: () => this.isPhoneOpen(),
        callPhoneTool: (name, args, callerId) =>
          this.callPhoneTool(name, args, callerId ?? "public"),
        healthDetails: () => this.session.healthDetails(),
        noteActivity: (entry) => this.notePhoneActivity(entry),
      },
      locationLog: this.locationLog,
      locationReadLimiter: commandRateLimiter,
      presenceLog: this.presenceLog,
      presenceConfig: this.presenceConfig,
      usageLog: this.usageLog,
      healthLog: this.healthLog,
      notificationLog: this.notificationLog,
      notificationCaptureStatus: () => this.notificationCaptureStatus(),
      assistantStore: this.assistantStore,
      wakeHookStatus: () => this.wakeHook.status(),
      readAuth: this.readAuth,
    });
  }

  private isPhoneOpen(): boolean {
    return this.session.isConnected();
  }

  private notePhoneActivity(entry: { title: string; detail: string }): boolean {
    const phone = this.session.currentSocket();
    if (!phone || phone.readyState !== WebSocket.OPEN) return false;
    const title = entry.title.slice(0, 80);
    const detail = entry.detail.slice(0, 240);
    try {
      phone.send(
        JSON.stringify({
          type: "activity",
          category: "tool",
          title,
          detail,
          status: "success",
        })
      );
      return true;
    } catch (err) {
      console.error("[WebSocket] Failed to record location activity on the phone");
      return false;
    }
  }

  private handlePresenceFrame(
    ws: WebSocket,
    message: { type?: string; snapshot?: unknown }
  ): void {
    if (!this.session.isCurrent(ws)) {
      console.error("[WebSocket] Ignored presence frame from a stale socket");
      return;
    }
    if (message.type === "presence_cleared") {
      this.presenceLog.clear();
      try {
        ws.send(JSON.stringify({ type: "presence_cleared_ack" }));
      } catch (err) {
        console.error("[WebSocket] Failed to ack presence clear");
      }
      return;
    }
    const budget = allowIngest("presence");
    if (!budget.ok) {
      console.error("[WebSocket] presence snapshot rate limited");
      try {
        ws.send(
          JSON.stringify({
            type: "presence_sync_ack",
            ok: false,
            error: "rate limited",
          })
        );
      } catch (err) {
        console.error("[WebSocket] Failed to ack presence sync");
      }
      return;
    }
    const result = this.presenceLog.ingest(message.snapshot);
    if (result.ok) {
      console.error("[WebSocket] presence snapshot stored");
    } else {
      console.error("[WebSocket] presence snapshot rejected");
    }
    try {
      ws.send(
        JSON.stringify({
          type: "presence_sync_ack",
          ok: result.ok,
          error: result.ok ? undefined : result.error,
        })
      );
    } catch (err) {
      console.error("[WebSocket] Failed to ack presence sync");
    }
  }

  private handleAssistantClear(ws: WebSocket): void {
    if (!this.session.isCurrent(ws)) {
      console.error("[WebSocket] Ignored assistant clear from a stale socket");
      return;
    }
    this.assistantStore.clear();
    try {
      ws.send(JSON.stringify({ type: "assistant_cleared_ack" }));
    } catch (err) {
      console.error("[WebSocket] Failed to ack assistant clear");
    }
  }

  private handleAssistantFrame(
    ws: WebSocket,
    message: Record<string, unknown>
  ): void {
    if (!this.session.isCurrent(ws)) {
      console.error("[WebSocket] Ignored assistant frame from a stale socket");
      return;
    }
    const kind = message.type;
    const result =
      kind === "notification_reply"
        ? this.assistantStore.ingestReply(message)
        : this.assistantStore.ingestPrompt(message);
    const id =
      result.accepted[0] ||
      (typeof message.id === "string" ? message.id : "");
    const ok = result.rejected.length === 0;
    if (result.rejected.length > 0) {
      console.error(
        `[WebSocket] assistant ${String(kind)} rejected=${result.rejected.length}`
      );
    } else {
      console.error(
        `[WebSocket] assistant ${String(kind)} accepted=${result.accepted.length} duplicate=${result.duplicate}`
      );
    }
    try {
      ws.send(
        JSON.stringify({
          type: "assistant_ack",
          id,
          ok,
          retryable: result.rejected.some((row) => row.retryable === true),
        })
      );
    } catch (err) {
      console.error("[WebSocket] Failed to ack assistant event");
    }
    if (!ok || result.duplicate) return;
    if (!id) return;
    const ts = typeof message.ts === "number" ? message.ts : Date.now();
    const event =
      kind === "notification_reply" ? "notification_reply" : "inbound_prompt";
    this.wakeHook.notify(event, id, ts);
  }

  private handleLocationFrame(ws: WebSocket, message: { type?: string; events?: unknown }): void {
    if (!this.session.isCurrent(ws)) {
      console.error("[WebSocket] Ignored location frame from a stale socket");
      return;
    }
    if (message.type === "location_cleared") {
      this.locationLog.clear();
      try {
        ws.send(JSON.stringify({ type: "location_cleared_ack" }));
      } catch (err) {
        console.error("[WebSocket] Failed to ack location clear");
      }
      return;
    }
    const locationBudget = allowIngest("location");
    if (!locationBudget.ok) {
      console.error("[WebSocket] location sync rate limited");
      try {
        ws.send(
          JSON.stringify({
            type: "location_sync_ack",
            accepted: [],
            rejected: [{ id: "", error: "rate limited" }],
          })
        );
      } catch (err) {
        console.error("[WebSocket] Failed to ack location sync");
      }
      return;
    }
    const result = this.locationLog.ingest(message.events);
    console.error(
      `[WebSocket] location sync accepted=${result.accepted.length} rejected=${result.rejected.length}`
    );
    try {
      ws.send(
        JSON.stringify({
          type: "location_sync_ack",
          accepted: result.accepted,
          rejected: result.rejected.map((item) => ({
            id: item.id,
            error: item.error,
          })),
        })
      );
    } catch (err) {
      console.error("[WebSocket] Failed to ack location sync");
    }
  }

  private handleUsageFrame(
    ws: WebSocket,
    message: { type?: string; days?: unknown; meta?: unknown }
  ): void {
    if (!this.session.isCurrent(ws)) {
      console.error("[WebSocket] Ignored usage frame from a stale socket");
      return;
    }
    if (message.type === "usage_cleared") {
      this.usageLog.clear();
      try {
        ws.send(JSON.stringify({ type: "usage_cleared_ack" }));
      } catch (err) {
        console.error("[WebSocket] Failed to ack usage clear");
      }
      return;
    }
    const usageBudget = allowIngest("usage");
    if (!usageBudget.ok) {
      console.error("[WebSocket] usage sync rate limited");
      try {
        ws.send(
          JSON.stringify({
            type: "usage_sync_ack",
            accepted: [],
            rejected: [{ id: "", error: "rate limited" }],
          })
        );
      } catch (err) {
        console.error("[WebSocket] Failed to ack usage sync");
      }
      return;
    }
    const result = this.usageLog.ingest(message.days, message.meta);
    console.error(
      `[WebSocket] usage sync accepted=${result.accepted.length} rejected=${result.rejected.length}`
    );
    try {
      ws.send(
        JSON.stringify({
          type: "usage_sync_ack",
          accepted: result.accepted,
          rejected: result.rejected.map((item) => ({
            id: item.id,
            error: item.error,
          })),
        })
      );
    } catch (err) {
      console.error("[WebSocket] Failed to ack usage sync");
    }
  }

  private handleHealthFrame(ws: WebSocket, message: { type?: string; summary?: unknown }): void {
    if (!this.session.isCurrent(ws)) {
      console.error("[WebSocket] Ignored health frame from a stale socket");
      return;
    }
    if (message.type === "health_cleared") {
      this.healthLog.clear();
      try {
        ws.send(JSON.stringify({ type: "health_cleared_ack" }));
      } catch (err) {
        console.error("[WebSocket] Failed to ack health clear");
      }
      return;
    }
    const healthBudget = allowIngest("health");
    if (!healthBudget.ok) {
      console.error("[WebSocket] health sync rate limited");
      try {
        ws.send(
          JSON.stringify({
            type: "health_sync_ack",
            ok: false,
            error: "rate limited",
          })
        );
      } catch (err) {
        console.error("[WebSocket] Failed to ack health sync");
      }
      return;
    }
    const result = this.healthLog.ingest(message.summary);
    console.error(
      `[WebSocket] health sync ${result.ok ? "accepted" : "rejected"}`
    );
    try {
      ws.send(
        JSON.stringify({
          type: "health_sync_ack",
          ok: result.ok,
          error: result.ok ? undefined : result.error,
        })
      );
    } catch (err) {
      console.error("[WebSocket] Failed to ack health sync");
    }
  }

  private authorizeMcpRead(
    tool: string,
    provided: string | null
  ): { callerId: string; coords: boolean } {
    if (!isSensitiveReadTool(tool)) {
      const bot = matchReadBot(this.readAuth, provided);
      return {
        callerId: bot?.id ?? "mcp",
        coords: bot?.coords === true,
      };
    }
    const auth = authorizeSensitiveRead({
      state: this.readAuth,
      tool,
      provided,
      envProvided: process.env.PHONEPI_READ_TOKEN ?? null,
      source: "mcp",
    });
    if (!auth.ok) {
      throw new McpError(ErrorCode.InvalidRequest, auth.text);
    }
    logSensitiveRead({
      callerId: auth.callerId,
      tool,
      source: "mcp",
    });
    return auth;
  }

  private callPhoneTool(
    name: string,
    args: Record<string, unknown>,
    callerId = "shared"
  ): Promise<unknown> {
    const validated = validatePhoneCommandArgs(name, args);
    if (!validated.ok) {
      if (name === "request_send_text") {
        appendSendLog(sendLogFromRejection(args, "rejected", validated.error));
      }
      return Promise.reject(new CommandValidationError(validated.error));
    }
    const params = validated.args;
    if (name === "request_send_text") {
      const mode = params.mode === "attributed" ? "attributed" : "as_alex";
      const budget = sendTextRateLimiter.allow(mode);
      if (!budget.ok) {
        appendSendLog(
          sendLogFromRejection(
            params,
            "rate_limited",
            `Rate limited: request_send_text ${mode} ${budget.scope}`
          )
        );
        return Promise.reject(
          new RateLimitError("request_send_text", budget.retryAfterSec)
        );
      }
    }
    if (isPhoneCommand(name)) {
      const spec = getPhoneCommand(name);
      if (spec) {
        const budget = commandRateLimiter.allow(
          `${callerId}:${spec.name}`,
          spec.maxPerMinute
        );
        if (!budget.ok) {
          return Promise.reject(
            new RateLimitError(spec.name, budget.retryAfterSec)
          );
        }
      }
    } else {
      const maxPerMinute = readOnlyToolRateLimit(name);
      if (maxPerMinute != null) {
        const budget = commandRateLimiter.allow(
          `${callerId}:${name}`,
          maxPerMinute
        );
        if (!budget.ok) {
          return Promise.reject(
            new RateLimitError(name, budget.retryAfterSec)
          );
        }
      }
    }
    const requestId = `req-${++this.requestCounter}`;
    return requestPhoneTool({
      connection: this.session.currentSocket(),
      pending: this.pendingRequests,
      requestId,
      tool: getPhoneCommand(name)?.phoneTool ?? name,
      params,
      debug: DEBUG_WEBSOCKET,
    });
  }

  private initializeWebSocketServer() {
    // Set up error handling
    this.setupErrorHandling();

    try {
      // Initialize WebSocket Server with debug logging
      console.error(`Starting WebSocket server on port ${this.serverPort}...`);
      this.wss = createPhoneWebSocketServer({
        port: this.serverPort,
        host: "0.0.0.0", // Explicitly listen on all interfaces
        verifyClient: createPhoneVerifyClient(),
      });

      this.wss.on("error", (error: any) => {
        if (error.code === "EADDRINUSE") {
          console.error(
            `Port ${this.serverPort} already in use, connecting to existing server...`
          );
          // Close our server instance that failed
          if (this.wss) {
            this.wss.close();
            this.wss = null;
          }
          this.connectToExistingServer();
        } else {
          console.error("WebSocket Server Error:", error);
        }
      });

      this.wss.on("connection", (ws, req) => {
        if (DEBUG_WEBSOCKET) {
          console.error(
            `[WebSocket] New connection from ${
              req.socket.remoteAddress || "unknown"
            }`
          );
        }
      });

      this.wss.on("listening", () => {
        console.error(
          `WebSocket server is listening on port ${this.serverPort}`
        );
        this.setupWebSocket();
        this.startNotificationCapture();
      });

      // Additional error handling for the server
      this.wss.on("close", () => {
        if (DEBUG_WEBSOCKET) {
          console.error("[WebSocket] Server closed");
        }
      });
    } catch (error) {
      console.error(
        "Failed to start WebSocket server, connecting to existing one...",
        error
      );
      this.connectToExistingServer();
    }
  }

  private connectToExistingServer() {
    console.error(
      `Attempting to connect to existing WebSocket server on port ${this.serverPort}...`
    );

    // Create a WebSocket client to connect to the existing server
    const ws = new WebSocket(`ws://localhost:${this.serverPort}`, {
      handshakeTimeout: 10000, // 10 seconds timeout for initial handshake
    });

    // Set a connection timeout
    const connectionTimeout = setTimeout(() => {
      if (ws.readyState !== WebSocket.OPEN) {
        if (DEBUG_WEBSOCKET) {
          console.error("[WebSocket] Connection attempt timed out");
        }
        ws.terminate();

        // After timeout, wait a bit and try to start our own server
        setTimeout(() => {
          if (DEBUG_WEBSOCKET) {
            console.error(
              "[WebSocket] Attempting to start our own server after connection timeout"
            );
          }
          this.initializeWebSocketServer();
        }, 5000);
      }
    }, 15000);

    ws.on("open", () => {
      clearTimeout(connectionTimeout);
      console.error("Connected to existing WebSocket server");
      bindPhoneConnection(this.session, ws, (line) => console.error(line), {
        onMessage: (socket, data) => {
          void this.processIncomingMessage(socket, data);
        },
        onCleared: () => {
          setTimeout(() => this.connectToExistingServer(), 5000);
        },
      });
      this.startPingInterval();
    });

    ws.on("error", (error) => {
      console.error("Error connecting to existing WebSocket server:", error);
    });

    // Set up error handling
    this.setupErrorHandling();
  }

  private setupErrorHandling() {
    // Error handling
    this.server.onerror = (error) => console.error("[MCP Error]", error);
    process.on("SIGINT", async () => {
      await this.cleanup();
      process.exit(0);
    });
  }

  private setupWebSocket() {
    if (!this.wss) {
      console.error("WebSocket server not initialized");
      return;
    }

    this.wss.on("connection", (ws) => {
      bindPhoneConnection(this.session, ws, (line) => console.error(line), {
        onMessage: (socket, data) => {
          void this.processIncomingMessage(socket, data);
        },
      });
    });

    // Start the ping interval
    this.startPingInterval();
  }

  private async processIncomingMessage(ws: WebSocket, data: any) {
    try {
      // Convert the data to string properly based on its type
      const dataString =
        typeof data === "string"
          ? data
          : data instanceof Buffer
          ? data.toString("utf-8")
          : JSON.stringify(data);

      if (DEBUG_WEBSOCKET) {
        console.error(
          `[WebSocket] Raw message received: ${dataString.substring(0, 200)}${
            dataString.length > 200 ? "..." : ""
          }`
        );
      }

      let message;
      try {
        message = JSON.parse(dataString);
      } catch (jsonError) {
        console.error(
          `[WebSocket] Failed to parse JSON: ${
            jsonError instanceof Error ? jsonError.message : String(jsonError)
          }`
        );
        // Send an error response if the message cannot be parsed
        try {
          ws.send(
            JSON.stringify({
              type: "error",
              error: "Invalid JSON format",
              originalMessage: dataString.substring(0, 100),
            })
          );
        } catch (sendError) {
          console.error(
            `[WebSocket] Failed to send error response: ${sendError}`
          );
        }
        return;
      }
      if (
        message &&
        (message.type === "location_sync" || message.type === "location_cleared")
      ) {
        this.handleLocationFrame(ws, message);
        return;
      }
      if (
        message &&
        (message.type === "presence_sync" || message.type === "presence_cleared")
      ) {
        this.handlePresenceFrame(ws, message);
        return;
      }
      if (
        message &&
        (message.type === "usage_sync" || message.type === "usage_cleared")
      ) {
        this.handleUsageFrame(ws, message);
        return;
      }
      if (
        message &&
        (message.type === "health_sync" || message.type === "health_cleared")
      ) {
        this.handleHealthFrame(ws, message);
        return;
      }
      if(DEBUG_WEBSOCKET) {
        console.error(
          `[WebSocket] Parsed message: ${JSON.stringify(redactWsDebug(message))}`
        );
      }
      // Process different message types
      if (message.type === "pong") {
        if (!this.session.notePong(ws)) {
          console.error("[WebSocket] Ignored pong from a stale socket");
        }
      } else if (message.type === "send_text_log") {
        if (!this.session.isCurrent(ws)) {
          console.error("[WebSocket] Ignored send log from a stale socket");
          return;
        }
        const entry = sendLogFromPhone(message as Record<string, unknown>);
        if (entry) appendSendLog(entry);
      } else if (message.type === "assistant_cleared") {
        this.handleAssistantClear(ws);
      } else if (
        message.type === "notification_reply" ||
        message.type === "inbound_prompt"
      ) {
        this.handleAssistantFrame(ws, message as Record<string, unknown>);
      } else if (message.type === "permissions") {
        if (!this.session.notePermissions(ws, message)) {
          console.error("[WebSocket] Ignored permissions from a stale socket");
        }
      } else if (message.type === "ping") {
        if (!this.session.isCurrent(ws)) {
          console.error("[WebSocket] Ignored ping from a stale socket");
          return;
        }
        try {
          ws.send(JSON.stringify({ type: "pong" }));
        } catch (err) {
          console.error("[WebSocket] Error replying to phone ping:", err);
        }
      } else if (message.type === "response") {
        if (!message.requestId) {
          console.error(
            `[WebSocket] Response missing requestId: ${JSON.stringify(message)}`
          );
          return;
        }

        const resolver = this.pendingRequests.get(message.requestId);
        if (resolver) {
          resolver(message.data);
          this.pendingRequests.delete(message.requestId);
          if (DEBUG_WEBSOCKET) {
            console.error(`[WebSocket] Resolved request: ${message.requestId}`);
          }
        } else {
          console.error(
            `[WebSocket] No pending request found for ID: ${message.requestId}`
          );
        }
      } else if (message.type === "request") {
        console.error(
          `[WebSocket] Received request from phone: ${JSON.stringify(message)}`
        );
        this.handleIncomingRequest(ws, message);
      } else if (message.type === "ping") {
        this.handleIncomingRequest(ws, {
          ...message,
          type: "ping",
          tool: "ping",
        });
      } else {
        console.error(`[WebSocket] Unknown message type: ${message.type}`);
      }
    } catch (error) {
      console.error("[WebSocket] Error processing message:", error);
    }
  }

  private startPingInterval() {
    // Clear any existing ping interval
    if (this._pingIntervalId) {
      clearInterval(this._pingIntervalId);
    }

    // Ping phone every 15 seconds to check connection
    const pingInterval = setInterval(() => {
      const timedOut = this.session.takeTimedOut(45000);
      if (timedOut) {
        console.error("[WebSocket] Phone timed out - no pong received");
        try {
          if (timedOut.readyState === WebSocket.OPEN) {
            console.error("[WebSocket] Closing timed out connection");
            if (timedOut.terminate) {
              timedOut.terminate();
            } else {
              timedOut.close(1000, "no pong received");
            }
          }
        } catch (err) {
          console.error(
            "[WebSocket] Error closing timed out connection:",
            err
          );
        }
        return;
      }
      const phone = this.session.currentSocket();
      if (phone && phone.readyState === WebSocket.OPEN) {
        try {
          phone.send(JSON.stringify({ type: "ping" }));
        } catch (err) {
          console.error("[WebSocket] Error sending ping:", err);
        }
      }
    }, 15000);

    // Store the interval ID for cleanup
    this._pingIntervalId = pingInterval;
  }

  private async handleIncomingRequest(ws: WebSocket, message: any) {
    // Handle incoming requests from the phone
    if (DEBUG_WEBSOCKET) {
      console.error(
        `[WebSocket] Processing request from phone: ${JSON.stringify(message)}`
      );
    }

    // Check if the request is properly formed
    if (!message.requestId) {
      if (DEBUG_WEBSOCKET) {
        console.error(
          `[WebSocket] Request missing requestId: ${JSON.stringify(message)}`
        );
      }
      return;
    }

    if (!message.tool) {
      if (DEBUG_WEBSOCKET) {
        console.error(
          `[WebSocket] Request missing tool: ${JSON.stringify(message)}`
        );
      }
      try {
        ws.send(
          JSON.stringify({
            type: "response",
            requestId: message.requestId,
            data: { status: "error", error: "Missing tool name in request" },
          })
        );
      } catch (sendError) {
        console.error(
          `[WebSocket] Failed to send error response: ${sendError}`
        );
      }
      return;
    }

    // Process the request based on the tool requested
    try {
      // Handle different tool requests
      const toolName = message.tool;
      const params = message.params || {};
      let responseData;

      // Example of how to handle specific tools
      if (toolName === "ping") {
        responseData = {
          status: "success",
          message: "pong",
          timestamp: Date.now(),
        };
      } else if (toolName === "get_server_info") {
        responseData = {
          status: "success",
          info: {
            version: "0.1.0",
            uptime: process.uptime(),
            nodeVersion: process.version,
            platform: process.platform,
          },
        };
      } else {
        if (DEBUG_WEBSOCKET) {
          // Forward the request to the phone and wait for its actual response
          console.error(`[WebSocket] Forwarding request to phone: ${toolName}`);
        }
        // Create a promise to handle the response from the phone
        const phoneResponse = new Promise((resolve, reject) => {
          // Set a reasonable timeout for the phone to respond
          const timeout = setTimeout(() => {
            this.pendingRequests.delete(message.requestId);
            reject(
              new Error(
                `Request to phone timed out after 30 seconds: ${toolName}`
              )
            );
          }, 30000);

          // Store the request ID with a custom callback for this specific request
          this.pendingRequests.set(message.requestId, (response) => {
            clearTimeout(timeout);
            resolve(response);
          });

          // Forward the request to the connected phone
          const phone = this.session.currentSocket();
          if (phone && phone.readyState === WebSocket.OPEN) {
            try {
              phone.send(
                JSON.stringify({
                  type: "request",
                  requestId: message.requestId,
                  tool: toolName,
                  params: params,
                })
              );
              if (DEBUG_WEBSOCKET) {
                console.error(
                  `[WebSocket] Forwarded request to phone: ${toolName} (ID: ${message.requestId})`
                );
              }
            } catch (sendError) {
              clearTimeout(timeout);
              this.pendingRequests.delete(message.requestId);
              reject(
                new Error(
                  `Failed to forward request to phone: ${
                    sendError instanceof Error
                      ? sendError.message
                      : String(sendError)
                  }`
                )
              );
            }
          } else {
            clearTimeout(timeout);
            this.pendingRequests.delete(message.requestId);
            reject(new Error("Phone not connected or connection not ready"));
          }
        });

        try {
          // Wait for the phone's response
          responseData = await phoneResponse;
          if (DEBUG_WEBSOCKET) {
            console.error(
              `[WebSocket] Received response from phone for ${
                message.requestId
              }: ${JSON.stringify(responseData)}`
            );
          }
        } catch (error) {
          console.error(
            `[WebSocket] Error waiting for phone response: ${error}`
          );
          responseData = {
            status: "error",
            error: `Failed to get response from phone: ${
              error instanceof Error ? error.message : String(error)
            }`,
            timestamp: Date.now(),
          };
        }
      }

      // Send the response
      ws.send(
        JSON.stringify({
          type: "response",
          requestId: message.requestId,
          data: responseData,
        })
      );

      if (DEBUG_WEBSOCKET) {
        console.error(
          `[WebSocket] Sent response for request ${
            message.requestId
          }: ${JSON.stringify(responseData)}`
        );
      }
    } catch (processError) {
      console.error(`[WebSocket] Error processing request: ${processError}`);
      try {
        ws.send(
          JSON.stringify({
            type: "response",
            requestId: message.requestId,
            data: {
              status: "error",
              error: `Failed to process request: ${
                processError instanceof Error
                  ? processError.message
                  : String(processError)
              }`,
            },
          })
        );
      } catch (sendError) {
        console.error(
          `[WebSocket] Failed to send error response: ${sendError}`
        );
      }
    }
  }

  private setupMcpHandlers() {
    // List available tools
    this.server.setRequestHandler(ListToolsRequestSchema, async () => ({
      tools: [
        {
          name: "get_battery_level",
          description: "Get the current battery level of the phone",
          inputSchema: {
            type: "object",
            properties: {},
            required: [],
          },
        },
        // {
        //   name: 'take_photo',
        //   description: 'Take a photo using the phone camera',
        //   inputSchema: {
        //     type: 'object',
        //     properties: {
        //       camera: {
        //         type: 'string',
        //         enum: ['front', 'back'],
        //         description: 'Which camera to use',
        //       },
        //     },
        //     required: ['camera'],
        //   },
        // },
        // {
        //   name: 'get_location',
        //   description: 'Get the current GPS location of the phone',
        //   inputSchema: {
        //     type: 'object',
        //     properties: {},
        //     required: [],
        //   },
        // },
        // Snippet tools
        {
          name: "add_snippet",
          description: "Add a new snippet to the phone",
          inputSchema: {
            type: "object",
            properties: {
              title: {
                type: "string",
                description: "Title of the snippet",
              },
              content: {
                type: "string",
                description: "Content of the snippet",
              },
              type: {
                type: "string",
                enum: ["note", "todo", "bookmark", "snippet", "draft"],
                description: "Type of the snippet",
              },
              tags: {
                type: "string",
                description: "Comma-separated tags for the snippet",
              },
            },
            required: ["title", "content", "type"],
          },
        },
        {
          name: "get_all_snippets",
          description: "Get all snippets from the phone",
          inputSchema: {
            type: "object",
            properties: {},
            required: [],
          },
        },
        {
          name: "get_snippet_by_id",
          description: "Get a specific snippet by ID",
          inputSchema: {
            type: "object",
            properties: {
              id: {
                type: "number",
                description: "Snippet ID",
              },
            },
            required: ["id"],
          },
        },
        {
          name: "get_snippets_by_type",
          description: "Get snippets by type",
          inputSchema: {
            type: "object",
            properties: {
              type: {
                type: "string",
                enum: ["note", "todo", "bookmark", "snippet", "draft"],
                description: "Type of snippets to retrieve",
              },
            },
            required: ["type"],
          },
        },
        {
          name: "search_snippets",
          description: "Search snippets by query",
          inputSchema: {
            type: "object",
            properties: {
              query: {
                type: "string",
                description: "Search query",
              },
            },
            required: ["query"],
          },
        },
        {
          name: "update_snippet",
          description: "Update an existing snippet",
          inputSchema: {
            type: "object",
            properties: {
              id: {
                type: "number",
                description: "Snippet ID",
              },
              title: {
                type: "string",
                description: "Title of the snippet",
              },
              content: {
                type: "string",
                description: "Content of the snippet",
              },
              type: {
                type: "string",
                enum: ["note", "todo", "bookmark", "snippet", "draft"],
                description: "Type of the snippet",
              },
              tags: {
                type: "string",
                description: "Comma-separated tags for the snippet",
              },
            },
            required: ["id", "title", "content", "type"],
          },
        },
        {
          name: "delete_snippet",
          description: "Delete a snippet from the phone",
          inputSchema: {
            type: "object",
            properties: {
              id: {
                type: "number",
                description: "Snippet ID to delete",
              },
            },
            required: ["id"],
          },
        },
        // Legacy carrier SMS — superseded by Google Messages bridge (gm_send_message, gm_get_messages, etc.)
        // {
        //   name: "send_sms",
        //   description: "Send an SMS message",
        //   inputSchema: {
        //     type: "object",
        //     properties: {
        //       to: { type: "string", description: "Phone number to send to" },
        //       message: { type: "string", description: "Message content" },
        //     },
        //     required: ["to", "message"],
        //   },
        // },
        // {
        //   name: "get_sms_messages",
        //   description:
        //     "Read SMS via Telephony provider (no RCS). Default limit is 100; 200 is the soft cap for routine queries — pass a higher limit when the user explicitly asks for more. Use gm_get_conversation instead.",
        //   inputSchema: {
        //     type: "object",
        //     properties: {
        //       contactName: { type: "string" },
        //       contactId: { type: "string" },
        //       from: { type: "string" },
        //       hours: { type: "number" },
        //       since: { type: "number" },
        //       limit: {
        //         type: "number",
        //         description:
        //           "Max messages (default 100). 200 is a soft cap for routine use; higher values are allowed when explicitly requested.",
        //       },
        //       includeSent: { type: "boolean" },
        //     },
        //     required: [],
        //   },
        // },
        {
          name: "make_call",
          description: "Make a phone call",
          inputSchema: {
            type: "object",
            properties: {
              to: {
                type: "string",
                description: "Phone number to call",
              },
            },
            required: ["to"],
          },
        },
        {
          name: "get_recent_calls",
          description:
            "Read recent phone call history from the device (incoming, outgoing, missed, etc.). Default limit is 50; 200 is the soft cap for routine queries — pass a higher limit when the user explicitly asks for more.",
          inputSchema: {
            type: "object",
            properties: {
              hours: {
                type: "number",
                description: "How many hours back to search (default 168 = 7 days)",
              },
              since: {
                type: "number",
                description: "Unix epoch ms — overrides hours when set",
              },
              limit: {
                type: "number",
                description:
                  "Max calls to return (default 50). 200 is a soft cap for routine use; higher values are allowed when explicitly requested.",
              },
              type: {
                type: "string",
                description:
                  "Filter by one call type: incoming, outgoing, missed, voicemail, rejected, blocked, answered_externally",
              },
              types: {
                type: "array",
                items: { type: "string" },
                description: "Filter by multiple call types",
              },
              number: {
                type: "string",
                description: "Filter to calls with this phone number",
              },
              includeContactNames: {
                type: "boolean",
                description:
                  "Resolve contact names for numbers (default true; needs READ_CONTACTS)",
              },
            },
            required: [],
          },
        },
        {
          name: "find_phone",
          description: "Make the phone beep to help locate it",
          inputSchema: {
            type: "object",
            properties: {
              loop: {
                type: "boolean",
                description:
                  "Whether to play the beep in a loop until dismissed",
                default: true,
              },
              showDismissUI: {
                type: "boolean",
                description: "Whether to show a UI for dismissing the beep",
                default: true,
              },
              notifyOnCompletion: {
                type: "boolean",
                description:
                  "Whether to send a notification when the task is complete",
                default: false,
              },
            },
            required: [],
          },
        },
        // {
        //   name: 'cancel_alarm_or_timer',
        //   description: 'Cancel a previously set alarm or timer',
        //   inputSchema: {
        //     type: 'object',
        //     properties: {
        //       identifier: {
        //         type: 'string',
        //         description: 'Identifier of the alarm/timer to cancel',
        //       },
        //     },
        //     required: ['identifier'],
        //   },
        // },
        // {
        //   name: 'stop_alarm',
        //   description: 'Stop the currently playing alarm sound',
        //   inputSchema: {
        //     type: 'object',
        //     properties: {},
        //     required: [],
        //   },
        // },
        {
          name: "copy_to_clipboard",
          description: "Copy text to the phone clipboard",
          inputSchema: {
            type: "object",
            properties: {
              text: {
                type: "string",
                description: "Text to copy to clipboard",
              },
            },
            required: ["text"],
          },
        },
        {
          name: "get_contacts",
          description: "Get all contacts from the phone",
          inputSchema: {
            type: "object",
            properties: {},
            required: [],
          },
        },
        {
          name: "search_contacts",
          description:
            "Search contacts by name. Use before gm_list_conversations or gm_get_conversation when you need to find someone.",
          inputSchema: {
            type: "object",
            properties: {
              query: {
                type: "string",
                description: "Name or partial name to search (e.g. \"Celia Griffiths\")",
              },
              limit: {
                type: "number",
                description: "Max results (default 10)",
              },
            },
            required: ["query"],
          },
        },
        {
          name: "get_contact_by_id",
          description: "Get a specific contact by ID",
          inputSchema: {
            type: "object",
            properties: {
              id: {
                type: "string",
                description: "Contact ID",
              },
            },
            required: ["id"],
          },
        },
        {
          name: "add_contact",
          description: "Add a new contact to the phone",
          inputSchema: {
            type: "object",
            properties: {
              contact: {
                type: "object",
                description: "Contact data",
                properties: {
                  name: {
                    type: "string",
                    description: "Full name of the contact",
                  },
                  firstName: {
                    type: "string",
                    description: "First name of the contact",
                  },
                  middleName: {
                    type: "string",
                    description: "Middle name of the contact",
                  },
                  lastName: {
                    type: "string",
                    description: "Last name of the contact",
                  },
                  phoneNumbers: {
                    type: "array",
                    description: "List of phone numbers",
                    items: {
                      type: "object",
                      properties: {
                        label: {
                          type: "string",
                          description:
                            'Label for the phone number (e.g., "home", "work", "mobile")',
                        },
                        number: {
                          type: "string",
                          description: "The phone number",
                        },
                      },
                      required: ["label", "number"],
                    },
                  },
                  emails: {
                    type: "array",
                    description: "List of email addresses",
                    items: {
                      type: "object",
                      properties: {
                        label: {
                          type: "string",
                          description:
                            'Label for the email (e.g., "home", "work")',
                        },
                        email: {
                          type: "string",
                          description: "The email address",
                        },
                      },
                      required: ["label", "email"],
                    },
                  },
                  addresses: {
                    type: "array",
                    description: "List of addresses",
                    items: {
                      type: "object",
                      properties: {
                        label: {
                          type: "string",
                          description:
                            'Label for the address (e.g., "home", "work")',
                        },
                        street: {
                          type: "string",
                          description: "Street address",
                        },
                        city: {
                          type: "string",
                          description: "City",
                        },
                        region: {
                          type: "string",
                          description: "State/Province/Region",
                        },
                        postalCode: {
                          type: "string",
                          description: "Postal/ZIP code",
                        },
                        country: {
                          type: "string",
                          description: "Country",
                        },
                      },
                      required: ["label"],
                    },
                  },
                  company: {
                    type: "string",
                    description: "Company name",
                  },
                  jobTitle: {
                    type: "string",
                    description: "Job title",
                  },
                  note: {
                    type: "string",
                    description: "Additional notes about the contact",
                  },
                },
                required: ["name"],
              },
            },
            required: ["contact"],
          },
        },
        {
          name: "update_contact",
          description: "Update an existing contact",
          inputSchema: {
            type: "object",
            properties: {
              contact: {
                type: "object",
                description: "Contact data with updates",
                properties: {
                  id: {
                    type: "string",
                    description: "Contact ID (required for updating)",
                  },
                  name: {
                    type: "string",
                    description: "Full name of the contact",
                  },
                  firstName: {
                    type: "string",
                    description: "First name of the contact",
                  },
                  middleName: {
                    type: "string",
                    description: "Middle name of the contact",
                  },
                  lastName: {
                    type: "string",
                    description: "Last name of the contact",
                  },
                  phoneNumbers: {
                    type: "array",
                    description: "List of phone numbers",
                    items: {
                      type: "object",
                      properties: {
                        id: {
                          type: "string",
                          description:
                            "ID of the phone number (required for updating existing numbers)",
                        },
                        label: {
                          type: "string",
                          description:
                            'Label for the phone number (e.g., "home", "work", "mobile")',
                        },
                        number: {
                          type: "string",
                          description: "The phone number",
                        },
                      },
                      required: ["label", "number"],
                    },
                  },
                  emails: {
                    type: "array",
                    description: "List of email addresses",
                    items: {
                      type: "object",
                      properties: {
                        id: {
                          type: "string",
                          description:
                            "ID of the email (required for updating existing emails)",
                        },
                        label: {
                          type: "string",
                          description:
                            'Label for the email (e.g., "home", "work")',
                        },
                        email: {
                          type: "string",
                          description: "The email address",
                        },
                      },
                      required: ["label", "email"],
                    },
                  },
                  addresses: {
                    type: "array",
                    description: "List of addresses",
                    items: {
                      type: "object",
                      properties: {
                        id: {
                          type: "string",
                          description:
                            "ID of the address (required for updating existing addresses)",
                        },
                        label: {
                          type: "string",
                          description:
                            'Label for the address (e.g., "home", "work")',
                        },
                        street: {
                          type: "string",
                          description: "Street address",
                        },
                        city: {
                          type: "string",
                          description: "City",
                        },
                        region: {
                          type: "string",
                          description: "State/Province/Region",
                        },
                        postalCode: {
                          type: "string",
                          description: "Postal/ZIP code",
                        },
                        country: {
                          type: "string",
                          description: "Country",
                        },
                      },
                      required: ["label"],
                    },
                  },
                  company: {
                    type: "string",
                    description: "Company name",
                  },
                  jobTitle: {
                    type: "string",
                    description: "Job title",
                  },
                  note: {
                    type: "string",
                    description: "Additional notes about the contact",
                  },
                },
                required: ["id", "name"],
              },
            },
            required: ["contact"],
          },
        },
        {
          name: "delete_contact",
          description: "Delete a contact from the phone",
          inputSchema: {
            type: "object",
            properties: {
              id: {
                type: "string",
                description: "Contact ID to delete",
              },
            },
            required: ["id"],
          },
        },
        {
          name: "share_snippet",
          description: "Share a snippet via messaging apps",
          inputSchema: {
            type: "object",
            properties: {
              id: {
                type: "number",
                description: "Snippet ID to share",
              },
              method: {
                type: "string",
                enum: ["default", "sms", "whatsapp", "clipboard"],
                description: "Sharing method to use",
                default: "default",
              },
              recipient: {
                type: "string",
                description:
                  "Phone number for SMS or WhatsApp (required for those methods)",
              },
            },
            required: ["id"],
          },
        },
        {
          name: "send_message",
          description:
            "Send a message to the phone that requires user attention or response",
          inputSchema: {
            type: "object",
            properties: {
              title: {
                type: "string",
                description: "Title of the message",
              },
              body: {
                type: "string",
                description: "Content of the message",
              },
              priority: {
                type: "string",
                enum: ["low", "normal", "high", "urgent"],
                description: "Priority level of the message",
                default: "normal",
              },
              responseOptions: {
                type: "array",
                description:
                  "Optional response options for the user to choose from",
                items: {
                  type: "string",
                },
              },
              requireResponse: {
                type: "boolean",
                description: "Whether a response is required from the user",
                default: false,
              },
              expiresIn: {
                type: "number",
                description:
                  "Optional expiration time in minutes (0 means no expiration)",
                default: 0,
              },
            },
            required: ["title", "body"],
          },
        },
        {
          name: "get_message_response",
          description: "Get the response to a previously sent message",
          inputSchema: {
            type: "object",
            properties: {
              messageId: {
                type: "string",
                description: "ID of the message to get the response for",
              },
            },
            required: ["messageId"],
          },
        },
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
        ...PHONE_COMMAND_SCHEMAS,
        ...GM_TOOL_SCHEMAS,
      ],
    }));

    // Handle tool calls
    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      const stripped = takeTokenArg(
        (request.params.arguments || {}) as Record<string, unknown>
      );
      const mcpAuth = this.authorizeMcpRead(request.params.name, stripped.token);
      if (isLocationReadTool(request.params.name)) {
        const args = stripped.rest;
        const outcome = runLocationRead({
          log: this.locationLog,
          limiter: commandRateLimiter,
          tool: request.params.name,
          args,
          activityLogged: false,
          includeCoords: mcpAuth.coords,
          callerId: mcpAuth.callerId,
        });
        if (outcome.status === 400) {
          throw new McpError(ErrorCode.InvalidParams, String(outcome.body.text));
        }
        if (outcome.status === 429) {
          throw new McpError(ErrorCode.InvalidRequest, String(outcome.body.text));
        }
        let text = String(outcome.body.text);
        if (
          this.notePhoneActivity({
            title: request.params.name,
            detail: "read box location history",
          })
        ) {
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

      if (isPresenceReadTool(request.params.name)) {
        const outcome = runPresenceRead({
          log: this.presenceLog,
          config: this.presenceConfig,
          limiter: commandRateLimiter,
          tool: request.params.name,
          activityLogged: false,
          includeCoords: mcpAuth.coords,
          callerId: mcpAuth.callerId,
        });
        if (outcome.status === 400) {
          throw new McpError(ErrorCode.InvalidParams, String(outcome.body.text));
        }
        if (outcome.status === 429) {
          throw new McpError(ErrorCode.InvalidRequest, String(outcome.body.text));
        }
        let text = String(outcome.body.text);
        if (
          this.notePhoneActivity({
            title: request.params.name,
            detail: "read box presence",
          })
        ) {
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

      if (isUsageReadTool(request.params.name)) {
        const args = stripped.rest;
        const outcome = runUsageRead({
          log: this.usageLog,
          limiter: commandRateLimiter,
          args,
          phoneConnected: this.isPhoneOpen(),
          activityLogged: false,
          callerId: mcpAuth.callerId,
        });
        if (outcome.status === 400) {
          throw new McpError(ErrorCode.InvalidParams, String(outcome.body.text));
        }
        if (outcome.status === 429) {
          throw new McpError(ErrorCode.InvalidRequest, String(outcome.body.text));
        }
        let text = String(outcome.body.text);
        if (
          this.notePhoneActivity({
            title: request.params.name,
            detail: "read box screen time",
          })
        ) {
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

      if (isHealthReadTool(request.params.name)) {
        const outcome = runHealthRead({
          log: this.healthLog,
          limiter: commandRateLimiter,
          phoneConnected: this.isPhoneOpen(),
          activityLogged: false,
          callerId: mcpAuth.callerId,
        });
        if (outcome.status === 429) {
          throw new McpError(ErrorCode.InvalidRequest, String(outcome.body.text));
        }
        let text = String(outcome.body.text);
        if (
          this.notePhoneActivity({
            title: request.params.name,
            detail: "read box health summary",
          })
        ) {
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

      if (isNotificationLogTool(request.params.name)) {
        const outcome = runNotificationLogRead({
          log: this.notificationLog,
          limiter: commandRateLimiter,
          args: stripped.rest,
          capture: () => this.notificationCaptureStatus(),
          wrapUntrusted: requireReadTokenAll(),
          callerId: mcpAuth.callerId,
        });
        if (outcome.status === 400) {
          throw new McpError(ErrorCode.InvalidParams, outcome.body.text);
        }
        if (outcome.status === 429) {
          throw new McpError(ErrorCode.InvalidRequest, outcome.body.text);
        }
        return { content: [{ type: "text", text: outcome.body.text }] };
      }

      if (isAssistantReadTool(request.params.name)) {
        const outcome = runAssistantRead({
          store: this.assistantStore,
          limiter: commandRateLimiter,
          tool: request.params.name,
          args: stripped.rest,
          activityLogged: false,
          callerId: mcpAuth.callerId,
        });
        if (outcome.status === 400) {
          throw new McpError(ErrorCode.InvalidParams, outcome.body.text);
        }
        if (outcome.status === 429) {
          throw new McpError(ErrorCode.InvalidRequest, outcome.body.text);
        }
        let text = outcome.body.text;
        if (
          this.notePhoneActivity({
            title: request.params.name,
            detail: "read box assistant events",
          })
        ) {
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

      if (request.params.name === "clear_assistant_events") {
        const validated = validatePhoneCommandArgs(
          request.params.name,
          stripped.rest
        );
        if (!validated.ok) {
          throw new McpError(ErrorCode.InvalidParams, validated.error);
        }
        const spec = getPhoneCommand(request.params.name);
        if (spec) {
          const budget = commandRateLimiter.allow(
            `${mcpAuth.callerId}:${spec.name}`,
            spec.maxPerMinute
          );
          if (!budget.ok) {
            throw new McpError(
              ErrorCode.InvalidRequest,
              `Rate limited: ${spec.name}. Try again in ${budget.retryAfterSec}s`
            );
          }
        }
        this.assistantStore.clear();
        let phoneCleared = false;
        if (this.isPhoneOpen()) {
          try {
            await this.callPhoneTool(
              request.params.name,
              validated.args,
              mcpAuth.callerId
            );
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

      if (isGmTool(request.params.name)) {
        try {
          const result = await gmessagesClient.callTool(
            request.params.name,
            stripped.rest
          );
          if (result.isError) {
            throw new McpError(ErrorCode.InternalError, result.text);
          }
          return {
            content: [{ type: "text", text: result.text }],
          };
        } catch (error: unknown) {
          throw new McpError(
            ErrorCode.InternalError,
            error instanceof Error ? error.message : "Google Messages tool failed"
          );
        }
      }

      const phone = this.session.currentSocket();
      if (!phone) {
        throw new McpError(
          ErrorCode.InvalidParams,
          "Phone not connected - please ensure the phone app is running and connected"
        );
      }

      if (phone.readyState !== WebSocket.OPEN) {
        throw new McpError(
          ErrorCode.InvalidParams,
          "Phone connection is not open"
        );
      }

      try {
        const response = await           this.callPhoneTool(
          request.params.name,
          stripped.rest,
          mcpAuth.callerId
        );

        // Check if this is a photo response with image data
        if (
          request.params.name === "take_photo" &&
          typeof response === "string"
        ) {
          try {
            const parsedResponse = JSON.parse(response);
            if (
              parsedResponse.imageData &&
              parsedResponse.imageData.startsWith("data:image/")
            ) {
              // Extract the base64 data and content type from the data URL
              const matches = parsedResponse.imageData.match(
                /^data:([^;]+);base64,(.+)$/
              );
              if (matches && matches.length === 3) {
                const contentType = matches[1]; // e.g., "image/jpeg"
                const base64Data = matches[2];

                // Return both the image and the text response with a portion of the image data for analysis
                // Include the first 10000 characters of the base64 data, which should be enough for basic analysis
                // without making the response too large
                const analysisData =
                  base64Data.length > 10000
                    ? base64Data.substring(0, 10000) + "..."
                    : base64Data;

                return {
                  content: [
                    {
                      type: "image",
                      mimeType: contentType,
                      data: base64Data,
                    },
                    {
                      type: "text",
                      text: JSON.stringify(
                        {
                          ...parsedResponse,
                          // Include a portion of the image data for analysis
                          imageData: `[${contentType} image data]`,
                          imageDataForAnalysis: `data:${contentType};base64,${analysisData}`,
                        },
                        null,
                        2
                      ),
                    },
                  ],
                };
              }
            }
          } catch (e) {
            console.error("Error parsing photo response:", e);
            // Fall back to default handling if parsing fails
          }
        }

        // Default handling for non-image responses
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
          `Failed to execute ${request.params.name}: ${
            error instanceof Error ? error.message : "Unknown error"
          }`
        );
      }
    });
  }

  private async cleanup() {
    console.error("Cleaning up server...");
    this.notificationPoller?.stop();
    stopGmessagesSidecar();

    // Clear ping interval if set
    if (this._pingIntervalId) {
      clearInterval(this._pingIntervalId);
      this._pingIntervalId = null;
    }

    // Close WebSocket server if it exists
    if (this.wss) {
      try {
        this.wss.close();
        console.error("WebSocket server closed");
      } catch (error) {
        console.error("Error closing WebSocket server:", error);
      }
    }

    if (this.httpApi) {
      try {
        const started = await this.httpApi;
        if (started) {
          await new Promise<void>((resolve) => started.server.close(() => resolve()));
          console.error("Local HTTP API closed");
        }
      } catch (error) {
        console.error("Error closing local HTTP API:", error);
      }
    }

    const phone = this.session.currentSocket();
    if (phone) {
      try {
        phone.close();
        console.error("Phone connection closed");
      } catch (error) {
        console.error("Error closing phone connection:", error);
      }
    }
  }

  async run() {
    // Register signal handlers for graceful shutdown
    process.on("SIGINT", async () => {
      console.error("Received SIGINT, shutting down...");
      await this.cleanup();
      process.exit(0);
    });

    process.on("SIGTERM", async () => {
      console.error("Received SIGTERM, shutting down...");
      await this.cleanup();
      process.exit(0);
    });

    await ensureGmessagesSidecar();

    // Start the server with the appropriate transport
    if (useStdio) {
      console.error("Using stdio transport");
      const transport = new StdioServerTransport();

      // Connect the server
      await this.server.connect(transport);
      console.error("Server ready with stdio transport");
    } else {
      console.error("Using WebSocket transport only");
    }

    console.error(`PhonePi MCP server running on port ${this.serverPort}...`);
    console.error(
      `Connect your phone to this server using port ${this.serverPort}`
    );
  }
}

// Create server instance with the specified port
const server = new PhonePiMCPServer(port, httpPort);

// Run the server
async function main() {
  try {
    await server.run();

    // If not using stdio, keep the process alive
    if (!useStdio) {
      console.error("Server running in standalone mode");
      // This prevents the process from exiting
      setInterval(() => {}, 1000 * 60 * 60); // Keep alive once per hour
    }
  } catch (error) {
    console.error("Error running server:", error);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error("Unhandled error in main:", error);
  process.exit(1);
});
