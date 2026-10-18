import assert from "node:assert/strict";
import test from "node:test";
import {
  ALARM_LABEL_MAX_LENGTH,
  TIMER_MAX_SECONDS,
  getPhoneCommand,
  isPhoneCommand,
  validatePhoneCommandArgs,
} from "../dist/commands.js";
import { isReadOnlyHttpTool } from "../dist/read-only-tools.js";
import { startLoopbackHttp } from "../dist/loopback-http.js";
import http from "node:http";

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

test("alarm and timer commands are allowlisted actions at 10 per minute", () => {
  for (const name of ["set_alarm", "set_timer", "dismiss_alarm", "dismiss_timer"]) {
    assert.equal(isPhoneCommand(name), true, name);
    assert.equal(isReadOnlyHttpTool(name), false, name);
    const spec = getPhoneCommand(name);
    assert.equal(spec?.kind, "action", name);
    assert.equal(spec?.maxPerMinute, 10, name);
    assert.equal(spec?.phoneTool, name, name);
  }
  assert.equal(isPhoneCommand("list_alarms"), false);
  assert.equal(isReadOnlyHttpTool("list_alarms"), true);
});

test("set_alarm accepts 6:30 AM weekdays labeled Work", () => {
  const result = validatePhoneCommandArgs("set_alarm", {
    hour: 6,
    minute: 30,
    label: "Work",
    days: "weekdays",
    period: "am",
  });
  assert.equal(result.ok, true);
  assert.deepEqual(result.args, {
    hour: 6,
    minute: 30,
    message: "Work",
    days: [2, 3, 4, 5, 6],
    skipUi: true,
  });
});

test("set_alarm rejects hour, minute, and label bounds", () => {
  assert.equal(
    validatePhoneCommandArgs("set_alarm", { minute: 0 }).ok,
    false
  );
  assert.match(
    validatePhoneCommandArgs("set_alarm", { hour: 24, minute: 0 }).error,
    /hour must be an integer from 0 to 23/
  );
  assert.match(
    validatePhoneCommandArgs("set_alarm", { hour: -1, minute: 0 }).error,
    /hour must be an integer from 0 to 23/
  );
  assert.match(
    validatePhoneCommandArgs("set_alarm", { hour: 6, minute: 60 }).error,
    /minute must be an integer from 0 to 59/
  );
  assert.match(
    validatePhoneCommandArgs("set_alarm", { hour: 6, minute: -1 }).error,
    /minute must be an integer from 0 to 59/
  );
  assert.match(
    validatePhoneCommandArgs("set_alarm", { hour: 6.5, minute: 0 }).error,
    /hour must be an integer from 0 to 23/
  );
  const longLabel = "W".repeat(ALARM_LABEL_MAX_LENGTH + 1);
  assert.match(
    validatePhoneCommandArgs("set_alarm", {
      hour: 6,
      minute: 30,
      message: longLabel,
    }).error,
    /at most 100 characters/
  );
  const maxLabel = "W".repeat(ALARM_LABEL_MAX_LENGTH);
  const ok = validatePhoneCommandArgs("set_alarm", {
    hour: 18,
    minutes: 0,
    message: maxLabel,
  });
  assert.equal(ok.ok, true);
  assert.equal(ok.args.message, maxLabel);
  assert.equal(ok.args.hour, 18);
  assert.equal(ok.args.minute, 0);
  assert.equal(ok.args.days, undefined);
});

test("set_alarm maps pm and rejects unknown days", () => {
  const pm = validatePhoneCommandArgs("set_alarm", {
    hour: 6,
    minute: 30,
    period: "PM",
    days: ["mon", "monday", "fri"],
  });
  assert.equal(pm.ok, true);
  assert.equal(pm.args.hour, 18);
  assert.deepEqual(pm.args.days, [2, 6]);

  const noon = validatePhoneCommandArgs("set_alarm", {
    hour: 12,
    minute: 0,
    period: "am",
  });
  assert.equal(noon.args.hour, 0);

  assert.match(
    validatePhoneCommandArgs("set_alarm", {
      hour: 6,
      minute: 0,
      days: ["funday"],
    }).error,
    /days must be/
  );
  const daily = validatePhoneCommandArgs("set_alarm", {
    hour: 7,
    minute: 15,
    days: "daily",
  });
  assert.deepEqual(daily.args.days, [1, 2, 3, 4, 5, 6, 7]);
});

test("set_timer accepts 1 second through 24 hours and rejects the rest", () => {
  assert.equal(TIMER_MAX_SECONDS, 86400);
  const min = validatePhoneCommandArgs("set_timer", { seconds: 1, label: "Tea" });
  assert.equal(min.ok, true);
  assert.deepEqual(min.args, { seconds: 1, message: "Tea", skipUi: true });

  const max = validatePhoneCommandArgs("set_timer", { seconds: 86400 });
  assert.equal(max.ok, true);
  assert.equal(max.args.seconds, 86400);
  assert.equal(max.args.message, undefined);

  for (const seconds of [0, -1, 86401, 15.5, "soon"]) {
    const result = validatePhoneCommandArgs("set_timer", { seconds });
    assert.equal(result.ok, false, String(seconds));
    assert.match(result.error, /seconds must be an integer from 1 to 86400/);
  }
  assert.match(
    validatePhoneCommandArgs("set_timer", {}).error,
    /seconds must be an integer from 1 to 86400/
  );
  const fifteen = validatePhoneCommandArgs("set_timer", { seconds: 15 * 60 });
  assert.equal(fifteen.args.seconds, 900);
});

test("dismiss validates time or label and refuses dismiss-all", () => {
  const byTime = validatePhoneCommandArgs("dismiss_alarm", {
    search: "time",
    hour: 6,
    minute: 30,
    period: "pm",
  });
  assert.equal(byTime.ok, true);
  assert.deepEqual(byTime.args, { search: "time", hour: 18, minute: 30 });

  const byLabel = validatePhoneCommandArgs("dismiss_alarm", {
    search: "label",
    label: "Work",
  });
  assert.deepEqual(byLabel.args, { search: "label", message: "Work" });

  assert.match(
    validatePhoneCommandArgs("dismiss_alarm", { search: "all" }).error,
    /search must be time or label/
  );
  assert.match(
    validatePhoneCommandArgs("dismiss_alarm", { search: "time", hour: 6 }).error,
    /minute must be an integer from 0 to 59/
  );
  assert.match(
    validatePhoneCommandArgs("dismiss_alarm", { search: "label" }).error,
    /message/
  );

  const timer = validatePhoneCommandArgs("dismiss_timer", { message: "Tea" });
  assert.deepEqual(timer.args, { message: "Tea" });
  const bare = validatePhoneCommandArgs("dismiss_timer", {});
  assert.equal(bare.ok, true);
  assert.deepEqual(bare.args, {});
});

test("other commands pass arguments through", () => {
  const result = validatePhoneCommandArgs("phone_navigate", { name: "Home" });
  assert.deepEqual(result, { ok: true, args: { name: "Home" } });
});

test("POST /api/command rejects invalid alarm args before calling the phone", async () => {
  const calls = [];
  const started = await startLoopbackHttp({
    port: 0,
    wsPort: 11041,
    bridge: {
      isPhoneConnected: () => true,
      callPhoneTool: async (name, args) => {
        calls.push({ name, args });
        return { status: "success", started: true };
      },
    },
    commandToken: "test-token",
  });
  assert.ok(started);
  try {
    const bad = await postCommand(started.port, {
      command: "set_alarm",
      arguments: { hour: 99, minute: 0, label: "Work" },
    });
    assert.equal(bad.status, 400);
    assert.equal(bad.json.isError, true);
    assert.match(bad.json.text, /hour must be an integer from 0 to 23/);
    assert.equal(calls.length, 0);
    assert.equal(bad.raw.includes("test-token"), false);

    const good = await postCommand(started.port, {
      command: "set_timer",
      arguments: { seconds: 900, label: "Pasta" },
    });
    assert.equal(good.status, 200);
    assert.equal(good.json.isError, false);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, "set_timer");
    assert.deepEqual(calls[0].args, {
      seconds: 900,
      message: "Pasta",
      skipUi: true,
    });
    const payload = JSON.parse(good.json.text);
    assert.equal(payload.status, "success");
  } finally {
    await new Promise((resolve) => started.server.close(resolve));
  }
});
