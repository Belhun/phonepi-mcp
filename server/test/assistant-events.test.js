import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "path";
import test from "node:test";
import {
  ASSISTANT_MAX_ENTRIES,
  AssistantStore,
  pickRowsToDrop,
  validateSendNotificationArgs,
} from "../dist/assistant-events.js";
import { CommandRateLimiter } from "../dist/command-rate-limit.js";
import { isPhoneCommand, validatePhoneCommandArgs } from "../dist/commands.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";
import { TEST_READ_AUTH, readHeaders } from "./read-auth-fixture.js";
import {
  isReadOnlyHttpTool,
  READ_ONLY_HTTP_TOOLS,
  READ_ONLY_TOOL_RATE_LIMITS,
} from "../dist/read-only-tools.js";

const NOW = Date.UTC(2026, 9, 25, 12, 0, 0);

function tempStore(nowRef = { t: NOW }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-assistant-"));
  const store = new AssistantStore(dir, 30, () => nowRef.t);
  return { dir, store, nowRef };
}

function postTool(port, body, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body);
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: "/api/tool",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(payload),
          ...extraHeaders,
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          resolve({ status: res.statusCode, json: JSON.parse(raw), raw });
        });
      }
    );
    req.on("error", reject);
    req.end(payload);
  });
}

function postCommand(port, body) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body);
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: "/api/command",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(payload),
          Authorization: "Bearer test-token",
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          resolve({ status: res.statusCode, json: JSON.parse(raw), raw });
        });
      }
    );
    req.on("error", reject);
    req.end(payload);
  });
}

test("send_notification is an allowlisted command; replies and prompts are read-only tools", () => {
  assert.equal(isPhoneCommand("send_notification"), true);
  assert.equal(isReadOnlyHttpTool("get_notification_replies"), true);
  assert.equal(isReadOnlyHttpTool("get_inbound_prompts"), true);
  assert.equal(isReadOnlyHttpTool("send_notification"), false);
  assert.equal(READ_ONLY_HTTP_TOOLS.has("get_notification_replies"), true);
  assert.equal(READ_ONLY_TOOL_RATE_LIMITS.get_notification_replies, 60);
  assert.equal(READ_ONLY_TOOL_RATE_LIMITS.get_inbound_prompts, 60);
});

test("send_notification validation: actions cap 3, requestId, high default", () => {
  const ok = validateSendNotificationArgs({
    title: "Dinner?",
    body: "Leave in 10?",
    requestId: "notif-aaaa",
    actions: ["OK", "Snooze 1h", "No"],
  });
  assert.equal(ok.ok, true);
  assert.equal(ok.args.priority, "high");
  assert.match(ok.args.title, /^From assistant:/);
  assert.deepEqual(ok.args.actions, ["OK", "Snooze 1h", "No"]);

  const tooMany = validatePhoneCommandArgs("send_notification", {
    title: "X",
    body: "Y",
    actions: ["A", "B", "C", "D"],
  });
  assert.equal(tooMany.ok, false);
  assert.match(tooMany.error, /at most 3/);

  const badId = validateSendNotificationArgs({
    title: "X",
    body: "Y",
    requestId: "short",
  });
  assert.equal(badId.ok, false);

  const missing = validateSendNotificationArgs({ title: "X" });
  assert.equal(missing.ok, false);
});

test("stores replies and prompts in 600 files, rejects secrets, dedupes by id", () => {
  const { dir, store } = tempStore();
  const first = store.ingestReply({
    id: "reply-aaaa",
    messageId: "notif-aaaa",
    action: "Yes",
    replyText: null,
    ts: NOW - 1_000,
  });
  assert.equal(first.duplicate, false);
  const again = store.ingestReply({
    id: "reply-aaaa",
    messageId: "notif-aaaa",
    action: "Yes",
    ts: NOW,
  });
  assert.equal(again.duplicate, true);
  const secret = store.ingestReply({
    id: "reply-bbbb",
    messageId: "notif-aaaa",
    action: "No",
    ts: NOW,
    token: "super-secret-token",
  });
  assert.equal(secret.accepted.length, 0);
  assert.match(secret.rejected[0].error, /secret field/);

  const prompt = store.ingestPrompt({
    id: "prompt-aaaa",
    text: "What's on my calendar",
    source: "qs_tile",
    ts: NOW,
  });
  assert.equal(prompt.duplicate, false);

  assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
  const replyFile = path.join(dir, "replies", "2026-10.jsonl");
  assert.equal(fs.statSync(replyFile).mode & 0o777, 0o600);
  const lines = fs.readFileSync(replyFile, "utf8").trim().split("\n");
  assert.equal(lines.length, 1);
  const row = JSON.parse(lines[0]);
  assert.equal(row.messageId, "notif-aaaa");
  assert.equal(row.action, "Yes");
  assert.equal(Object.hasOwn(row, "token"), false);

  const replies = store.queryReplies({ messageId: "notif-aaaa" });
  assert.equal(replies.length, 1);
  assert.equal(replies[0].action, "Yes");
  const prompts = store.queryPrompts({});
  assert.equal(prompts.length, 1);
  assert.equal(prompts[0].source, "qs_tile");
});

test("HTTP /api/tool serves replies and prompts while the phone is offline", async () => {
  const { store } = tempStore();
  store.ingestReply({
    id: "reply-cccccc",
    messageId: "notif-cccc",
    action: "reply",
    replyText: "On my way",
    ts: NOW,
  });
  store.ingestPrompt({
    id: "prompt-cccccc",
    text: "Ask the assistant about traffic",
    source: "shortcut",
    ts: NOW,
  });
  let phoneCalls = 0;
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    bridge: {
      isPhoneConnected: () => false,
      callPhoneTool: async () => {
        phoneCalls += 1;
        throw new Error("should not call the phone");
      },
    },
    assistantStore: store,
    readAuth: TEST_READ_AUTH,
  });
  assert.ok(started);
  try {
    const replies = await postTool(
      started.port,
      {
        tool: "get_notification_replies",
        arguments: { messageId: "notif-cccc" },
      },
      readHeaders()
    );
    assert.equal(replies.status, 200);
    const replyBody = JSON.parse(replies.json.text);
    assert.equal(replyBody.status, "success");
    assert.equal(replyBody.count, 1);
    assert.equal(replyBody.replies[0].action.untrusted, true);
    assert.match(replyBody.replies[0].action.text, /reply/);
    assert.equal(replyBody.replies[0].untrusted, true);
    assert.match(replyBody.replies[0].replyText.text, /On my way/);

    const prompts = await postTool(
      started.port,
      {
        tool: "get_inbound_prompts",
        arguments: {},
      },
      readHeaders()
    );
    assert.equal(prompts.status, 200);
    const promptBody = JSON.parse(prompts.json.text);
    assert.equal(promptBody.prompts[0].untrusted, true);
    assert.match(promptBody.prompts[0].text.text, /Ask the assistant about traffic/);
    assert.equal(phoneCalls, 0);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("POST /api/command send_notification validates then calls the phone", async () => {
  const calls = [];
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    bridge: {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push({ name, args });
        return { status: "success", messageId: args.requestId, duplicate: false };
      },
    },
    commandToken: "test-token",
  });
  assert.ok(started);
  try {
    const bad = await postCommand(started.port, {
      command: "send_notification",
      arguments: { title: "Hi", actions: ["A", "B", "C", "D"] },
    });
    assert.equal(bad.status, 400);
    assert.equal(calls.length, 0);

    const ok = await postCommand(started.port, {
      command: "send_notification",
      arguments: {
        title: "Leave soon?",
        body: "Dinner in 20",
        requestId: "notif-dddd",
        actions: ["OK", "Snooze 1h"],
      },
    });
    assert.equal(ok.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, "send_notification");
    assert.equal(calls[0].args.requestId, "notif-dddd");
    assert.match(calls[0].args.title, /^From assistant:/);
    assert.deepEqual(calls[0].args.actions, ["OK", "Snooze 1h"]);
    assert.equal(calls[0].args.priority, "high");
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("GET /api/health includes hookFailed and never the wake URL or secret", async () => {
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    bridge: {
      isPhoneConnected: () => false,
      callPhoneTool: async () => ({}),
    },
    wakeHookStatus: () => ({ wakeHookEnabled: true, hookFailed: 4 }),
  });
  assert.ok(started);
  try {
    const res = await new Promise((resolve, reject) => {
      const req = http.request(
        { host: "127.0.0.1", port: started.port, path: "/api/health", method: "GET" },
        (r) => {
          const chunks = [];
          r.on("data", (c) => chunks.push(c));
          r.on("end", () =>
            resolve({
              status: r.statusCode,
              json: JSON.parse(Buffer.concat(chunks).toString("utf8")),
              raw: Buffer.concat(chunks).toString("utf8"),
            })
          );
        }
      );
      req.on("error", reject);
      req.end();
    });
    assert.equal(res.status, 200);
    assert.equal(res.json.wakeHookEnabled, true);
    assert.equal(res.json.hookFailed, 4);
    assert.equal(res.raw.includes("secret"), false);
    assert.equal(res.raw.toLowerCase().includes("http://"), false);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});

test("assistant cap drops oldest rows instead of rejecting new ones", () => {
  let t = NOW;
  const limiter = new CommandRateLimiter(1, () => t);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-assistant-cap-"));
  const store = new AssistantStore(dir, 30, () => NOW, limiter);
  for (let i = 0; i < ASSISTANT_MAX_ENTRIES; i += 1) {
    t += 2;
    const id = `prompt-${String(i).padStart(8, "0")}`;
    const result = store.ingestPrompt({
      id,
      text: `prompt ${i}`,
      source: "app",
      ts: NOW - (ASSISTANT_MAX_ENTRIES - i) * 1000,
    });
    assert.equal(result.accepted[0], id, id);
  }
  t += 2;
  const overflow = store.ingestPrompt({
    id: "prompt-newest01",
    text: "newest",
    source: "app",
    ts: NOW,
  });
  assert.equal(overflow.accepted[0], "prompt-newest01");
  assert.equal(overflow.rejected.length, 0);
  assert.equal(overflow.droppedOldest, 1);
  const rows = store.queryPrompts({ limit: ASSISTANT_MAX_ENTRIES });
  assert.equal(rows.length, ASSISTANT_MAX_ENTRIES);
  assert.equal(rows[rows.length - 1].id, "prompt-newest01");
  assert.equal(
    rows.some((row) => row.id === "prompt-00000000"),
    false
  );
});

test("assistant rate-limit rejection is retryable", () => {
  let t = NOW;
  const limiter = new CommandRateLimiter(60_000, () => t);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-assistant-rl-"));
  const store = new AssistantStore(dir, 30, () => t, limiter);
  for (let i = 0; i < 30; i += 1) {
    const result = store.ingestPrompt({
      id: `prompt-rl${String(i).padStart(6, "0")}`,
      text: `n${i}`,
      source: "app",
      ts: t,
    });
    assert.equal(result.rejected.length, 0);
  }
  const limited = store.ingestPrompt({
    id: "prompt-rl-over",
    text: "later",
    source: "app",
    ts: t,
  });
  assert.equal(limited.accepted.length, 0);
  assert.equal(limited.rejected[0].error, "rate limited");
  assert.equal(limited.rejected[0].retryable, true);
  assert.equal(limited.rejected[0].id, "prompt-rl-over");
  assert.equal(typeof limited.rejected[0].retryAfterSec, "number");
});

test("duplicate prompt ids skip the ingest limiter", () => {
  let t = NOW;
  const limiter = new CommandRateLimiter(60_000, () => t);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-assistant-dup-"));
  const store = new AssistantStore(dir, 30, () => t, limiter);
  for (let i = 0; i < 30; i += 1) {
    const result = store.ingestPrompt({
      id: `prompt-dup${String(i).padStart(5, "0")}`,
      text: `n${i}`,
      source: "app",
      ts: t,
    });
    assert.equal(result.rejected.length, 0);
  }
  const resend = store.ingestPrompt({
    id: "prompt-dup00000",
    text: "n0",
    source: "app",
    ts: t,
  });
  assert.equal(resend.duplicate, true);
  assert.equal(resend.rejected.length, 0);
  const extra = store.ingestPrompt({
    id: "prompt-dup-extra",
    text: "extra",
    source: "app",
    ts: t,
  });
  assert.equal(extra.rejected[0].retryable, true);
});

test("40 queued prompts converge: resends of stored ids do not consume budget", () => {
  let t = NOW;
  const limiter = new CommandRateLimiter(60_000, () => t);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-assistant-40-"));
  const store = new AssistantStore(dir, 30, () => t, limiter);
  const sends = [];
  for (let i = 0; i < 40; i += 1) {
    const id = `prompt-q${String(i).padStart(5, "0")}`;
    const result = store.ingestPrompt({
      id,
      text: `p${i}`,
      source: "app",
      ts: t,
    });
    sends.push(result);
  }
  const accepted = sends.filter((row) => row.accepted.length && !row.duplicate).length;
  const limited = sends.filter((row) => row.rejected[0]?.retryable).length;
  assert.equal(accepted, 30);
  assert.equal(limited, 10);
  let resendSends = 0;
  for (let i = 0; i < 30; i += 1) {
    const result = store.ingestPrompt({
      id: `prompt-q${String(i).padStart(5, "0")}`,
      text: `p${i}`,
      source: "app",
      ts: t,
    });
    resendSends += 1;
    assert.equal(result.duplicate, true);
    assert.equal(result.rejected.length, 0);
  }
  t += 60_000;
  let recovered = 0;
  for (let i = 30; i < 40; i += 1) {
    const result = store.ingestPrompt({
      id: `prompt-q${String(i).padStart(5, "0")}`,
      text: `p${i}`,
      source: "app",
      ts: t,
    });
    recovered += 1;
    assert.equal(result.accepted[0], `prompt-q${String(i).padStart(5, "0")}`);
  }
  assert.equal(resendSends, 30);
  assert.equal(recovered, 10);
  assert.equal(store.queryPrompts({ limit: 200 }).length, 40);
});

test("cap drop prefers rows a bot already read", () => {
  const rows = [
    { id: "a", ts: 1 },
    { id: "b", ts: 2 },
    { id: "c", ts: 3 },
  ];
  const dropped = pickRowsToDrop(rows, 1, new Set(["c"]));
  assert.deepEqual(
    dropped.map((row) => row.id),
    ["c"]
  );
});

test("assistant rewrite writes new files then swaps", () => {
  let t = NOW;
  const limiter = new CommandRateLimiter(1, () => t);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "phonepi-assistant-swap-"));
  const store = new AssistantStore(dir, 30, () => NOW, limiter);
  for (let i = 0; i < ASSISTANT_MAX_ENTRIES; i += 1) {
    t += 2;
    store.ingestPrompt({
      id: `prompt-s${String(i).padStart(7, "0")}`,
      text: `s${i}`,
      source: "app",
      ts: NOW - (ASSISTANT_MAX_ENTRIES - i) * 1000,
    });
  }
  t += 2;
  store.ingestPrompt({
    id: "prompt-snewest1",
    text: "newest",
    source: "app",
    ts: NOW,
  });
  const promptDir = path.join(dir, "prompts");
  assert.equal(fs.existsSync(`${promptDir}.next`), false);
  assert.equal(fs.existsSync(`${promptDir}.bak`), false);
  assert.equal(fs.existsSync(promptDir), true);
  const files = fs.readdirSync(promptDir).filter((name) => name.endsWith(".jsonl"));
  assert.ok(files.length >= 1);
});

