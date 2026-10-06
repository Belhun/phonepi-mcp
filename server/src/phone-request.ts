import { WebSocket } from "ws";

export interface PhoneSocket {
  readyState: number;
  send(data: string): void;
}

export interface PhoneRequestOptions {
  connection: PhoneSocket | null;
  pending: Map<string, (response: any) => void>;
  requestId: string;
  tool: string;
  params: Record<string, unknown>;
  debug?: boolean;
  timeoutMs?: number;
}

/**
 * Send one tool request on the existing phone socket and wait for its response.
 * MCP stdio and the loopback HTTP API both use this so a second WebSocket is
 * never opened.
 */
export function requestPhoneTool(options: PhoneRequestOptions): Promise<unknown> {
  const {
    connection,
    pending,
    requestId,
    tool,
    params,
    debug = false,
    timeoutMs = 30000,
  } = options;

  const message = {
    type: "request",
    requestId,
    tool,
    params: params || {},
  };

  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      pending.delete(requestId);
      reject(new Error("Request timed out after 30 seconds"));
    }, timeoutMs);

    pending.set(requestId, (response) => {
      clearTimeout(timeout);
      if (debug) {
        console.error(
          `[WebSocket] Received response for request ${requestId}: ${JSON.stringify(
            response
          )}`
        );
      }
      resolve(response);
    });

    if (connection && connection.readyState === WebSocket.OPEN) {
      try {
        const messageStr = JSON.stringify(message);
        if (debug) {
          console.error(`[WebSocket] Sending request to phone: ${messageStr}`);
        }
        connection.send(messageStr);
      } catch (err) {
        clearTimeout(timeout);
        pending.delete(requestId);
        console.error(
          `[WebSocket] Failed to send message: ${
            err instanceof Error ? err.message : String(err)
          }`
        );
        reject(
          new Error(
            `Failed to send message: ${
              err instanceof Error ? err.message : String(err)
            }`
          )
        );
      }
    } else {
      clearTimeout(timeout);
      pending.delete(requestId);
      console.error(
        `[WebSocket] Cannot send message: Phone not connected (readyState: ${connection?.readyState})`
      );
      reject(new Error("Phone not connected or connection not ready"));
    }
  });
}
