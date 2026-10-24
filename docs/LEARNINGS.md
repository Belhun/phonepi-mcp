# PhonePi learnings

Standing log for this PR branch (`cursor/phone-navigate-locations-1ceb`). Update this file in the same change as every future PhonePi feature, bugfix, or device lesson. Do not merge this branch from this log. Never commit secrets (`PHONEPI_WS_TOKEN`, command tokens, hostnames with tokens in the URL, APK signing keystores, or personal MagicDNS names that are not already public in the repo).

Current companion: **0.3.2** (versionCode **14**). 0.3.1 / versionCode 13 is the location-history release.

Bot-facing skill: [BOT-SKILL.md](BOT-SKILL.md).

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
- In-app updates: Ed25519-signed manifest (`versionCode`, `versionName`, APK `sha256`, `minVersion`) plus APK sha256 match plus pinned **release** signing-cert SHA-256. Release builds do not accept the debug cert. Keys live under `~/.phonepi/release` (mode 700/600), never in git. Debug → release is a one-time uninstall and sideload; Android will not update across different signing certs.

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

### 0.2.5 — reconnect after self-update

Field test 2026-10-13: `phone_push_update` force downloaded, verified, showed the overlay installer, and Alex tapped Update. After the reinstall the phone did not reconnect.

Cause: replacing the APK kills the process. `BootReceiver` only handled `BOOT_COMPLETED` / quickboot, so the saved host sat unused until someone opened PhonePi.

Fix: `PackageReplacedReceiver` on `ACTION_MY_PACKAGE_REPLACED` restarts `ConnectionService` from the saved host (same helper as boot). Package replace reconnects whenever a host is saved; it does not use the "Reconnect when the phone boots" toggle. `MY_PACKAGE_REPLACED` is a documented exemption for starting that foreground service. Android-only.

### 0.2.6 — release signing and signed update manifests

Alex approved two items from the security review (nothing else from that review).

**Release signing.** `assembleRelease` signs from a PKCS12 keystore given by `PHONEPI_RELEASE_STORE_FILE` / `PHONEPI_RELEASE_STORE_PASSWORD` / `PHONEPI_RELEASE_KEY_ALIAS` (env or Gradle properties, never committed). `scripts/generate-release-keys.sh` writes `~/.phonepi/release/` at mode 700/600. The updater checks the downloaded APK's signing-cert SHA-256 against a pin computed from that keystore at build time (`BuildConfig.PINNED_RELEASE_CERT_SHA256`) **and** the manifest APK sha256 before showing Install. `REQUIRE_RELEASE_CERT_PIN` is true on release builds, so a debug-signed APK is rejected.

**Signed manifest.** `manifest.json` includes `versionCode`, `versionName`, `sha256`, `minVersion`, and an Ed25519 `signature` over `v1\nversionCode\nversionName\nsha256\nminVersion\n`. The public key is embedded at build time (`UPDATE_MANIFEST_PUBLIC_KEY` from `PHONEPI_UPDATE_PUBKEY` or `~/.phonepi/release/manifest-ed25519.pub`). The app rejects a bad signature and a version downgrade. Force update may reinstall the **same** versionCode only. Box script: `scripts/sign-update-manifest.sh`. Private key stays outside the repo.

**One-time debug → release:** uninstall debug PhonePi, sideload the release APK. Android will not in-app-update across signing certs. Details: `docs/release-signing.md`.

### 0.2.7 — volume control and midnight max

Read-only `get_volumes` (on `READ_ONLY_HTTP_TOOLS`, 60/min): alarm, ring, notification, media, voice/call, system with current/max, plus ringer mode and Do Not Disturb state.

Write command `phone_set_volume` (`POST /api/command`, 20/min): `stream` + `level` (int or `"max"`), or `max_all`. While DND is on, ring/notification changes need **Do Not Disturb access** (`ACCESS_NOTIFICATION_POLICY`). The app requests that grant only when the midnight toggle is on or DND is currently on; alarm volume still works without it.

Optional on-phone **Max volumes at midnight** (default off): `AlarmManager.setExactAndAllowWhileIdle` at local 00:00, rescheduled after fire, on boot, on package replace, and on process start. Android 12+ may need Alarms & reminders (`SCHEDULE_EXACT_ALARM`); the toggle opens that settings page only if the permission is missing.

Volume panel sits on the scrolling main screen between Tool permissions and Notification sharing.

### 0.2.8 — connection stays up, health says when it dropped

The phone reconnects forever. Backoff is exponential with ±20% jitter and a 5 minute cap (1s, 2s, 4s, …). It does not stop in a terminal Failed state. A network change (`ConnectivityManager` `onAvailable` / `onCapabilitiesChanged`) and the app coming to the foreground retry immediately instead of waiting out the cap. The phone sends its own ping and tears the socket down if nothing arrives for 45s, so the UI cannot stay on Connected for a dead link. Status text is `Connected`, `Connecting…`, `Reconnecting in Ns (reason)`, or `Disconnected`. Boot reconnect and package-replace reconnect are unchanged.

The server keeps the phone slot only for the current socket. The previous socket's close or error cannot clear the new one. That was the bug that left `/api/health` at `phoneConnected:false` while the phone still showed connected. Stderr lines start with an ISO-8601 timestamp. Health adds `lastConnectedAt`, `lastDisconnectedAt`, `lastDisconnectCode`, `lastDisconnectReason`, `lastPongAt`, `phonePermissions`, and `missingPermissions`. The phone sends those six grants on connect and when they change: overlay, post notifications, full-screen intent, notification listener, DND access, exact alarms. Health stays read-only on loopback and does not grow a token or personal fields.

Box supervision: `scripts/install-phonepi-user-service.sh` (systemd `--user`, then `loginctl enable-linger`) or `scripts/phonepi-watchdog.sh` when linger is unavailable. `scripts/phonepi-health-check.sh` reads only `http://127.0.0.1:11043/api/health` and exits 0 connected, 1 phone offline, 2 server down.

### 0.2.9 — read-only recent photos

`get_recent_photos` is on `READ_ONLY_HTTP_TOOLS` (30/min): newest camera and gallery images, metadata only, default limit 5, hard cap 20, optional `since` (epoch ms). The MediaStore scan is the newest 100 by `DATE_ADDED` (seconds, converted to ms). `DATE_TAKEN` stays milliseconds. No image bytes.

`get_photo` is a token-gated command (`POST /api/command` and MCP, kind `read`, 12/min), not on `/api/tool`. Image bytes are more sensitive than battery level or the metadata list. Args: `id`, `maxDimension` (default 1600, or `"full"`), `quality` (40–95, default 80), `format` (`jpeg` or `webp`).

The WebSocket protocol has one JSON frame per tool response and no chunking. The phone recompresses and steps quality, then long edge, until the tool JSON is at most 900 KB so the frame stays under the 1 MB ceiling used by the HTTP API. If it still does not fit, the tool returns an error instead of an oversized frame. `"full"` starts at the original long edge, capped at 4096 before that fit loop. It is not the original file.

Returned bytes are a new JPEG or WebP. Original EXIF, including GPS, camera make, and the orientation tag, is not copied. Orientation is applied to the pixels. The JSON metadata does not include coordinates. The pixels can still show a place. Recent activity logs the id, display name, and byte count, not the base64.

Permission: `READ_MEDIA_IMAGES` on Android 13+, `READ_EXTERNAL_STORAGE` (`maxSdkVersion` 32) otherwise. Android 14 "Select photos" (`READ_MEDIA_VISUAL_USER_SELECTED`) counts as granted for the images the user picked. An in-app **Share recent photos** switch defaults off. Both the switch and the OS grant are required. Missing either returns `grantNeeded` (`photo_sharing` or the permission name) and does not throw. The tools only query and `openInputStream`. They never insert, update, delete, or upload.

Health still reports the same six grants. Photo access is not added there.

### 0.3.0 — Clock alarms and timers

Commands (`POST /api/command`, token, 10/min, Recent activity): `set_alarm`, `set_timer`, `dismiss_alarm`, `dismiss_timer`. Read (`POST /api/tool`, no token, 60/min): `list_alarms`.

`set_alarm` is `AlarmClock.ACTION_SET_ALARM` with `EXTRA_HOUR`, `EXTRA_MINUTES`, optional `EXTRA_MESSAGE` (max 100), optional `EXTRA_DAYS` (Calendar day numbers; bots send `weekdays`, `weekends`, `daily`, or weekday names), and `EXTRA_SKIP_UI` true. Hour is 0-23, or 1-12 with `period` am/pm. Example: 6:30 AM weekdays labeled Work.

`set_timer` is `ACTION_SET_TIMER` with `EXTRA_LENGTH` (1 through 86400 seconds), optional message, `EXTRA_SKIP_UI` true. A 15 minute timer is 900 seconds. The old phone-only path that fell back to a PhonePi notification is not the bot result anymore. If Clock cannot take the timer, status is `error`.

`list_alarms` returns `AlarmManager.getNextAlarmClock()` (`nextAlarm`, or null) and `phonePiAlarms`: alarms PhonePi asked Clock to create (hour, minute, label, days, createdAt). Cap 40, newest first, SharedPreferences. Not a full Clock inventory.

Dismiss uses `ACTION_DISMISS_ALARM` (`ALARM_SEARCH_MODE_TIME` or `ALARM_SEARCH_MODE_LABEL`) and `ACTION_DISMISS_TIMER`. There is no dismiss-all. If `resolveActivity` finds no handler, the tool returns `status:"error"`, `started:false`, `supported:false`. `started:true` means the intent was handed off. Android does not confirm the alarm was saved or deleted. Third-party apps cannot edit arbitrary Clock alarms.

Background starts follow the navigation rule. PhonePi fires the Clock intent only when it is in the foreground or **Display over other apps** is granted. Otherwise it posts a full-screen notification and reports `started:false`, and it does not also fire the intent (SKIP_UI can succeed invisibly, and a later tap would create a second alarm). The local alarm log is written only after that handoff.

Permission: `com.android.alarm.permission.SET_ALARM` (install-time, already required for timers). Manifest `<queries>` include `SET_ALARM`, `SET_TIMER`, `DISMISS_ALARM`, and `DISMISS_TIMER`. The server rejects bad hour, minute, label, days, or seconds with HTTP 400 before the phone is called. The phone checks again. The shared token is not logged.

### 0.3.1 — location history

Feasibility: [location-timeline-feasibility.md](location-timeline-feasibility.md). A third-party app cannot trigger Google's Timeline export. No public intent, content provider, or API. Takeout no longer has it. The unofficial Geller fetch and reading `odlh-storage.db` are not sanctioned. PhonePi does not use them, and it does not drive the Settings UI. Alex can export by hand (Settings, Location, Location services, Timeline, Export Timeline data) and share the JSON or pick it with **Import Timeline export**. The parser takes `semanticSegments` and older `timelineObjects`. Raw `locations` points are rejected.

The log bots use is PhonePi's own opt-in history. **Location history** is off by default. It needs `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, and `ACCESS_BACKGROUND_LOCATION` (**Allow all the time**) to record while PhonePi is backgrounded. `ACTIVITY_RECOGNITION` (**Physical activity**) marks drives. While-in-use still logs only while the app is open. Denial leaves the switch off, or shows that background logging will not run.

Capture is `FusedLocationProvider` at balanced power (15 minute idle interval, 3 minutes while a drive is open, 80 m minimum distance), activity transitions, a 150 m exit geofence, and a one-shot significant-motion sensor. It does not poll GPS. A visit is a dwell of 8 minutes inside 120 m. A drive needs about 250 m. Visits are reverse-geocoded with Android `Geocoder` on the device.

Rows live in a separate Room file, `phonepi-location.db`, capped at 8000 events and about 400 days, so this does not bump the main database (that one still uses destructive migration). A Timeline import does not arm a geofence on an old visit. The phone syncs unsynced rows over the existing WebSocket (`location_sync` / `location_sync_ack`). A wipe sends `location_cleared` before any new rows. The box appends `~/.phonepi/location/YYYY-MM.jsonl` (directory 700, files 600). Retention defaults to 12 months (`PHONEPI_LOCATION_RETENTION_MONTHS`, 1 through 120). Same id with an older `updatedAtMs` does not overwrite. An event that carries `token` or another secret field is rejected and not written.

Reads (no token, 30/min, work when the phone is offline): `get_location_history` (`from`, `to`, `max` default 50 cap 200, optional `kind`) and `search_places` (`query`, `max` default 20 cap 50). `YYYY-MM-DD` is a UTC day. An afternoon in California needs an offset such as `-07:00`. Search requires every word (length at least 2) to appear in the place name or address. Writes (token, `/api/command` only): `pause_location_logging` (`paused` boolean, 10/min) and `clear_location_history` (`confirm` must be `"delete"`, 4/min). Invalid args are HTTP 400 and do not call the phone. The phone logs the switch, pause, delete, import, recorded visits, and those two commands in Recent activity. A box-side read sends an `activity` frame when the phone is connected (`activityLogged` on the result). The server log line for a sync is a count, not coordinates, and it never logs the token.

### 0.3.2: safe texts (SMS)

Commands (`POST /api/command`, token): `request_send_text` and `get_send_status`. The phone sends with `SmsManager`. The box does not.

Path choice is in [send-text.md](send-text.md). The Google Messages bridge stays off this feature. It is an unofficial client, and a send from the box would not keep the confirm on the phone. Opening the default messenger would let Alex edit the draft and could not finish a spoken "send" or an assistant-signed send. There is no sanctioned third-party RCS send that also locks the body. Carrier SMS is the path.

`mode` defaults to `as_alex`. That text always needs a tap on Send, or a clear spoken "send" while driving. It expires in 5 minutes. Cancel and expiry are final. `attributed` may send with no tap only when **Allow assistant-signed texts without confirm** is on. The phone itself adds `[Alex's assistant Grok]: `. A bot field such as `confirmed` or `skipConfirm` is HTTP 400. Putting the prefix in the message does not change the mode.

One recipient. A name that matches several contacts, or none, returns `needs_clarification` and sends nothing. No groups. Message cap 1000 characters. Rates are separate: `as_alex` 5/min and 30/day, `attributed` 5/min and 20/day. HTTP 200 is not "sent". Poll `get_send_status`.

Kill switch **Allow bots to request texts** is off by default. Off rejects new requests and cancels pending texts that have not reached the carrier. Grants: `SEND_SMS`, `READ_CONTACTS` (names), `POST_NOTIFICATIONS`, `RECORD_AUDIO` (spoken confirm), `ACTIVITY_RECOGNITION` (IN_VEHICLE), `BLUETOOTH_CONNECT` (car audio or hands-free). Spoken confirm uses a short microphone foreground service, not the all-day connection service. Audio focus is transient and may duck.

Logs: Recent activity on the phone, and `~/.phonepi/sent-texts.jsonl` on the box (timestamp, name, masked number, message, outcome, confirm method, mode). The token is not logged. versionCode is **14** so it sits after the location-history versionCode 13.

Bots send only when Alex names the person and the message in that moment. Not on their own. Not as a reply. Not as an acknowledgement.

## Mistakes and fixes

| Symptom | Cause | Fix |
| --- | --- | --- |
| `phone_navigate` reported `started:true` / `method:activity` but Maps never opened (Pixel 9 Pro XL, 0.2.1) | Android 10+ **background activity launch** can swallow `startActivity` with no exception. Returning from `startActivity` is not proof the activity ran. | Trust the start only if overlay is granted or PhonePi is in the foreground. Otherwise always FSI and report `full_screen_notification`. Grant **Display over other apps**. |
| `assembleDebug` failed in `NotificationMapper.kt` on `Notification.EXTRA_SUBSTITUTE_APP_NAME` | That constant is `@hide`. The SDK used for 0.2.3 does not expose it. | Read extras with the public key string `"android.substName"`. Confirm with a real `./gradlew assembleDebug`, not kotlinc alone. |
| Main screen drew under the status bar and gesture nav; **Share all** clipped; per-app list and **Recent activity** unreachable (0.2.3) | Edge-to-edge without window insets. Parent `Column(fillMaxSize)` did not scroll. `ActivityLogPanel(Modifier.weight(1f))` plus a nested `LazyColumn` was squeezed to zero height once Notification sharing took the rest of the screen. Nested `heightIn(max = 280.dp).verticalScroll` on the app list hid rows behind the nav bar. | `WindowInsets.safeDrawing` on the scaffold. One parent `verticalScroll`. Wrap-content log and share rows. Keep **Recent activity** / **Clear** last, below Notification sharing. |
| Phone could not resolve the Tailscale host (`*.ts.net`) even though the PC was up | Tailnet MagicDNS was not the resolver the phone used. | Turn on Tailscale DNS / MagicDNS on the phone (Tailscale app DNS settings, not a PhonePi code change). Then reconnect. |
| `adb connect` to wireless debugging stopped working after a Wi-Fi or tailnet change | Android **wireless debugging** assigns a new TCP port when the network changes. The old `ip:port` is dead. | Re-open Wireless debugging on the Pixel, copy the new port, pair/connect again. Do not bake a port into scripts. |
| After `phone_push_update` / in-app Install, the phone stayed disconnected (2026-10-13) | The installer replaces the APK and kills the process. Boot reconnect does not run on package replace. | `ACTION_MY_PACKAGE_REPLACED` receiver starts `ConnectionService` from the last saved host. |
| `/api/health` said `phoneConnected:false` after the phone reconnected, and the phone UI stayed on Connected (2026-10-16) | `ws.on("close")` always assigned `phoneConnection = null`. `close()` is async, so the old socket's close ran after the new socket was stored and cleared it. The server then stopped pinging. The phone had no timeout of its own, and it gave up after about 10 tries. | `PhoneSession.clearIfCurrent` ignores close/error/timeout from any socket that is not the current one. The phone pings and drops a silent socket after 45s, then reconnects forever. |
| A "Tuesday afternoon" query missed a drive that was in that afternoon in California | `YYYY-MM-DD` and bare UTC instants are not Pacific time. A drive at 11:30 Pacific is 18:30 UTC, which is outside `12:00-07:00` to `18:00-07:00`. | Document that date-only bounds are UTC. Bots send an offset for local afternoon. `search_places` matches every word, so `pizza vista` hits a name and an address that are not one substring. |
| `buildJsonObject { put("endMs", endMs ?: JsonNull) }` failed Kotlin compile | `Long?` and `JsonNull` become `Any`, and `put` wants a `JsonElement`. | `endMs?.let(::JsonPrimitive) ?: JsonNull`. |

## iPhone: Android-only vs portable

Portable (keep this contract if an iPhone companion is built):

- MCP tool names, JSON params, allowlists, sliding-window rate limits, on-device activity log.
- One authenticated socket from the phone; loopback HTTP on the PC for extra local clients so they do not steal the phone socket.
- Locations stored on the device; the PC never launches maps itself.
- Default-deny sharing (notification allowlist empty, Share all off).
- Honest status: if the OS blocked the action, say so in the tool result. Do not invent `started:true`.
- Updates: serve a signed artifact + Ed25519-signed manifest (version, hash, minVersion) over the same host and token; verify hash, signature, and a pinned release signing identity; reject downgrades; the human confirms install. Never silent install. Never put private keys in git.
- Secrets stay in env / phone prefs. Not in git.

Android-only (do not assume iOS equivalents):

- `Intent` / URI schemes (`google.navigation:`, `geo:`), package `com.google.android.apps.maps`, Android Auto **Start now**.
- BAL, `SYSTEM_ALERT_WINDOW`, full-screen intent notifications, `NavigateBridgeActivity`.
- `NotificationListenerService` reading **other apps'** notifications. iOS has no public equivalent; a Notification Service Extension only sees this app's own notifications unless Apple grants a special entitlement.
- `PackageInstaller`, `REQUEST_INSTALL_PACKAGES`, **Install unknown apps**, FileProvider APKs, and pinning the APK **signing certificate** SHA-256. iOS distribution is TestFlight / App Store / MDM; you cannot swap a debug-signed build for a release-signed one in place on Android either (uninstall once).
- `RECEIVE_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`. iOS has neither; reconnect on next launch or a background task the OS may not run. After an iOS update the user usually opens the app anyway — restore the socket there from saved endpoint prefs.
- Compose edge-to-edge / `WindowInsets.safeDrawing`.
- ADB wireless debugging and its changing TCP port.
- Hidden `Notification` extras such as `android.substName`.
- `AudioManager` streams, Do Not Disturb policy access, and `AlarmManager` exact midnight alarms. iOS volume APIs and Focus modes differ; a nightly max would be a local notification or BG task, not `BOOT_COMPLETED`.
- `MediaStore.Images`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`, and `READ_EXTERNAL_STORAGE`. iOS uses `PHPhotoLibrary` authorization. Keep the tool names, the default-off share switch, the token gate on image bytes, and recompress-before-send so GPS EXIF does not ride along.
- `AlarmClock` intents (`SET_ALARM`, `SET_TIMER`, `DISMISS_ALARM`, `DISMISS_TIMER`), `SET_ALARM` permission, and `AlarmManager.getNextAlarmClock()`. iOS has no public API for another app to create or dismiss Clock alarms. Keep the tool names and the honest "not confirmed" status; an iPhone companion would have to say the action is unsupported.
- `FusedLocationProvider`, `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION`, activity-transition PendingIntents, geofences, `Sensor.TYPE_SIGNIFICANT_MOTION`, and `Geocoder`. iOS would use Core Location visits and `CLGeocoder`. Keep the tool names, the opt-in default off, the JSONL copy on the box, and the rule that a Timeline export is a file the person shares. Google's Settings path for Timeline export is Android-only.
- `SmsManager`, `SEND_SMS`, notification actions, `TextToSpeech`, `SpeechRecognizer`, `ACTIVITY_RECOGNITION`, and Bluetooth device class. Keep the command names, the two modes, the phone-added prefix, the default-off switches, and "HTTP 200 is not sent". iOS cannot send SMS from a third-party app without the system composer, which the user can edit. An iPhone companion would have to say direct send is unsupported.
- Forever reconnect with a capped exponential backoff is portable. `ConnectivityManager` callbacks and the foreground-service keepalive are Android-only. On iOS, retry when the app becomes active and when a path monitor reports a change. Do not show connected until the socket has had recent traffic.

iPhone maps/nav sketch (not implemented): open an Apple Maps or Google Maps iOS URL when the companion is allowed to become foreground. Do not expect to start Maps from a background daemon the way overlay+FSI does on Android. A tap-to-open notification is the likely fallback.

iPhone notification reading sketch (not implemented): without an Apple entitlement, do not promise `get_notifications` for other apps. Push that limitation in the tool schema rather than returning an empty list that looks like "no notifications".

## Device notes (Pixel + Tailscale)

- Overlay is the grant that actually opens Maps from the background. FSI is the fallback, not a substitute for overlay if you want Maps to appear unprompted.
- Keep the PhonePi connection notification if the OS otherwise kills the socket when Maps comes to the front.
- Tailscale Serve terminates TLS on the PC; the phone still needs MagicDNS (or a literal tailnet IP) and the WS token.
- Sideload **0.3.1** (or in-app update from a 0.3.0 release build). Debug → release is still a one-time uninstall. Later APKs go in `~/.phonepi/updates` and must be signed with `scripts/sign-update-manifest.sh`.
- Location history stays off until Alex turns it on and grants precise location plus **Allow all the time**. **Physical activity** improves drives. Pause and Delete all history are on the phone. A Timeline backfill is a file he exports himself and shares into PhonePi.
- Clock alarms need Google Clock (or another app that handles the intents) and, for a silent background set, **Display over other apps**. `SET_ALARM` is granted at install. Dismiss can fail when the Clock app has no dismiss activity; the tool says so.
- Recent photos stay off until Alex turns on **Share recent photos** and grants Photos. `get_photo` needs the shared token. The bytes are recompressed, so GPS EXIF is not in the payload.
- Texts stay off until Alex turns on **Allow bots to request texts** and grants SMS, contacts, and notifications. Assistant-signed texts stay off until the second switch. Spoken confirm needs the microphone. Car detection also wants physical activity and nearby Bluetooth devices. Sideload **0.3.2** (versionCode 14).

## Box refresh recovery (2026-10-18)

A box refresh wipes `/usr/local/bin/tailscale*`, the Tailscale state, and `server/dist`, but `~/.phonepi` (release key, updates, env, cert) survives. The phone then sits at "Connecting".
One-command fix: `scripts/restore-after-refresh.sh` (idempotent). It installs Tailscale 1.104.1 if missing, starts tailscaled (userspace, SOCKS5 127.0.0.1:1055, state `/var/lib/tailscale`), rebuilds `server/dist` if missing, starts the watchdog, and re-applies `tailscale serve --https=443 → 127.0.0.1:11041` when logged in.
Tailscale state does not survive a refresh, so login is needed every time: `sudo tailscale up --hostname=grokbot-box`, approve as akcool151, then rerun the script. If the node comes back as `grokbot-box-1`, delete the stale `grokbot-box` machine in the admin console and rename it, or the phone's saved address breaks.

## Self-healing after box refresh (2026-10-18, supersedes the manual login above)

- **Persisted paths** (both `~/.phonepi` and `/workspace` survive a refresh; `/usr/local/bin`, `/var/lib`, `server/dist` do not):
  - Tailscale binaries: `~/.phonepi/bin/{tailscale,tailscaled}` (1.104.1). Download is a fallback only.
  - Tailscale state: `~/.phonepi/tailscale/` (`tailscaled.state`, socket `tailscaled.sock`, log `tailscaled.log`).
- tailscaled now runs **as the box user**, not root: userspace networking needs no root, and box-owned state can be copied by the home sync. `/var/run/tailscale/tailscaled.sock` is symlinked to it, so `sudo tailscale ...` still works. Otherwise use `tailscale --socket=~/.phonepi/tailscale/tailscaled.sock ...`.
- Login: persisted state normally means no login is needed. If logged out, the script uses `TS_AUTHKEY` (reusable, non-ephemeral auth key from Alex), passed through an fd (`--auth-key=file:/dev/fd/8`), so it is never in argv, logs, or child envs. **Never print or commit it. Do not use TAILSCALE_API_KEY.** The key's expiry is unknown. When it expires and the state is lost, the script logs "tailscale up failed" and Alex must mint a new key.
- Autostart: there is no systemd or cron. A guarded block at the top of `~/.bashrc` (snippet in `scripts/bashrc-autorestore.snippet`) launches the script detached and silent whenever tailscaled or the watchdog is not running. Agent shells start with `bash -ilc` (`~/.profile` → `~/.bashrc`), so the first Shell call by any agent after a refresh heals the box. The script re-adds the hook if `~/.bashrc` was reset. Log: `~/.phonepi/autorestore.log`.
- The script is flock-guarded (`/tmp/phonepi-restore.lock`). Daemons are started with `9>&-` so they don't inherit the lock. (Bug found: tailscaled held the lock forever.)
- It also starts the read-only Google Messages bridge (127.0.0.1:11042) through `get-texts.sh -status` if it is down, with OPENMESSAGES_READONLY stripped as before.

## 0.3.3 (versionCode 15): server list + automatic failover (2026-10-23)

- **Servers list** (`ServerPrefs`, prefs file `phonepi_servers`): name, host, port, token, primary flag. One-time migration from 0.3.2 seeds **HP** (`hp1.tailcbcc46.ts.net:443`, primary) and **Box** (the host/port 0.3.2 last connected to, else `grokbot-box.tailcbcc46.ts.net:443`, fallback). The saved token carries over to both. No retyping. `TailscaleNet.hostToIp` now maps `hp1.tailcbcc46.ts.net → 100.108.138.113` (same Private-DNS workaround as the Dell). Box is not mapped. It keeps resolving the way 0.3.2 did.
- **Failover state machine** (`FailoverMachine`, pure Kotlin, unit-tested). `WebSocketManager` feeds it events. One live WebSocket at a time.
  - Start on the primary (or the pinned server).
  - Leave the primary after **3 failed attempts** or **15 s** since the link went down, whichever comes first. A 15 s deadline timer also cancels a hanging handshake. Any single open that has not finished the handshake within **10 s** counts as a failure. With fast failures (refused or 502), the attempts land at 0 s, ~1 s and ~3 s, so the Box takes over in about 3–4 s. With a dead host (dropped SYN), it takes 15 s.
  - The first hop of an outage opens at once. Later hops (both servers down) only happen after 3 failures and keep the normal 1 s → 5 min backoff, so a total outage does not hammer either server.
  - While on a fallback, the app **re-probes the primary every 3 min** with an HTTPS `HEAD /phonepi/update/manifest` (Bearer token, 5 s/8 s timeouts). It never opens a second WebSocket. A healthy answer is any 2xx–4xx except 401/403. A 5xx (Serve 502 when node is down), a timeout, or an auth failure counts as down. When the primary is healthy the app closes the Box link and opens the HP link immediately.
  - Every switch is logged in Recent activity as `Server switch: HP → Box (3 failed attempts)` and similar. Connection logs no longer include the `?token=` query.
- **Connect screen** shows `Server: HP (primary) · auto-failover on`, a **Switch server** row (`Auto`, `HP`, `Box`), the server list, and an **Automatic failover** switch. Tapping a server pins it (manual override, no failover, no re-probe). **Auto** clears the pin. Turning the switch off keeps the app on the primary (or pin) and never hops. Typing a host and pressing Connect saves it to the list and pins it. Forget saved host wipes the list and does not reseed.
- **Setup link**: `phonepi://setup?host=&port=&token=&name=&primary=true`. A missing `token` keeps the saved one, and a new server borrows the shared token. `primary=true` makes it the primary and returns to Auto. Without `primary=true` the link adds or updates the server and pins it (the same "connect there" meaning as 0.3.2).
- **Send retry**: `send_text_log` frames (the phone → server record of each text) carry a stable `requestId`, reused on every retry. If the first hand-off fails because there is no live socket, the app retries after **2 s, 5 s, 10 s** (17 s total, which covers the 15 s failover) through `PhoneUplink`, so a retry goes to whichever server is now active. After that it logs an error in Recent activity, and the line stays in the outbox for the next connect. A frame the socket accepted is never resent. Retry and outbox flush remove the line under one lock, so the phone sends each line at most once. The SMS itself is sent by the phone's carrier path and is never retried by this code. **Follow-up (server):** the server does not ack `send_text_log` or dedupe by `requestId` yet. Add both so retries can also cover "socket accepted, but the link died before the server read it".
- Release flow: build → `~/.phonepi/updates-staging/0.3.3/` → `scripts/sign-update-manifest.sh --dir <staging> --apk phonepi-0.3.3-15.apk --min-version 8`. Copy the APK and manifest into `~/.phonepi/updates` (and the HP's update dir) only after the HP server is confirmed. Because of the migration, the first connect after install goes to the HP.

## HP primary, box standby (2026-10-23)

Alex: "Make the HP the main computer we run everything off of… HP primary, the box standby/backup running PhonePi's server too." Server side only here; the app-side failover is a separate change.

- **HP (`hp1`, 100.108.138.113, `hp1.tailcbcc46.ts.net`):** Node 22 LTS in `/opt/node22` (symlinked into `/usr/local/bin`), code in `/opt/phonepi`, systemd system unit `phonepi-server.service` (User=belhun, Restart=always), env `/etc/phonepi/env` (root 600: `PHONEPI_WS_TOKEN` (same value as the box), `PHONEPI_UPDATE_DIR`, `PHONEPI_WS_HOST=127.0.0.1`, `PHONEPI_HTTP_PORT=11043`), data and log in `/home/belhun/.phonepi`. Phone socket binds 127.0.0.1:11041; the only way in is `tailscale serve --https=443 → 127.0.0.1:11041` (`wss://hp1.tailcbcc46.ts.net/phonepi`). Seeded with a copy of `updates/`, `notifications/`, `location/`.
- **Server `--host` / `PHONEPI_WS_HOST`:** bind address for the phone WebSocket. Default stays `0.0.0.0` (box unchanged).
- **Box bot API moved 11043 → 11045** (`PHONEPI_HTTP_PORT=11045` in `~/.phonepi/env`). **127.0.0.1:11043 is now the failover front** (`scripts/phonepi-failover.py`, supervised by `scripts/phonepi-failover.sh`, venv `~/.phonepi/venv`, log `~/.phonepi/failover.log`). Bots call 11043 unchanged.
  - Routing follows the phone (it holds one socket, to one server): HP up + phone there → HP; box up + phone there → box; phone on neither → the server it was last on, else HP, else box. A connect failure retries once on the other backend; a request that already reached a backend is never replayed.
  - HP leg: paramiko SSH `direct-tcpip` to HP 127.0.0.1:11043 over the box's Tailscale SOCKS5 (127.0.0.1:1055) with the grok-assistant key (persisted copy `~/.phonepi/ssh/grok-assistant`, HP host key pinned in `~/.phonepi/ssh/known_hosts`). The HP bot API stays loopback-only.
  - Responses carry `X-PhonePi-Server: hp|box`; `/api/health` adds `servedBy`. `GET /api/failover` returns the routing state (also `~/.phonepi/failover-state.json`).
  - `scripts/phonepi-target` shows the active server and both healths; `phonepi-target pin hp|box` / `phonepi-target auto` override.
  - History: every 30 min the **standby** gets any notification/location entries it lacks from the active server (merge by `id` + newest `updatedAt`, convergent), then only the standby is restarted to load them. The active server is never touched. Readers going through 11043 (e.g. the Home · Notifications triage, which uses `get_notification_log` with a `nextSince` cursor and a 7-day content-hash dedupe) see the server the phone is on.
  - `restore-after-refresh.sh` now checks the box server on 11045 and starts the failover front; the `~/.bashrc` hook also fires when `phonepi-failover.sh run` is missing.
  - Cutover measured 2026-10-23: phone reconnected to the box 6.8 s after the box server restart; a front restart leaves 11043 dark ~2 s.
- **Google Messages bridge + send gateway stay box-only** (11042/11044). Running a copied session on a second host would fight the one paired web session, and the session must not be copied. An HP standby bridge would need its own pairing by Alex.
- **Setup links:** `~/.phonepi/setup-links/{hp,box}-setup-{link.txt,qr.png}` (mode 600) hold `phonepi://setup?host=…&port=443&token=…&name=…`. Never paste them in chat.
- **Mistake fixed:** `/phonepi/update/manifest` returned 404 "manifest sha256 does not match phonepi-0.3.0-12.apk" on both servers. `pickApkFile` takes the newest mtime and the post-refresh copy left 0.3.0 newer than 0.3.2. Fixed by touching `phonepi-0.3.2-14.apk`; a code fix (prefer the manifest's `fileName`) is still open.
- **Open:** the HP node key expires 2026-12-22 08:27 UTC (Dec 22 12:27 AM PST); disable key expiry for hp1 in the admin console.

## PhonePi Clock 0.1.0 (versionCode 1)

Separate app, `dev.phonepi.clock`, so a companion update cannot drop an alarm. Phase 1 only. Sounds are ringtones and files the user picked. Spotify is a reserved redirect (`phonepi-clock://spotify-callback`) with no client code. "Hey Google" alarms stay in Google Clock. The Saturday Google Clock alarms were not copied.

- Alarm state is on the phone (Room, device-protected storage). The box keeps `~/.phonepi/clock/snapshot.json` for offline reads (`stale:true`). Writes are HTTP 503 when the phone is offline and are not queued.
- Idempotency is the phone ledger (`requestId` UUID, 7 days, first body wins). The server passes the id through and does not replay.
- IPC is a signature permission, `dev.phonepi.clock.permission.CONTROL`, plus provider `dev.phonepi.clock.provider`. Both APKs must share a cert. Debug uses `~/.android/debug.keystore` (`androiddebugkey` / `android`). Release uses `PHONEPI_RELEASE_STORE_FILE`, the same keystore as the companion. SHA-1 for a future Spotify app: `./gradlew :app:signingReport` in `phonepi-clock`, or `keytool -list -v`. Use the cert of the build that is installed. Details in [clock/IPC.md](clock/IPC.md).
- Day bits are Mon=1 through Sun=64. A spring-forward gap rings at the next valid minute. A fall-back hour rings once, on the earlier offset.
- `scripts/clock-emulator-test.sh -s SERIAL` installs the clock APK, then the companion, and checks `dumpsys alarm` for `dev.phonepi.clock` after Doze, a timezone change, a locked reboot, and an `adb install -r` with a higher versionCode. It takes an emulator or a device serial. It sets a PIN only when the device has no credential, and it restores the date, timezone, and that PIN. Do not point it at the Pixel unless that reboot and lock change is intended.
