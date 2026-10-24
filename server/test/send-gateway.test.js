import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  GatewayRateLimiter,
  SendGateway,
  attemptStampsFromLog,
  finalText,
  normalizeNumber,
  parseConversations,
  resolveRecipient,
  startSendGateway,
  validateRequest,
} from "../dist/send-gateway.js";
import { ATTRIBUTION_PREFIX } from "../dist/send-text.js";

const TOKEN = "t".repeat(48);
const RAW = [
  { ConversationID: "1", Name: "Rachel", IsGroup: false, LastMessageTS: 300,
    Participants: JSON.stringify([{ name: "Alex", number: "+15550000000", is_me: true }, { name: "Rachel", number: "+15551112222" }]) },
  { ConversationID: "2", Name: "Mom", IsGroup: false, LastMessageTS: 200,
    Participants: JSON.stringify([{ name: "Alex", number: "+15550000000", is_me: true }, { name: "Mom", number: "+15553334444" }]) },
  { ConversationID: "3", Name: "Family", IsGroup: true, LastMessageTS: 100,
    Participants: JSON.stringify([{ name: "Mom", number: "+15553334444" }, { name: "Rachel", number: "+15551112222" }]) },
  { ConversationID: "4", Name: "Rachel", IsGroup: false, LastMessageTS: 50,
    Participants: JSON.stringify([{ name: "Rachel", number: "+15551112222" }]) },
  { ConversationID: "5", Name: "Sam W", IsGroup: false, LastMessageTS: 40,
    Participants: JSON.stringify([{ name: "Sam W", number: "+15556667777" }]) },
  { ConversationID: "6", Name: "Sam W", IsGroup: false, LastMessageTS: 30,
    Participants: JSON.stringify([{ name: "Sam W", number: "+15558889999" }]) },
];
const CONVS = parseConversations(RAW);

function tmpPaths() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "sgw-"));
  const tokenFile = path.join(dir, "send-gateway.token");
  fs.writeFileSync(tokenFile, TOKEN + "\n", { mode: 0o600 });
  return { dir, tokenFile, bridgeKeyFile: path.join(dir, "bridge-send.key"), logFile: path.join(dir, "sent-texts.jsonl") };
}

function fakeBridge(reply = { httpStatus: 200, text: "DELIVERED: ok", isError: false }) {
  const sent = [];
  return {
    sent,
    async listConversations() { return CONVS; },
    async sendMessage(args) { sent.push(args); return reply; },
  };
}

const good = (extra = {}) => ({
  conversation_id: "2",
  message: "running late, there in 10",
  mode: "as_alex",
  authorized_by: "alex",
  authorization_quote: "text my mom that I'm running late, there in 10",
  requested_by: "Grok Bot",
  ...extra,
});

const readLog = (f) => fs.readFileSync(f, "utf8").trim().split("\n").map((l) => JSON.parse(l));

test("normalizeNumber treats 10-digit US numbers as +1", () => {
  assert.equal(normalizeNumber("(555) 333-4444"), "15553334444");
  assert.equal(normalizeNumber("+1 555 333 4444"), "15553334444");
});

test("parseConversations drops is_me and duplicate participants", () => {
  assert.equal(CONVS[0].others.length, 1);
  assert.equal(CONVS[0].others[0].name, "Rachel");
});

test("resolve by conversation_id, phone, name; groups and unknowns rejected", () => {
  assert.equal(resolveRecipient(CONVS, { conversation_id: "2" }).conv.conversationId, "2");
  const g = resolveRecipient(CONVS, { conversation_id: "3" });
  assert.equal(g.ok, false); assert.equal(g.status, "rejected");
  assert.equal(resolveRecipient(CONVS, { conversation_id: "99" }).status, "needs_clarification");
  const p = resolveRecipient(CONVS, { phone_number: "555-111-2222" });
  assert.equal(p.conv.conversationId, "1"); // most recent of 1 and 4
  assert.match(p.note, /most recent/);
  assert.equal(resolveRecipient(CONVS, { phone_number: "+15550001234" }).status, "needs_clarification");
  assert.equal(resolveRecipient(CONVS, { contact_name: "mom" }).conv.conversationId, "2");
  const amb = resolveRecipient(CONVS, { contact_name: "Sam W" });
  assert.equal(amb.status, "needs_clarification"); assert.equal(amb.candidates.length, 2);
  const fuzzy = resolveRecipient(CONVS, { contact_name: "Rach" });
  assert.equal(fuzzy.status, "needs_clarification"); assert.ok(fuzzy.candidates.length >= 1);
});

test("validateRequest enforces the required fields", () => {
  assert.equal(validateRequest(good()).ok, true);
  assert.match(validateRequest(good({ authorized_by: "grok" })).error, /authorized_by/);
  assert.match(validateRequest(good({ authorization_quote: "" })).error, /authorization_quote/);
  assert.match(validateRequest(good({ requested_by: "" })).error, /requested_by/);
  assert.match(validateRequest(good({ mode: "silent" })).error, /mode/);
  assert.match(validateRequest(good({ phone_number: "+15553334444" })).error, /exactly one/);
  assert.match(validateRequest({ ...good(), conversation_id: undefined, phone_number: "+15553334444, +15551112222" }).error, /ONE number/);
  assert.match(validateRequest(good({ conversation_id: ["1", "2"] })).error, /single string/);
  assert.match(validateRequest(good({ recipients: ["1"] })).error, /unknown field/);
  assert.match(validateRequest(good({ skip_rate_limit: true })).error, /unknown field/);
  assert.match(validateRequest(good({ message: "x".repeat(1001) })).error, /longer/);
  assert.match(validateRequest(good({ message: "a\u0000b" })).error, /control/);
});

test("attributed mode adds the prefix once; as_alex is untouched", () => {
  assert.equal(finalText("attributed", "hi"), ATTRIBUTION_PREFIX + "hi");
  assert.equal(finalText("attributed", ATTRIBUTION_PREFIX + "hi"), ATTRIBUTION_PREFIX + "hi");
  assert.equal(finalText("as_alex", "hi"), "hi");
});

test("wrong or missing token is refused and nothing is sent", async () => {
  const p = tmpPaths(); const b = fakeBridge(); const gw = new SendGateway(b, p);
  assert.equal((await gw.handle(undefined, good())).status, "unauthorized");
  assert.equal((await gw.handle("nope", good())).status, "unauthorized");
  assert.equal(b.sent.length, 0);
});

test("token file with loose permissions disables the gateway", async () => {
  const p = tmpPaths(); fs.chmodSync(p.tokenFile, 0o644);
  const b = fakeBridge(); const gw = new SendGateway(b, p);
  assert.equal((await gw.handle(TOKEN, good())).status, "unauthorized");
});

test("dry_run resolves and never calls send", async () => {
  const p = tmpPaths(); const b = fakeBridge(); const gw = new SendGateway(b, p);
  const r = await gw.handle(TOKEN, good({ dry_run: true, mode: "attributed" }));
  assert.equal(r.status, "dry_run_ok");
  assert.equal(r.recipient.conversation_id, "2");
  assert.equal(r.recipient.number, "+*******4444");
  assert.equal(r.final_message, ATTRIBUTION_PREFIX + "running late, there in 10");
  assert.equal(b.sent.length, 0);
  const [e] = readLog(p.logFile);
  assert.equal(e.result, "dry_run_ok"); assert.equal(e.attempted, false); assert.equal(e.channel, "gmessages-gateway");
  assert.equal(e.message_preview.length, 40); assert.equal(e.message_sha256.length, 64);
});

test("real send forwards one conversation with prefix and logs it", async () => {
  const p = tmpPaths(); const b = fakeBridge(); const gw = new SendGateway(b, p);
  const r = await gw.handle(TOKEN, good({ mode: "attributed" }));
  assert.equal(r.status, "delivered");
  assert.deepEqual(b.sent, [{ conversation_id: "2", phone_number: "+15553334444", message: ATTRIBUTION_PREFIX + "running late, there in 10" }]);
  const e = readLog(p.logFile).at(-1);
  assert.equal(e.result, "delivered"); assert.equal(e.attempted, true); assert.equal(e.requested_by, "Grok Bot");
  assert.equal(e.recipient, "Mom"); assert.equal(e.mode, "attributed");
});

test("NOT DELIVERED and bridge 403 are reported as failures", async () => {
  const p = tmpPaths();
  const r1 = await new SendGateway(fakeBridge({ httpStatus: 200, text: "NOT DELIVERED: status=FAILED", isError: true }), p).handle(TOKEN, good());
  assert.equal(r1.ok, false); assert.equal(r1.status, "not_delivered");
  const r2 = await new SendGateway(fakeBridge({ httpStatus: 403, text: "read-only bridge: tool not allowed", isError: true }), tmpPaths()).handle(TOKEN, good());
  assert.equal(r2.status, "bridge_error"); assert.match(r2.error, /send key/);
});

test("unknown phone number gets needs_clarification and is not sent", async () => {
  const p = tmpPaths(); const b = fakeBridge(); const gw = new SendGateway(b, p);
  const r = await gw.handle(TOKEN, { ...good(), conversation_id: undefined, phone_number: "+15550009999" });
  assert.equal(r.status, "needs_clarification"); assert.equal(b.sent.length, 0);
});

test("rate limit: 5 per minute, 30 per day", async () => {
  let now = 1_000_000_000_000;
  const lim = new GatewayRateLimiter(5, 30, () => now);
  for (let i = 0; i < 5; i++) assert.equal(lim.take(), true);
  assert.equal(lim.take(), false);
  for (let i = 0; i < 25; i++) { now += 61_000; assert.equal(lim.take(), true); }
  now += 61_000; assert.equal(lim.take(), false); // 30 in 24h
  now += 24 * 3600_000; assert.equal(lim.take(), true);
});

test("6th send in a minute is rate_limited and dry runs don't count", async () => {
  const p = tmpPaths(); const b = fakeBridge(); const gw = new SendGateway(b, p);
  for (let i = 0; i < 3; i++) assert.equal((await gw.handle(TOKEN, good({ dry_run: true }))).status, "dry_run_ok");
  for (let i = 0; i < 5; i++) assert.equal((await gw.handle(TOKEN, good())).status, "delivered");
  assert.equal((await gw.handle(TOKEN, good())).status, "rate_limited");
  assert.equal(b.sent.length, 5);
});

test("rate state survives restart via the log", async () => {
  const p = tmpPaths(); const b = fakeBridge();
  const gw1 = new SendGateway(b, p);
  for (let i = 0; i < 5; i++) await gw1.handle(TOKEN, good());
  assert.equal(attemptStampsFromLog(p.logFile).length, 5);
  const gw2 = new SendGateway(b, p);
  assert.equal((await gw2.handle(TOKEN, good())).status, "rate_limited");
});

test("HTTP server: /health, auth header, status codes, body limit", async () => {
  const p = tmpPaths(); const b = fakeBridge();
  const server = await startSendGateway(new SendGateway(b, p), 0);
  const port = server.address().port;
  const call = (method, pathName, body, headers = {}) => new Promise((resolve, reject) => {
    const payload = body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body);
    const req = http.request({ host: "127.0.0.1", port, path: pathName, method, headers: { "Content-Type": "application/json", ...headers } }, (res) => {
      const c = []; res.on("data", (d) => c.push(d)); res.on("end", () => resolve({ code: res.statusCode, body: JSON.parse(Buffer.concat(c).toString() || "{}") }));
    });
    req.on("error", reject); if (payload) req.write(payload); req.end();
  });
  try {
    assert.equal((await call("GET", "/health")).code, 200);
    assert.equal((await call("POST", "/send", good({ dry_run: true }))).code, 401);
    const ok = await call("POST", "/send", good({ dry_run: true }), { Authorization: `Bearer ${TOKEN}` });
    assert.equal(ok.code, 200); assert.equal(ok.body.status, "dry_run_ok");
    const alt = await call("POST", "/send", good({ dry_run: true }), { "X-PhonePi-Send-Token": TOKEN });
    assert.equal(alt.code, 200);
    assert.equal((await call("POST", "/send", good({ authorized_by: "me", dry_run: true }), { Authorization: `Bearer ${TOKEN}` })).code, 400);
    assert.equal((await call("POST", "/send", { ...good(), conversation_id: "99", dry_run: true }, { Authorization: `Bearer ${TOKEN}` })).code, 422);
    assert.equal((await call("POST", "/send", "x".repeat(20000), { Authorization: `Bearer ${TOKEN}` })).code, 413);
    assert.equal((await call("POST", "/api/tool", {})).code, 404);
    assert.equal(b.sent.length, 0);
  } finally {
    server.close();
  }
});
