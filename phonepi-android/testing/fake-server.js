// Fake PhonePi WebSocket server — runs every active MCP tool in sequence.
// Run: node phonepi-android/testing/fake-server.js
//
// Logs print in THIS terminal. Phone-side: adb logcat -s PhonePiWS:*
//
// Note: the Android app only implements handlers you have registered in
// ConnectionService. Unimplemented tools return status:error "Unknown tool".

const path = require("path");

function loadWs() {
  try {
    return require("ws");
  } catch (e) {
    const serverModules = path.resolve(__dirname, "..", "..", "server", "node_modules");
    return require(path.join(serverModules, "ws"));
  }
}

const { WebSocketServer } = loadWs();

const PORT = 11041;
const DELAY_MS = 800; // pause between tool calls

/** @type {Array<{ tool: string, params?: object | ((state: object) => object), label?: string }>} */
const TOOL_TESTS = [
  { tool: "get_battery_level", params: {} },

  { tool: "copy_to_clipboard", params: { text: "PhonePi fake-server test clip" } },

  { tool: "set_timer", params: { seconds: 5 } },

  {
    tool: "send_notification",
    params: {
      title: "PhonePi test",
      body: "Fake server notification",
      priority: "default",
    },
  },

  {
    tool: "find_phone",
    label: "find_phone (loop=false, safe test)",
    params: { loop: false, showDismissUI: false, notifyOnCompletion: false },
  },

  {
    tool: "send_message",
    params: {
      title: "Test message",
      body: "Reply from fake-server harness",
      priority: "normal",
      requireResponse: false,
    },
  },

  {
    tool: "get_message_response",
    params: (s) => ({ messageId: s.lastMessageId || "missing-id" }),
    label: "get_message_response (uses last messageId)",
  },

  {
    tool: "add_snippet",
    params: {
      title: "Fake server note",
      content: "Created by testing/fake-server.js",
      type: "note",
      tags: "test,phonepi",
    },
  },

  { tool: "get_all_snippets", params: {} },

  {
    tool: "get_snippet_by_id",
    params: (s) => ({ id: s.lastSnippetId ?? 1 }),
    label: "get_snippet_by_id (uses last snippet id)",
  },

  { tool: "get_snippets_by_type", params: { type: "note" } },

  { tool: "search_snippets", params: { query: "fake server" } },

  {
    tool: "update_snippet",
    params: (s) => ({
      id: s.lastSnippetId ?? 1,
      title: "Updated note",
      content: "Updated by fake-server",
      type: "note",
      tags: "test,updated",
    }),
  },

  {
    tool: "share_snippet",
    params: (s) => ({
      id: s.lastSnippetId ?? 1,
      method: "clipboard",
    }),
  },

  {
    tool: "delete_snippet",
    params: (s) => ({ id: s.lastSnippetId ?? 1 }),
    label: "delete_snippet (cleanup)",
  },

  {
    tool: "send_sms",
    label: "send_sms (skipped — set RUN_SMS=1 to enable)",
    params: { to: "+15555550100", message: "PhonePi test SMS" },
    skip: () => !process.env.RUN_SMS,
  },

  {
    tool: "make_call",
    label: "make_call (skipped — set RUN_CALL=1 to enable)",
    params: { to: "+15555550100" },
    skip: () => !process.env.RUN_CALL,
  },

  { tool: "get_recent_calls", params: { hours: 168, limit: 20 } },

  { tool: "get_contacts", params: {} },

  {
    tool: "get_contact_by_id",
    params: (s) => ({ id: s.lastContactId || "0" }),
    label: "get_contact_by_id (uses first contact if any)",
  },

  {
    tool: "add_contact",
    params: {
      contact: {
        name: "PhonePi Test",
        phoneNumbers: [{ label: "mobile", number: "+15555550199" }],
        emails: [{ label: "work", email: "test@phonepi.dev" }],
      },
    },
  },

  {
    tool: "update_contact",
    params: (s) => ({
      contact: {
        id: s.lastContactId || "0",
        name: "PhonePi Test Updated",
      },
    }),
    label: "update_contact (needs contact id from add_contact)",
  },

  {
    tool: "delete_contact",
    params: (s) => ({ id: s.lastContactId || "0" }),
    label: "delete_contact (cleanup)",
    skip: (s) => !s.lastContactId,
  },
];

const wss = new WebSocketServer({ host: "0.0.0.0", port: PORT });
let requestId = 0;

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function resolveParams(entry, state) {
  const raw = entry.params;
  if (typeof raw === "function") return raw(state);
  return raw ?? {};
}

function captureState(state, tool, data) {
  if (!data || data.status !== "success") return;
  if (tool === "send_message" && data.messageId) state.lastMessageId = data.messageId;
  if (tool === "add_snippet" && data.id != null) state.lastSnippetId = data.id;
  if (tool === "get_all_snippets" && Array.isArray(data.snippets) && data.snippets[0]?.id) {
    state.lastSnippetId = data.snippets[0].id;
  }
  if (tool === "add_contact" && data.contact?.id) state.lastContactId = data.contact.id;
  if (tool === "add_contact" && data.contact?.id == null && data.id) state.lastContactId = data.id;
  if (tool === "get_contacts" && Array.isArray(data.contacts) && data.contacts[0]?.id) {
    state.lastContactId = data.contacts[0].id;
  }
}

wss.on("connection", (ws) => {
  console.log("\n=== phone connected ===\n");
  ws.send(JSON.stringify({ type: "ping" }));

  const pending = new Map();
  const state = {};
  const results = { pass: 0, fail: 0, skip: 0 };

  ws.on("message", (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.type === "pong") return;

    if (msg.type === "response" && pending.has(msg.requestId)) {
      const { tool, label } = pending.get(msg.requestId);
      pending.delete(msg.requestId);

      const data = msg.data ?? {};
      const ok = data.status === "success";
      const tag = ok ? "PASS" : "FAIL";
      if (ok) results.pass++;
      else results.fail++;

      console.log(`[${tag}] ${label || tool}`);
      console.log(JSON.stringify(data, null, 2));
      console.log();

      captureState(state, tool, data);
    } else {
      console.log("unexpected message:", JSON.stringify(msg, null, 2));
    }
  });

  const send = (tool, params) => {
    const id = `req-${++requestId}`;
    pending.set(id, { tool, label: null });
    ws.send(JSON.stringify({ type: "request", requestId: id, tool, params }));
    return id;
  };

  const ping = setInterval(() => ws.send(JSON.stringify({ type: "ping" })), 15000);
  ws.on("close", () => {
    clearInterval(ping);
    console.log("=== phone disconnected ===");
    console.log(`Summary: ${results.pass} pass, ${results.fail} fail, ${results.skip} skip\n`);
  });

  (async () => {
    await sleep(500);

    for (const entry of TOOL_TESTS) {
      if (entry.skip && entry.skip(state)) {
        results.skip++;
        console.log(`[SKIP] ${entry.label || entry.tool}`);
        console.log();
        continue;
      }

      const params = resolveParams(entry, state);
      const label = entry.label || entry.tool;
      const id = `req-${++requestId}`;
      pending.set(id, { tool: entry.tool, label });
      ws.send(
        JSON.stringify({
          type: "request",
          requestId: id,
          tool: entry.tool,
          params,
        }),
      );
      console.log(`[SEND] ${label}`);
      await sleep(DELAY_MS);
    }

    console.log("=== all tools sent; waiting for remaining responses ===\n");
  })();
});

console.log(`fake server on ws://0.0.0.0:${PORT}`);
console.log(`Running ${TOOL_TESTS.length} tool tests (${DELAY_MS}ms apart)`);
console.log("Dangerous tools: set RUN_SMS=1 or RUN_CALL=1 to include SMS/call tests\n");
