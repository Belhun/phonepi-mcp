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
import {
  generateManifestKeyPair,
  signManifest,
  verifyManifestSignature,
} from "../dist/update-sign.js";
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

function writeSignedBundle(dir, bytes, fields = {}) {
  const { privateKeyPem, publicKeyHex } = generateManifestKeyPair();
  const sha256 = sha256Hex(bytes);
  const signed = {
    versionCode: fields.versionCode ?? 8,
    versionName: fields.versionName ?? "0.2.6",
    sha256,
    minVersion: fields.minVersion ?? 1,
  };
  const signature = signManifest(signed, privateKeyPem);
  fs.writeFileSync(path.join(dir, fields.apkName ?? "phonepi.apk"), bytes);
  fs.writeFileSync(
    path.join(dir, "manifest.json"),
    JSON.stringify({ ...signed, size: bytes.length, signature })
  );
  return { publicKeyHex, signature, sha256 };
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

test("Ed25519 golden vector matches the Android verifier", () => {
  const fields = {
    versionCode: 8,
    versionName: "0.2.6",
    sha256: "ab".repeat(32),
    minVersion: 1,
  };
  const pub = "24101ab91ac7edd8e32b53680314a82b9a85ff966156f7593355a68a2e541393";
  const sig =
    "b27c6bef61223aa65a4bc9a762c896ea8a74baf5a9c9932a998e401167a28b5c" +
    "37a1234acb18cf8253c781a3346d020d228cbab301048f41e31d65f57b7bea0a";
  assert.equal(verifyManifestSignature(fields, sig, pub), true);
  assert.equal(
    verifyManifestSignature({ ...fields, versionCode: 9 }, sig, pub),
    false
  );
});

test("loadUpdateBundle requires a signature and verifies it when a pubkey is set", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-upd-"));
  const bytes = Buffer.from("fake-apk-bytes");
  const { publicKeyHex } = writeSignedBundle(dir, bytes, {
    versionCode: 8,
    versionName: "0.2.6",
  });
  const loaded = await loadUpdateBundle(dir, { PHONEPI_UPDATE_PUBKEY: publicKeyHex });
  assert.equal("error" in loaded, false);
  assert.equal(loaded.manifest.versionCode, 8);
  assert.equal(loaded.manifest.versionName, "0.2.6");
  assert.equal(loaded.manifest.minVersion, 1);
  assert.equal(loaded.manifest.size, bytes.length);
  assert.equal(loaded.manifest.sha256, sha256Hex(bytes));
  assert.equal(loaded.manifest.sha256, createHash("sha256").update(bytes).digest("hex"));
  assert.equal(loaded.manifest.signature.length, 128);
});

test("unsigned sidecar is rejected", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-upd-"));
  fs.writeFileSync(path.join(dir, "phonepi.apk"), Buffer.from("x"));
  fs.writeFileSync(
    path.join(dir, "manifest.json"),
    JSON.stringify({ versionCode: 8, versionName: "0.2.6" })
  );
  const loaded = await loadUpdateBundle(dir, { PHONEPI_UPDATE_PUBKEY: "aa".repeat(32) });
  assert.equal("error" in loaded, true);
  assert.match(loaded.error, /[Ss]ign/);
});

test("GET /phonepi/update requires the WS token and serves a signed manifest plus apk", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-upd-"));
  const bytes = Buffer.from("pkg-bytes-for-http");
  const { publicKeyHex } = writeSignedBundle(dir, bytes, {
    apkName: "phonepi-0.2.6-8.apk",
    versionCode: 8,
    versionName: "0.2.6",
  });

  const previousToken = process.env.PHONEPI_WS_TOKEN;
  const previousDir = process.env.PHONEPI_UPDATE_DIR;
  const previousPub = process.env.PHONEPI_UPDATE_PUBKEY;
  process.env.PHONEPI_WS_TOKEN = "upd-secret";
  process.env.PHONEPI_UPDATE_DIR = dir;
  process.env.PHONEPI_UPDATE_PUBKEY = publicKeyHex;

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
    assert.equal(json.versionCode, 8);
    assert.equal(json.versionName, "0.2.6");
    assert.equal(json.minVersion, 1);
    assert.equal(json.size, bytes.length);
    assert.equal(json.sha256, sha256Hex(bytes));
    assert.equal(json.signature.length, 128);

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
    if (previousPub === undefined) delete process.env.PHONEPI_UPDATE_PUBKEY;
    else process.env.PHONEPI_UPDATE_PUBKEY = previousPub;
  }
});

test("phone_push_update is an allowlisted write command", () => {
  assert.equal(isPhoneCommand("phone_push_update"), true);
  assert.equal(PHONE_COMMAND_NAMES.includes("phone_push_update"), true);
});
