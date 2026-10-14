import { createHash } from "crypto";
import { promises as fs } from "fs";
import os from "os";
import path from "path";

export const DEFAULT_UPDATE_DIR = path.join(os.homedir(), ".phonepi", "updates");

export interface UpdateManifest {
  versionCode: number;
  versionName: string;
  sha256: string;
  size: number;
  fileName: string;
}

export function updateDirFromEnv(
  env: NodeJS.ProcessEnv = process.env
): string {
  const raw = env.PHONEPI_UPDATE_DIR?.trim();
  if (raw) return path.resolve(raw);
  return DEFAULT_UPDATE_DIR;
}

/** `phonepi-0.2.5-7.apk` or `anything-1.0.0-12.apk` */
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
  dir: string
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
  const sidecar = await readSidecar(dir, entries);
  const fromName = parseVersionFromFileName(apkName);
  const versionCode = sidecar?.versionCode ?? fromName?.versionCode;
  const versionName = sidecar?.versionName ?? fromName?.versionName;
  if (versionCode == null || !versionName) {
    return {
      error:
        `APK ${apkName} needs versionCode and versionName ` +
        `(filename like phonepi-0.2.5-7.apk, or ${path.join(dir, "manifest.json")})`,
    };
  }
  return {
    apkPath,
    manifest: {
      versionCode,
      versionName,
      sha256: sha256Hex(data),
      size: data.length,
      fileName: apkName,
    },
  };
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
): Promise<{ versionCode: number; versionName: string } | null> {
  if (!entries.includes("manifest.json")) return null;
  try {
    const raw = JSON.parse(
      await fs.readFile(path.join(dir, "manifest.json"), "utf8")
    ) as { versionCode?: unknown; versionName?: unknown };
    const versionCode = Number(raw.versionCode);
    const versionName =
      typeof raw.versionName === "string" ? raw.versionName.trim() : "";
    if (!Number.isInteger(versionCode) || versionCode < 1 || !versionName) {
      return null;
    }
    return { versionCode, versionName };
  } catch {
    return null;
  }
}
