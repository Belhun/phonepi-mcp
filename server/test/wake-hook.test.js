import assert from "node:assert/strict";
import http from "node:http";
import test from "node:test";
import {
  WAKE_HOOK_SECRET_ENV,
  WAKE_HOOK_URL_ENV,
  WakeHook,
  readWakeHookConfig,
  wakeHeaders,
} from "../dist/wake-hook.js";

test("wake hook is disabled when the URL env is unset or invalid", () => {
  assert.equal(readWakeHookConfig({}).url, null);
  assert.equal(readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "" }).url, null);
  assert.equal(readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "not-a-url" }).url, null);
  assert.equal(readWakeHookConfig({ [WAKE_HOOK_URL_ENV]: "ftp://x" }).url, null);
  const hook = new WakeHook(readWakeHookConfig({}));
  assert.equal(hook.enabled, false);
  assert.deepEqual(hook.status(), { wakeHookEnabled: false, hookFailed: 0 });
});

test("wake hook config reads URL and secret without exposing them in status", () => {
  const cfg = readWakeHookConfig({
    [WAKE_HOOK_URL_ENV]: "http://127.0.0.1:9/wake",
    [WAKE_HOOK_SECRET_ENV]: "super-secret-wake",
  });
  assert.equal(cfg.url, "http://127.0.0.1:9/wake");
  assert.equal(cfg.secret, "super-secret-wake");
  const hook = new WakeHook(cfg, async () => ({ ok: true }));
  const json = JSON.stringify(hook.status());
  assert.equal(json.includes("secret"), false);
  assert.equal(json.includes("127.0.0.1"), false);
  assert.equal(hook.status().wakeHookEnabled, true);
});

test("wake POST body is only event, id, and ts; secret is a header", async () => {
  const posts = [];
  const hook = new WakeHook(
    {
      url: "http://127.0.0.1:9/wake",
      secret: "hook-secret",
      timeoutMs: 500,
      maxAttempts: 1,
      backoffMs: [],
    },
    async (req) => {
      posts.push(req);
      return { ok: true };
    }
  );
  hook.notify("inbound_prompt", "prompt-aaaa", 1_700_000_000_000);
  await hook.waitForIdle();
  assert.equal(posts.length, 1);
  assert.deepEqual(Object.keys(posts[0].body).sort(), ["event", "id", "ts"]);
  assert.deepEqual(posts[0].body, {
    event: "inbound_prompt",
    id: "prompt-aaaa",
    ts: 1_700_000_000_000,
  });
  assert.equal(posts[0].headers["X-PhonePi-Wake-Secret"], "hook-secret");
  assert.equal(Object.hasOwn(posts[0].body, "text"), false);
  assert.equal(Object.hasOwn(posts[0].body, "replyText"), false);
});

test("wakeHeaders omits the secret header when unset", () => {
  assert.deepEqual(wakeHeaders(null), { "Content-Type": "application/json" });
  assert.equal(wakeHeaders("abc")["X-PhonePi-Wake-Secret"], "abc");
});

test("failed wake retries with backoff, then increments hookFailed and logs no secrets", async () => {
  const delays = [];
  const logs = [];
  let calls = 0;
  const hook = new WakeHook(
    {
      url: "http://example.invalid/wake?token=super-secret-wake",
      secret: "super-secret-wake",
      timeoutMs: 200,
      maxAttempts: 3,
      backoffMs: [5, 7],
    },
    async () => {
      calls += 1;
      return { ok: false };
    },
    async (ms) => {
      delays.push(ms);
    },
    (line) => logs.push(line)
  );
  hook.notify("notification_reply", "reply-bbbb", 10);
  await hook.waitForIdle();
  assert.equal(calls, 3);
  assert.deepEqual(delays, [5, 7]);
  assert.equal(hook.status().hookFailed, 1);
  assert.equal(logs.join("\n"), "wake hook failed");
  assert.equal(logs.join("").includes("secret"), false);
  assert.equal(logs.join("").includes("example"), false);
});

test("in-flight duplicate ids are skipped and invalid ids never POST", async () => {
  let calls = 0;
  const hook = new WakeHook(
    {
      url: "http://127.0.0.1:9/wake",
      secret: null,
      timeoutMs: 500,
      maxAttempts: 1,
      backoffMs: [],
    },
    async () => {
      calls += 1;
      await new Promise((r) => setTimeout(r, 30));
      return { ok: true };
    }
  );
  hook.notify("inbound_prompt", "prompt-inflight", 1);
  hook.notify("inbound_prompt", "prompt-inflight", 2);
  hook.notify("inbound_prompt", "short", 3);
  hook.notify("inbound_prompt", "nope!", 4);
  await hook.waitForIdle();
  assert.equal(calls, 1);
  assert.equal(hook.status().hookFailed, 0);
});

test("duplicate ids are not posted twice after success", async () => {
  let calls = 0;
  const hook = new WakeHook(
    {
      url: "http://127.0.0.1:9/wake",
      secret: null,
      timeoutMs: 500,
      maxAttempts: 1,
      backoffMs: [],
    },
    async () => {
      calls += 1;
      return { ok: true };
    }
  );
  hook.notify("inbound_prompt", "prompt-cccc", 1);
  await hook.waitForIdle();
  hook.notify("inbound_prompt", "prompt-cccc", 2);
  await hook.waitForIdle();
  assert.equal(calls, 1);
  assert.equal(hook.status().hookFailed, 0);
});

test("notify does not throw when the poster throws; hookFailed still counts", async () => {
  const hook = new WakeHook(
    {
      url: "http://127.0.0.1:9/wake",
      secret: "hidden",
      timeoutMs: 50,
      maxAttempts: 1,
      backoffMs: [],
    },
    async () => {
      throw new Error("nope http://127.0.0.1:9 secret=hidden");
    },
    async () => {},
    () => {}
  );
  hook.notify("inbound_prompt", "prompt-dddd", 3);
  await hook.waitForIdle();
  assert.equal(hook.status().hookFailed, 1);
});

test("real HTTP POST reaches a local listener with no message text", async () => {
  const seen = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      seen.push({
        method: req.method,
        path: req.url,
        secret: req.headers["x-phonepi-wake-secret"],
        body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
      });
      res.writeHead(204);
      res.end();
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const hook = new WakeHook(
    readWakeHookConfig({
      [WAKE_HOOK_URL_ENV]: `http://127.0.0.1:${port}/wake`,
      [WAKE_HOOK_SECRET_ENV]: "from-env",
    })
  );
  hook.notify("notification_reply", "reply-eeeeee", 42);
  await hook.waitForIdle();
  server.close();
  assert.equal(seen.length, 1);
  assert.equal(seen[0].method, "POST");
  assert.equal(seen[0].path, "/wake");
  assert.equal(seen[0].secret, "from-env");
  assert.deepEqual(seen[0].body, {
    event: "notification_reply",
    id: "reply-eeeeee",
    ts: 42,
  });
});
