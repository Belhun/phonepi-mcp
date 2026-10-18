/**
 * Allowlisted phone commands. MCP tools and POST /api/command share this
 * registry. Unknown names never reach the phone. To add a future action,
 * append a spec here, handle it on the phone, and keep the same name.
 */

import {
  CommandValidationError,
  ALARM_LABEL_MAX_LENGTH,
  TIMER_MAX_SECONDS,
  validatePhoneCommandArgs as validateAlarmCommandArgs,
  type ValidatedCommandArgs,
} from "./alarm-commands.js";
import { validateSendTextArgs } from "./send-text.js";

export { ALARM_LABEL_MAX_LENGTH, TIMER_MAX_SECONDS, CommandValidationError };
export type { ValidatedCommandArgs };

export function validatePhoneCommandArgs(
  name: string,
  args: Record<string, unknown>
): ValidatedCommandArgs {
  if (name === "request_send_text" || name === "get_send_status") {
    return validateSendTextArgs(name, args);
  }
  return validateAlarmCommandArgs(name, args);
}

export type PhoneCommandKind = "read" | "write" | "action";

export interface PhoneCommand {
  name: string;
  kind: PhoneCommandKind;
  /** WebSocket tool name sent to the phone. Always the MCP name. */
  phoneTool: string;
  maxPerMinute: number;
  description: string;
  inputSchema: {
    type: "object";
    properties: Record<string, unknown>;
    required: string[];
  };
}

export const PHONE_COMMANDS: readonly PhoneCommand[] = [
  {
    name: "phone_list_locations",
    kind: "read",
    phoneTool: "phone_list_locations",
    maxPerMinute: 60,
    description:
      "List saved navigation destinations stored on the phone (name, address, coordinates, optional Maps URL).",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
  },
  {
    name: "phone_add_location",
    kind: "write",
    phoneTool: "phone_add_location",
    maxPerMinute: 20,
    description:
      "Save a named destination on the phone. Provide an address, lat/lng, and/or a Google Maps place URL. Same name overwrites.",
    inputSchema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          description: "Short label, for example home or Costco",
        },
        address: {
          type: "string",
          description: "Street address or place name Maps can search",
        },
        latitude: {
          type: "number",
          description: "Latitude in decimal degrees",
        },
        longitude: {
          type: "number",
          description: "Longitude in decimal degrees",
        },
        mapsUrl: {
          type: "string",
          description: "Optional Google Maps place, search, or directions URL",
        },
      },
      required: ["name"],
    },
  },
  {
    name: "phone_remove_location",
    kind: "write",
    phoneTool: "phone_remove_location",
    maxPerMinute: 20,
    description: "Delete a saved destination by name (case-insensitive).",
    inputSchema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          description: "Saved location name to remove",
        },
      },
      required: ["name"],
    },
  },
  {
    name: "phone_navigate",
    kind: "action",
    phoneTool: "phone_navigate",
    maxPerMinute: 6,
    description:
      "Open Google Maps navigation on the phone to a saved name, address, coordinates, or Maps link. Uses google.navigation with geo/https fallbacks. Does not skip Android Auto's Start now prompt.",
    inputSchema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          description: "Saved location name",
        },
        address: {
          type: "string",
          description: "Address or place query when not using a saved name",
        },
        latitude: {
          type: "number",
          description: "Latitude in decimal degrees",
        },
        longitude: {
          type: "number",
          description: "Longitude in decimal degrees",
        },
        mapsUrl: {
          type: "string",
          description: "Google Maps place, search, or directions URL",
        },
      },
      required: [],
    },
  },
  {
    name: "phone_push_update",
    kind: "write",
    phoneTool: "phone_push_update",
    maxPerMinute: 6,
    description:
      "Tell the connected phone to download the APK from this server's /phonepi/update/apk, verify sha256 and the signing certificate, and show the system installer. Alex still has to tap Install. Does not install silently. force (default true) reinstalls even when versionCode matches.",
    inputSchema: {
      type: "object",
      properties: {
        force: {
          type: "boolean",
          description:
            "Re-download and prompt even if the phone already has this versionCode (default true)",
        },
      },
      required: [],
    },
  },
  {
    name: "phone_set_volume",
    kind: "write",
    phoneTool: "phone_set_volume",
    maxPerMinute: 20,
    description:
      "Set a phone volume stream (alarm, ring, notification, media, voice/call, system) to a level or 'max'. max_all sets every stream to max and restores ringer mode to normal. While Do Not Disturb is on, ring/notification changes need Do Not Disturb access on the phone; alarm volume still works.",
    inputSchema: {
      type: "object",
      properties: {
        stream: {
          type: "string",
          description:
            "alarm | ring | notification | media | voice (or call) | system. Ignored when max_all is true.",
        },
        level: {
          description: "Integer 0..max for that stream, or the string 'max'",
        },
        max_all: {
          type: "boolean",
          description: "Set every stream to its max (default false)",
        },
      },
      required: [],
    },
  },
  {
    name: "get_photo",
    kind: "read",
    phoneTool: "get_photo",
    maxPerMinute: 12,
    description:
      "Fetch one photo from the phone by MediaStore id (from get_recent_photos) as base64 JPEG or WebP, plus metadata. Read-only. Requires the shared token because image bytes are more sensitive than battery or the metadata list. Default long edge is 1600px (or maxDimension, or \"full\"). The phone recompresses so the single JSON frame stays under 1 MB. Original EXIF, including GPS, is not in the returned bytes. Needs Photos permission and the in-app Share recent photos switch.",
    inputSchema: {
      type: "object",
      properties: {
        id: {
          type: "string",
          description: "MediaStore image id from get_recent_photos",
        },
        maxDimension: {
          description:
            'Long-edge pixels from 64 to 4096 (default 1600), or "full" for the largest size that still fits the 1 MB frame',
        },
        quality: {
          type: "number",
          description:
            "JPEG or WebP quality from 40 to 95 (default 80). Lowered automatically if needed to fit",
        },
        format: {
          type: "string",
          description: "jpeg (default) or webp",
        },
      },
      required: ["id"],
    },
  },
  {
    name: "set_alarm",
    kind: "action",
    phoneTool: "set_alarm",
    maxPerMinute: 10,
    description:
      "Create an alarm in the phone Clock app (AlarmClock.ACTION_SET_ALARM) with SKIP_UI. hour is 0-23 (or 1-12 with period am/pm). minute is 0-59. Optional message/label up to 100 characters. Optional days: weekday names, weekdays, weekends, or daily. Android does not confirm the alarm was saved.",
    inputSchema: {
      type: "object",
      properties: {
        hour: {
          type: "number",
          description: "Hour 0-23. Use 1-12 together with period.",
        },
        minute: {
          type: "number",
          description: "Minute 0-59. Alias: minutes.",
        },
        minutes: {
          type: "number",
          description: "Alias of minute",
        },
        period: {
          type: "string",
          description: "Optional am or pm when hour is 1-12",
        },
        message: {
          type: "string",
          description: "Alarm label, at most 100 characters. Alias: label.",
        },
        label: {
          type: "string",
          description: "Alias of message",
        },
        days: {
          description:
            'Repeat days: "weekdays", "weekends", "daily", or a list of weekday names. Omit for a one-shot alarm.',
        },
      },
      required: ["hour", "minute"],
    },
  },
  {
    name: "set_timer",
    kind: "action",
    phoneTool: "set_timer",
    maxPerMinute: 10,
    description:
      "Start a countdown in the phone Clock app (AlarmClock.ACTION_SET_TIMER) with SKIP_UI. seconds is 1 through 86400 (24 hours). Optional message/label up to 100 characters. This does not schedule a PhonePi notification if Clock cannot take the timer.",
    inputSchema: {
      type: "object",
      properties: {
        seconds: {
          type: "number",
          description: "Length in seconds, from 1 to 86400",
        },
        message: {
          type: "string",
          description: "Timer label, at most 100 characters. Alias: label.",
        },
        label: {
          type: "string",
          description: "Alias of message",
        },
      },
      required: ["seconds"],
    },
  },
  {
    name: "dismiss_alarm",
    kind: "action",
    phoneTool: "dismiss_alarm",
    maxPerMinute: 10,
    description:
      "Ask the Clock app to dismiss an alarm via ACTION_DISMISS_ALARM. search is time (hour + minute) or label (message). Android does not let PhonePi delete arbitrary alarms, and a started:true result does not prove the alarm is gone. If this Clock app has no dismiss handler, the phone returns status error.",
    inputSchema: {
      type: "object",
      properties: {
        search: {
          type: "string",
          description: "time or label",
        },
        hour: { type: "number", description: "Hour 0-23 when search is time" },
        minute: {
          type: "number",
          description: "Minute 0-59 when search is time",
        },
        message: {
          type: "string",
          description: "Label to match when search is label. Alias: label.",
        },
        label: { type: "string", description: "Alias of message" },
      },
      required: ["search"],
    },
  },
  {
    name: "dismiss_timer",
    kind: "action",
    phoneTool: "dismiss_timer",
    maxPerMinute: 10,
    description:
      "Ask the Clock app to dismiss a timer via ACTION_DISMISS_TIMER. Optional message/label. Android does not confirm the timer stopped. If this Clock app has no dismiss handler, the phone returns status error.",
    inputSchema: {
      type: "object",
      properties: {
        message: {
          type: "string",
          description: "Optional timer label. Alias: label.",
        },
        label: { type: "string", description: "Alias of message" },
      },
      required: [],
    },
  },
  {
    name: "request_send_text",
    kind: "action",
    phoneTool: "request_send_text",
    maxPerMinute: 10,
    description:
      "Ask the phone to text one person. Nothing is sent by this call alone. recipient is one contact name or one phone number. message is the exact text, at most 1000 characters. mode is as_alex (default) or attributed. as_alex waits for Alex to tap Send or say send while driving, and expires in 5 minutes. attributed may send without that confirm only when the phone toggle is on; the phone adds the prefix \"[Alex's assistant Grok]: \" itself. An ambiguous contact returns needs_clarification and sends nothing. HTTP 200 does not mean sent. Check get_send_status. Send only when Alex names the person and the message in that moment. Never on your own, and never for a reply or an acknowledgement. A cancelled or expired send is final.",
    inputSchema: {
      type: "object",
      properties: {
        recipient: {
          type: "string",
          description: "One contact name or one phone number. No groups.",
        },
        message: {
          type: "string",
          description: "Exact text to send, at most 1000 characters. The phone does not let you edit it later.",
        },
        mode: {
          type: "string",
          description:
            "as_alex (default) requires Alex's tap or spoken send. attributed sends only with the phone-added assistant prefix, and only if that toggle is on.",
        },
      },
      required: ["recipient", "message"],
    },
  },
  {
    name: "get_send_status",
    kind: "read",
    phoneTool: "get_send_status",
    maxPerMinute: 60,
    description:
      "Read one text request by id. status is pending, sent, cancelled, expired, failed, needs_clarification, or rejected. HTTP 200 does not mean the text was sent. Poll this after request_send_text.",
    inputSchema: {
      type: "object",
      properties: {
        id: {
          type: "string",
          description: "Id returned by request_send_text",
        },
      },
      required: ["id"],
    },
  },
];

const BY_NAME = new Map(PHONE_COMMANDS.map((command) => [command.name, command]));

export function getPhoneCommand(name: string): PhoneCommand | undefined {
  return BY_NAME.get(name);
}

export function isPhoneCommand(name: string): boolean {
  return BY_NAME.has(name);
}

export const PHONE_COMMAND_NAMES = PHONE_COMMANDS.map((command) => command.name);

export const PHONE_COMMAND_SCHEMAS = PHONE_COMMANDS.map((command) => ({
  name: command.name,
  description: command.description,
  inputSchema: command.inputSchema,
}));

export class RateLimitError extends Error {
  readonly statusCode = 429;
  constructor(command: string, retryAfterSec: number) {
    super(`Rate limited: ${command}. Try again in ${retryAfterSec}s`);
    this.name = "RateLimitError";
  }
}
