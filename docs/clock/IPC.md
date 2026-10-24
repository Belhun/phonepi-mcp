# PhonePi Clock IPC

PhonePi Clock is a separate app. Package `dev.phonepi.clock`. Version 0.1.0 (versionCode 1). A PhonePi companion update cannot stop an alarm that is already scheduled here.

Google Clock still owns "Hey Google" alarms. `set_alarm`, `set_timer`, `dismiss_alarm`, `dismiss_timer`, and `list_alarms` stay on that path.

## Control permission

The clock app defines `dev.phonepi.clock.permission.CONTROL` with `protectionLevel="signature"`.

The companion holds `uses-permission` for that name. Android grants it only when both APKs are signed by the same certificate. A debug clock build cannot be controlled by a release companion, and the reverse is also denied.

## Provider

Authority `dev.phonepi.clock.provider`. Exported, `directBootAware`, permission `CONTROL`.

```
call(method, extras["payload"] = JSON) -> extras["payload"] = JSON
```

Methods: `clock_list_alarms`, `clock_get_alarm`, `clock_health`, `clock_history`, `clock_create_alarm`, `clock_update_alarm`, `clock_delete_alarm`, `clock_enable_alarm`, `clock_disable_alarm`, `clock_skip_next`, `clock_unskip`, `clock_snooze`, `clock_dismiss`.

Writes require `requestId`, a UUID. The phone stores the first result for 7 days. A repeat of that id returns the stored body with `replayed:true`. The server does not replay and does not queue a write while the phone is offline (HTTP 503).

Days are a bitmask, not `Calendar.DAY_OF_WEEK`: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64. Weekdays=31, weekends=96, daily=127. Mask 0 is one-time.

## Snapshot

Action `dev.phonepi.clock.action.SNAPSHOT`. The clock app sends it with `setPackage("dev.phonepi.companion")` and the CONTROL permission. Extra `snapshot` is JSON (`alarms`, `nextAlarm`, `health`, `updatedAt`).

The companion forwards that JSON on the phone WebSocket as `{"type":"clock_snapshot","snapshot":...}`.

The box stores the last copy at `~/.phonepi/clock/snapshot.json` (override `PHONEPI_CLOCK_DIR`). Directory mode 700. File mode 600. Fields named token, password, secret, authorization, wstoken, or ts_authkey are dropped. Reads use this file when the phone is offline and set `stale:true`. `GET /api/health` adds `clockHealth` as a short status (`ok`, `degraded`, `watching`, `unknown`). It does not embed the health object.

## Signing and the Spotify SHA-1

Phase 3 will add Spotify music alarms. This build has no Spotify code. The manifest already claims the redirect `phonepi-clock://spotify-callback` (`scheme` `phonepi-clock`, `host` `spotify-callback`) on `MainActivity`. The activity ignores that link.

A Spotify developer app needs the SHA-1 of the certificate that signs the clock APK you install.

Debug APK (`assembleDebug`), including the APK from this branch:

- Keystore: `~/.android/debug.keystore`
- Alias: `androiddebugkey`
- Store password and key password: `android`

Release APK (`assembleRelease`):

- Same keystore as the PhonePi companion.
- `PHONEPI_RELEASE_STORE_FILE`
- Alias `PHONEPI_RELEASE_KEY_ALIAS` (default `phonepi`)
- Passwords `PHONEPI_RELEASE_STORE_PASSWORD` and `PHONEPI_RELEASE_KEY_PASSWORD`
- Release builds refuse to package if that keystore file is missing.

The Pixel sideload should use the release certificate, the same one as `dev.phonepi.companion`. That is the SHA-1 to paste into the Spotify dashboard. The debug SHA-1 is only for an emulator or a debug companion.

From `phonepi-clock`:

```bash
./gradlew :app:signingReport
```

Or, for one keystore:

```bash
keytool -list -v -keystore "$PHONEPI_RELEASE_STORE_FILE" -alias "${PHONEPI_RELEASE_KEY_ALIAS:-phonepi}"
```

Copy the SHA-1 line. Do not commit the keystore or the store password.
