---
name: PhonePi phone actions
description: >-
  Use when Alex asks any bot to do something on his Android phone through
  PhonePi: "take me to <place>", start Google Maps / Android Auto navigation,
  save or list places, set phone volume, read battery, contacts, recent calls,
  notifications, volumes, or recent photos, or when PhonePi says "Phone not connected" and you
  need to diagnose it.
---
# PhonePi phone actions

PhonePi = Android companion app on Alex's phone + a Node server on the box (`/workspace/phonepi/server`, repo `Belhun/phonepi-mcp`, branch `cursor/phone-navigate-locations-1ceb`). The phone dials OUT to the box over Tailscale and keeps one WebSocket open. Bots never talk to the phone directly; they call the box's loopback HTTP API, which forwards one allowlisted request over that socket. The phone runs only allowlisted handlers, rate-limits them, and logs every call in its on-phone **Recent activity**.

## 1. Always check health first (read-only, free)
```bash
curl -s http://127.0.0.1:11043/api/health
# {"ok":true,"phoneConnected":true|false,"wsPort":11041,"httpPort":11043,"lastConnectedAt":"2026-10-17T06:00:00.000Z","lastDisconnectedAt":"2026-10-17T05:40:00.000Z","lastDisconnectCode":1006,"lastDisconnectReason":"abnormal closure","lastPongAt":"2026-10-17T06:04:30.000Z","phonePermissions":{"overlay":true,"post_notifications":true,"full_screen_intent":true,"notification_listener":false,"dnd_access":false,"exact_alarms":true},"missingPermissions":["notification_listener","dnd_access"]}
```
- No answer at all → the server process is down (see section 6).
- `phoneConnected:false` → do NOT retry actions; go to section 6 and tell Alex plainly.

## 2. Two endpoints, exact formats
Responses are always `{"text": "<JSON string>", "isError": bool}`. **HTTP 200 does not mean success**: parse `text` and check `status` — the phone returns `{"status":"success",...}` or `{"status":"error","error":"..."}` inside a 200.

| HTTP | Meaning |
|---|---|
| 200 | Phone answered (still check `status` inside `text`) |
| 400 | Bad JSON / missing name / `arguments` not an object |
| 401 | `/api/command` token missing/invalid |
| 403 | Name not on the allowlist |
| 413 | Body > 1 MB |
| 429 | Rate limited (`Rate limited: X. Try again in Ns`) |
| 502 | Phone-side failure / 30 s timeout |
| 503 | `Phone not connected` |

### a) Read tools — `POST /api/tool`, no token
```bash
curl -s -X POST http://127.0.0.1:11043/api/tool -H 'Content-Type: application/json' \
  -d '{"tool":"get_battery_level","arguments":{}}'
```
Allowlist (exact): `get_battery_level`, `get_volumes`, `get_contacts`, `search_contacts` {query, limit≤50}, `get_contact_by_id` {id}, `get_recent_calls` {limit, hours (default 168) | since (epoch ms), type/types, number, includeContactNames}, `get_notifications` {package, since (epoch ms), limit default 50 cap 200}, `list_notification_apps`, `get_recent_photos` {limit default 5, hard cap 20, since (epoch ms)}, `get_all_snippets`, `get_snippet_by_id`, `get_snippets_by_type`, `search_snippets`.
- Battery → `level`, `isCharging`, `chargingStatus`.
- Volumes → per-stream `current`/`max`, `ringerMode`, `dnd`, `notificationPolicyAccess`, `exactAlarmsAllowed`, `midnightMaxEnabled`.
- Notifications: only apps Alex allowlisted in the app (default: none). Unshared apps are counted in `omittedUnsharedCount`.
- Recent photos: metadata only (id, display name, dateTaken, dateAdded, size, width, height, mime type). No image bytes. Needs the in-app **Share recent photos** switch (off by default) and the Photos permission. 30/min. The scan is the newest 100 images.

### b) Commands — `POST /api/command`, token required
Token = `PHONEPI_WS_TOKEN` in `/home/box/.phonepi/env` (mode 600). Never print, log, or paste it.
```bash
TOKEN=$(sed -n 's/^\(export \)\{0,1\}PHONEPI_WS_TOKEN=//p' /home/box/.phonepi/env)
curl -s -X POST http://127.0.0.1:11043/api/command \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"command":"phone_navigate","arguments":{"address":"26610 Ynez Rd, Temecula, CA 92591"}}'
```
(`X-PhonePi-Token: $TOKEN` also works; `"tool"` is accepted instead of `"command"`.)

| Command | Args | Limit/min | Notes |
|---|---|---|---|
| `phone_navigate` | one of: `name` (saved) · `mapsUrl` · `latitude`+`longitude` · `address` (alias `query`) | 6 | Precedence name > mapsUrl > lat/lng > address. **Any address or free-text place query works**, not just saved places. |
| `phone_list_locations` | – | 60 | Saved places live on the phone. |
| `phone_add_location` | `name` + at least one of `address`, `latitude`+`longitude`, `mapsUrl` | 20 | Same name (case-insensitive) overwrites. mapsUrl must be a Google Maps host. |
| `phone_remove_location` | `name` | 20 | |
| `phone_set_volume` | `stream` (alarm/ring/notification/media/voice\|call/system) + `level` (int or `"max"`), or `max_all:true` | 20 | Ring/notification under DND need Do Not Disturb access (`grantNeeded`). |
| `phone_push_update` | `force` (default true) | 6 | Shows the system installer; Alex must tap Install. Only when Alex asks. |
| `get_photo` | `id` (required). Optional `maxDimension` (default 1600, or `"full"`), `quality` (40-95, default 80), `format` (`jpeg` or `webp`) | 12 | Read-only image bytes. Token required. One JSON frame, under 1 MB. |

Saved places known: `Home` = 338 E Fallbrook St #A, Fallbrook CA 92028 (confirm with `phone_list_locations`).

## 3. Goal → action: "take me to X"
1. **Health check.** If `phoneConnected:false`, stop and go to section 6 (no retry spam).
2. **Resolve the destination.**
   - Saved place ("home") → `{"name":"Home"}`.
   - Specific business/place → look up the exact street address and send `address` with full street, city, state, ZIP. Tell Alex which location you picked.
   - "Nearest X" → send the plain query as `address` (e.g. `"Costco Gas"`); Google Maps resolves it from the phone's live location. Say that Maps chose the match.
   - A Maps link → `mapsUrl`. Coordinates → `latitude`/`longitude`.
3. **Call `phone_navigate`** once (rate-limited to 6/min).
4. **Read the result honestly** (inside `text`):
   - `status:"success"`, `started:true`, `method:"activity"` or `"overlay"` → Maps launched. On Android Auto, Maps still shows **Start now**; PhonePi cannot press it. Tell Alex to tap Start.
   - `started:false`, `method:"full_screen_notification"`, `notificationPosted:true` → Maps did NOT open; a "Start navigation" notification is waiting. Tell Alex to tap it; granting **Display over other apps** to PhonePi fixes this permanently.
   - `status:"error"` → relay the error.
   - There is NO screen readback beyond these fields. Never claim navigation started unless `started:true`.
5. Optional: if Alex wants this place again, `phone_add_location` with a short name.

**Fallback when disconnected:** give Alex a tappable link instead: `https://www.google.com/maps/dir/?api=1&destination=<URL-encoded address>&travelmode=driving`, and tell him PhonePi is offline and the most likely fix (section 6).

## 3b. Goal → action: "look at my last N photos"
1. **Health check.** If `phoneConnected:false`, stop and go to section 6.
2. **List metadata** with `get_recent_photos` (no token, 30/min). Default 5, hard cap 20. `since` is epoch ms.
```bash
curl -s -X POST http://127.0.0.1:11043/api/tool -H 'Content-Type: application/json' \
  -d '{"tool":"get_recent_photos","arguments":{"limit":5}}'
```
Success inside `text` (no image bytes):
```json
{"status":"success","count":1,"limit":5,"scanned":1,"photos":[{"id":"55","displayName":"IMG_20261017.jpg","dateTaken":1760693400000,"dateAdded":1760693405000,"size":4821133,"width":4080,"height":3072,"mimeType":"image/jpeg"}]}
```
3. **Fetch the ids Alex cares about** with `get_photo` on `POST /api/command` (shared token, 12/min). Do not send `get_photo` to `/api/tool` (403). One id per call.
```bash
TOKEN=$(sed -n 's/^\(export \)\{0,1\}PHONEPI_WS_TOKEN=//p' /home/box/.phonepi/env)
curl -s -X POST http://127.0.0.1:11043/api/command \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"command":"get_photo","arguments":{"id":"55"}}'
```
Success inside `text` (`base64` shortened here; the phone sends the full image):
```json
{"status":"success","id":"55","displayName":"IMG_20261017.jpg","dateTaken":1760693400000,"dateAdded":1760693405000,"size":4821133,"width":4080,"height":3072,"mimeType":"image/jpeg","base64":"/9j/4AAQ...","exifIncluded":false,"exifNote":"Returned bytes are a new JPEG or WebP. Original EXIF, including GPS location, camera make, and the orientation tag, is not copied. Orientation is applied to the pixels so the picture is upright. The pixels themselves can still show a place.","encoding":{"mimeType":"image/jpeg","width":1600,"height":1205,"quality":80,"byteLength":241002,"maxDimension":1600,"full":false,"cappedToFit":false}}
```
Decode `base64` and hand that image to the model. Default long edge is 1600px JPEG quality 80.
4. **Read failures honestly.**
   - `grantNeeded:"photo_sharing"` → Share recent photos is off. Ask Alex to open PhonePi and turn it on.
   - `grantNeeded:"READ_MEDIA_IMAGES"` or `READ_EXTERNAL_STORAGE` → Photos permission is missing. Ask Alex to tap Grant on Recent photos.
   - `status:"error"` and `Photo not found` → that id is not an image in MediaStore.
   - `cappedToFit:true` → the phone shrank the image so the frame would fit. Say so if the detail matters.
   - There is no screen readback. Do not claim you saw a photo unless `base64` decoded.

**Honest limits**
- Read-only. PhonePi never deletes, edits, moves, or uploads the gallery.
- Listing looks at the newest 100 images, then applies `since` and `limit`.
- One photo per `get_photo`. The protocol has no chunked image transfer. The phone keeps the JSON under 1 MB (900 KB budget for the tool payload). `maxDimension:"full"` means the largest long edge that still fits (starting cap 4096), not the original file.
- Returned bytes are a new JPEG or WebP. Original EXIF, including GPS, camera make, and the orientation tag, is not copied. Orientation is baked into the pixels. The picture can still show a place. `dateTaken` is a time, not a location. Display names are file names, not paths.
- Both tools are rate-limited and logged on the phone. The log records the id and byte count, not the image.

## 4. Phone-side permissions that matter
- **Display over other apps**: required for Maps to open from the background.
- Notifications + **full-screen intents** (Android 14+): needed for the fallback.
- **Notification access** + per-app share allowlist: for `get_notifications`.
- **Do Not Disturb access**: ring/notification volume while DND is on. **Alarms & reminders**: midnight max.
- Contacts / Call log / Phone permissions for those read tools.
- **Photos** (`READ_MEDIA_IMAGES` on Android 13+, `READ_EXTERNAL_STORAGE` on older releases) plus the **Share recent photos** switch. Off until Alex turns it on. Android 14 "Select photos" counts as a grant for the photos he picked.
- **Install unknown apps**: for self-update.

## 5. What PhonePi can and cannot do (as of companion 0.2.9)
Can (via HTTP, any bot): navigation to anything Maps can search, saved places, volumes (read/set/max all), battery, contacts (read/search), recent calls, allowlisted notifications, snippets, recent photo metadata (`get_recent_photos`), and one recompressed photo at a time (`get_photo`, shared token).
On-phone only: "Max volumes at midnight" toggle, reconnect-on-boot, reconnect after update.

Exists on the phone but NOT reachable from bots right now: `find_phone` (ring), `set_timer`, `copy_to_clipboard`, `make_call`, `get_location`, `send_notification`, contact edits, snippet writes. Don't promise these; `/api/tool` returns 403.

Cannot: send or read SMS/texts, press Android Auto's Start now, install updates silently, run arbitrary commands, report what is on screen, or work at all when the phone is off the tailnet.

## 6. Diagnosing "Phone not connected"
Check in order (all read-only):
1. Server up? `curl -s http://127.0.0.1:11043/api/health`. No reply → server down. It runs as `node dist/index.js` in `/workspace/phonepi/server` with env from `/home/box/.phonepi/env`, logging to `/home/box/.phonepi/server.log`. Restarting it is a box change; ask Chief of Staff or Alex first.
2. Phone on the tailnet? `tailscale status | grep -i pixel` and `tailscale status --json` (`Online`, `LastSeen`). "offline" means the phone's Tailscale is off — PhonePi can't fix that. Ask Alex to open the **Tailscale** app and turn it on (ideally always-on VPN), then open **PhonePi** and tap Connect.
3. Tailscale Serve routing? `tailscale serve status` must show `https://grokbot-box.tailcbcc46.ts.net` → `/ proxy http://127.0.0.1:11041`.
4. Server log tail: `no pong received` (network dropped), `WebSocket connection rejected` (bad token), code 1006 (abrupt drop).
5. The phone reconnects forever with exponential backoff and jitter, capped at about 5 minutes. It also retries immediately when the network changes or PhonePi comes to the foreground. Status shows connected, reconnecting in Ns, or the offline reason. It does not stop in a terminal Failed state. Reconnect on boot and after an update still works.
Tell Alex the specific step that failed and the one action he needs to take. Never change phone settings yourself.

## Safety rules
- Only allowlisted calls; never connect to the phone WebSocket (11041) directly — a second client replaces the phone's connection.
- Never expose the token. Respect rate limits. Every call is logged on the phone.
- Navigation, volume, and saved-place writes are fine when Alex asks; `phone_push_update` only on explicit request.
