/**
 * Allowlisted phone commands. MCP tools and POST /api/command share this
 * registry. Unknown names never reach the phone. To add a future action,
 * append a spec here, handle it on the phone, and keep the same name.
 */

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
