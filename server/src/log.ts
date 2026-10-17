const ISO_PREFIX = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z(?:\s|$)/;

export function formatLogLine(message: string, now: Date = new Date()): string {
  if (ISO_PREFIX.test(message)) return message;
  return `${now.toISOString()} ${message}`;
}

type ConsoleMethod = (...args: unknown[]) => void;

export interface StampableConsole {
  error: ConsoleMethod;
}

/**
 * Prefix every console.error line with an ISO-8601 timestamp.
 * Stdout stays untouched so the MCP stdio channel is not rewritten.
 * A line that already starts with a timestamp is left alone.
 */
export function installTimestampedConsole(
  target: StampableConsole = console,
  now: () => Date = () => new Date()
): void {
  const original = target.error.bind(target);
  target.error = (...args: unknown[]) => {
    if (args.length === 0) {
      original(formatLogLine("", now()));
      return;
    }
    const [first, ...rest] = args;
    if (typeof first === "string") {
      original(formatLogLine(first, now()), ...rest);
      return;
    }
    original(formatLogLine(String(first), now()), ...rest);
  };
}
