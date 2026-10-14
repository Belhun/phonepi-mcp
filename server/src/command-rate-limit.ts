/**
 * Sliding-window limiter shared by MCP phone commands and POST /api/command.
 */

export class CommandRateLimiter {
  private readonly hits = new Map<string, number[]>();

  constructor(
    private readonly windowMs = 60_000,
    private readonly now: () => number = Date.now
  ) {}

  allow(name: string, maxPerWindow: number): { ok: true } | { ok: false; retryAfterSec: number } {
    const t = this.now();
    const cutoff = t - this.windowMs;
    const recent = (this.hits.get(name) ?? []).filter((ts) => ts > cutoff);
    if (recent.length >= maxPerWindow) {
      this.hits.set(name, recent);
      const oldest = recent[0] ?? t;
      const retryAfterSec = Math.max(1, Math.ceil((oldest + this.windowMs - t) / 1000));
      return { ok: false, retryAfterSec };
    }
    recent.push(t);
    this.hits.set(name, recent);
    return { ok: true };
  }
}

/** Process-wide limiter so MCP and HTTP share one budget. */
export const commandRateLimiter = new CommandRateLimiter();
