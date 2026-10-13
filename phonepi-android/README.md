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

1. Install this companion APK (sideload) and **Google Maps**.
2. In PhonePi, set the same WebSocket token as `PHONEPI_WS_TOKEN` on the PC, then Connect. Notifications permission is requested on connect.
3. Grant **full-screen notifications** (Android 14+): PhonePi → Tool permissions → Maps navigation, or Settings → Apps → PhonePi → Full screen notifications. Android 10+ blocks background `startActivity`; the connectedDevice foreground service tries first, then a full-screen notification opens Maps.
4. Optional: keep PhonePi's link notification visible so the socket stays up while Maps takes the foreground.

No extra Maps API key. No Display-over-other-apps permission is required for the default path.

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

The router catches any exception and returns an error payload, so a tool bug never drops the socket.
