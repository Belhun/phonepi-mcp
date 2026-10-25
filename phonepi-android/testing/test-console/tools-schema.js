/**
 * Tool definitions for the PhonePi test console.
 * Fields map to WebSocket request params sent to the Android app.
 */

const SNIPPET_TYPES = ["note", "todo", "bookmark", "snippet", "draft"];
const MESSAGE_PRIORITY = ["low", "normal", "high", "urgent"];
const SHARE_METHODS = ["default", "sms", "whatsapp", "clipboard"];

/** @type {import("./server").ToolDef[]} */
const TOOLS = [
  {
    id: "get_battery_level",
    label: "Battery level",
    group: "Device",
    description: "Read current charge % and charging state. No params.",
    fields: [],
  },
  {
    id: "get_recent_photos",
    label: "Recent photos",
    group: "Photos",
    description: "Metadata for the newest camera/gallery images. No bytes. Needs the Share recent photos switch.",
    fields: [
      { key: "limit", label: "Limit (default 5, cap 20)", type: "number", default: 5, min: 1 },
      { key: "since", label: "Since epoch ms (optional)", type: "number", default: 0 },
    ],
  },
  {
    id: "get_photo",
    label: "Get photo",
    group: "Photos",
    description: "One recompressed image as base64. Token-gated command. Read-only. EXIF/GPS is not copied.",
    fields: [
      { key: "id", label: "MediaStore id", type: "text", required: true, default: "" },
      { key: "maxDimension", label: "Max long edge or full", type: "text", default: "1600" },
      { key: "quality", label: "Quality 40-95", type: "number", default: 80, min: 40 },
      { key: "format", label: "jpeg or webp", type: "text", default: "jpeg" },
    ],
  },
  {
    id: "get_volumes",
    label: "Get volumes",
    group: "Device",
    description: "Read alarm/ring/notification/media/voice/system current and max, ringer mode, and DND.",
    fields: [],
  },
  {
    id: "phone_set_volume",
    label: "Set volume",
    group: "Device",
    description: "Set one stream to a level or max, or max_all. Ring/notification in DND need Do Not Disturb access.",
    fields: [
      { key: "stream", label: "Stream", type: "text", default: "alarm" },
      { key: "level", label: "Level or max", type: "text", default: "max" },
      { key: "max_all", label: "Max all streams", type: "checkbox", default: false },
    ],
  },
  {
    id: "copy_to_clipboard",
    label: "Copy to clipboard",
    group: "Device",
    description: "Copy text to the phone clipboard (needs app focus on some Android versions).",
    fields: [
      { key: "text", label: "Text", type: "textarea", required: true, default: "Hello from PhonePi test console" },
    ],
  },
  {
    id: "set_timer",
    label: "Set timer",
    group: "Device",
    description: "Starts a timer in the system Clock app (Google Clock on Pixel). Shows in Clock when supported.",
    fields: [
      { key: "seconds", label: "Seconds", type: "number", required: true, default: 30, min: 1 },
      { key: "message", label: "Timer label", type: "text", default: "PhonePi timer" },
      {
        key: "skipUi",
        label: "Start silently (no Clock UI — may not work on all devices)",
        type: "checkbox",
        default: false,
      },
    ],
  },
  {
    id: "find_phone",
    label: "Find phone",
    group: "Device",
    description: "Rings the phone on loop with optional location. Stop via dismiss UI, notification, or stop_alarm.",
    fields: [
      { key: "loop", label: "Loop until stopped", type: "checkbox", default: true },
      { key: "showDismissUI", label: "Show full-screen Stop button", type: "checkbox", default: true },
      { key: "includeLocation", label: "Include GPS location in response", type: "checkbox", default: true },
    ],
  },
  {
    id: "stop_alarm",
    label: "Stop find phone",
    group: "Device",
    description: "Stops the looping find-phone alarm.",
    fields: [
      { key: "sessionId", label: "Session id (optional)", type: "text", default: "" },
    ],
  },
  {
    id: "get_location",
    label: "Get location",
    group: "Device",
    description: "Returns a basic lat/lng fix. Requires location permission on the phone.",
    fields: [],
  },
  {
    id: "get_notifications",
    label: "Get notifications",
    group: "Notifications",
    description: "Read recent/active notifications from allowlisted apps. Never dismisses or replies.",
    fields: [
      { key: "package", label: "Package filter (optional)", type: "text", default: "" },
      { key: "since", label: "Since epoch ms (optional)", type: "number", default: 0 },
      { key: "limit", label: "Max results", type: "number", default: 50, min: 1 },
    ],
  },
  {
    id: "list_notification_apps",
    label: "List notification apps",
    group: "Notifications",
    description: "Apps that have posted a notification, plus whether each is shared. No bodies.",
    fields: [],
  },
  {
    id: "send_notification",
    label: "Send notification",
    group: "Notifications",
    description: "Actionable PhonePi notification (up to 3 buttons plus Reply). Mirrors to a paired watch.",
    fields: [
      { key: "title", label: "Title", type: "text", required: true, default: "Leave soon?" },
      { key: "body", label: "Body", type: "textarea", required: true, default: "Dinner in 20. Head out?" },
      { key: "requestId", label: "requestId (idempotent)", type: "text", default: "notif-test-1" },
      { key: "actions", label: "Actions (comma-separated, max 3)", type: "text", default: "OK,Snooze 1h,No" },
      { key: "priority", label: "Priority", type: "select", options: ["default", "high", "urgent"], default: "high" },
    ],
  },
  {
    id: "send_message",
    label: "Send interactive message",
    group: "Notifications",
    description: "Interactive notification with action buttons. Tap notification or use buttons, then poll get_message_response.",
    fields: [
      { key: "title", label: "Title", type: "text", required: true, default: "Question" },
      { key: "body", label: "Body", type: "textarea", required: true, default: "Tap a response when ready" },
      { key: "priority", label: "Priority", type: "select", options: MESSAGE_PRIORITY, default: "normal" },
      { key: "requireResponse", label: "Require response", type: "checkbox", default: false },
      { key: "expiresIn", label: "Expires in (minutes, 0=never)", type: "number", default: 0, min: 0 },
      { key: "responseOptions", label: "Response options (comma-separated)", type: "text", default: "Yes,No" },
    ],
  },
  {
    id: "get_message_response",
    label: "Get message response",
    group: "Notifications",
    description: "Poll state for a messageId returned by send_message.",
    fields: [
      { key: "messageId", label: "Message ID", type: "text", required: true, default: "" },
    ],
  },
  {
    id: "add_snippet",
    label: "Add snippet",
    group: "Snippets",
    fields: [
      { key: "title", label: "Title", type: "text", required: true, default: "Test note" },
      { key: "content", label: "Content", type: "textarea", required: true, default: "Snippet body" },
      { key: "type", label: "Type", type: "select", options: SNIPPET_TYPES, default: "note" },
      { key: "tags", label: "Tags (comma-separated)", type: "text", default: "test" },
    ],
  },
  {
    id: "get_all_snippets",
    label: "List all snippets",
    group: "Snippets",
    fields: [],
  },
  {
    id: "get_snippet_by_id",
    label: "Get snippet by ID",
    group: "Snippets",
    fields: [{ key: "id", label: "Snippet ID", type: "number", required: true, default: 1 }],
  },
  {
    id: "get_snippets_by_type",
    label: "Snippets by type",
    group: "Snippets",
    fields: [{ key: "type", label: "Type", type: "select", options: SNIPPET_TYPES, default: "note" }],
  },
  {
    id: "search_snippets",
    label: "Search snippets",
    group: "Snippets",
    fields: [{ key: "query", label: "Query", type: "text", required: true, default: "test" }],
  },
  {
    id: "update_snippet",
    label: "Update snippet",
    group: "Snippets",
    fields: [
      { key: "id", label: "Snippet ID", type: "number", required: true, default: 1 },
      { key: "title", label: "Title", type: "text", required: true, default: "Updated title" },
      { key: "content", label: "Content", type: "textarea", required: true, default: "Updated content" },
      { key: "type", label: "Type", type: "select", options: SNIPPET_TYPES, default: "note" },
      { key: "tags", label: "Tags", type: "text", default: "test" },
    ],
  },
  {
    id: "delete_snippet",
    label: "Delete snippet",
    group: "Snippets",
    fields: [{ key: "id", label: "Snippet ID", type: "number", required: true, default: 1 }],
  },
  {
    id: "share_snippet",
    label: "Share snippet",
    group: "Snippets",
    description: "default opens the Android share sheet (may open browser/apps).",
    fields: [
      { key: "id", label: "Snippet ID", type: "number", required: true, default: 1 },
      { key: "method", label: "Method", type: "select", options: SHARE_METHODS, default: "clipboard" },
      { key: "recipient", label: "Recipient (SMS/WhatsApp)", type: "text", default: "" },
    ],
  },
  {
    id: "get_contacts",
    label: "List contacts",
    group: "Contacts",
    fields: [],
  },
  {
    id: "get_contact_by_id",
    label: "Get contact by ID",
    group: "Contacts",
    fields: [{ key: "id", label: "Contact ID", type: "text", required: true, default: "" }],
  },
  {
    id: "add_contact",
    label: "Add contact",
    group: "Contacts",
    description: "Uses a JSON contact object (see template).",
    fields: [
      {
        key: "contact",
        label: "Contact JSON",
        type: "json",
        required: true,
        default: {
          name: "PhonePi Test",
          phoneNumbers: [{ label: "mobile", number: "+15555550199" }],
          emails: [{ label: "work", email: "test@phonepi.dev" }],
        },
      },
    ],
  },
  {
    id: "update_contact",
    label: "Update contact",
    group: "Contacts",
    fields: [
      {
        key: "contact",
        label: "Contact JSON (must include id)",
        type: "json",
        required: true,
        default: { id: "", name: "PhonePi Test Updated" },
      },
    ],
  },
  {
    id: "delete_contact",
    label: "Delete contact",
    group: "Contacts",
    fields: [{ key: "id", label: "Contact ID", type: "text", required: true, default: "" }],
  },
  {
    id: "search_contacts",
    label: "Search contacts",
    group: "Contacts",
    fields: [
      { key: "query", label: "Name", type: "text", required: true, default: "Celia Griffiths" },
      { key: "limit", label: "Max results", type: "number", default: 10 },
    ],
  },
  // Legacy carrier SMS — use Google Messages gm_* via MCP instead
  // {
  //   id: "list_sms_subscriptions",
  //   label: "List SMS SIMs",
  //   group: "Telephony",
  //   description: "Lists active SIMs for dual-SIM phones.",
  //   fields: [],
  // },
  // {
  //   id: "send_sms",
  //   label: "Send SMS",
  //   group: "Telephony",
  //   danger: true,
  //   fields: [
  //     { key: "to", label: "Phone number", type: "text", required: true, default: "+15555550100" },
  //     { key: "message", label: "Message", type: "textarea", required: true, default: "PhonePi test SMS" },
  //     { key: "simChoice", label: "SIM", type: "simSelect", default: "" },
  //   ],
  // },
  // {
  //   id: "get_sms_messages",
  //   label: "Read SMS thread",
  //   group: "Telephony",
  //   description: "Reads inbox + sent via Telephony provider (no RCS).",
  //   fields: [
  //     { key: "contact", label: "Contact", type: "contactPicker", required: true },
  //     { key: "hours", label: "Hours back", type: "number", default: 24 },
  //     { key: "limit", label: "Max messages", type: "number", default: 100 },
  //     { key: "includeSent", label: "Include sent", type: "checkbox", default: true },
  //   ],
  // },
  {
    id: "phone_list_locations",
    label: "List saved locations",
    group: "Navigation",
    description: "Destinations stored on the phone for phone_navigate.",
    fields: [],
  },
  {
    id: "phone_add_location",
    label: "Add saved location",
    group: "Navigation",
    fields: [
      { key: "name", label: "Name", type: "text", required: true, default: "home" },
      { key: "address", label: "Address", type: "text", default: "1600 Amphitheatre Parkway, Mountain View" },
      { key: "mapsUrl", label: "Maps URL (optional)", type: "text", default: "" },
    ],
  },
  {
    id: "phone_remove_location",
    label: "Remove saved location",
    group: "Navigation",
    fields: [
      { key: "name", label: "Name", type: "text", required: true, default: "home" },
    ],
  },
  {
    id: "phone_push_update",
    label: "Push update",
    group: "Device",
    description: "Download the server APK, verify it, and show the system installer. Alex still taps Install.",
    fields: [
      { key: "force", label: "Force (reinstall even if versionCode matches)", type: "checkbox", default: true },
    ],
  },
  {
    id: "phone_navigate",
    label: "Navigate",
    group: "Navigation",
    description: "Opens Google Maps navigation. Uses a saved name, address, or Maps link. Does not skip Android Auto Start now.",
    fields: [
      { key: "name", label: "Saved name", type: "text", default: "home" },
      { key: "address", label: "Address (if no name)", type: "text", default: "" },
      { key: "mapsUrl", label: "Maps URL (if no name)", type: "text", default: "" },
    ],
  },
  {
    id: "make_call",
    label: "Make call",
    group: "Telephony",
    danger: true,
    description: "Starts a REAL phone call. Phone needs CALL_PHONE permission.",
    fields: [
      { key: "to", label: "Phone number", type: "text", required: true, default: "+15555550100" },
    ],
  },
  {
    id: "get_recent_calls",
    label: "Recent calls",
    group: "Telephony",
    description: "Reads call history from the device. Default limit 50; soft cap 200 unless you pass a higher limit.",
    fields: [
      { key: "hours", label: "Hours back", type: "number", default: 168 },
      { key: "limit", label: "Max calls", type: "number", default: 50 },
      {
        key: "type",
        label: "Call type filter",
        type: "select",
        options: ["", "incoming", "outgoing", "missed", "voicemail", "rejected", "blocked"],
        default: "",
      },
      { key: "number", label: "Phone number filter", type: "text", default: "" },
      { key: "includeContactNames", label: "Resolve contact names", type: "checkbox", default: true },
    ],
  },
];

module.exports = { TOOLS };
