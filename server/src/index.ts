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
import {
  PHONE_COMMAND_SCHEMAS,
  RateLimitError,
  getPhoneCommand,
  isPhoneCommand,
} from "./commands.js";
import {
  NOTIFICATION_TOOL_SCHEMAS,
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
        callPhoneTool: (name, args) => this.callPhoneTool(name, args),
        healthDetails: () => this.session.healthDetails(),
      },
    });
  }

  private isPhoneOpen(): boolean {
    return this.session.isConnected();
  }

  private callPhoneTool(
    name: string,
    args: Record<string, unknown>
  ): Promise<unknown> {
    if (isPhoneCommand(name)) {
      const spec = getPhoneCommand(name);
      if (spec) {
        const budget = commandRateLimiter.allow(spec.name, spec.maxPerMinute);
        if (!budget.ok) {
          return Promise.reject(
            new RateLimitError(spec.name, budget.retryAfterSec)
          );
        }
      }
    } else {
      const maxPerMinute = readOnlyToolRateLimit(name);
      if (maxPerMinute != null) {
        const budget = commandRateLimiter.allow(name, maxPerMinute);
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
      params: args,
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
      if(DEBUG_WEBSOCKET) {
        console.error(`[WebSocket] Parsed message: ${JSON.stringify(message)}`);
      }
      // Process different message types
      if (message.type === "pong") {
        if (!this.session.notePong(ws)) {
          console.error("[WebSocket] Ignored pong from a stale socket");
        }
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
        //   name: 'set_alarm',
        //   description: 'Set an alarm for a specific time',
        //   inputSchema: {
        //     type: 'object',
        //     properties: {
        //       time: {
        //         type: 'string',
        //         description: 'Time to set the alarm for (ISO 8601 format)',
        //       },
        //     },
        //     required: ['time'],
        //   },
        // },
        {
          name: "set_timer",
          description: "Set a timer for a specific duration",
          inputSchema: {
            type: "object",
            properties: {
              seconds: {
                type: "number",
                description: "Duration of the timer in seconds",
                minimum: 1,
              },
            },
            required: ["seconds"],
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
        {
          name: "send_notification",
          description: "Send a notification to the phone",
          inputSchema: {
            type: "object",
            properties: {
              title: {
                type: "string",
                description: "Title of the notification",
              },
              body: {
                type: "string",
                description: "Content of the notification",
              },
              priority: {
                type: "string",
                enum: ["default", "high"],
                description: "Priority level of the notification",
                default: "default",
              },
              data: {
                type: "object",
                description: "Additional data to include with the notification",
              },
            },
            required: ["title", "body"],
          },
        },
        ...NOTIFICATION_TOOL_SCHEMAS,
        ...VOLUME_TOOL_SCHEMAS,
        ...PHONE_COMMAND_SCHEMAS,
        ...GM_TOOL_SCHEMAS,
      ],
    }));

    // Handle tool calls
    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      if (isGmTool(request.params.name)) {
        try {
          const result = await gmessagesClient.callTool(
            request.params.name,
            (request.params.arguments || {}) as Record<string, unknown>
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
        const response = await this.callPhoneTool(
          request.params.name,
          (request.params.arguments || {}) as Record<string, unknown>
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
