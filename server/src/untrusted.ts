/**
 * Wrap untrusted text so bots treat it as data, not as instructions.
 * Prompt/reply text, app names, workout titles, live notification text,
 * and Google Messages bodies go through this.
 */

const GM_READ_TOOLS = new Set([
  "gm_get_messages",
  "gm_get_conversation",
  "gm_search_messages",
  "gm_list_conversations",
]);

const GM_BODY_KEYS = new Set(["text", "body", "message", "snippet", "preview"]);

export const UNTRUSTED_BEGIN = "<<<PHONEPI_UNTRUSTED_DATA>>>";
export const UNTRUSTED_END = "<<<END_PHONEPI_UNTRUSTED_DATA>>>";

export interface UntrustedText {
  untrusted: true;
  provenance: string;
  text: string;
}

export function wrapUntrusted(
  value: string | null | undefined,
  provenance: string
): UntrustedText | null {
  if (value == null) return null;
  const text = neutralizeDelimiters(String(value));
  if (text === "") return null;
  return {
    untrusted: true as const,
    provenance,
    text: `${UNTRUSTED_BEGIN}\n${text}\n${UNTRUSTED_END}`,
  };
}

export function wrapUntrustedOrEmpty(
  value: string | null | undefined,
  provenance: string
): UntrustedText {
  return (
    wrapUntrusted(value, provenance) ?? {
      untrusted: true as const,
      provenance,
      text: `${UNTRUSTED_BEGIN}\n${UNTRUSTED_END}`,
    }
  );
}

/**
 * Neutralise fence markers inside the payload so a third-party string cannot
 * close the wrapper early.
 */
export function neutralizeDelimiters(text: string): string {
  return text
    .split(UNTRUSTED_BEGIN)
    .join("[PHONEPI_UNTRUSTED_DATA]")
    .split(UNTRUSTED_END)
    .join("[END_PHONEPI_UNTRUSTED_DATA]");
}

function stringField(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function alreadyWrapped(value: unknown): boolean {
  return (
    !!value &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    (value as { untrusted?: unknown }).untrusted === true &&
    typeof (value as { text?: unknown }).text === "string"
  );
}

/**
 * Live get_notifications: title/text/appName become {untrusted, provenance, text}.
 * Phone poller still sees raw strings because it calls the phone directly.
 */
export function wrapLiveNotificationsPayload(payload: unknown): unknown {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return payload;
  }
  const record = payload as Record<string, unknown>;
  if (!Array.isArray(record.notifications)) return payload;
  return {
    ...record,
    notifications: record.notifications.map((item, index) => {
      if (!item || typeof item !== "object" || Array.isArray(item)) return item;
      const row = item as Record<string, unknown>;
      if (alreadyWrapped(row.text) || alreadyWrapped(row.title)) return row;
      const key =
        typeof row.key === "string" && row.key.trim()
          ? row.key.trim()
          : `i${index}`;
      return {
        ...row,
        title: wrapUntrustedOrEmpty(stringField(row.title), `get_notifications:${key}:title`),
        text: wrapUntrustedOrEmpty(stringField(row.text), `get_notifications:${key}:text`),
        appName:
          wrapUntrusted(stringField(row.appName), `get_notifications:${key}:app`) ??
          row.appName,
      };
    }),
  };
}

export function markPhoneToolResponse(name: string, payload: unknown): unknown {
  if (name === "get_notifications") return wrapLiveNotificationsPayload(payload);
  return payload;
}

/**
 * gm_* reads keep original string body fields so Phone & Texts still parses
 * them. Extra untrusted + provenance flags sit beside those strings.
 * Send/draft/react tools are left untouched.
 */
export function annotateGmReadText(tool: string, text: string): string {
  if (!GM_READ_TOOLS.has(tool) || !text.trim()) return text;
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return text;
  }
  const marked = markGmValue(parsed, tool, "message");
  try {
    return JSON.stringify(marked);
  } catch {
    return text;
  }
}

function markGmValue(value: unknown, tool: string, fallbackId: string): unknown {
  if (Array.isArray(value)) {
    return value.map((item, index) =>
      markGmValue(item, tool, `${fallbackId}:${index}`)
    );
  }
  if (!value || typeof value !== "object") return value;
  const record = value as Record<string, unknown>;
  const copy: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(record)) {
    copy[key] = markGmValue(entry, tool, gmChildId(record, key, fallbackId));
  }
  if (shouldFlagGmMessage(copy)) {
    const id =
      typeof copy.id === "string" && copy.id.trim()
        ? copy.id.trim()
        : fallbackId;
    if (copy.untrusted !== true) copy.untrusted = true;
    if (typeof copy.provenance !== "string") {
      copy.provenance = `${tool}:${id}`;
    }
  }
  return copy;
}

function gmChildId(
  record: Record<string, unknown>,
  key: string,
  fallbackId: string
): string {
  if (typeof record.id === "string" && record.id.trim()) {
    return `${record.id.trim()}:${key}`;
  }
  return `${fallbackId}:${key}`;
}

function shouldFlagGmMessage(record: Record<string, unknown>): boolean {
  const hasBody = [...GM_BODY_KEYS].some(
    (key) => typeof record[key] === "string" && String(record[key]).length > 0
  );
  if (!hasBody) return false;
  return (
    typeof record.id === "string" ||
    typeof record.conversation_id === "string" ||
    typeof record.phone_number === "string" ||
    typeof record.sender === "string" ||
    typeof record.timestamp === "string" ||
    typeof record.ts === "number"
  );
}
