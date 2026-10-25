# Sleep and health (Health Connect)

Companion **0.3.4**. PhonePi reads Health Connect on the Pixel. It does not use the Samsung Health Data SDK. Galaxy Watch 7 sleep, steps, heart rate, exercise, and blood oxygen reach Health Connect only after Samsung Health is allowed to write those types.

`get_health_summary` is a box copy (`POST /api/tool`, 30/min, **`PHONEPI_READ_TOKEN`**). It works when the phone is offline (`stale` true). Daily totals only: last night's sleep (start, end, total, stages), steps today and 7-day, resting and average heart rate, recent workouts. Workout titles are untrusted data. No raw heart-rate streams. Reads the last 7 days only (`READ_HEALTH_DATA_HISTORY` is not requested). `lastNightSleepEndMs` / `lastNightSleepEndIso` are the sleep end for later Clock bedtime/wake work. Do not change the phonepi-clock app from this feature.

Files: `~/.phonepi/health/` (directory 700, files 600). `PHONEPI_HEALTH_DIR` overrides the path. `PHONEPI_HEALTH_RETENTION_DAYS` defaults to 30 (1–365).

The in-app **Sleep and health** switch is on by default. Alex still has to grant Health Connect. Pause and delete are on the phone and as token commands: `pause_health_sync` (`paused` must be `true`, 10/min; resume is phone-only) and `clear_health_cache` (`confirm` must be `"delete"`, 4/min). **Stop all sharing** pauses this stream with the others.

Samsung Health can lag minutes to hours. The payload's `syncedAtIso` is when PhonePi last read Health Connect, not when the watch recorded a beat. `sourceLagNote` says that.

## What Alex does on the phone

### 1. Samsung Health → Health Connect (once)

Galaxy Watch 7 writes to Samsung Health. Samsung Health must be allowed to write into Health Connect on the Pixel (Android 14+ has Health Connect built in).

1. Open **Samsung Health**.
2. Tap the **drawer** (three lines) or your **profile picture**.
3. Tap **Settings**.
4. Tap **Health Connect**. If the row is missing, open Android **Settings → Health & fitness → Health Connect**, then return to Samsung Health.
5. Turn Health Connect **on** if Samsung Health asks.
6. Open **App permissions** / **Data to add** for **Samsung Health**.
7. Allow Samsung Health to **write**:
   - Sleep
   - Steps
   - Heart rate
   - Exercise
   - Blood oxygen (oxygen saturation)
8. Leave those on. Watch data can take minutes to hours to show up in Health Connect.

On some Samsung Health builds the same screen is **Settings → Connected services → Health Connect**.

### 2. PhonePi (every install / after revoke)

1. Install this companion (**0.3.4**, versionCode 16). Debug → release is still a one-time uninstall.
2. Open **PhonePi** and scroll to **Sleep and health**. The switch starts **on**.
3. Read **Privacy policy** (Android requires this rationale before health permissions). Tap **I understand, continue**.
4. Tap **Grant Health Connect**. Allow:
   - Sleep (sessions and stages)
   - Steps
   - Heart rate
   - Resting heart rate
   - Exercise
   - Blood oxygen
   - Access in the background (`READ_HEALTH_DATA_IN_BACKGROUND`)
   - Do **not** grant past-data history if offered; PhonePi only reads the last 7 days
5. The row under the switch shows what is granted. **Pause sync** and **Delete health cache** are on that same panel.

Hourly WorkManager reads Health Connect and pushes a summary over the existing WebSocket. A connect also triggers a read.

## Bot read

```bash
curl -s -X POST http://127.0.0.1:11043/api/tool -H 'Content-Type: application/json' \
  -d '{"tool":"get_health_summary","arguments":{}}'
```

`stale` is true when the phone is offline. `lastNightSleepEndMs` is the last-night sleep end for Clock bedtime/wake later. Quote only the totals the task needs.
