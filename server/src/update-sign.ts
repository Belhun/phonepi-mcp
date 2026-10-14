import { createPublicKey, generateKeyPairSync, sign, verify } from "crypto";
import { promises as fs } from "fs";
import os from "os";
import path from "path";

export const DEFAULT_RELEASE_DIR = path.join(os.homedir(), ".phonepi", "release");
export const DEFAULT_PRIV_PATH = path.join(DEFAULT_RELEASE_DIR, "manifest-ed25519.pem");
export const DEFAULT_PUB_PATH = path.join(DEFAULT_RELEASE_DIR, "manifest-ed25519.pub");

export interface SignedManifestFields {
  versionCode: number;
  versionName: string;
  sha256: string;
  minVersion: number;
}

/** Bytes that Ed25519 signs. Keep in lockstep with UpdateCrypto.signedPayload on Android. */
export function canonicalUpdatePayload(fields: SignedManifestFields): Buffer {
  const sha = fields.sha256.trim().toLowerCase();
  const name = fields.versionName.trim();
  return Buffer.from(
    `v1\n${fields.versionCode}\n${name}\n${sha}\n${fields.minVersion}\n`,
    "utf8"
  );
}

export function rawPublicKeyHex(publicKeyPem: string): string {
  const key = createPublicKey(publicKeyPem);
  const der = key.export({ type: "spki", format: "der" });
  return Buffer.from(der.subarray(der.length - 32)).toString("hex");
}

export function signManifest(
  fields: SignedManifestFields,
  privateKeyPem: string
): string {
  const sig = sign(null, canonicalUpdatePayload(fields), privateKeyPem);
  return sig.toString("hex");
}

export function verifyManifestSignature(
  fields: SignedManifestFields,
  signatureHex: string,
  publicKeyHex: string
): boolean {
  const pub = publicKeyHex.trim().toLowerCase();
  const sig = signatureHex.trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(pub) || !/^[0-9a-f]{128}$/.test(sig)) {
    return false;
  }
  const spki = Buffer.concat([
    Buffer.from("302a300506032b6570032100", "hex"),
    Buffer.from(pub, "hex"),
  ]);
  const key = createPublicKey({ key: spki, format: "der", type: "spki" });
  try {
    return verify(null, canonicalUpdatePayload(fields), key, Buffer.from(sig, "hex"));
  } catch {
    return false;
  }
}

export async function readPrivateKeyPem(
  filePath: string = DEFAULT_PRIV_PATH
): Promise<string> {
  return fs.readFile(filePath, "utf8");
}

export async function readPublicKeyHex(
  filePath: string = DEFAULT_PUB_PATH
): Promise<string> {
  return (await fs.readFile(filePath, "utf8")).trim().toLowerCase();
}

export function generateManifestKeyPair(): {
  privateKeyPem: string;
  publicKeyHex: string;
} {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  return {
    privateKeyPem: privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
    publicKeyHex: rawPublicKeyHex(
      publicKey.export({ type: "spki", format: "pem" }).toString()
    ),
  };
}
