# Presence (`get_presence`)

Bots can ask where Alex is before messaging him. Presence is a live snapshot, not the visit/drive log. Location history is a separate switch; both start on.

## What `get_presence` returns

`POST /api/tool` (**read token** `PHONEPI_READ_TOKEN`), 30/min. Reads the box copy at `~/.phonepi/presence/current.json`. Works when the phone is offline. Default is place label + status. Raw coordinates only for a bot scoped with `coords: true`.

```json
{"tool":"get_presence","arguments":{}}
```

Fields:

- `place` — saved place name marked presence, or `away`. `home` / `work` / `church` if those are the saved names.
- `minutesAtPlace` — minutes since the last arrive at that place.
- `driving` / `moving` / `activity` — activity recognition (`still`, `walking`, `running`, `cycling`, `driving`, `unknown`).
- `dnd` — interruption state (`off`, `priority`, `total_silence`, `alarms`, or `quiet` while in-app PhonePi quiet is on). PhonePi never flips system Do Not Disturb.
- `latitude` / `longitude` / `accuracyMeters` — raw coordinates only when the caller is scoped for them **and** presence is on, not paused, and fresh (`PHONEPI_PRESENCE_TTL_MS`, default 15 minutes). Otherwise null. Turning Presence off sends a clear; the box drops coordinates when off, paused, or stale. A paused snapshot is not fresh.
- `expected_place` / `matches_expected` — from the config file below. `null` when no hours cover now.
- `enabled` / `paused` — phone switches.
- `reason: "no_snapshot"` when the box has not received a push yet. `expected_place` still fills from config.

Pause and clear are token commands on `POST /api/command`, same shape as location history:

- `pause_presence_logging` `{ "paused": true }` 10/min. Resume is phone-only.
- `clear_presence_history` `{ "confirm": "delete" }` 4/min

The in-app **Presence** switch is the master on/off. It starts **on**. **Stop all sharing** pauses this stream with the others. Pause stops capture and keeps the last snapshot without coordinates. Clear deletes the snapshot on the phone and the box. Saved places stay.

## Config (work hours and named places)

File: `~/.phonepi/presence/config.json` (mode 600). Created on first server start if missing. Override with `PHONEPI_PRESENCE_CONFIG` or put it in `PHONEPI_PRESENCE_DIR`. Edit the file. Hours are not hard-coded in the matcher.

Default:

```json
{
  "places": {
    "home": "",
    "work": "",
    "church": ""
  },
  "timezone": "America/Los_Angeles",
  "hours": {
    "work": {
      "mon": [{ "start": "08:00", "end": "17:00" }],
      "tue": [{ "start": "08:00", "end": "17:00" }],
      "wed": [{ "start": "08:00", "end": "17:00" }],
      "thu": [{ "start": "08:00", "end": "17:00" }],
      "fri": [{ "start": "08:00", "end": "17:00" }],
      "sat": [],
      "sun": []
    },
    "church": {}
  }
}
```

Work is 8:00 AM–5:00 PM Monday–Friday, Pacific. End is exclusive, so 5:00 PM is no longer work. Church hours are empty until Alex sets them. `places.home` / `places.work` / `places.church` are the saved PhonePi names. Leave them blank until those places exist; during work hours `expected_place` is then `"work"`.

Set a mapping after the saved names exist:

```json
"places": { "home": "Home", "work": "Shop", "church": "Church" }
```

`matches_expected` is true when the live `place` equals `expected_place` or the mapped role name.

## Phone: geofences and the 3-minute check

Saved places with `presence: true` (or the in-app toggle) get an enter/leave geofence. Cap 100. Re-applied after reboot, app update, and process start. A 3-minute inexact alarm covers geofence lag (2–5 min) and missed events.

`phone_add_location` accepts optional `presence` true/false. `phone_list_locations` returns `presence` on each row.

## Permissions Alex must grant

1. Turn **Presence** on if he turned it off (it starts on).
2. **Precise location**, then **Allow all the time** (`ACCESS_BACKGROUND_LOCATION`). While-in-use only updates while PhonePi is open.
3. **Physical activity** (`ACTIVITY_RECOGNITION`) so driving/moving is real.
4. Mark the saved places that should count as presence (Home, Work, Church, or any other).
5. Fill `places.home` / `places.work` / `places.church` in the config when those names are known.

`ACCESS_NOTIFICATION_POLICY` is already in the manifest so DND state can be read. PhonePi does not change DND.

## Urgent messages and Do Not Disturb

Channel id `phonepi_urgent_messages`, user-visible name **PhonePi urgent messages**, importance HIGH. High-priority `send_notification` uses this channel.

PhonePi does **not** change any phone setting. It does not mark the channel as a DND bypass. To hear urgent PhonePi messages while Do Not Disturb is on, Alex does this himself:

1. Settings → Sound & vibration → Do Not Disturb
2. App notifications / exceptions
3. Allow **PhonePi urgent messages**

Until he does that, DND can silence the channel. That is expected.

## What this does not do

It does not build screen time, notification action buttons, Health Connect, or Wear OS. Location history visits/drives stay on `get_location_history` / `search_places`.
