# PhonePi MCP: Turn your phone into a toolbox for AI assistants using Model Context Protocol

A powerful MCP server and companion app that allows you to manage and control your phone remotely using natural language commands via your favorite MCP-supported AI apps such as Cursor and Claude Desktop. This project provides a comprehensive set of tools and APIs to interact with your mobile device programmatically.

## Overview

Phone MCP enables you to:
- Manage contacts and messages
- Handle snippets and notes
- Control phone settings and notifications
- Send SMS and make calls
- Share content across apps
- Monitor battery status
- Set timers and reminders
- Find your phone with audio alerts

## Security
Security is a high concern. Here are a few aspects taken into account around security:
- You are completely in charge around hosting the MCP server locally. The code is completely open source.
- On the app front, you are in charge of what permissions to give depending on the features you want to use.
- The app and the server are linked over your local network. There's no 3p remote servers involved in linking.
- It is highly recommended you use this app over a secure network. Avoid public networks like airports and restaurants.
- Tailscale is highly recommended for creating a private & secure VPN to connect your phone and desktops

## Phone navigation (Maps)

The MCP server can ask the PhonePi Android app to open Google Maps navigation. Destinations live on the phone. The PC never fires Android intents.

MCP tools:

- `phone_list_locations`
- `phone_add_location`
- `phone_remove_location`
- `phone_navigate`

`phone_navigate` starts Maps with `google.navigation:q=<encoded>` (then `geo:0,0?q=` / a Maps HTTPS URL). Package is `com.google.android.apps.maps`. On Android Auto, Maps shows its usual **Start now** prompt; PhonePi does not skip that.

See [server/README.md](server/README.md) for the loopback `POST /api/command` API and [phonepi-android/README.md](phonepi-android/README.md) for what to install and grant on the phone.

Standing log of shipped features, mistakes, and iPhone portability: [docs/LEARNINGS.md](docs/LEARNINGS.md). Update that file with every future PhonePi change on this branch. Never commit secrets.

## In-app update

The MCP server serves the latest APK at `GET /phonepi/update/manifest` and `GET /phonepi/update/apk` (same host as the phone WebSocket, `PHONEPI_WS_TOKEN` required). Drop a release APK in `PHONEPI_UPDATE_DIR` (default `~/.phonepi/updates`) and sign `manifest.json` with `scripts/sign-update-manifest.sh`. `phone_push_update` verifies the Ed25519 signature, APK sha256, and pinned release cert, then shows the system installer; Alex still taps Install. Debug → release is a one-time uninstall; see [docs/release-signing.md](docs/release-signing.md).

## Alarms and timers

Companion **0.3.1**. `list_alarms` is a no-token read (`POST /api/tool`): the next alarm `AlarmManager` reports, plus alarms PhonePi created. `set_alarm`, `set_timer`, `dismiss_alarm`, and `dismiss_timer` are token commands (`POST /api/command`, 10/min). They use the standard Clock intents (`ACTION_SET_ALARM`, `ACTION_SET_TIMER`, `ACTION_DISMISS_ALARM`, `ACTION_DISMISS_TIMER`) with `SKIP_UI` on create. Example: 6:30 AM weekdays labeled Work, or a 15 minute timer (`seconds: 900`).

## Location history

Companion **0.3.1**. Google's Timeline export cannot be triggered by an app. PhonePi keeps its own opt-in log (off until Alex turns **Location history** on) and can import a Timeline JSON he exports by hand. See [docs/location-timeline-feasibility.md](docs/location-timeline-feasibility.md).

No-token reads (`POST /api/tool`, 30/min), from the box copy, including when the phone is offline:

- `get_location_history` with `from`, `to` (epoch ms, ISO-8601, or `YYYY-MM-DD` as a UTC day), `max` (default 50, cap 200), optional `kind` `visit` or `drive`
- `search_places` with `query` and `max` (default 20, cap 50). Every word must match the place name or address. Returns the address and visit dates.

Where was I last Tuesday afternoon:

```json
{"tool":"get_location_history","arguments":{"from":"2026-10-13T12:00:00-07:00","to":"2026-10-13T18:00:00-07:00","max":20}}
```

Address of the pizza place in Vista:

```json
{"tool":"search_places","arguments":{"query":"pizza vista"}}
```

Token commands: `pause_location_logging` (`paused` true or false, 10/min) and `clear_location_history` (`confirm` must be `"delete"`, 4/min). The phone has the same pause and delete actions. The box stores `~/.phonepi/location/YYYY-MM.jsonl` for 12 months unless `PHONEPI_LOCATION_RETENTION_MONTHS` says otherwise.

## Presence

Companion presence snapshot for bots that need to know where Alex is before messaging him. **Presence** on the phone starts on. Grant location **Allow all the time**, then mark saved places as presence. Details: [docs/presence.md](docs/presence.md).

No-token read (`POST /api/tool`, 30/min), from the box copy, including when the phone is offline:

- `get_presence` — place label, minutes there, driving/moving, Do Not Disturb, lat/lng/accuracy, `capturedAtMs` freshness, plus `expected_place` / `matches_expected` from `~/.phonepi/presence/config.json`

Default work hours in that config (editable, not hard-coded): 08:00–17:00 Monday–Friday, `America/Los_Angeles`. Church hours stay empty. Place names stay empty until Alex maps home/work/church to saved places.

Token commands: `pause_presence_logging` (`paused` true or false, 10/min) and `clear_presence_history` (`confirm` must be `"delete"`, 4/min).

Urgent PhonePi messages use the **PhonePi urgent messages** channel. PhonePi does not change Do Not Disturb. Alex can allow that channel through DND himself.
## Screen time

Companion **0.3.4**. Daily totals from Android `UsageStatsManager`. No event timeline and no notification contents.

`get_screen_time` is a no-token read (`POST /api/tool`, 30/min) from the box copy, including when the phone is offline (`stale` true then, or when the last sync is older than 30 minutes):

- `days` (default 7, cap 90) or `from` / `to` as `YYYY-MM-DD` local phone dates
- `top` (default 10, cap 25) apps by foreground minutes
- Returns screen-on minutes, unlock count, top apps, and last time the phone was used

```json
{"tool":"get_screen_time","arguments":{"days":7,"top":10}}
```

Token commands: `pause_usage_logging` (`paused` true or false, 10/min) and `clear_usage_history` (`confirm` must be `"delete"`, 4/min). The master switch is on by default. Alex still has to grant **Usage access** in Settings. The box stores `~/.phonepi/usage/YYYY-MM-DD.json` (dir 700, files 600) for 90 days unless `PHONEPI_USAGE_RETENTION_DAYS` says otherwise.
## Sleep and health

Companion **0.3.4**. Health Connect on the Pixel (Galaxy Watch 7 via Samsung Health). PhonePi does not use the Samsung Health Data SDK.

Read-only `get_health_summary` (`POST /api/tool`, 30/min) returns last night's sleep, steps today and 7-day, resting and average heart rate, and recent workouts as **daily totals**. No raw heart-rate streams. `lastNightSleepEndMs` is the sleep end for later Clock bedtime/wake. Works when the phone is offline (`stale` true). Samsung Health can lag minutes to hours; `syncedAtIso` is the last Health Connect read.

```json
{"tool":"get_health_summary","arguments":{}}
```

The in-app **Sleep and health** switch starts on. Alex still grants Health Connect after the privacy screen. Token commands: `pause_health_sync` (`paused` true or false, 10/min) and `clear_health_cache` (`confirm` must be `"delete"`, 4/min). Box files: `~/.phonepi/health` (dir 700, files 600). See [docs/health.md](docs/health.md) for the Samsung Health → Health Connect taps.

Android will not let PhonePi edit or delete arbitrary Clock alarms. A dismiss result says whether Clock accepted the intent. It does not prove the alarm is gone. If that Clock app has no dismiss handler, the phone returns `status:"error"`. HTTP 200 still has to be parsed for `status` and `started`.

## Texts

Companion **0.3.2**. Bots ask with `request_send_text` (`POST /api/command`, token). The phone sends carrier SMS with `SmsManager` after its own rules. The box does not send the text, and this does not use the Google Messages bridge.

`as_alex` (the default) reads as Alex and always needs a tap on Send, or a spoken "send" while driving. It expires in 5 minutes. `attributed` sends only when **Allow assistant-signed texts without confirm** is on. The phone adds `[Alex's assistant Grok]: ` itself. Both switches start off. **Allow bots to request texts** is the kill switch.

One recipient. An unclear name returns `needs_clarification` and sends nothing. HTTP 200 is not "sent". Poll `get_send_status`. Cancel and expiry are final. Send only when Alex names the person and the message in that moment. Details: [docs/send-text.md](docs/send-text.md).

## Volumes

Read-only `get_volumes` (loopback `POST /api/tool` and MCP) returns alarm, ring, notification, media, voice, and system current/max, plus ringer mode and Do Not Disturb. `phone_set_volume` is a write command (`stream` + `level` or `"max"`, or `max_all`). Optional **Max volumes at midnight** on the phone uses an exact alarm so the clock is audible if the PC is offline. Ring/notification changes while DND is on need Do Not Disturb access, requested only when that grant is needed.

## Notification reading

Read-only. MCP and `POST /api/tool` expose `get_notifications` (filters: `package`, `since`, `limit`) and `list_notification_apps`. They never dismiss, reply to, or act on a notification. By default no app's bodies are shared until they are picked in PhonePi (Share all is off). If Android withholds a field, the payload says so instead of omitting it.

## Getting Started

For detailed documentation, features, and setup instructions, please visit:
[phonepimcp.com](https://phonepimcp.com)

### Google Messages bridge (SMS + RCS)

To pair or run the Google Messages sync server from the repo root:

```powershell
.\pair-gmessages.ps1    # one-time QR pairing
.\serve-gmessages.ps1   # sync server + web UI
```

See [messages-bridge/README.md](messages-bridge/README.md) for build prerequisites and details.

## Support & Issues

If you encounter any bugs or have feature requests, please [open an issue](https://github.com/yourusername/phone-mcp/issues) on our GitHub repository. We actively monitor and respond to issues.

## Contributing

We welcome contributions from the community! Here's how you can contribute:

1. **Fork the Repository**
   - Create your own fork of the code
   - Make your changes in a new branch

2. **Code Style**
   - Follow the existing code style and conventions
   - Include comments where necessary
   - Write clear commit messages

3. **Testing**
   - Wait for the maintainer to test your changes

4. **Submit a Pull Request**
   - Provide a clear description of the changes
   - Reference any related issues
   - Wait for review and address any feedback

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Security

If you discover any security-related issues, please open an issue instead of using the issue tracker.
