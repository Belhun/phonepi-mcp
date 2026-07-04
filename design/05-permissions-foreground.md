# Permissions, foreground service, and background launches

The permission model, the manifest, and the two Android behaviors that need special handling: the foreground service cap and background activity launches.

## Permission tiers

Request dangerous permissions only when the user enables that tool group. This shrinks the on-device blast radius and matches Android's runtime model.

| Tier | Tools | Permissions | Default |
|------|-------|-------------|---------|
| BASIC | battery, clipboard, timer, find_phone, notification, message, snippets | `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `VIBRATE`, foreground service | On |
| MESSAGING | send_sms, share_snippet (sms) | `SEND_SMS` | Off |
| TELEPHONY | make_call | `CALL_PHONE` | Off |
| CONTACTS | contact CRUD | `READ_CONTACTS`, `WRITE_CONTACTS` | Off |
| LOCATION | get_location | `ACCESS_FINE_LOCATION` | Off |
| CAMERA | take_photo | `CAMERA` | Off |

Flow per tier: user flips the switch in `ToolsScreen`, the app shows a short rationale, then the system permission dialog appears. If denied, the tier's tools return an error payload.

## Manifest

```xml
<!-- Always -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- CHANGE_NETWORK_STATE qualifies the app to start a connectedDevice FGS (API 14+); ACCESS_NETWORK_STATE alone does not -->
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- Tier: enable per tool group -->
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.CAMERA" />

<uses-feature android:name="android.hardware.telephony" android:required="false" />
<uses-feature android:name="android.hardware.camera.any" android:required="false" />
```

Declaring a permission in the manifest does not grant it. Dangerous permissions still need a runtime grant. Sideloading is what keeps `SEND_SMS` and `CALL_PHONE` usable; Play would block them for a non-default-handler app.

## Foreground service type and the 6-hour cap

The socket runs in a foreground service. The service type decides whether Android caps its runtime.

Android 15 (API 35) limits `dataSync` and `mediaProcessing` foreground services to **6 hours per 24-hour period** in the background. After the cap, the system calls `Service.onTimeout()`. If the service does not stop within seconds, the app crashes with a `RemoteServiceException`. Bringing the app to the foreground resets the timer, but a companion link cannot depend on that.

**Decision:** type the connection service `connectedDevice`. It has no 6-hour cap and fits a paired-desktop link. See ADR-007 in [00-decisions.md](00-decisions.md).

`find_phone` audio is a separate concern. Its looping alarm can use a short-lived `mediaPlayback` service started while the app handles the request, stopped on dismiss. That audio session is brief, so the cap is not a problem.

```kotlin
override fun onTimeout(startId: Int) {
    webSocketManager.disconnect()
    stopSelf()
}
```

## Background activity launches and `make_call`

Android 14 (API 34) blocks a backgrounded app from starting an activity. `make_call` uses `Intent.ACTION_CALL`, which starts the dialer activity. An AI-triggered call usually arrives while the app sits in the background, so a direct `startActivity` from the service gets blocked.

Two supported paths:

### Path A: full-screen-intent notification (recommended for v1)

Post a high-priority notification with a full-screen intent. The `PendingIntent` opts into background activity start. The user sees a call prompt; tapping it (or the full-screen surface) launches the call activity. This stays within platform rules and keeps a human in the loop, which also helps telephony safety.

```kotlin
val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

val options = ActivityOptions.makeBasic().apply {
    pendingIntentBackgroundActivityStartMode =
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
}
val pending = PendingIntent.getActivity(
    context, 0, callIntent,
    PendingIntent.FLAG_IMMUTABLE, options.toBundle(),
)

val notif = NotificationCompat.Builder(context, "mcp_call")
    .setSmallIcon(R.drawable.ic_call)
    .setContentTitle("Call $number?")
    .setFullScreenIntent(pending, true)
    .setCategory(NotificationCompat.CATEGORY_CALL)
    .setAutoCancel(true)
    .build()
```

Respond on the WebSocket with `{ "status": "success", "to": number }` once the intent is posted. Success means the dial prompt started, not that the call connected.

### Path B: bring the app forward, then call

If the connection Activity is already visible, call `startActivity` directly. This works only when the app has window focus.

### What to avoid

- Do not request `ROLE_DIALER`. A companion app should not become the default dialer.
- `TelecomManager.placeCall` needs the dialer role; skip it for v1.
- The `phoneCall` foreground service type plus `MANAGE_OWN_CALLS` is for VoIP self-managed calls, not for placing a normal cellular call. Not needed here.

## Clipboard focus rule

`copy_to_clipboard` needs window focus on API 29+. From the background, bring an Activity forward, write the clip in `onWindowFocusChanged(true)`, then send the response. If you cannot gain focus, return an error payload explaining the limit. See [`../research/02-tools/device-tools.md`](../research/02-tools/device-tools.md).

## Exact alarms

`set_timer` and `set_alarm` need exact scheduling. On API 31+, `SCHEDULE_EXACT_ALARM` is granted through Settings, not a runtime dialog. Detect a missing grant and guide the user to the Settings toggle. `USE_EXACT_ALARM` is an alternative but draws Play scrutiny; for a sideloaded app, `SCHEDULE_EXACT_ALARM` is fine.

## Network security config

For v1 local development over `ws://`, allow cleartext to the dev host only.

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

The future WSS version replaces this with a pinned local CA. See [06-security-roadmap.md](06-security-roadmap.md).
