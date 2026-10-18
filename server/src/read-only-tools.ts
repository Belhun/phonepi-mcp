/** Tools a local PowerShell assistant may call over the loopback HTTP API. */
export const READ_ONLY_HTTP_TOOLS = new Set<string>([
  "get_battery_level",
  "get_recent_calls",
  "get_contacts",
  "search_contacts",
  "get_contact_by_id",
  "get_all_snippets",
  "get_snippet_by_id",
  "get_snippets_by_type",
  "search_snippets",
  "get_notifications",
  "list_notification_apps",
  "get_volumes",
  "get_recent_photos",
  "list_alarms",
]);

/** Sliding-window limits (per minute) for sensitive read tools. */
export const READ_ONLY_TOOL_RATE_LIMITS: Record<string, number> = {
  get_notifications: 60,
  list_notification_apps: 60,
  get_volumes: 60,
  get_recent_photos: 30,
  list_alarms: 60,
};

export function isReadOnlyHttpTool(name: string): boolean {
  return READ_ONLY_HTTP_TOOLS.has(name);
}

export function readOnlyToolRateLimit(name: string): number | undefined {
  return READ_ONLY_TOOL_RATE_LIMITS[name];
}

export const NOTIFICATION_TOOL_SCHEMAS = [
  {
    name: "get_notifications",
    description:
      "Read recent and currently active phone notifications (app name, title, text, time, package). Read-only: never dismisses, replies, or acts. Only apps Alex allowlisted in PhonePi are included (Share all is off by default). If Android hid a field, it is returned as '(hidden by Android)' with a reason — nothing is redacted silently. Filters: package, since (epoch ms), limit (default 50, cap 200).",
    inputSchema: {
      type: "object",
      properties: {
        package: {
          type: "string",
          description: "Optional package name filter, for example com.google.android.gm",
        },
        since: {
          type: "number",
          description: "Unix epoch milliseconds; only notifications at or after this time",
        },
        limit: {
          type: "number",
          description: "Max notifications to return (default 50, cap 200)",
        },
      },
      required: [],
    },
  },
  {
    name: "list_notification_apps",
    description:
      "List apps that have posted a notification, with whether each is on the share allowlist. Does not include notification bodies. Read-only.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
  },
];

export const PHOTO_TOOL_SCHEMAS = [
  {
    name: "get_recent_photos",
    description:
      "List the newest camera and gallery images on the phone. Metadata only: id, displayName, dateTaken, dateAdded, size, width, height, mimeType. No image bytes. Read-only: never deletes, edits, or uploads photos. Needs the Photos permission and the in-app Share recent photos switch (off by default). limit default 5, hard cap 20. since is epoch milliseconds. The scan looks at the newest 100 images. Fetch bytes with get_photo (token required).",
    inputSchema: {
      type: "object",
      properties: {
        limit: {
          type: "number",
          description: "How many photos to return (default 5, hard cap 20)",
        },
        since: {
          type: "number",
          description: "Epoch milliseconds. Only photos taken or added at or after this time",
        },
      },
      required: [],
    },
  },
];

export const ALARM_TOOL_SCHEMAS = [
  {
    name: "list_alarms",
    description:
      "Read the next alarm AlarmManager reports (getNextAlarmClock) and the alarms PhonePi itself asked the Clock app to create (time, label, repeat days, created-at). Read-only. This is not a full Clock inventory. Android does not let PhonePi list or edit other apps' alarms. Changing alarms is set_alarm, set_timer, dismiss_alarm, and dismiss_timer.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
  },
];

export const VOLUME_TOOL_SCHEMAS = [
  {
    name: "get_volumes",
    description:
      "Read phone volume streams (alarm, ring, notification, media, voice, system) with current and max, plus ringer mode and Do Not Disturb state. Read-only. Changing volumes is phone_set_volume.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
  },
];
