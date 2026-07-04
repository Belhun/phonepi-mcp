# Decision log

Architecture decision records for the PhonePi DIY app. Each record states the choice, the reason, and what it rules out.

## ADR-001: Build a native Kotlin Android app

**Status:** Accepted

**Choice:** Write the companion app in Kotlin against the Android SDK.

**Why:** The app's real work is calling device APIs: `SmsManager`, `ContactsContract`, `AlarmManager`, `ClipboardManager`, `NotificationManager`. Kotlin reaches these with no bridge. Flutter and React Native add a platform-channel layer for the same calls, and React Native has the weakest story for silent SMS send and background sockets.

**Rules out:** Flutter and React Native for v1. Revisit only if you want an iOS companion, where Flutter becomes the better fallback.

## ADR-002: Phone is a WebSocket client, not an MCP server

**Status:** Accepted

**Choice:** The app opens an outbound WebSocket to the existing `phonepi-mcp` Node server and answers tool requests. It does not run its own MCP server.

**Why:** The server already bridges Cursor (MCP over stdio) to the phone (custom JSON over WebSocket on port 11041). Reusing it means zero server changes for v1 and a working setup in days.

**Rules out:** The MCP Kotlin SDK on the phone (wrong transport, heavy). On-device MCP server designs (premex-ab style) stay a possible future mode, not v1.

## ADR-003: Target full tool parity in v1

**Status:** Accepted

**Choice:** Implement all 22 active tools plus the 5 commented-out tools (27 total).

**Why:** You want every feature the server documents. The commented tools (`take_photo`, `get_location`, `set_alarm`, `cancel_alarm_or_timer`, `stop_alarm`) already have schemas; `take_photo` even has server-side image handling.

**Note:** The 5 disabled tools need a small server change to appear in `ListTools`. That is the one planned exception to "keep the server untouched," and it lands in the parity milestone. See [04-tool-handlers.md](04-tool-handlers.md).

## ADR-004: Keep the Node server untouched for v1

**Status:** Accepted

**Choice:** Do not modify `server/src/index.ts` until a working prototype exists.

**Why:** Faster path to a first end-to-end test. The protocol is stable enough to build against now.

**Rules out:** Server-side auth in v1. That moves to the hardening milestone (ADR-006).

## ADR-005: v1 networking is localhost / adb only

**Status:** Accepted

**Choice:** For v1, connect the phone to the PC over USB with `adb reverse tcp:11041 tcp:11041`, so the app talks to `127.0.0.1:11041`. Wi‑Fi LAN IP is the fallback for day-to-day testing.

**Why:** Smallest network exposure while the protocol has no auth. No LAN peer can reach the socket over USB forwarding.

**Future:** Tailscale-only remote access in a later version. See [06-security-roadmap.md](06-security-roadmap.md).

## ADR-006: Security hardening is a planned later version

**Status:** Accepted (deferred)

**Choice:** Ship a working local app first. Add pairing tokens, session auth, WSS with certificate pinning, and Tailscale ACLs in a later version.

**Why:** You asked to iterate fast first. The local-only posture in ADR-005 keeps v1 safe enough during development.

**Risk accepted:** The v1 socket has no auth. Mitigated by USB forwarding and not binding to the LAN unless needed.

## ADR-007: Use the `connectedDevice` foreground service type

**Status:** Accepted

**Choice:** Run the persistent WebSocket inside a foreground service typed `connectedDevice`, not `dataSync`.

**Why:** Android 15 caps `dataSync` (and `mediaProcessing`) foreground services at 6 hours per 24-hour period in the background. A companion link must stay up longer. `connectedDevice` has no such cap and fits the "talking to a paired desktop" use case.

**Cost:** `connectedDevice` expects a device-style permission (for example Bluetooth, USB, or network-state). The app must declare a matching permission. Detail in [05-permissions-foreground.md](05-permissions-foreground.md).

**Defensive step:** Still implement `Service.onTimeout()` so any future type change cannot crash the app.

## ADR-010: `make_call` uses a full-screen-intent confirmation

**Status:** Accepted

**Choice:** `make_call` posts a high-priority full-screen-intent notification. The user taps to place the call. The app does not auto-dial.

**Why:** It works from the background under Android 14+ rules, and it keeps a human in the loop. An AI can be steered by prompt injection; a one-tap confirm blocks silent premium-number or spam calls. Detail in [05-permissions-foreground.md](05-permissions-foreground.md).

**Rules out:** Direct auto-dial and the `ROLE_DIALER` / `TelecomManager.placeCall` path for v1.

## ADR-011: Confirm SMS and calls on the phone in v1

**Status:** Accepted

**Choice:** `send_sms` and `make_call` show a phone-side confirmation before they act, starting in v1.

**Why:** These are the two costly, hard-to-undo actions. Confirming them is cheap insurance against prompt injection while the server still has no auth.

## ADR-012: Target API 36 (Android 16)

**Status:** Accepted

**Choice:** `targetSdk = 36`, `compileSdk = 36`. The test device runs Android 16.

**Why:** Build against the version the device runs. The design already handles the Android 15/16 foreground-service cap (via `connectedDevice`) and the Android 14 background-launch rules. Google Play will require API 36 for updates from August 2026 anyway.

**Note:** `connectedDevice` confirmed uncapped on Android 16; only `dataSync` and `mediaProcessing` carry the 6-hour limit.

## ADR-008: Physical device over Wi‑Fi for testing

**Status:** Accepted

**Choice:** Test on a real Android phone. Use Wi‑Fi/LAN as the default and USB `adb reverse` when you want isolation.

**Why:** SMS and calls do not work on emulators. Clipboard focus rules and foreground-service behavior also differ on real hardware.

## ADR-009: Sideload distribution

**Status:** Accepted

**Choice:** Install via signed APK, not the Play Store.

**Why:** Play restricts `SEND_SMS` and `CALL_LOG` permissions to default handler apps. Sideloading keeps full telephony access for personal use.

**Cost:** No auto-updates; Play Protect may warn on install. Acceptable for a personal tool.

## Resolved directions

| # | Question | Answer |
|---|----------|--------|
| 1 | Test device Android version | Android 16 (API 36) → `targetSdk = 36` (ADR-012) |
| 2 | `make_call` mode | Full-screen-intent confirmation (ADR-010) |
| 3 | Confirmation prompts | Confirm SMS and calls in v1 (ADR-011) |
| 4 | Package name | `dev.phonepi.companion` |

## Open questions

These do not block v1. Capture answers as you build.

| # | Question | Affects |
|---|----------|---------|
| 1 | Do you want a desktop "device approval" step before tools run, even in v1? | Connection UI |
| 2 | Should `find_phone` use max volume regardless of the ringer/silent mode? | Audio focus design |
