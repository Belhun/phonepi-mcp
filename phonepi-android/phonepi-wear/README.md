# PhonePi Wear OS

Watch APK for Alex's Galaxy Watch 7 and a future Pixel Watch (Wear OS 4/5). Module `:phonepi-wear`.

Same `applicationId` as the phone companion (`dev.phonepi.companion`) and the same release key. That pairing is required for the Wear OS Data Layer. The watch never holds `PHONEPI_WS_TOKEN`, `TS_AUTHKEY`, the send-gateway token, or the bridge key. It talks only to the phone app (MessageClient / DataClient). The phone forwards prompts over the existing WebSocket and runs quiet locally.

This module does not embed the wear APK in the phone APK. Sideload it onto the watch.

## What is on the watch

- A tile with **Ask assistant**, **Next alarm**, **Quiet 1h**, **I'm OK**
- An Ask assistant screen (voice or keyboard) that sends text to the phone
- A complication that shows the next alarm from `AlarmManager.getNextAlarmClock()` (the Clock app on `cursor/phonepi-clock-p1` publishes through that system API) plus a presence label if the phone has pushed one (stub until `cursor/phonepi-presence`)

Watch Face Push is not used. This is a tile + complication data source, not a watch face.

## Build (do not install from this repo)

From `phonepi-android/`:

```bash
./gradlew :phonepi-wear:assembleDebug
./gradlew :phonepi-wear:assembleRelease
```

Outputs:

- `phonepi-wear/build/outputs/apk/debug/phonepi-wear-debug.apk`
- `phonepi-wear/build/outputs/apk/release/phonepi-wear-release.apk`

Release signing is the same env as the companion (`PHONEPI_RELEASE_STORE_FILE` / password / alias). See [docs/release-signing.md](../../docs/release-signing.md). Debug and release cannot overlay each other on the watch.

## One-time sideload over Wi-Fi debugging (Linux)

Do this from the Linux machine. Do not run these commands as part of a PR check. Pairing is once per network until the watch forgets it.

1. Watch: Settings → About watch → Software information → tap **Software version** seven times.
2. Watch: Settings → Developer options → **Wireless debugging** → On. Tap **Pair new device**. Note the IP, **pairing** port, and six-digit code. The debug port on the Wireless debugging screen is a different port.
3. Phone: install the PhonePi companion signed with the same cert, open it, and Connect.
4. From Linux:

```bash
adb pair WATCH_IP:PAIR_PORT
# type the six-digit code when asked

adb connect WATCH_IP:DEBUG_PORT
adb devices

adb install -r phonepi-wear/build/outputs/apk/release/phonepi-wear-release.apk
```

5. Watch: add the PhonePi tile (tiles carousel → Add → PhonePi). Optional: add the **Next alarm** complication on a watch face.
6. Phone: grant **Do Not Disturb access** if Quiet for 1h should run (same grant the volume tools already use).

If `adb connect` dies after a Wi-Fi change, the watch assigned a new debug port. Open Wireless debugging again and reconnect. Do not bake the port into a script.

## Tests

```bash
./gradlew :app:testDebugUnitTest :phonepi-wear:testDebugUnitTest
```

Message handling is covered on the phone module (`WearMessageDispatcherTest`). The watch module tests path/payload encoding.
