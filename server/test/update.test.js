import assert from "node:assert/strict";
import { createHash } from "crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { WebSocket } from "ws";
import { createPhoneWebSocketServer } from "../dist/phone-ws.js";
import { createPhoneVerifyClient } from "../dist/ws-token.js";
import {
  DEFAULT_UPDATE_DIR,
  loadUpdateBundle,
  parseVersionFromFileName,
  sha256Hex,
  updateDirFromEnv,
} from "../dist/update-store.js";
import { isPhoneCommand, PHONE_COMMAND_NAMES } from "../dist/commands.js";

function get(port, urlPath, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: "127.0.0.1", port, path: urlPath, method: "GET", headers },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const buf = Buffer.concat(chunks);
          resolve({
            status: res.statusCode,
            headers: res.headers,
            buf,
            text: buf.toString("utf8"),
          });
        });
      }
    );
    req.on("error", reject);
    req.end();
  });
}

test("filename encodes versionName and versionCode", () => {
  assert.deepEqual(parseVersionFromFileName("phonepi-0.2.4-6.apk"), {
    versionName: "0.2.4",
    versionCode: 6,
  });
  assert.equal(parseVersionFromFileName("phonepi.apk"), null);
});

test("update dir defaults to ~/.phonepi/updates", () => {
  assert.equal(updateDirFromEnv({}), DEFAULT_UPDATE_DIR);
  assert.equal(
    updateDirFromEnv({ PHONEPI_UPDATE_DIR: "/tmp/phonepi-apks" }),
    path.resolve("/tmp/phonepi-apks")
  );
});

test("loadUpdateBundle hashes the apk and prefers sidecar versions", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-upd-"));
  const bytes = Buffer.from("fake-apk-bytes");
  fs.writeFileSync(path.join(dir, "phonepi.apk"), bytes);
  fs.writeFileSync(
    path.join(dir, "manifest.json"),
    JSON.stringify({ versionCode: 6, versionName: "0.2.4" })
  );
  const loaded = await loadUpdateBundle(dir);
  assert.equal("error" in loaded, false);
  assert.equal(loaded.manifest.versionCode, 6);
  assert.equal(loaded.manifest.versionName, "0.2.4");
  assert.equal(loaded.manifest.size, bytes.length);
  assert.equal(loaded.manifest.sha256, sha256Hex(bytes));
  assert.equal(loaded.manifest.sha256, createHash("sha256").update(bytes).digest("hex"));
});

test("GET /phonepi/update requires the WS token and serves manifest plus apk", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-upd-"));
  const bytes = Buffer.from("pkg-bytes-for-http");
  fs.writeFileSync(path.join(dir, "phonepi-0.2.4-6.apk"), bytes);

  const previousToken = process.env.PHONEPI_WS_TOKEN;
  const previousDir = process.env.PHONEPI_UPDATE_DIR;
  process.env.PHONEPI_WS_TOKEN = "upd-secret";
  process.env.PHONEPI_UPDATE_DIR = dir;

  const wss = createPhoneWebSocketServer({
    port: 0,
    host: "127.0.0.1",
    verifyClient: createPhoneVerifyClient(() => {}),
  });
  await new Promise((resolve) => wss.once("listening", resolve));
  const port = wss.address().port;

  try {
    const denied = await get(port, "/phonepi/update/manifest");
    assert.equal(denied.status, 401);

    const wrong = await get(port, "/phonepi/update/manifest?token=nope");
    assert.equal(wrong.status, 401);

    const manifest = await get(port, "/phonepi/update/manifest?token=upd-secret");
    assert.equal(manifest.status, 200);
    const json = JSON.parse(manifest.text);
    assert.equal(json.versionCode, 6);
    assert.equal(json.versionName, "0.2.4");
    assert.equal(json.size, bytes.length);
    assert.equal(json.sha256, sha256Hex(bytes));

    const apk = await get(port, "/phonepi/update/apk", {
      Authorization: "Bearer upd-secret",
    });
    assert.equal(apk.status, 200);
    assert.equal(apk.buf.equals(bytes), true);
    assert.match(
      apk.headers["content-type"],
      /android\.package-archive/
    );

    const ws = new WebSocket(`ws://127.0.0.1:${port}/phonepi?token=upd-secret`);
    await new Promise((resolve, reject) => {
      ws.on("open", resolve);
      ws.on("error", reject);
    });
    ws.close();
  } finally {
    await new Promise((resolve) => wss.close(resolve));
    if (previousToken === undefined) delete process.env.PHONEPI_WS_TOKEN;
    else process.env.PHONEPI_WS_TOKEN = previousToken;
    if (previousDir === undefined) delete process.env.PHONEPI_UPDATE_DIR;
    else process.env.PHONEPI_UPDATE_DIR = previousDir;
  }
});

test("phone_push_update is an allowlisted write command", () => {
  assert.equal(isPhoneCommand("phone_push_update"), true);
  assert.equal(PHONE_COMMAND_NAMES.includes("phone_push_update"), true);
});
