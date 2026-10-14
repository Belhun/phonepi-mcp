import { IncomingMessage, ServerResponse } from "http";
import { createReadStream } from "fs";
import {
  tokenFromHttpRequest,
  tokenMatches,
  wsTokenFromEnv,
} from "./ws-token.js";
import { loadUpdateBundle, updateDirFromEnv } from "./update-store.js";

const MANIFEST_PATH = "/phonepi/update/manifest";
const APK_PATH = "/phonepi/update/apk";

export function isUpdatePath(pathname: string): boolean {
  return pathname === MANIFEST_PATH || pathname === APK_PATH;
}

export async function handleUpdateRequest(
  req: IncomingMessage,
  res: ServerResponse,
  options: { env?: NodeJS.ProcessEnv } = {}
): Promise<boolean> {
  let pathname: string;
  try {
    pathname = new URL(req.url || "/", "http://127.0.0.1").pathname;
  } catch {
    return false;
  }
  if (!isUpdatePath(pathname)) return false;

  if (req.method !== "GET" && req.method !== "HEAD") {
    sendJson(res, 405, { error: "Method not allowed" });
    return true;
  }

  const env = options.env ?? process.env;
  const expected = wsTokenFromEnv(env);
  const provided = tokenFromHttpRequest(req);
  if (expected == null) {
    sendJson(res, 401, { error: "PHONEPI_WS_TOKEN is required for updates" });
    return true;
  }
  if (provided == null || provided === "") {
    sendJson(res, 401, { error: "Missing update token" });
    return true;
  }
  if (!tokenMatches(expected, provided)) {
    sendJson(res, 401, { error: "Invalid update token" });
    return true;
  }

  const dir = updateDirFromEnv(env);
  const loaded = await loadUpdateBundle(dir, env);
  if ("error" in loaded) {
    sendJson(res, 404, { error: loaded.error });
    return true;
  }

  if (pathname === MANIFEST_PATH) {
    if (req.method === "HEAD") {
      res.writeHead(200, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
      });
      res.end();
      return true;
    }
    sendJson(res, 200, { ...loaded.manifest });
    return true;
  }

  res.writeHead(200, {
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": loaded.manifest.size,
    "Cache-Control": "no-store",
    "X-PhonePi-Version-Code": String(loaded.manifest.versionCode),
    "X-PhonePi-Version-Name": loaded.manifest.versionName,
    "X-PhonePi-Sha256": loaded.manifest.sha256,
    "Content-Disposition": `attachment; filename="${loaded.manifest.fileName}"`,
  });
  if (req.method === "HEAD") {
    res.end();
    return true;
  }
  createReadStream(loaded.apkPath).pipe(res);
  return true;
}

function sendJson(
  res: ServerResponse,
  status: number,
  body: Record<string, unknown>
): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
    "Cache-Control": "no-store",
  });
  res.end(payload);
}
