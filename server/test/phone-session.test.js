import assert from "node:assert/strict";
import test from "node:test";
import {
  bindPhoneConnection,
  parsePhonePermissions,
} from "../dist/phone-session.js";
import { PhoneSession } from "../dist/phone-session.js";

function fakeSocket() {
  const listeners = { close: [], error: [], message: [] };
  const socket = {
    readyState: 1,
    close(code, reason) {
      socket.readyState = 2;
      queueMicrotask(() => {
        socket.readyState = 3;
        for (const fn of listeners.close) {
          fn(code ?? 1000, Buffer.from(String(reason ?? "")));
        }
      });
    },
    terminate() {
      socket.close(1006, "terminated");
    },
    send() {},
    on(event, fn) {
      listeners[event].push(fn);
      return socket;
    },
    emitError(err) {
      for (const fn of listeners.error) fn(err);
    },
  };
  return socket;
}

function deferred() {
  return new Promise((resolve) => setImmediate(resolve));
}

test("old socket close does not clear the replacement connection", async () => {
  const session = new PhoneSession(() => new Date("2026-10-17T06:00:00.000Z"));
  const logs = [];
  const first = fakeSocket();
  const second = fakeSocket();
  bindPhoneConnection(session, first, (line) => logs.push(line));
  bindPhoneConnection(session, second, (line) => logs.push(line));
  assert.equal(session.isCurrent(second), true);
  assert.equal(session.isConnected(), true);
  await deferred();
  assert.equal(session.isCurrent(second), true);
  assert.equal(session.isConnected(), true);
  const health = session.healthDetails();
  assert.equal(health.lastConnectedAt, "2026-10-17T06:00:00.000Z");
  assert.equal(health.lastDisconnectedAt, null);
  assert.match(logs.join("\n"), /Stale socket closed/);
  assert.match(logs.join("\n"), /current phone kept/);
});

test("closing the current socket records code and reason", async () => {
  let now = new Date("2026-10-17T06:00:00.000Z");
  const session = new PhoneSession(() => now);
  const socket = fakeSocket();
  bindPhoneConnection(session, socket, () => {});
  now = new Date("2026-10-17T06:05:00.000Z");
  socket.close(1006, "abnormal closure");
  await deferred();
  assert.equal(session.isConnected(), false);
  const health = session.healthDetails();
  assert.equal(health.lastDisconnectedAt, "2026-10-17T06:05:00.000Z");
  assert.equal(health.lastDisconnectCode, 1006);
  assert.equal(health.lastDisconnectReason, "abnormal closure");
});

test("a timed-out socket cannot clear the phone that replaced it", async () => {
  let now = new Date("2026-10-17T06:00:00.000Z");
  const session = new PhoneSession(() => now);
  const first = fakeSocket();
  bindPhoneConnection(session, first, () => {});
  now = new Date("2026-10-17T06:01:00.000Z");
  const dead = session.takeTimedOut(45_000);
  assert.equal(dead, first);
  assert.equal(session.isConnected(), false);
  const second = fakeSocket();
  now = new Date("2026-10-17T06:01:01.000Z");
  bindPhoneConnection(session, second, () => {});
  first.close(1006, "late");
  await deferred();
  assert.equal(session.isCurrent(second), true);
  assert.equal(session.isConnected(), true);
  assert.equal(session.healthDetails().lastDisconnectReason, "no pong received");
  assert.equal(session.healthDetails().lastDisconnectCode, 1000);
});

test("stale pong and permissions do not update the live socket", () => {
  let now = new Date("2026-10-17T06:00:00.000Z");
  const session = new PhoneSession(() => now);
  const first = fakeSocket();
  const second = fakeSocket();
  bindPhoneConnection(session, first, () => {});
  bindPhoneConnection(session, second, () => {});
  now = new Date("2026-10-17T06:00:30.000Z");
  assert.equal(session.notePong(first, now), false);
  assert.equal(session.healthDetails().lastPongAt, "2026-10-17T06:00:00.000Z");
  assert.equal(session.notePong(second, now), true);
  assert.equal(session.healthDetails().lastPongAt, "2026-10-17T06:00:30.000Z");
  assert.equal(
    session.notePermissions(first, {
      permissions: { overlay: false },
    }),
    false
  );
  assert.equal(session.healthDetails().phonePermissions, null);
  assert.equal(
    session.notePermissions(second, {
      type: "permissions",
      token: "super-secret-token",
      permissions: {
        overlay: true,
        post_notifications: false,
        token: "still-secret",
      },
    }),
    true
  );
  const health = session.healthDetails();
  assert.equal(health.phonePermissions.overlay, true);
  assert.equal(health.phonePermissions.post_notifications, false);
  assert.equal(health.phonePermissions.token, undefined);
  assert.ok(health.missingPermissions.includes("post_notifications"));
  assert.ok(health.missingPermissions.includes("dnd_access"));
  assert.equal(health.missingPermissions.includes("overlay"), false);
  assert.equal(JSON.stringify(health).includes("secret"), false);
});

test("parsePhonePermissions rejects a body that is not a flag map", () => {
  assert.equal(parsePhonePermissions(null), null);
  assert.equal(parsePhonePermissions({ token: "x" }), null);
  assert.equal(parsePhonePermissions({ permissions: ["overlay"] }), null);
});

test("an error on a replaced socket does not clear the new one", () => {
  const session = new PhoneSession(() => new Date("2026-10-17T06:00:00.000Z"));
  const logs = [];
  const first = fakeSocket();
  const second = fakeSocket();
  bindPhoneConnection(session, first, (line) => logs.push(line));
  first.readyState = 3;
  bindPhoneConnection(session, second, (line) => logs.push(line));
  first.emitError(new Error("boom"));
  assert.equal(session.isCurrent(second), true);
  assert.equal(session.isConnected(), true);
  assert.match(logs.join("\n"), /Ignored error from a stale socket/);
});

test("a timeout close notifies once when nothing has replaced the socket", async () => {
  let now = new Date("2026-10-17T06:00:00.000Z");
  const session = new PhoneSession(() => now);
  let cleared = 0;
  const socket = fakeSocket();
  bindPhoneConnection(session, socket, () => {}, {
    onCleared: () => {
      cleared += 1;
    },
  });
  now = new Date("2026-10-17T06:01:00.000Z");
  const dead = session.takeTimedOut(45_000);
  assert.equal(dead, socket);
  dead.terminate();
  await deferred();
  assert.equal(cleared, 1);
  assert.equal(session.isConnected(), false);
  socket.readyState = 3;
  socket.emitError(new Error("late"));
  assert.equal(cleared, 1);
});

test("an error on the open current socket does not drop it", () => {
  const session = new PhoneSession(() => new Date("2026-10-17T06:00:00.000Z"));
  const socket = fakeSocket();
  bindPhoneConnection(session, socket, () => {});
  socket.emitError(new Error("transient"));
  assert.equal(session.isCurrent(socket), true);
  assert.equal(session.isConnected(), true);
});
