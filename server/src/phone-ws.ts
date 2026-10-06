import { IncomingMessage } from "http";
import { WebSocketServer } from "ws";

export function createPhoneWebSocketServer(options: {
  port: number;
  host: string;
  verifyClient: (info: { req: IncomingMessage }) => boolean;
}): WebSocketServer {
  return new WebSocketServer({
    port: options.port,
    host: options.host,
    // Leave the path unrestricted. The Android app upgrades `/phonepi`
    // (with an optional `?token=`). `tailscale serve` to
    // http://127.0.0.1:<wsPort> forwards that path and the WebSocket
    // upgrade through unchanged. Filtering on `/phonepi` would also reject
    // older clients that connect at `/`.
    verifyClient: options.verifyClient,
    clientTracking: true,
    perMessageDeflate: {
      zlibDeflateOptions: {
        level: 6,
      },
      zlibInflateOptions: {
        chunkSize: 10 * 1024,
      },
      threshold: 1024,
    },
  });
}
