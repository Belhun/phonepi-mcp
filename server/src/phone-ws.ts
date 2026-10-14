import { IncomingMessage, ServerResponse, createServer } from "http";
import { WebSocketServer } from "ws";
import { handleUpdateRequest } from "./update-http.js";

export function createPhoneWebSocketServer(options: {
  port: number;
  host: string;
  verifyClient: (info: { req: IncomingMessage }) => boolean;
}): WebSocketServer {
  const httpServer = createServer((req: IncomingMessage, res: ServerResponse) => {
    void handleUpdateRequest(req, res).then((handled) => {
      if (handled || res.headersSent) return;
      res.writeHead(404, {
        "Content-Type": "text/plain; charset=utf-8",
        "Cache-Control": "no-store",
      });
      res.end("Not found");
    }).catch((err) => {
      if (res.headersSent) return;
      res.writeHead(500, { "Content-Type": "application/json; charset=utf-8" });
      res.end(
        JSON.stringify({
          error: err instanceof Error ? err.message : "Update handler failed",
        })
      );
    });
  });

  const wss = new WebSocketServer({
    server: httpServer,
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

  httpServer.on("error", (err) => {
    wss.emit("error", err);
  });

  const originalClose = wss.close.bind(wss);
  wss.close = (cb?: (err?: Error) => void) => {
    originalClose((err) => {
      httpServer.close(() => cb?.(err));
    });
  };

  httpServer.listen(options.port, options.host);
  return wss;
}
