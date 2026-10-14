# PhonePi learnings

Standing log for this PR branch (`cursor/phone-navigate-locations-1ceb`). Update this file in the same change as every future PhonePi feature, bugfix, or device lesson. Do not merge this branch from this log. Never commit secrets (`PHONEPI_WS_TOKEN`, command tokens, hostnames with tokens in the URL, APK signing keystores, or personal MagicDNS names that are not already public in the repo).

Current companion: **0.2.4** (versionCode **6**).

## How to update

1. Add a short subsection under **Features shipped** (version + versionCode) or **Mistakes and fixes** (symptom → cause → fix).
2. If the change is Android-only or would differ on iPhone, add a line under **iPhone: Android-only vs portable**.
3. Keep identifiers exact: tool names, URI schemes, permission names, env vars, ports.

## Invariants that keep getting relearned

- The PC never fires Android intents or shell. The phone runs allowlisted `ToolHandler`s. New write/action tools go in both `phonepi-android/.../PhoneCommands.kt` and `server/src/commands.ts`.
- One phone WebSocket (`0.0.0.0:11041`, optional `/phonepi` upgrade). Loopback HTTP is `127.0.0.1:11043` and read-only except `POST /api/command` for allowlisted phone actions.
- `PHONEPI_WS_TOKEN` is required for update HTTP and should be set for the phone socket. The server must not log the token value.
- Rate limits and the on-phone **Recent activity** log apply to tools and commands.
- Background `startActivity` on Android 10+ can return success and still do nothing. Trust a start only if PhonePi is in the foreground or `Settings.canDrawOverlays` is true. Otherwise post a full-screen notification and report that method honestly.
- Sideload/install is never silent. The user taps Install in the system UI.

## Features shipped

### PR #1 dependency (loopback HTTP + WS token)

Not a companion version bump. Same Node process: MCP stdio, phone WebSocket, loopback `GET /api/health` and `POST /api/tool` (`READ_ONLY_HTTP_TOOLS`). `PHONEPI_WS_TOKEN` via header or `?token=`. `/phonepi` WebSocket upgrades for Tailscale Serve.

### 0.2.1 — saved locations and Maps navigation

Destinations live on the phone (Room). MCP / `POST /api/command`: `phone_list_locations`, `phone_add_location`, `phone_remove_location`, `phone_navigate`.

`phone_navigate` tries `google.navigation:q=<encoded>`, then `geo:0,0?q=`, then an HTTPS Maps URL. Package is `com.google.android.apps.maps`. Android Auto still shows Maps' **Start now**; PhonePi does not skip it.

### 0.2.2 — reliable navigation, boot reconnect, honest status

Pixel 9 Pro XL on 0.2.1 returned `started:true, method:activity, notificationPosted:false` while Maps never appeared: Android dropped the background activity start with no exception (BAL).

Fix: **Display over other apps** (`SYSTEM_ALERT_WINDOW` / `Settings.canDrawOverlays`) is the trusted background exemption. If overlay is not granted and PhonePi is not in the foreground, always post a high-priority full-screen notification (FSI on Android 14+) and never report `started:true` from that `startActivity`. `method` is `activity` | `overlay` | `full_screen_notification` | `none`. Permissions panel deep-links to overlay and FSI settings.

Optional **Reconnect when the phone boots** (`RECEIVE_BOOT_COMPLETED` / `BootReceiver`) reconnects to the last saved host. Default on; toggle on the connect screen.

### 0.2.3 — read-only notification reading

`NotificationListenerService` keeps the last 200 posted notifications plus currently active ones. It never dismisses, replies, or acts.

MCP and loopback HTTP: `get_notifications` (`package`, `since`, `limit`) and `list_notification_apps`. Both are on `READ_ONLY_HTTP_TOOLS`, 60/min, logged.

Share allowlist starts **empty**. **Share all** is **off**. Alex picks apps in **Notification sharing** after they have posted a notification (prefs). Unshared apps are counted in `omittedUnsharedCount`, not dropped silently. If Android withholds a field, the value is `(hidden by Android)` plus a `hiddenFields` reason.

Grant: **Notification access** (listener setting), not the usual post-notifications permission.

Compile fix in the same version: `Notification.EXTRA_SUBSTITUTE_APP_NAME` is hidden and broke `assembleDebug`. Use the extra key literal `"android.substName"`.

### 0.2.4 — self-update, push update, scroll + insets, Recent activity restored

Server (same WebSocket HTTP host, token required): `GET /phonepi/update/manifest` and `GET /phonepi/update/apk`. Drop APKs in `PHONEPI_UPDATE_DIR` (default `~/.phonepi/updates`): `phonepi-<versionName>-<versionCode>.apk`, or `phonepi.apk` plus `manifest.json` with `versionCode` and `versionName`. sha256 and size are computed from the file.

Phone: **Check for update** (newer `versionCode`), quiet banner on connect, **Force update** even when `versionCode` matches. Download to app cache, verify sha256 and the signing cert against the installed app, then open the system installer (`PackageInstaller` / `ACTION_VIEW` FileProvider). Grant **Install unknown apps** once (`REQUEST_INSTALL_PACKAGES`).

`phone_push_update` is a write command (command token, 6/min, logged, `force` default true). If the phone is backgrounded, use the same overlay/FSI prompt path as navigation. Return value says whether the installer prompt was shown. Alex still taps Install.

UI: `enableEdgeToEdge()` plus `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`. Root content is a `Box` + `Column(fillMaxWidth + verticalScroll)` (not `fillMaxSize().verticalScroll()`). Screen order: connect → update → permissions → Notification sharing (Share all and per-app rows in the parent scroll, no nested 280.dp scroller) → **Recent activity** with **Clear** (wrap-content, no `weight(1f)` / nested `LazyColumn`).

## Mistakes and fixes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `phone_navigate` reported `started:true` / `method:activity` but Maps never opened (Pixel 9 Pro XL, 0.2.1) | Android 10+ **background activity launch** can swallow `startActivity` with no exception. Returning from `startActivity` is not proof the activity ran. | Trust the start only if overlay is granted or PhonePi is in the foreground. Otherwise always FSI and report `full_screen_notification`. Grant **Display over other apps**. |
| `assembleDebug` failed in `NotificationMapper.kt` on `Notification.EXTRA_SUBSTITUTE_APP_NAME` | That constant is `@hide`. The SDK used for 0.2.3 does not expose it. | Read extras with the public key string `"android.substName"`. Confirm with a real `./gradlew assembleDebug`, not kotlinc alone. |
| Main screen drew under the status bar and gesture nav; **Share all** clipped; per-app list and **Recent activity** unreachable (0.2.3) | Edge-to-edge without window insets. Parent `Column(fillMaxSize)` did not scroll. `ActivityLogPanel(Modifier.weight(1f))` plus a nested `LazyColumn` was squeezed to zero height once Notification sharing took the rest of the screen. Nested `heightIn(max = 280.dp).verticalScroll` on the app list hid rows behind the nav bar. | `WindowInsets.safeDrawing` on the scaffold. One parent `verticalScroll`. Wrap-content log and share rows. Keep **Recent activity** / **Clear** last, below Notification sharing. |
| Phone could not resolve the Tailscale host (`*.ts.net`) even though the PC was up | Tailnet MagicDNS was not the resolver the phone used. | Turn on Tailscale DNS / MagicDNS on the phone (Tailscale app DNS settings, not a PhonePi code change). Then reconnect. |
| `adb connect` to wireless debugging stopped working after a Wi-Fi or tailnet change | Android **wireless debugging** assigns a new TCP port when the network changes. The old `ip:port` is dead. | Re-open Wireless debugging on the Pixel, copy the new port, pair/connect again. Do not bake a port into scripts. |

## iPhone: Android-only vs portable

Portable (keep this contract if an iPhone companion is built):

- MCP tool names, JSON params, allowlists, sliding-window rate limits, on-device activity log.
- One authenticated socket from the phone; loopback HTTP on the PC for extra local clients so they do not steal the phone socket.
- Locations stored on the device; the PC never launches maps itself.
- Default-deny sharing (notification allowlist empty, Share all off).
- Honest status: if the OS blocked the action, say so in the tool result. Do not invent `started:true`.
- Updates: serve a signed artifact + manifest (version, hash, size) over the same host and token; verify hash and signing identity; the human confirms install. Never silent install.
- Secrets stay in env / phone prefs. Not in git.

Android-only (do not assume iOS equivalents):

- `Intent` / URI schemes (`google.navigation:`, `geo:`), package `com.google.android.apps.maps`, Android Auto **Start now**.
- BAL, `SYSTEM_ALERT_WINDOW`, full-screen intent notifications, `NavigateBridgeActivity`.
- `NotificationListenerService` reading **other apps'** notifications. iOS has no public equivalent; a Notification Service Extension only sees this app's own notifications unless Apple grants a special entitlement.
- `PackageInstaller`, `REQUEST_INSTALL_PACKAGES`, **Install unknown apps**, FileProvider APKs. iOS distribution is TestFlight / App Store / MDM, not a local IPA installer from the MCP host.
- `RECEIVE_BOOT_COMPLETED`. iOS has no boot broadcast; reconnect on next launch or a background task the OS may not run.
- Compose edge-to-edge / `WindowInsets.safeDrawing`.
- ADB wireless debugging and its changing TCP port.
- Hidden `Notification` extras such as `android.substName`.

iPhone maps/nav sketch (not implemented): open an Apple Maps or Google Maps iOS URL when the companion is allowed to become foreground. Do not expect to start Maps from a background daemon the way overlay+FSI does on Android. A tap-to-open notification is the likely fallback.

iPhone notification reading sketch (not implemented): without an Apple entitlement, do not promise `get_notifications` for other apps. Push that limitation in the tool schema rather than returning an empty list that looks like "no notifications".

## Device notes (Pixel + Tailscale)

- Overlay is the grant that actually opens Maps from the background. FSI is the fallback, not a substitute for overlay if you want Maps to appear unprompted.
- Keep the PhonePi connection notification if the OS otherwise kills the socket when Maps comes to the front.
- Tailscale Serve terminates TLS on the PC; the phone still needs MagicDNS (or a literal tailnet IP) and the WS token.
- Sideload **0.2.4** once. Later APKs go in `~/.phonepi/updates` (or `PHONEPI_UPDATE_DIR`).
