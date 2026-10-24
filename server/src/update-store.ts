import { createHash } from "crypto";
import { promises as fs } from "fs";
import os from "os";
import path from "path";
import {
  DEFAULT_PUB_PATH,
  SignedManifestFields,
  verifyManifestSignature,
} from "./update-sign.js";

export const DEFAULT_UPDATE_DIR = path.join(os.homedir(), ".phonepi", "updates");

export interface UpdateManifest extends SignedManifestFields {
  size: number;
  fileName: string;
  signature: string;
}

export function updateDirFromEnv(
  env: NodeJS.ProcessEnv = process.env
): string {
  const raw = env.PHONEPI_UPDATE_DIR?.trim();
  if (raw) return path.resolve(raw);
  return DEFAULT_UPDATE_DIR;
}

/** `phonepi-0.2.6-8.apk` or `anything-1.0.0-12.apk` */
export function parseVersionFromFileName(
  fileName: string
): { versionName: string; versionCode: number } | null {
  const match = fileName.match(/-(\d+\.\d+\.\d+(?:[-.][\w.]+)?)-(\d+)\.apk$/i);
  if (!match) return null;
  return { versionName: match[1], versionCode: Number(match[2]) };
}

export function sha256Hex(data: Buffer): string {
  return createHash("sha256").update(data).digest("hex");
}

export async function loadUpdateBundle(
  dir: string,
  env: NodeJS.ProcessEnv = process.env
): Promise<{ apkPath: string; manifest: UpdateManifest } | { error: string }> {
  let entries: string[];
  try {
    entries = await fs.readdir(dir);
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code === "ENOENT") {
      return { error: `No update directory at ${dir}` };
    }
    return { error: `Cannot read update directory: ${(err as Error).message}` };
  }

  const apkName = await pickApkFile(dir, entries);
  if (!apkName) {
    return { error: `No APK in ${dir}` };
  }
  const apkPath = path.join(dir, apkName);
  const data = await fs.readFile(apkPath);
  const fileSha = sha256Hex(data);
  const sidecar = await readSidecar(dir, entries);
  if (!sidecar) {
    return {
      error:
        `Signed manifest.json is required in ${dir} ` +
        `(run scripts/sign-update-manifest.sh)`,
    };
  }
  if (sidecar.sha256 !== fileSha) {
    return {
      error: `manifest sha256 does not match ${apkName}`,
    };
  }
  const pub = await resolvePublicKeyHex(env);
  if (pub) {
    if (!verifyManifestSignature(sidecar, sidecar.signature, pub)) {
      return { error: "manifest Ed25519 signature is invalid" };
    }
  } else if (!/^[0-9a-f]{128}$/.test(sidecar.signature)) {
    return { error: "manifest missing Ed25519 signature" };
  }
  return {
    apkPath,
    manifest: {
      ...sidecar,
      size: data.length,
      fileName: apkName,
    },
  };
}

async function resolvePublicKeyHex(
  env: NodeJS.ProcessEnv
): Promise<string | null> {
  const inline = env.PHONEPI_UPDATE_PUBKEY?.trim().toLowerCase();
  if (inline && /^[0-9a-f]{64}$/.test(inline)) return inline;
  const filePath = env.PHONEPI_UPDATE_PUBKEY_FILE?.trim() || DEFAULT_PUB_PATH;
  try {
    const hex = (await fs.readFile(filePath, "utf8")).trim().toLowerCase();
    return /^[0-9a-f]{64}$/.test(hex) ? hex : null;
  } catch {
    return null;
  }
}

async function pickApkFile(dir: string, entries: string[]): Promise<string | null> {
  const apks = entries.filter((name) => name.toLowerCase().endsWith(".apk"));
  if (apks.includes("phonepi.apk")) return "phonepi.apk";
  if (apks.length === 0) return null;
  const stats = await Promise.all(
    apks.map(async (name) => {
      const st = await fs.stat(path.join(dir, name));
      return { name, mtime: st.mtimeMs };
    })
  );
  stats.sort((a, b) => b.mtime - a.mtime);
  return stats[0]?.name ?? null;
}

async function readSidecar(
  dir: string,
  entries: string[]
): Promise<(SignedManifestFields & { signature: string }) | null> {
  if (!entries.includes("manifest.json")) return null;
  try {
    const raw = JSON.parse(
      await fs.readFile(path.join(dir, "manifest.json"), "utf8")
    ) as {
      versionCode?: unknown;
      versionName?: unknown;
      sha256?: unknown;
      minVersion?: unknown;
      signature?: unknown;
    };
    const versionCode = Number(raw.versionCode);
    const minVersion = Number(raw.minVersion);
    const versionName =
      typeof raw.versionName === "string" ? raw.versionName.trim() : "";
    const sha256 =
      typeof raw.sha256 === "string" ? raw.sha256.trim().toLowerCase() : "";
    const signature =
      typeof raw.signature === "string" ? raw.signature.trim().toLowerCase() : "";
    if (!Number.isInteger(versionCode) || versionCode < 1 || !versionName) {
      return null;
    }
    if (!Number.isInteger(minVersion) || minVersion < 1) {
      return null;
    }
    if (!/^[0-9a-f]{64}$/.test(sha256) || !/^[0-9a-f]{128}$/.test(signature)) {
      return null;
    }
    return { versionCode, versionName, sha256, minVersion, signature };
  } catch {
    return null;
  }
}
