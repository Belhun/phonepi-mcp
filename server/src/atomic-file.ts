/**
 * Atomic box-file writes. Temp file, fsync, rename, always chmod.
 * Refuse symlink targets. PHONEPI_*_DIR overrides must stay under ~/.phonepi
 * (tests may also use os.tmpdir()).
 */

import fs from "fs";
import os from "os";
import path from "path";

export const BOX_DIR_MODE = 0o700;
export const BOX_FILE_MODE = 0o600;

export function phonepiHome(env: NodeJS.ProcessEnv = process.env): string {
  return path.join(os.homedir(), ".phonepi");
}

export function resolveBoxDir(
  envKey: string,
  subdir: string,
  env: NodeJS.ProcessEnv = process.env
): string {
  const fallback = path.join(phonepiHome(env), subdir);
  const raw = env[envKey]?.trim();
  if (!raw) return fallback;
  const resolved = path.resolve(raw);
  if (!isAllowedBoxPath(resolved, env)) return fallback;
  return resolved;
}

export function isAllowedBoxPath(
  resolved: string,
  env: NodeJS.ProcessEnv = process.env
): boolean {
  return (
    isInside(resolved, phonepiHome(env)) || isInside(resolved, os.tmpdir())
  );
}

export function ensureBoxDir(dir: string): void {
  assertNotSymlink(dir, { mustExist: false });
  fs.mkdirSync(dir, { recursive: true, mode: BOX_DIR_MODE });
  assertNotSymlink(dir, { mustExist: true });
  fs.chmodSync(dir, BOX_DIR_MODE);
}

/**
 * Write [contents] to [file] via a sibling temp, fsync, rename, chmod 600.
 * Refuses to follow a symlink at [file] or its parent.
 */
export function atomicWriteFile(file: string, contents: string | Buffer): void {
  const dir = path.dirname(file);
  ensureBoxDir(dir);
  assertNotSymlink(file, { mustExist: false });
  const tmp = path.join(
    dir,
    `.${path.basename(file)}.${process.pid}.${Date.now()}.tmp`
  );
  const fd = fs.openSync(tmp, "w", BOX_FILE_MODE);
  try {
    fs.writeFileSync(fd, contents);
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.chmodSync(tmp, BOX_FILE_MODE);
  fs.renameSync(tmp, file);
  fs.chmodSync(file, BOX_FILE_MODE);
}

export function atomicAppendLine(file: string, line: string): void {
  const existing = safeReadFile(file);
  const next = existing + (existing.endsWith("\n") || existing === "" ? "" : "\n") +
    (line.endsWith("\n") ? line : `${line}\n`);
  atomicWriteFile(file, next);
}

export function safeReadFile(file: string): string {
  if (!fs.existsSync(file)) return "";
  assertNotSymlink(file, { mustExist: true });
  return fs.readFileSync(file, "utf8");
}

export function removeFile(file: string): void {
  if (!fs.existsSync(file)) return;
  assertNotSymlink(file, { mustExist: true });
  fs.rmSync(file, { force: true });
}

function isInside(resolved: string, root: string): boolean {
  const rel = path.relative(path.resolve(root), resolved);
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel));
}

function assertNotSymlink(
  target: string,
  options: { mustExist: boolean }
): void {
  let stat: fs.Stats;
  try {
    stat = fs.lstatSync(target);
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code === "ENOENT" && !options.mustExist) return;
    throw err;
  }
  if (stat.isSymbolicLink()) {
    throw new Error("refusing to use a symlink as a box file");
  }
}
