/**
 * Sliding-window ingest cap shared by presence, usage, health, and assistant
 * WebSocket frames. Keyed by stream name so one noisy source cannot flood
 * the others.
 */

import { CommandRateLimiter } from "./command-rate-limit.js";

export const INGEST_PER_MINUTE = {
  presence: 30,
  usage: 12,
  health: 12,
  assistant: 30,
  location: 30,
} as const;

export const ingestRateLimiter = new CommandRateLimiter();

export function allowIngest(
  stream: keyof typeof INGEST_PER_MINUTE,
  limiter: CommandRateLimiter = ingestRateLimiter
): { ok: true } | { ok: false; retryAfterSec: number } {
  return limiter.allow(`ingest:${stream}`, INGEST_PER_MINUTE[stream]);
}
