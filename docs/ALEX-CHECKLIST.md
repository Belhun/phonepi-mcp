# What Alex does on the phone, watch, and server

Companion **0.3.7** (versionCode **19**). Wear **0.1.0** (versionCode **1**). Same `applicationId` `dev.phonepi.companion` and the same release key. PhonePi never flips Do Not Disturb. Never print `PHONEPI_WS_TOKEN`, `PHONEPI_READ_TOKEN`, `PHONEPI_WAKE_HOOK_URL`, or `PHONEPI_WAKE_HOOK_SECRET`.

Presence, screen time, location history, and sleep/health sharing start **on**. Bots still only read through the allowlisted loopback tools. Sensitive reads (`get_presence`, `get_screen_time`, `get_health_summary`, `get_notification_replies`, `get_inbound_prompts`, `get_location_history`, `search_places`) need `PHONEPI_READ_TOKEN`. Pause and delete stay on the phone and as token commands. Resume of paused location, presence, usage, and health is phone-only.

Install **release-signed** APKs only (phone and watch). Never sideload the debug watch APK. Before install, print the signing cert and compare phone vs watch:

```bash
apksigner verify --print-certs phonepi-android/app/build/outputs/apk/release/app-release.apk
apksigner verify --print-certs phonepi-android/phonepi-wear/build/outputs/apk/release/phonepi-wear-release.apk
```

Print SHA-256 (and SHA-1) from those two commands and compare phone vs watch. The SHA-256 lines must match (same release key). The SHA-256 is not checked into this repo. SHA-1 of the current release cert is `49:B1:AB:F2:98:F5:D4:9A:B3:8B:A2:30:6F:E9:E3:2E:E4:89:48:9D` if you want a second check. Debug APKs are for this box's tests, not the Pixel or watch.

## Phone companion

1. Sideload the **release** APK `phonepi-android/app/build/outputs/apk/release/app-release.apk` (or the signed update). Open PhonePi and Connect. The WebSocket token is stored in the Android Keystore (one-time migration from the old prefs value so an already-paired phone keeps working).
2. **Usage access:** Settings → Usage access → enable PhonePi. Tool permissions and the Screen time panel open that screen. Needed for `get_screen_time`.
3. **Location:** grant precise location, then **Allow all the time**, plus **Physical activity**. Needed for presence and location history. Mark saved places as presence on the Presence panel (or `phone_add_location` with `presence: true`).
4. **Health Connect:** PhonePi → Sleep and health → Privacy policy → Grant. Galaxy Watch 7 data lives in Samsung Health first. Samsung Health → Settings → Health Connect must write sleep, steps, heart rate, exercise, and blood oxygen. PhonePi only reads Health Connect. It does not use the Samsung Health Data SDK.
5. **DND exception:** PhonePi does not change Do Not Disturb. **PhonePi quiet 1 hour** (Connect screen or watch Quiet 1h) holds PhonePi's own non-urgent notifications and shows a summary when quiet ends. Presence reports `pi_quiet`, not a fake system DND value. To hear urgent PhonePi cards while system DND is on, allow the **PhonePi urgent messages** channel in system Settings yourself.
6. **Ask assistant:**
   - Quick Settings: shade → edit tiles → add **Ask assistant**.
   - Home shortcut: long-press the PhonePi icon → drag **Ask assistant**.
   - Pixel Quick Tap: Settings → System → Gestures → Quick Tap → Use Quick Tap → Open app → **Ask assistant** (the extra launcher entry), not the main PhonePi icon.

Pause and delete (each stream has a visible on/off):

- **Stop all sharing** on the Connect screen is on only when location history, presence, usage, and health are all paused. One tap turns sharing back on. Each stream still has its own pause on its panel.
- Presence: pause / clear snapshot
- Screen time: pause / delete usage
- Location history: pause / delete history
- Sleep and health: pause / delete cache
- Ask assistant: **Delete prompts and replies**

Work hours live on the box in `~/.phonepi/presence/config.json` (default 08:00–17:00 Monday–Friday, `America/Los_Angeles`). Church hours stay empty until you edit that file.

## Wear OS watch

Sideload from the Linux machine over wireless debugging. Do not bake the debug port into a script; it changes when Wi-Fi changes. Pairing is once per network until the watch forgets it.

1. Watch: Settings → About watch → Software information → tap **Software version** seven times.
2. Watch: Settings → Developer options → **Wireless debugging** → On. Tap **Pair new device**. Note the IP, pairing port, and six-digit code. The debug port on the Wireless debugging screen is a different port.
3. Phone: companion installed and Connect.
4. From Linux:

```bash
adb pair WATCH_IP:PAIR_PORT
adb connect WATCH_IP:DEBUG_PORT
adb install -r phonepi-wear/build/outputs/apk/release/phonepi-wear-release.apk
```

5. Watch: add the PhonePi tile. Optional: add the **Next alarm** complication.
6. After the watch APK is installed: watch **Settings → Developer options → Wireless debugging → Off**, then **Revoke authorizations** (or unpair this computer) so the debug port is not left open.

Ask assistant on the watch sends a `watch_relay` prompt through the phone. Quiet 1h is in-app PhonePi quiet (confirm tap on the watch). The watch never holds the PhonePi token.

## Server env file

Edit `/home/box/.phonepi/env` (mode 600). Restart the PhonePi server after saving.

Set these names (values stay in that file):

- `PHONEPI_WS_TOKEN` — phone socket, `/api/command`, and update HTTP. Required, 32+ characters. The server will not start without it.
- `PHONEPI_WS_HOST` — phone WebSocket bind. Default `127.0.0.1`. Loopback and Tailscale (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`) are allowed. Anything else needs `PHONEPI_ALLOW_WIDE_BIND=1`.
- `PHONEPI_ALLOW_QUERY_TOKEN` — Phase A leave unset or `1` so old APKs can still send `?token=`. Set `0` in Phase B after logs show zero `query token used (deprecated)` for about 7 days.
- `PHONEPI_READ_TOKEN` — sensitive reads (`get_presence`, `get_screen_time`, `get_health_summary`, `get_notification_replies`, `get_inbound_prompts`, `get_location_history`, `search_places`, `get_location`, `phone_list_locations`) **and**, by default, the strict reads (notification log, live notifications, call log, contacts, photos list). Separate from the WS token. Default bot is place labels only (`coords: false`). Optional `PHONEPI_READ_BOTS` JSON or `~/.phonepi/read-bots.json` (mode 0600) for per-bot scoped tokens (`coords: true` only for bots allowed raw coordinates). `PHONEPI_REQUIRE_READ_TOKEN_ALL` defaults on; set `=0` only to restore the old open reads. `PHONEPI_OPEN_READ_TOOLS` is a comma list of strict tools that may stay token-free (default empty). It does not add `get_photo` or `gm_*` (known trade-off: gating `gm_*` would break Phone & Texts). Tokens must be 32+ characters and must not contain `placeholder`, `choose-a-long`, or `changeme`. Optional `PHONEPI_MCP_USE_ENV_READ_TOKEN=1` lets a single-bot MCP process reuse the env token.

Every process running as the same box user can read `~/.phonepi/env` and `read-bots.json`. Real isolation needs separate OS users or containers. MCP is the full-trust channel; per-bot coordinate limits are enforced on MCP for `get_location` / `phone_list_locations` / history, but any MCP client that can spawn the server still shares that user. HTTP `/api/tool` is the place to give each bot its own token.

**Do this before deploying this build.** Strict reads are on by default. The Home · Notifications bot's poller will 401 unless it already has a scoped read token. Merge a poller bot into the existing `read-bots.json` (mode 0600) first:

```bash
TOKEN=$(openssl rand -hex 32)
python3 - "$TOKEN" <<'PY'
import json, os, sys
from pathlib import Path
token = sys.argv[1]
path = Path.home() / ".phonepi" / "read-bots.json"
path.parent.mkdir(mode=0o700, exist_ok=True)
bots = []
if path.exists() and path.stat().st_size:
    try:
        loaded = json.loads(path.read_text())
        if isinstance(loaded, list):
            bots = loaded
    except json.JSONDecodeError:
        bots = []
bots = [b for b in bots if not (isinstance(b, dict) and b.get("id") == "notif-poller")]
bots.append({
    "id": "notif-poller",
    "token": token,
    "tools": ["get_notification_log"],
    "coords": False,
})
if not path.exists():
    path.touch()
os.chmod(path, 0o600)
path.write_text(json.dumps(bots, indent=2) + "\n")
os.chmod(path, 0o600)
PY
```

Give that poller token to the Home · Notifications bot **before** restarting the new server. Watch `~/.phonepi/server.log` for 401s on `get_notification_log` / `get_notifications`. Notification text is wrapped as untrusted either way.

## Deploy notes: network hardening (HP + box)

Do not deploy or merge from this PR; Alex and the Security bot review it first. After merge, about 10 minutes on each machine. Order matters.

1. **Poller token first** (box, and HP if a poller runs there). Create/update `~/.phonepi/read-bots.json` as above. Point the Home · Notifications bot at that scoped token. Confirm a poll succeeds on the current server.
2. **Env files.** Keep a `.bak` of each file.
   - **HP** `/etc/phonepi/env`: keep `PHONEPI_WS_HOST=127.0.0.1`. Add `PHONEPI_ALLOW_QUERY_TOKEN=1`. Leave `PHONEPI_REQUIRE_READ_TOKEN_ALL` unset (defaults on) or set `=1`. Keep `PHONEPI_HTTP_PORT` as it is (failover front 11043). `tailscale serve --https=443 → 127.0.0.1:11041` stays.
   - **Box** `~/.phonepi/env`: add `PHONEPI_WS_HOST=127.0.0.1`, `PHONEPI_ALLOW_QUERY_TOKEN=1`. Leave `PHONEPI_REQUIRE_READ_TOKEN_ALL` unset or `=1`. Keep `PHONEPI_HTTP_PORT=11045`. Serve `/ → 127.0.0.1:11041` stays.
3. **Restart the new binary.** HP: systemd user unit from `scripts/install-phonepi-user-service.sh`. Box: `scripts/phonepi-watchdog.sh` (stop/start). A missing env file or token now **refuses to start** instead of running open.
4. **Check.** `ss -ltnp | grep 11041` shows `127.0.0.1:11041`. WS upgrade without a token is 401; with `Authorization: Bearer` it is 101. `/api/tool get_notifications` without a read token is 401; the poller still succeeds. Health check uses `PHONEPI_HTTP_PORT` (box 11045, not 11043).
5. **Phone.** Sideload companion **0.3.7** (versionCode **19**) when you want the header-only WS token. Old APKs keep working during Phase A because `?token=` is still accepted. Release APKs only allow cleartext to loopback, so the phone stays on `wss://<node>.ts.net`.

**Phase B** (after about 7 days of zero `query token used (deprecated)` in the logs): set `PHONEPI_ALLOW_QUERY_TOKEN=0` on HP and box and restart. Old APKs will fail to connect until they update.

**Rollback:** restore the previous env `.bak` and restart. To restore the old bind: `PHONEPI_WS_HOST=0.0.0.0` plus `PHONEPI_ALLOW_WIDE_BIND=1`. To restore open reads: `PHONEPI_REQUIRE_READ_TOKEN_ALL=0`. `PHONEPI_ALLOW_QUERY_TOKEN=1` plus a restart keeps old APKs working.

**Could break:** Home · Notifications poller (401 if its token is missing); any client still dialing `hp1:11041` instead of the ts.net Serve name; a box refresh with no env file (server stays down until env is restored). Untested on a real phone: OkHttp `Authorization` through Tailscale Serve / wss (expected to pass; confirm on device during Phase A). Docker `:2375` is platform-managed and is not changed here.

**Verify the header on device (Phase A):** after sideloading 0.3.7, `adb logcat -s PhonePiWS:*` should show `wss://<node>.ts.net:443/phonepi` with no `?token=`. The server log should accept the connection without `query token used (deprecated)`. A `curl` to the Serve URL with `Authorization: Bearer` (never print the value) should 101 on upgrade. If Serve dropped the header, the phone would 401 and you would still see query-token lines from the old APK only.
- `PHONEPI_WAKE_HOOK_URL` — `https` URL (http only for loopback `127.0.0.0/8` or tailnet `100.64.0.0/10` / `*.ts.net`). Unset disables the hook.
- `PHONEPI_WAKE_HOOK_SECRET` — optional. Sent as `X-PhonePi-Wake-Secret`.
- Optional: `PHONEPI_HTTP_HOSTS` if loopback HTTP must accept extra `Host` values. Default is `127.0.0.1`, `localhost`, `[::1]`. Tailscale Serve still fronts WebSocket 11041, not `/api/tool`.

Do not print the values. `GET /api/health` shows `wakeHookEnabled` and `hookFailed` only. It never includes the URL or secret.

Optional: `PHONEPI_WAKE_HOOK_TIMEOUT_MS` (default 2000, clamp 200–10000).
