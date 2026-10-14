# PhonePi companion (Android)

Open-source Android client for the `phonepi-mcp` server. It connects out over WebSocket and runs phone-side tools. Design lives in [`../design/`](../design/README.md).

## Status

Scaffold + Milestone 1 start:

- WebSocket client with server-driven ping/pong
- `connectedDevice` foreground service (no Android 15/16 6-hour cap)
- `ToolRouter` with one-handler-per-tool dispatch and a uniform `{status}` envelope
- First tool: `get_battery_level`
- Compose connect screen (host, port, connect/disconnect, live status)

Remaining tools follow the milestone plan in [`../design/04-tool-handlers.md`](../design/04-tool-handlers.md).

## Toolchain

| Tool                   | Version              |
|------------------------|----------------------|
| AGP                    | 8.11.2               |
| Gradle                 | 8.14.4 (wrapper)     |
| Kotlin                 | 2.4.0                |
| AndroidX core-ktx      | 1.15.0 (SDK 36 cap)  |
| AndroidX lifecycle     | 2.8.7 (SDK 36 cap)   |
| JDK                    | 17                   |
| compileSdk / targetSdk | 36 (Android 16)      |
| minSdk                 | 26                   |

Build with Android Studio Meerkat (2024.3.1 Patch 1) or newer. On first open, let Android Studio generate the Gradle wrapper jar and sync. The system JDK is 25; point Gradle at JDK 17 (Android Studio bundles one) under Settings > Build > Gradle.

## Open and run

1. Open `phonepi-android/` in Android Studio (open this folder, not the repo root).
2. Let it sync; accept any SDK 36 / build-tools install prompts.
3. Plug in the test phone (Android 16) with USB debugging on.
4. Run the app.

## Connect the app to the server

### USB isolation (recommended for v1)

```powershell
adb reverse tcp:11041 tcp:11041
```

Then in the app set Host `127.0.0.1`, Port `11041`, tap Connect. Nothing on the LAN can reach the socket.

### Wi-Fi LAN

Set Host to the PC's LAN IP and Port `11041`. Keep this to a trusted network until auth lands (see [`../design/06-security-roadmap.md`](../design/06-security-roadmap.md)).

## Test without Cursor (fake server)

You do not need the full MCP stack to test a handler. This tiny server mimics the protocol: it pings, then sends a tool request and prints the response. It reuses the `ws` package already installed in `../server`.

```powershell
node phonepi-android/testing/fake-server.js
```

## Desktop test console (recommended)

A simple browser UI to pick any tool, edit its params, and run it on your phone.

```powershell
# Terminal 1 — test console (Web UI + phone WebSocket)
node phonepi-android/testing/test-console/server.js
# Or double-click: phonepi-android/testing/test-console/start-console.bat

# Terminal 2 — USB forward
adb reverse tcp:11041 tcp:11041
```

Open **http://127.0.0.1:8765** in your browser. Connect the phone app to `127.0.0.1:11041`.

- Left: all tools by category
- Center: editable fields per tool (custom notification text, SMS number, etc.)
- Right: live log of sends and responses
- **Run all (safe)** skips SMS and calls

Telephony tools show a confirmation before sending real SMS/calls.

---

Legacy fake-server details:

Save as `testing/fake-server.js`, then run it from the `server` folder so it finds `ws`:

```js
// testing/fake-server.js
const { WebSocketServer } = require("ws");
const wss = new WebSocketServer({ host: "0.0.0.0", port: 11041 });
let id = 0;
wss.on("connection", (ws) => {
  console.log("phone connected");
  ws.send(JSON.stringify({ type: "ping" }));
  ws.on("message", (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.type === "pong") return;
    console.log("response:", JSON.stringify(msg, null, 2));
  });
  const send = (tool, params = {}) =>
    ws.send(JSON.stringify({ type: "request", requestId: `req-${++id}`, tool, params }));
  setTimeout(() => send("get_battery_level"), 1500);
});
console.log("fake server on ws://0.0.0.0:11041");
```

```powershell
cd "..\server"
node ..\phonepi-android\testing\fake-server.js
```

Watch the phone with `adb logcat -s PhonePiWS:*`. You should see the battery response printed by the fake server.

## Phone navigation

The app stores named destinations locally (Room) and starts Google Maps when the server sends `phone_navigate`. It never runs a caller-supplied intent, action, or shell command.

### What to install and grant

1. Install this companion APK (sideload, **0.2.4** or newer) and **Google Maps**.
2. In PhonePi, set the same WebSocket token as `PHONEPI_WS_TOKEN` on the PC, then Connect. Notifications permission is requested on connect.
3. Grant **Display over other apps** (Appear on top): PhonePi → Tool permissions, or Settings → Apps → PhonePi → Appear on top. That is the reliable way to open Maps while PhonePi is in the background or the screen is off. Android can swallow `startActivity` from the background without throwing.
4. Grant **full-screen notifications** (Android 14+) as the fallback when overlay is off: Settings → Apps → PhonePi → Full screen notifications. Without overlay, PhonePi always posts a high-priority full-screen notification instead of trusting a background start.
5. Optional: leave **Reconnect when the phone boots** on (default) so the saved host reconnects after reboot. Turn it off on the connect screen if you do not want that.
6. Optional: keep PhonePi's link notification visible so the socket stays up while Maps takes the foreground.

No extra Maps API key.

### How to test

With the phone linked and Maps installed:

```powershell
# PC
$env:PHONEPI_WS_TOKEN = "choose-a-long-secret"
cd server
npm test
node dist/index.js --stdio --http-port 11043
```

On the phone, add a destination from the test console (`phone_add_location`, name `home`) or from MCP, then run `phone_navigate` with `{ "name": "home" }`. Maps should open driving navigation. If the phone is in Android Auto, confirm Maps' **Start now** prompt appears.

Activity log on the phone records list/add/remove/navigate.

## Notification reading

PhonePi can show recent and currently active notifications to MCP / the loopback HTTP API. It never dismisses, replies, or otherwise acts on them.

### What Alex must grant

1. Sideload **0.2.4** (or newer), or use in-app update from 0.2.4 onward.
2. **Notification access** (once): PhonePi → Tool permissions → Notification access, which opens the system Notification listener settings. Enable PhonePi. This is separate from the usual notification *posting* permission.
3. In PhonePi → **Notification sharing**, pick apps that have posted a notification (the list stays empty until something notifies after access is granted). Nothing is shared until then. Optional: **Share all** (off by default).
4. Existing grants still needed for other features: WebSocket token, Display over other apps (Maps), full-screen intents (Maps fallback), call/contacts/location if those tools are used.

`get_notifications` returns app name, title, text, time, and package. If Android hid a field, the value is `(hidden by Android)` plus a `hiddenFields` reason. Unshared apps are counted in `omittedUnsharedCount`, not silently dropped. Tools are rate-limited (60/min) and show up in Recent activity.

The main screen scrolls (connect → update → permissions → notification sharing → **Recent activity**) and sits inside the system bars. Scroll to the bottom for **Share all**, per-app toggles, and **Recent activity** with its **Clear** button.

## In-app update

The PC serves `GET /phonepi/update/manifest` and `GET /phonepi/update/apk` on the same WebSocket host (token required). Drop APKs in `PHONEPI_UPDATE_DIR` (default `~/.phonepi/updates`): `phonepi-0.2.4-6.apk` or `phonepi.apk` plus `manifest.json` with `versionCode` and `versionName`.

On the phone: **Check for update** / **Force update**, or MCP `phone_push_update`. PhonePi verifies sha256 and the signing cert, then opens the system installer. Alex still taps Install. Grant **Install unknown apps** once (Tool permissions).

Unit tests (no device):

```powershell
cd phonepi-android
.\gradlew.bat test
```

## Adding a tool

1. Create `tools/YourTool.kt` implementing `ToolHandler`.
2. Return `success { put("key", value) }` or `toolError("reason")`.
3. Register it in `ConnectionService.onCreate()`.
4. For a new **phone action** (navigate-style), also add the same name to `commands/PhoneCommands.kt` and `server/src/commands.ts` so MCP, HTTP `/api/command`, and the phone rate-limit share one allowlist.
5. Update [docs/LEARNINGS.md](../docs/LEARNINGS.md) in the same change (feature, mistake, or iPhone note). Do not put tokens in that file.

The router catches any exception and returns an error payload, so a tool bug never drops the socket.
