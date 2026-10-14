import { promises as fs } from "fs";
import path from "path";
import {
  DEFAULT_PRIV_PATH,
  signManifest,
} from "./update-sign.js";
import {
  parseVersionFromFileName,
  sha256Hex,
  updateDirFromEnv,
} from "./update-store.js";

function argValue(flag: string): string | undefined {
  const index = process.argv.indexOf(flag);
  if (index < 0 || index + 1 >= process.argv.length) return undefined;
  return process.argv[index + 1];
}

function printHelp(): void {
  console.log(`Sign an update manifest with the Ed25519 key kept outside the repo.

Usage:
  node dist/sign-update-cli.js [--dir DIR] [--apk FILE] [--min-version N] [--key PEM]

Writes DIR/manifest.json. Private key default: ~/.phonepi/release/manifest-ed25519.pem
`);
}

async function main(): Promise<void> {
  if (process.argv.includes("--help") || process.argv.includes("-h")) {
    printHelp();
    return;
  }
  const dir = path.resolve(argValue("--dir") ?? updateDirFromEnv());
  const apkArg = argValue("--apk");
  const minVersion = Number(argValue("--min-version") ?? "1");
  const keyPath = argValue("--key") ?? process.env.PHONEPI_UPDATE_PRIVKEY_FILE ?? DEFAULT_PRIV_PATH;
  let entries: string[];
  try {
    entries = await fs.readdir(dir);
  } catch {
    throw new Error(`No update directory at ${dir}`);
  }
  const apkName =
    apkArg ??
    (entries.includes("phonepi.apk")
      ? "phonepi.apk"
      : entries.filter((n) => n.toLowerCase().endsWith(".apk")).sort((a, b) => b.localeCompare(a))[0]);
  if (!apkName) {
    throw new Error(`No APK in ${dir}`);
  }
  const apkPath = path.isAbsolute(apkName) ? apkName : path.join(dir, apkName);
  const bytes = await fs.readFile(apkPath);
  const fromName = parseVersionFromFileName(path.basename(apkPath));
  const versionCode = Number(argValue("--version-code") ?? fromName?.versionCode);
  const versionName = argValue("--version-name") ?? fromName?.versionName;
  if (!Number.isInteger(versionCode) || versionCode < 1 || !versionName) {
    throw new Error(
      "Need versionCode and versionName (filename phonepi-0.2.6-8.apk, or --version-code / --version-name)"
    );
  }
  if (!Number.isInteger(minVersion) || minVersion < 1) {
    throw new Error("--min-version must be an integer >= 1");
  }
  const privateKeyPem = await fs.readFile(keyPath, "utf8");
  const sha256 = sha256Hex(bytes);
  const fields = { versionCode, versionName, sha256, minVersion };
  const signature = signManifest(fields, privateKeyPem);
  const manifest = {
    ...fields,
    size: bytes.length,
    fileName: path.basename(apkPath),
    signature,
  };
  const out = path.join(dir, "manifest.json");
  await fs.writeFile(out, JSON.stringify(manifest, null, 2) + "\n", { mode: 0o644 });
  console.log(`Wrote ${out}`);
  console.log(`version ${versionName} (${versionCode}) sha256 ${sha256}`);
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
