import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { isPhoneCommand, validatePhoneCommandArgs } from "../dist/commands.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";
import {
  ATTRIBUTION_PREFIX,
  SendTextLedger,
  SendTextRateLimiter,
  appendSendLog,
  expectedCarrierText,
  maskPhone,
  prepareSendText,
} from "../dist/send-text.js";

const logFile = path.join(os.tmpdir(), `phonepi-sent-texts-${process.pid}.jsonl`);
process.env.PHONEPI_SEND_LOG = logFile;

function postCommand(port, body, headers = {}) {
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
          ...headers,
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          let json = null;
          try {
            json = JSON.parse(raw);
          } catch {
            json = null;
          }
          resolve({ status: res.statusCode, json, raw });
        });
      }
    );
    req.on("error", reject);
    req.end(payload);
  });
}

test("request_send_text and get_send_status are allowlisted commands", () => {
  assert.equal(isPhoneCommand("request_send_text"), true);
  assert.equal(isPhoneCommand("get_send_status"), true);
  assert.equal(isPhoneCommand("send_sms"), false);
  assert.equal(isPhoneCommand("gm_send_message"), false);
  assert.equal(isPhoneCommand("make_call"), false);
});

test("validation rejects empty, long, grouped, and bypass fields", () => {
  assert.equal(
    validatePhoneCommandArgs("request_send_text", {
      recipient: "Mom",
      message: "On my way",
    }).ok,
    true
  );
  const grouped = validatePhoneCommandArgs("request_send_text", {
    recipient: "Mom, Dad",
    message: "Hi",
  });
  assert.equal(grouped.ok, false);
  const bypass = validatePhoneCommandArgs("request_send_text", {
    recipient: "Mom",
    message: "Hi",
    confirmed: true,
  });
  assert.equal(bypass.ok, false);
  assert.match(bypass.error, /cannot be set by the bot/);
  assert.equal(
    validatePhoneCommandArgs("request_send_text", {
      recipient: "Mom",
      message: "",
    }).ok,
    false
  );
  assert.equal(
    validatePhoneCommandArgs("request_send_text", {
      recipient: "Mom",
      message: "x".repeat(1001),
    }).ok,
    false
  );
  assert.equal(
    validatePhoneCommandArgs("request_send_text", {
      recipient: "Mom",
      message: "Hi",
      mode: "secret",
    }).ok,
    false
  );
  const status = validatePhoneCommandArgs("get_send_status", { id: "send-123456" });
  assert.equal(status.ok, true);
  assert.deepEqual(status.args, { id: "send-123456" });
  assert.equal(validatePhoneCommandArgs("get_send_status", {}).ok, false);
});

test("server forwards the raw message and does not add the prefix", () => {
  const asAlex = prepareSendText({
    recipient: "Mom",
    message: `${ATTRIBUTION_PREFIX}I am Alex`,
  });
  assert.equal(asAlex.ok, true);
  assert.equal(asAlex.value.mode, "as_alex");
  assert.equal(asAlex.value.requiresConfirmation, true);
  assert.equal(asAlex.value.message, `${ATTRIBUTION_PREFIX}I am Alex`);
  assert.equal(
    expectedCarrierText(asAlex.value.mode, asAlex.value.message),
    `${ATTRIBUTION_PREFIX}I am Alex`
  );

  const attributed = prepareSendText({
    recipient: "Mom",
    message: "Running late",
    mode: "attributed",
  });
  assert.equal(attributed.ok, true);
  assert.equal(attributed.value.requiresConfirmation, false);
  assert.equal(attributed.value.message, "Running late");
  assert.equal(
    expectedCarrierText("attributed", attributed.value.message).startsWith(ATTRIBUTION_PREFIX),
    true
  );
  assert.equal(
    expectedCarrierText("attributed", `${ATTRIBUTION_PREFIX}Running late`),
    `${ATTRIBUTION_PREFIX}Running late`
  );
});

test("as_alex never sends without confirmation, and cancel or expiry is final", () => {
  const ledger = new SendTextLedger();
  const created = ledger.request({
    id: "send-asalex",
    mode: "as_alex",
    recipientName: "Mom",
    numberMasked: maskPhone("+15551234567"),
    message: "On my way",
    now: 1_000,
  });
  assert.equal(created.send, false);
  assert.equal(created.record.status, "pending");
  assert.equal(created.record.sent, false);

  const blocked = ledger.apply(
    "send-asalex",
    { type: "attributed_send", carrierText: `${ATTRIBUTION_PREFIX}On my way` },
    2_000
  );
  assert.equal(blocked.send, false);
  assert.equal(blocked.record.status, "pending");

  const sent = ledger.apply("send-asalex", { type: "confirm", method: "tap" }, 3_000);
  assert.equal(sent.send, true);
  assert.equal(sent.record.status, "sent");
  assert.equal(sent.record.confirmMethod, "tap");
  assert.equal(sent.carrierText, "On my way");

  const again = ledger.apply("send-asalex", { type: "confirm", method: "spoken" }, 4_000);
  assert.equal(again.send, false);
  assert.equal(again.record.status, "sent");

  const spoken = new SendTextLedger();
  spoken.request({
    id: "send-spoken",
    mode: "as_alex",
    recipientName: "Mom",
    numberMasked: "***4567",
    message: "Here",
    now: 0,
  });
  const said = spoken.apply("send-spoken", { type: "confirm", method: "spoken" }, 10);
  assert.equal(said.send, true);
  assert.equal(said.record.confirmMethod, "spoken");

  const cancelled = new SendTextLedger();
  cancelled.request({
    id: "send-cancel",
    mode: "as_alex",
    recipientName: "Mom",
    numberMasked: "***4567",
    message: "Nope",
    now: 0,
  });
  const didCancel = cancelled.apply("send-cancel", { type: "cancel" }, 10);
  assert.equal(didCancel.send, false);
  assert.equal(didCancel.record.status, "cancelled");
  const afterCancel = cancelled.apply("send-cancel", { type: "confirm", method: "tap" }, 20);
  assert.equal(afterCancel.send, false);
  assert.equal(afterCancel.record.status, "cancelled");

  const expired = new SendTextLedger();
  expired.request({
    id: "send-expire",
    mode: "as_alex",
    recipientName: "Mom",
    numberMasked: "***4567",
    message: "Late",
    now: 0,
  });
  const tooLate = expired.apply(
    "send-expire",
    { type: "confirm", method: "tap" },
    5 * 60 * 1000
  );
  assert.equal(tooLate.send, false);
  assert.equal(tooLate.record.status, "expired");
  const retry = expired.apply("send-expire", { type: "confirm", method: "spoken" }, 5 * 60 * 1000 + 1);
  assert.equal(retry.send, false);
  assert.equal(retry.record.status, "expired");
});

test("attributed sends only when the carrier text has the prefix", () => {
  const ledger = new SendTextLedger();
  ledger.request({
    id: "send-attr",
    mode: "attributed",
    recipientName: "Mom",
    numberMasked: "***4567",
    message: "Traffic is slow",
    now: 0,
  });
  const missing = ledger.apply(
    "send-attr",
    { type: "attributed_send", carrierText: "Traffic is slow" },
    10
  );
  assert.equal(missing.send, false);
  assert.equal(missing.record.status, "failed");

  const again = new SendTextLedger();
  const pending = again.request({
    id: "send-attr-ok",
    mode: "attributed",
    recipientName: "Mom",
    numberMasked: "***4567",
    message: "Traffic is slow",
    now: 0,
  });
  assert.equal(pending.send, false);
  const body = expectedCarrierText("attributed", "Traffic is slow");
  assert.equal(body.startsWith(ATTRIBUTION_PREFIX), true);
  const sent = again.apply("send-attr-ok", { type: "attributed_send", carrierText: body }, 10);
  assert.equal(sent.send, true);
  assert.equal(sent.record.confirmMethod, "attributed");
  assert.equal(sent.record.message.startsWith(ATTRIBUTION_PREFIX), true);
  const second = again.apply("send-attr-ok", { type: "attributed_send", carrierText: body }, 20);
  assert.equal(second.send, false);
});

test("rate limits are per mode, 5 a minute, and the daily caps differ", () => {
  let now = 10_000;
  const limiter = new SendTextRateLimiter(() => now);
  for (let i = 0; i < 5; i += 1) {
    assert.equal(limiter.allow("as_alex").ok, true);
  }
  const minute = limiter.allow("as_alex");
  assert.equal(minute.ok, false);
  assert.equal(minute.scope, "minute");
  assert.equal(limiter.allow("attributed").ok, true);

  now += 60_000;
  for (let i = 0; i < 25; i += 1) {
    now += 60_000;
    assert.equal(limiter.allow("as_alex").ok, true, `as_alex hit ${i}`);
  }
  const day = limiter.allow("as_alex");
  assert.equal(day.ok, false);
  assert.equal(day.scope, "day");

  const attributed = new SendTextRateLimiter(() => now);
  for (let i = 0; i < 20; i += 1) {
    now += 60_000;
    assert.equal(attributed.allow("attributed").ok, true);
  }
  const attributedDay = attributed.allow("attributed");
  assert.equal(attributedDay.ok, false);
  assert.equal(attributedDay.scope, "day");
});

test("send log keeps the message and drops any token", () => {
  fs.rmSync(logFile, { force: true });
  appendSendLog(
    {
      timestamp: "2026-10-18T20:00:00.000Z",
      id: "send-log-1",
      recipientName: "Mom",
      numberMasked: maskPhone("+15551234567"),
      message: "On my way",
      outcome: "pending",
      confirmMethod: null,
      mode: "as_alex",
      token: "super-secret-token",
      number: "+15551234567",
    },
    logFile
  );
  const line = fs.readFileSync(logFile, "utf8").trim();
  const parsed = JSON.parse(line);
  assert.equal(parsed.outcome, "pending");
  assert.equal(parsed.message, "On my way");
  assert.equal(parsed.recipientName, "Mom");
  assert.equal(parsed.numberMasked.includes("1234") || parsed.numberMasked.endsWith("4567"), true);
  assert.equal(JSON.stringify(parsed).includes("super-secret-token"), false);
  assert.equal(JSON.stringify(parsed).includes("+15551234567"), false);
  assert.equal(parsed.token, undefined);
});

test("HTTP request_send_text does not send and does not rewrite a pending result", async () => {
  const calls = [];
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    commandToken: "test-token",
    bridge: {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push({ name, args });
        return {
          status: "pending",
          id: "send-http-1",
          sent: false,
          mode: args.mode,
          message: args.message,
        };
      },
    },
  });
  try {
    const res = await postCommand(started.port, {
      command: "request_send_text",
      arguments: {
        recipient: "Mom",
        message: "Pick up milk",
        mode: "as_alex",
        skipConfirm: true,
      },
    });
    assert.equal(res.status, 400);
    assert.equal(calls.length, 0);

    const ok = await postCommand(started.port, {
      command: "request_send_text",
      arguments: { recipient: "Mom", message: "Pick up milk" },
    });
    assert.equal(ok.status, 200);
    const body = JSON.parse(ok.json.text);
    assert.equal(body.status, "pending");
    assert.equal(body.sent, false);
    assert.notEqual(body.status, "sent");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, "request_send_text");
    assert.equal(calls[0].args.message, "Pick up milk");
    assert.equal(calls[0].args.mode, "as_alex");
    assert.equal(Object.hasOwn(calls[0].args, "skipConfirm"), false);
    assert.equal(calls[0].args.message.startsWith(ATTRIBUTION_PREFIX), false);

    const attributed = await postCommand(started.port, {
      command: "request_send_text",
      arguments: {
        recipient: "Mom",
        message: "Running late",
        mode: "attributed",
      },
    });
    assert.equal(attributed.status, 200);
    assert.equal(calls[1].args.mode, "attributed");
    assert.equal(calls[1].args.message, "Running late");
    assert.equal(calls[1].args.message.startsWith(ATTRIBUTION_PREFIX), false);
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});
