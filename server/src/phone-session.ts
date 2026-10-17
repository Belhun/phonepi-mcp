/** WebSocket.OPEN. Kept local so tests can use plain objects. */
export const PHONE_SOCKET_OPEN = 1;

export const PHONE_PERMISSION_KEYS = [
  "overlay",
  "post_notifications",
  "full_screen_intent",
  "notification_listener",
  "dnd_access",
  "exact_alarms",
] as const;

export type PhonePermissionKey = (typeof PHONE_PERMISSION_KEYS)[number];

export interface PhoneSocket {
  readyState: number;
  close(code?: number, reason?: string): void;
  terminate?: () => void;
  send?(data: string): void;
  on?(event: string, listener: (...args: never[]) => void): void;
}

export interface PhoneHealthDetails {
  lastConnectedAt: string | null;
  lastDisconnectedAt: string | null;
  lastDisconnectCode: number | null;
  lastDisconnectReason: string | null;
  lastPongAt: string | null;
  phonePermissions: Record<PhonePermissionKey, boolean> | null;
  missingPermissions: string[];
}

export interface PhoneCloseInfo {
  code?: number | null;
  reason?: string | null;
}

const EMPTY_HEALTH: PhoneHealthDetails = {
  lastConnectedAt: null,
  lastDisconnectedAt: null,
  lastDisconnectCode: null,
  lastDisconnectReason: null,
  lastPongAt: null,
  phonePermissions: null,
  missingPermissions: [],
};

export function reasonToString(reason: unknown): string {
  if (reason == null) return "";
  if (typeof Buffer !== "undefined" && Buffer.isBuffer(reason)) {
    return reason.toString("utf8");
  }
  if (typeof reason === "string") return reason;
  return String(reason);
}

/**
 * Accept only the allowlisted boolean flags. Anything else (including a token
 * field) is dropped. Missing or non-true flags are reported as missing.
 */
export function parsePhonePermissions(message: unknown): {
  phonePermissions: Record<PhonePermissionKey, boolean>;
  missingPermissions: string[];
} | null {
  if (!message || typeof message !== "object" || Array.isArray(message)) {
    return null;
  }
  const raw = (message as { permissions?: unknown }).permissions;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const flags = raw as Record<string, unknown>;
  const phonePermissions = {} as Record<PhonePermissionKey, boolean>;
  for (const key of PHONE_PERMISSION_KEYS) {
    phonePermissions[key] = flags[key] === true;
  }
  const missingPermissions = PHONE_PERMISSION_KEYS.filter(
    (key) => phonePermissions[key] !== true
  );
  return { phonePermissions, missingPermissions };
}

/**
 * The one phone slot. A newer socket replaces the older one. Close and error
 * handlers must call clearIfCurrent so a late event from the socket we already
 * replaced cannot mark the new socket disconnected.
 */
export class PhoneSession<T extends PhoneSocket = PhoneSocket> {
  private current: T | null = null;
  private lastConnectedAt: string | null = null;
  private lastDisconnectedAt: string | null = null;
  private lastDisconnectCode: number | null = null;
  private lastDisconnectReason: string | null = null;
  private lastPongAtMs: number | null = null;
  private lastPongAt: string | null = null;
  private permissions: Record<PhonePermissionKey, boolean> | null = null;
  private missing: string[] = [];
  private lastCleared: T | null = null;
  private lastClearReason: string | null = null;

  constructor(private readonly now: () => Date = () => new Date()) {}

  currentSocket(): T | null {
    return this.current;
  }

  isCurrent(socket: T): boolean {
    return this.current === socket;
  }

  isConnected(): boolean {
    return this.current != null && this.current.readyState === PHONE_SOCKET_OPEN;
  }

  /**
   * Adopt [next] as the live phone. The previous socket is closed after the
   * swap, so its later close/error event is not current and must not clear
   * [next].
   */
  replace(next: T): T | null {
    const previous = this.current;
    this.current = next;
    const at = this.now();
    this.lastConnectedAt = at.toISOString();
    this.lastPongAtMs = at.getTime();
    this.lastPongAt = at.toISOString();
    this.permissions = null;
    this.missing = [];
    if (previous && previous !== next) {
      try {
        previous.close(4000, "replaced by new phone connection");
      } catch {
        /* The old socket is already going away. */
      }
      return previous;
    }
    return null;
  }

  /**
   * Clear the live slot only when [socket] is still that slot.
   * Returns true when this call recorded a disconnect.
   */
  clearIfCurrent(socket: T, info: PhoneCloseInfo = {}): boolean {
    if (this.current !== socket) return false;
    this.current = null;
    this.lastCleared = socket;
    this.lastDisconnectedAt = this.now().toISOString();
    this.lastDisconnectCode =
      typeof info.code === "number" && Number.isFinite(info.code) ? info.code : null;
    const reason = info.reason == null ? "" : String(info.reason).trim();
    this.lastDisconnectReason = reason.length > 0 ? reason : null;
    this.lastClearReason = this.lastDisconnectReason;
    return true;
  }

  /**
   * A close/error that arrives after takeTimedOut already cleared this socket.
   * Returns true once, and only when nothing newer has taken the slot, so the
   * caller can finish the same disconnect (for example reconnect in client mode).
   */
  consumeTimeoutClose(socket: T): boolean {
    if (this.current != null) return false;
    if (this.lastCleared !== socket) return false;
    if (this.lastClearReason !== "no pong received") return false;
    this.lastCleared = null;
    return true;
  }

  notePong(socket: T, at: Date = this.now()): boolean {
    if (this.current !== socket) return false;
    this.lastPongAtMs = at.getTime();
    this.lastPongAt = at.toISOString();
    return true;
  }

  notePermissions(socket: T, message: unknown): boolean {
    if (this.current !== socket) return false;
    const parsed = parsePhonePermissions(message);
    if (!parsed) return false;
    this.permissions = parsed.phonePermissions;
    this.missing = parsed.missingPermissions;
    return true;
  }

  /**
   * If the current socket has not ponged within [timeoutMs], drop it from the
   * slot and return it so the caller can terminate it. A later close from that
   * socket is stale and will not clear a replacement.
   */
  takeTimedOut(timeoutMs: number, at: Date = this.now()): T | null {
    const socket = this.current;
    if (!socket || this.lastPongAtMs == null) return null;
    if (at.getTime() - this.lastPongAtMs <= timeoutMs) return null;
    this.clearIfCurrent(socket, { code: 1000, reason: "no pong received" });
    return socket;
  }

  healthDetails(): PhoneHealthDetails {
    return {
      lastConnectedAt: this.lastConnectedAt,
      lastDisconnectedAt: this.lastDisconnectedAt,
      lastDisconnectCode: this.lastDisconnectCode,
      lastDisconnectReason: this.lastDisconnectReason,
      lastPongAt: this.lastPongAt,
      phonePermissions: this.permissions ? { ...this.permissions } : null,
      missingPermissions: [...this.missing],
    };
  }
}

export function emptyHealthDetails(): PhoneHealthDetails {
  return {
    ...EMPTY_HEALTH,
    missingPermissions: [],
  };
}

type BindableSocket = PhoneSocket & {
  on(event: "close", listener: (code: number, reason: unknown) => void): void;
  on(event: "error", listener: (err: unknown) => void): void;
  on(event: "message", listener: (data: unknown) => void): void;
};

export interface BindPhoneHooks<T extends PhoneSocket> {
  onMessage?: (socket: T, data: unknown) => void;
  /** Fires only when this socket was still current and has now been cleared. */
  onCleared?: (code: number | null, reason: string) => void;
}

/**
 * Swap this socket in as the phone and attach close/error handlers that
 * cannot clobber a newer socket.
 */
export function bindPhoneConnection<T extends BindableSocket>(
  session: PhoneSession<T>,
  socket: T,
  log: (line: string) => void,
  hooks: BindPhoneHooks<T> = {}
): void {
  session.replace(socket);
  log("[WebSocket] Phone connected");

  socket.on("message", (data) => {
    hooks.onMessage?.(socket, data);
  });

  socket.on("close", (code, reason) => {
    const text = reasonToString(reason);
    const cleared = session.clearIfCurrent(socket, { code, reason: text });
    if (!cleared) {
      if (session.consumeTimeoutClose(socket)) {
        log(
          `[WebSocket] Timed out socket closed (code: ${code}, reason: ${
            text || "none"
          })`
        );
        hooks.onCleared?.(1000, "no pong received");
        return;
      }
      const kept = session.currentSocket() != null;
      log(
        `[WebSocket] Stale socket closed (code: ${code}, reason: ${
          text || "none"
        }); ${kept ? "current phone kept" : "slot already empty"}`
      );
      return;
    }
    log(
      `[WebSocket] Phone disconnected (code: ${code}, reason: ${text || "none"})`
    );
    hooks.onCleared?.(code, text);
  });

  socket.on("error", (error) => {
    const message = error instanceof Error ? error.message : String(error);
    log(`[WebSocket] Phone connection error: ${message}`);
    if (socket.readyState === PHONE_SOCKET_OPEN) return;
    const cleared = session.clearIfCurrent(socket, {
      code: null,
      reason: "socket error",
    });
    if (!cleared) {
      log("[WebSocket] Ignored error from a stale socket");
      return;
    }
    hooks.onCleared?.(null, "socket error");
  });

  try {
    socket.send?.(JSON.stringify({ type: "ping" }));
  } catch (err) {
    log(
      `[WebSocket] Error sending initial ping: ${
        err instanceof Error ? err.message : String(err)
      }`
    );
  }
}
