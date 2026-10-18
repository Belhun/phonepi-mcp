# PhonePi MCP

A MCP server that lets you control your phone from your favorite AI apps!

## Prerequisites
You need to setup the PhonePi MCP app on your phone. Please visit [PhonePi MCP](https://phonepimcp.com) for instructions.

# MCP Server Setup
Simply follow the steps below to setup the server in your favorite AI apps such as Claude, Cline or Cursor.

## Installation

Install the package globally:

```bash
npm install -g phonepi-mcp
```

Or run directly with npx:

```bash
npx phonepi-mcp
```

## Setup
Simply add the following MCP config to your AI app:
```
{
  "mcpServers": {
    "phonepi-mcp": {
      "command": "npx",
      "args": [
        "phonepi-mcp",
        "start"
      ]
    }
  }
}
```

Save the file with the above settings. This is all you need for your setup besides the mobile app.

## Connecting
* Ensure your phone and your desktop are on the same network. Using something like Tailscale is recommended for convenience, but it's optional.
* Enter the local ip address and port (11041) in the companion mobile app to connect to the server.
* Once everything's connected correctly, you can simply start asking your AI assistant to do stuff! For example, try asking it to send a test notification to your phone.

## Local read-only HTTP API

The same Node process also listens on `127.0.0.1:11043`. Local PowerShell assistants can read phone data there without opening a second WebSocket. The phone keeps the one socket on port 11041. A second WebSocket client still kicks the phone off, so other tools should use this HTTP API instead.

Stdout stays the MCP channel. HTTP logs go to stderr. If port 11043 is already taken, the process logs that and keeps serving MCP.

### Ports and flags

| Name | Default | Override |
| --- | --- | --- |
| Phone WebSocket | `0.0.0.0:11041` | `--port` |
| Loopback HTTP | `127.0.0.1:11043` | `--http-port` or `PHONEPI_HTTP_PORT` |
| Phone WebSocket token | off | `PHONEPI_WS_TOKEN` |

`--http-port` wins over `PHONEPI_HTTP_PORT`. The HTTP listener always binds `127.0.0.1`. It will not bind a LAN or Tailscale address.

```bash
node dist/index.js --stdio --http-port 11043
```

```powershell
$env:PHONEPI_HTTP_PORT = "11043"
$env:PHONEPI_WS_TOKEN = "choose-a-long-secret"
```

Do not commit the token. The server never logs its value.

### `GET /api/health`

```json
{
  "ok": true,
  "phoneConnected": false,
  "wsPort": 11041,
  "httpPort": 11043,
  "lastConnectedAt": null,
  "lastDisconnectedAt": "2026-10-17T05:40:00.000Z",
  "lastDisconnectCode": 1006,
  "lastDisconnectReason": "abnormal closure",
  "lastPongAt": "2026-10-17T05:39:30.000Z",
  "phonePermissions": {
    "overlay": true,
    "post_notifications": true,
    "full_screen_intent": true,
    "notification_listener": false,
    "dnd_access": false,
    "exact_alarms": true
  },
  "missingPermissions": ["notification_listener", "dnd_access"]
}
```

No contact names, numbers, snippets, or token. `ok` means this HTTP API is up. `phoneConnected` is whether the current phone socket is open. `lastConnectedAt`, `lastDisconnectedAt`, and `lastPongAt` are ISO-8601 timestamps or null. `lastDisconnectCode` and `lastDisconnectReason` describe the latest drop (for example `1006` / `abnormal closure`, or `no pong received`). `phonePermissions` is the allowlisted grant map the phone last sent. `missingPermissions` lists the ones that are not granted. A replaced socket's close cannot clear a newer connection, so `phoneConnected` stays true after a reconnect.

Every server log line on stderr starts with an ISO-8601 timestamp.

### Supervision

The process is `node dist/index.js` in the `server/` directory. Env comes from `/home/box/.phonepi/env` (mode 600). Logs go to `/home/box/.phonepi/server.log`.

Prefer a systemd user unit. It does not need root to install. `loginctl enable-linger` is what makes it start at boot with nobody logged in. If linger is denied, use the watchdog instead.

```bash
scripts/install-phonepi-user-service.sh install
loginctl enable-linger "$USER"
systemctl --user status phonepi-server.service
tail -f /home/box/.phonepi/server.log
scripts/install-phonepi-user-service.sh uninstall
```

Watchdog fallback (no systemd, no root). `install` adds a crontab `@reboot` line.

```bash
scripts/phonepi-watchdog.sh start
scripts/phonepi-watchdog.sh status
scripts/phonepi-watchdog.sh install
scripts/phonepi-watchdog.sh stop
scripts/phonepi-watchdog.sh uninstall
```

`scripts/phonepi-server.sh` sources the env file and execs `node dist/index.js`. It does not print the env.

### Offline check

`scripts/phonepi-health-check.sh` only GETs `http://127.0.0.1:11043/api/health`.

| Exit | Meaning |
| --- | --- |
| 0 | Phone connected |
| 1 | Server is up, phone is offline. The line includes how long, from `lastDisconnectedAt`. |
| 2 | Server down or unreachable |

```bash
scripts/phonepi-health-check.sh
scripts/phonepi-health-check.sh --json
```

It never reads the token, calls `/api/command`, or connects to port 11041. A second WebSocket client would replace the phone.

### `POST /api/tool`

Body:

```json
{ "tool": "get_battery_level", "arguments": {} }
```

`arguments` is optional and defaults to `{}`.

Success is HTTP 200:

```json
{ "text": "{\n  \"level\": 80\n}", "isError": false }
```

`text` is the phone payload as pretty-printed JSON, the same text the MCP tool returns.

| Status | When | Body |
| --- | --- | --- |
| 200 | Allowlisted tool ran | `{ "text", "isError": false }` |
| 400 | Bad JSON, missing tool name, or `arguments` is not an object | `{ "text", "isError": true }` |
| 403 | Tool is not on the allowlist | `{ "text", "isError": true }` |
| 429 | Over the per-tool rate limit (`get_notifications`, `list_notification_apps`, `get_volumes`, `get_recent_photos`, `list_alarms`) | `{ "text", "isError": true }` |
| 503 | Phone socket is not open | `{ "text", "isError": true }` |
| 502 | The phone request failed (for example a timeout) | `{ "text", "isError": true }` |

Allowlist:

- `get_battery_level`
- `get_recent_calls`
- `get_contacts`
- `search_contacts`
- `get_contact_by_id`
- `get_all_snippets`
- `get_snippet_by_id`
- `get_snippets_by_type`
- `search_snippets`
- `get_notifications` (60/min)
- `list_notification_apps` (60/min)
- `get_volumes` (60/min)
- `get_recent_photos` (30/min, metadata only, hard cap 20)
- `list_alarms` (60/min). Next alarm from `AlarmManager.getNextAlarmClock()`, plus alarms PhonePi created. Not a full Clock inventory.

Everything else is rejected with 403 and is not sent to the phone. That includes `make_call`, `find_phone`, `set_alarm`, `set_timer`, `dismiss_alarm`, `dismiss_timer`, `copy_to_clipboard`, `send_message`, `send_notification`, snippet or contact add/update/delete, `share_snippet`, and every `gm_*` Google Messages tool. Clock writes use `POST /api/command`.

```powershell
Invoke-RestMethod http://127.0.0.1:11043/api/health

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11043/api/tool `
  -ContentType "application/json" `
  -Body '{"tool":"get_battery_level","arguments":{}}'
```

Use `127.0.0.1`, not a hostname that might resolve to IPv6.

`phone_navigate` and saved-location tools are **not** on this read-only list. Use `POST /api/command` (below) or MCP.

### `POST /api/command`

Allowlisted phone actions. This is the extensible command surface: new phone actions register in `src/commands.ts` and as a phone-side handler of the same name. Unknown names return 403 and are not sent to the phone. The server never runs Android intents or shell.

`PHONEPI_WS_TOKEN` is required. Send it as `Authorization: Bearer <token>`, `X-PhonePi-Token: <token>`, or `?token=`.

Body:

```json
{ "command": "phone_navigate", "arguments": { "name": "home" } }
```

| Command | Kind | Budget |
| --- | --- | --- |
| `phone_list_locations` | read | 60/min |
| `phone_add_location` | write | 20/min |
| `phone_remove_location` | write | 20/min |
| `phone_navigate` | action | 6/min |
| `phone_push_update` | write | 6/min |
| `phone_set_volume` | write | 20/min |
| `get_photo` | read | 12/min |
| `set_alarm` | action | 10/min |
| `set_timer` | action | 10/min |
| `dismiss_alarm` | action | 10/min |
| `dismiss_timer` | action | 10/min |

`get_photo` is a read, but it is not on `POST /api/tool`. Image bytes need the shared token. `get_recent_photos` is the no-token metadata list. The phone recompresses each image so one JSON frame stays under 1 MB. Original EXIF, including GPS, is not copied into those bytes.

`set_alarm` creates a Clock alarm (`ACTION_SET_ALARM`, `SKIP_UI`). `hour` is 0-23, or 1-12 with `period` `am`/`pm`. `minute` is 0-59. Optional `message` or `label` (max 100 characters). Optional `days`: `weekdays`, `weekends`, `daily`, or weekday names. Omit `days` for a one-shot alarm. Example: 6:30 AM weekdays labeled Work is `{"hour":6,"minute":30,"period":"am","days":"weekdays","label":"Work"}`.

`set_timer` starts a Clock countdown (`ACTION_SET_TIMER`, `SKIP_UI`). `seconds` is 1 through 86400. A 15 minute timer is `{"seconds":900}`. Optional label, same 100 character cap. If Clock cannot take the timer, the phone returns `status:"error"`. It does not schedule a PhonePi countdown instead.

`dismiss_alarm` sends `ACTION_DISMISS_ALARM` with `search` `time` (hour + minute) or `label`. `dismiss_timer` sends `ACTION_DISMISS_TIMER` (optional label). Android does not let a third-party app delete arbitrary Clock alarms. `started:true` means the intent was handed to Clock, not that the alarm is gone. If that Clock app has no dismiss handler, the phone returns `status:"error"` and `supported:false`. HTTP 200 with `status:"success"` is still not proof the alarm changed.

Invalid hour, minute, label, days, or seconds is HTTP 400 and is not sent to the phone. `list_alarms` stays on `POST /api/tool`.

MCP exposes the same names. Rate limits are shared between MCP and HTTP.

### App updates (`/phonepi/update`)

On the phone WebSocket port (the same host Tailscale Serve publishes):

- `GET /phonepi/update/manifest` — `{ versionCode, versionName, sha256, minVersion, size, fileName, signature }`
- `GET /phonepi/update/apk` — the APK bytes

`PHONEPI_WS_TOKEN` is required (`Authorization: Bearer`, `X-PhonePi-Token`, or `?token=`).

Directory: `PHONEPI_UPDATE_DIR` (default `~/.phonepi/updates`). Put a release APK named `phonepi-0.2.7-9.apk` (or `phonepi.apk`) and run `scripts/sign-update-manifest.sh` so `manifest.json` is Ed25519-signed. The APK bytes' sha256 must match the signed `sha256`. See [docs/release-signing.md](../docs/release-signing.md).

```powershell
$env:PHONEPI_WS_TOKEN = "choose-a-long-secret"

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11043/api/command `
  -Headers @{ "X-PhonePi-Token" = $env:PHONEPI_WS_TOKEN } `
  -ContentType "application/json" `
  -Body '{"command":"phone_list_locations","arguments":{}}'

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11043/api/command `
  -Headers @{ Authorization = "Bearer $env:PHONEPI_WS_TOKEN" } `
  -ContentType "application/json" `
  -Body '{"command":"phone_add_location","arguments":{"name":"home","address":"1600 Amphitheatre Parkway, Mountain View, CA"}}'

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11043/api/command `
  -Headers @{ Authorization = "Bearer $env:PHONEPI_WS_TOKEN" } `
  -ContentType "application/json" `
  -Body '{"command":"phone_navigate","arguments":{"name":"home"}}'
```

| Status | When |
| --- | --- |
| 200 | Allowlisted command ran on the phone. Parse `text` for `status` / `started`. HTTP 200 is not success by itself. |
| 400 | Invalid JSON, or clock arguments failed validation (hour, minute, label, days, seconds). Not sent to the phone. |
| 401 | Token missing, wrong, or `PHONEPI_WS_TOKEN` unset |
| 403 | Name is not on the command allowlist (includes `make_call`, `shell`, arbitrary intents) |
| 429 | Over the per-command rate limit |
| 503 | Phone socket is not open |
| 502 | Phone request failed |

### Phone WebSocket token

If `PHONEPI_WS_TOKEN` is unset or empty, the phone socket accepts every client (same as before). If it is set, the upgrade must include a matching `token` query parameter. The comparison is constant-time. The Android app already appends `?token=` when a token is configured.

### Tailscale Serve

Put the phone WebSocket behind Tailscale Serve so the phone connects with HTTPS inside the tailnet. Leave Funnel off. Funnel would publish the socket on the public internet.

Serve proxies to the local phone port and keeps the `/phonepi` path, which is what the app dials. WebSocket upgrades on that path are forwarded to the Node listener.

```powershell
tailscale serve --bg --https=443 http://127.0.0.1:11041
tailscale funnel off
```

`tailscale serve status` should show a tailnet-only proxy to `http://127.0.0.1:11041`. It should not show Funnel.

In the phone app, set the host to the machine's MagicDNS name (`<machine>.<tailnet>.ts.net`). For a `*.ts.net` host the app uses `wss` on port 443 and the path `/phonepi`. Put the same secret in the app token field and in `PHONEPI_WS_TOKEN`.

Do not point Serve at port 11043. That HTTP API is for processes on this PC. It shares the phone socket the MCP server already holds.

The phone listener still binds `0.0.0.0:11041` so existing LAN and `adb reverse` setups keep working. If you want the phone to arrive only through Serve, block inbound 11041 on other interfaces in the firewall. Serve itself connects to `127.0.0.1`.

# MCP server management cli (Optional)

## Standalone usage
The setup instructions above is all you need for setting it up with respect to MCP clients like Cline, Claude Desktop etc.

But, PhonePi MCP also provides a standalone command-line interface for you to manage the MCP server from outside the clients.

### Commands

- `start`: Start the MCP server
- `stop`: Stop the running MCP server
- `status`: Check if the MCP server is running
- `restart`: Restart the MCP server

### Starting the Server

```bash
phonepi-mcp start
```

Options:
- `-p, --port <port>`: Specify the port to run the server on (default: 11041)
- `-b, --background`: Run the server in the background

Example:
```bash
phonepi-mcp start -p 8080 -b
```

### Checking Server Status

```bash
phonepi-mcp status
```

### Stopping the Server

```bash
phonepi-mcp stop
```

### Restarting the Server

```bash
phonepi-mcp restart
```

Options:
- `-p, --port <port>`: Specify the port to run the server on (default: 11041)
- `-b, --background`: Run the server in the background

## Connecting Your Phone

1. Install the PhonePi MCP app on your phone
2. Start the server on your computer using `phonepi-mcp start`
3. In the app, connect to your computer's IP address and port (default 11041)

## Features

- Remote phone control via MCP protocol
- Get battery level
- Send SMS messages
- Make phone calls
- Find your phone by making it beep
- Set timers and alarms
- Copy text to clipboard
- Manage contacts
- Send notifications

## Development

Build the package:

```bash
npm run build
```

Run in development mode:

```bash
npm run dev
```

## License

MIT