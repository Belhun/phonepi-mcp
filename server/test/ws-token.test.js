import assert from "node:assert/strict";
import test from "node:test";
import {
  ALLOW_QUERY_TOKEN_ENV,
  INSECURE_NO_TOKEN_ENV,
  MIN_WS_TOKEN_CHARS,
  authorizeWsConnection,
  createPhoneVerifyClient,
  noteQueryTokenUse,
  parseWsToken,
  queryTokenUseCount,
  requireWsTokenForListen,
  resetQueryTokenUseForTests,
  selectWsProtocol,
  tokenFromHttpRequest,
  tokenFromWsHeaders,
  wsTokenFromEnv,
} from "../dist/ws-token.js";

const TOKEN = "phonepi-test-token-32-chars-ok!!";
assert.equal(TOKEN.length, MIN_WS_TOKEN_CHARS);

function withEnv(env, fn) {
  const keys = Object.keys(env);
  const previous = {};
  for (const key of keys) {
    previous[key] = process.env[key];
    if (env[key] == null) delete process.env[key];
    else process.env[key] = env[key];
  }
  try {
    return fn();
  } finally {
    for (const key of keys) {
      if (previous[key] === undefined) delete process.env[key];
      else process.env[key] = previous[key];
    }
  }
}

test("unset or empty token is rejected", () => {
  resetQueryTokenUseForTests();
  assert.deepEqual(parseWsToken({}), { ok: false, reason: "missing" });
  assert.equal(wsTokenFromEnv({}), null);
  assert.equal(wsTokenFromEnv({ PHONEPI_WS_TOKEN: "" }), null);
  assert.equal(wsTokenFromEnv({ PHONEPI_WS_TOKEN: "   " }), null);
  assert.equal(authorizeWsConnection("/phonepi", null, {}), false);
  assert.equal(
    authorizeWsConnection({ url: "/phonepi" }, TOKEN, {}),
    false
  );
});

test("startup exits without a token except insecure loopback", () => {
  const missing = requireWsTokenForListen("127.0.0.1", {});
  assert.equal(missing.ok, false);
  assert.match(missing.message, /PHONEPI_WS_TOKEN required/);

  const short = requireWsTokenForListen("127.0.0.1", {
    PHONEPI_WS_TOKEN: "too-short",
  });
  assert.equal(short.ok, false);
  assert.match(short.message, /32/);

  const ok = requireWsTokenForListen("127.0.0.1", { PHONEPI_WS_TOKEN: TOKEN });
  assert.equal(ok.ok, true);
  assert.equal(ok.token, TOKEN);

  const insecureLoopback = requireWsTokenForListen("127.0.0.1", {
    [INSECURE_NO_TOKEN_ENV]: "1",
  });
  assert.equal(insecureLoopback.ok, true);
  assert.equal(insecureLoopback.token, null);

  const insecureWide = requireWsTokenForListen("0.0.0.0", {
    [INSECURE_NO_TOKEN_ENV]: "1",
  });
  assert.equal(insecureWide.ok, false);
  assert.match(insecureWide.message, /loopback/);
});

test("Bearer, X-PhonePi-Token, and subprotocol are accepted", () => {
  resetQueryTokenUseForTests();
  const env = { PHONEPI_WS_TOKEN: TOKEN };
  assert.equal(
    authorizeWsConnection(
      { url: "/phonepi", headers: { authorization: `Bearer ${TOKEN}` } },
      TOKEN,
      env
    ),
    true
  );
  assert.equal(
    authorizeWsConnection(
      { url: "/phonepi", headers: { "x-phonepi-token": TOKEN } },
      TOKEN,
      env
    ),
    true
  );
  assert.equal(
    authorizeWsConnection(
      {
        url: "/phonepi",
        headers: {
          "sec-websocket-protocol": `phonepi.v1, phonepi.token.${TOKEN}`,
        },
      },
      TOKEN,
      env
    ),
    true
  );
  assert.equal(
    tokenFromWsHeaders({ authorization: `Bearer ${TOKEN}` }),
    TOKEN
  );
  assert.equal(selectWsProtocol(["phonepi.token.secret", "phonepi.v1"]), "phonepi.v1");
  assert.equal(selectWsProtocol(["phonepi.token.secret"]), false);
});

test("query is accepted only when ALLOW_QUERY_TOKEN is not 0", () => {
  resetQueryTokenUseForTests();
  const logs = [];
  const envOn = {};
  assert.equal(
    authorizeWsConnection(
      { url: `/phonepi?token=${TOKEN}` },
      TOKEN,
      envOn,
      (line) => logs.push(line)
    ),
    true
  );
  assert.match(logs.join("\n"), /query token used \(deprecated\)/);
  assert.equal(logs.join("\n").includes(TOKEN), false);
  assert.equal(queryTokenUseCount() >= 1, true);

  const envOff = { [ALLOW_QUERY_TOKEN_ENV]: "0" };
  assert.equal(
    authorizeWsConnection({ url: `/phonepi?token=${TOKEN}` }, TOKEN, envOff),
    false
  );
  assert.equal(
    tokenFromHttpRequest({ url: `/api/command?token=${TOKEN}`, headers: {} }, envOff),
    null
  );
  assert.equal(
    tokenFromHttpRequest(
      { url: `/api/command?token=${TOKEN}`, headers: {} },
      envOn,
      () => {}
    ),
    TOKEN
  );
});

test("a wrong token is rejected", () => {
  assert.equal(
    authorizeWsConnection(
      { url: "/phonepi", headers: { authorization: "Bearer nope-nope-nope-nope-nope-nope-12" } },
      TOKEN,
      {}
    ),
    false
  );
  assert.equal(
    authorizeWsConnection({ url: `/phonepi?token=wrong-wrong-wrong-wrong-wrong-12` }, TOKEN, {}),
    false
  );
});

test("tokens never appear in log output", () => {
  resetQueryTokenUseForTests();
  const logs = [];
  const verify = createPhoneVerifyClient((line) => logs.push(line), {
    PHONEPI_WS_TOKEN: TOKEN,
  });
  assert.equal(
    verify({
      req: {
        url: `/phonepi?token=${TOKEN}`,
        headers: {},
        socket: { remoteAddress: "127.0.0.1" },
      },
    }),
    true
  );
  assert.equal(
    verify({
      req: {
        url: "/phonepi",
        headers: {},
        socket: { remoteAddress: "127.0.0.1" },
      },
    }),
    false
  );
  noteQueryTokenUse((line) => logs.push(line));
  const joined = logs.join("\n");
  assert.match(joined, /rejected/);
  assert.match(joined, /query token used \(deprecated\)/);
  assert.equal(joined.includes(TOKEN), false);
  assert.equal(joined.includes("phonepi-test-token"), false);
});

test("insecure no-token accepts only when the flag is set", () => {
  withEnv({ [INSECURE_NO_TOKEN_ENV]: "1" }, () => {
    assert.equal(authorizeWsConnection("/phonepi", null), true);
  });
  assert.equal(authorizeWsConnection("/phonepi", null, {}), false);
});
