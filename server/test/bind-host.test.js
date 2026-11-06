import assert from "node:assert/strict";
import test from "node:test";
import {
  ALLOW_WIDE_BIND_ENV,
  BindHostError,
  DEFAULT_WS_HOST,
  isLoopbackHost,
  isNarrowHost,
  isTailscaleHost,
  resolveWsHost,
} from "../dist/bind-host.js";

test("default bind is 127.0.0.1", () => {
  assert.equal(DEFAULT_WS_HOST, "127.0.0.1");
  assert.equal(resolveWsHost([], {}), "127.0.0.1");
  assert.equal(isLoopbackHost("127.0.0.1"), true);
  assert.equal(isLoopbackHost("::1"), true);
  assert.equal(isLoopbackHost("localhost"), true);
  assert.equal(isLoopbackHost("[::1]"), true);
  assert.equal(isLoopbackHost("0.0.0.0"), false);
});

test("Tailscale addresses are allowed without a wide-bind opt-in", () => {
  assert.equal(isTailscaleHost("100.64.0.1"), true);
  assert.equal(isTailscaleHost("100.86.134.67"), true);
  assert.equal(isTailscaleHost("100.127.255.255"), true);
  assert.equal(isTailscaleHost("100.63.255.255"), false);
  assert.equal(isTailscaleHost("100.128.0.1"), false);
  assert.equal(isTailscaleHost("fd7a:115c:a1e0::1"), true);
  assert.equal(isNarrowHost("100.79.4.36"), true);
  assert.equal(resolveWsHost([], { PHONEPI_WS_HOST: "100.86.134.67" }), "100.86.134.67");
  assert.equal(
    resolveWsHost(["--host", "fd7a:115c:a1e0::2"], {}),
    "fd7a:115c:a1e0::2"
  );
});

test("wide bind without opt-in throws", () => {
  assert.throws(
    () => resolveWsHost([], { PHONEPI_WS_HOST: "0.0.0.0" }),
    (err) => {
      assert.ok(err instanceof BindHostError);
      assert.match(err.message, /PHONEPI_ALLOW_WIDE_BIND=1/);
      assert.match(err.message, /0\.0\.0\.0/);
      return true;
    }
  );
  assert.throws(() => resolveWsHost(["--host", "::"], {}), BindHostError);
  assert.throws(
    () => resolveWsHost([], { PHONEPI_WS_HOST: "192.168.1.10" }),
    BindHostError
  );
});

test("--host wins over PHONEPI_WS_HOST", () => {
  assert.equal(
    resolveWsHost(["--host", "127.0.0.1"], { PHONEPI_WS_HOST: "0.0.0.0" }),
    "127.0.0.1"
  );
});

test("wide bind with opt-in is accepted", () => {
  const previous = console.error;
  const lines = [];
  console.error = (line) => lines.push(String(line));
  try {
    assert.equal(
      resolveWsHost([], {
        PHONEPI_WS_HOST: "0.0.0.0",
        [ALLOW_WIDE_BIND_ENV]: "1",
      }),
      "0.0.0.0"
    );
    assert.match(lines.join("\n"), /WARNING/);
    assert.equal(lines.join("\n").includes("token"), false);
  } finally {
    console.error = previous;
  }
});
