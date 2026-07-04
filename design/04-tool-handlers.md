# Tool handlers

The full tool set, the handler plan for each, and the v1 build order. Per-tool params and response shapes live in the research docs; this page is the build map.

## Tool inventory (27 total)

22 active tools plus 5 commented-out tools. Source: `server/src/index.ts`.

| # | Tool | Tier | Android API | Status in server |
|---|------|------|-------------|------------------|
| 1 | `get_battery_level` | BASIC | `BatteryManager` | Active |
| 2 | `copy_to_clipboard` | BASIC | `ClipboardManager` | Active |
| 3 | `set_timer` | BASIC | `AlarmManager` | Active |
| 4 | `find_phone` | BASIC | `Ringtone` / `MediaPlayer` + FGS | Active |
| 5 | `send_notification` | BASIC | `NotificationManager` | Active |
| 6 | `send_message` | BASIC | `NotificationManager` + Room | Active |
| 7 | `get_message_response` | BASIC | Room lookup | Active |
| 8 | `add_snippet` | BASIC | Room | Active |
| 9 | `get_all_snippets` | BASIC | Room | Active |
| 10 | `get_snippet_by_id` | BASIC | Room | Active |
| 11 | `get_snippets_by_type` | BASIC | Room | Active |
| 12 | `search_snippets` | BASIC | Room + FTS | Active |
| 13 | `update_snippet` | BASIC | Room | Active |
| 14 | `delete_snippet` | BASIC | Room | Active |
| 15 | `share_snippet` | BASIC/MESSAGING | `ACTION_SEND` / SMS / clipboard | Active |
| 16 | `send_sms` | MESSAGING | `SmsManager` | Active |
| 17 | `make_call` | TELEPHONY | `ACTION_CALL` + full-screen intent | Active |
| 18 | `get_contacts` | CONTACTS | `ContactsContract` | Active |
| 19 | `get_contact_by_id` | CONTACTS | `ContactsContract` | Active |
| 20 | `add_contact` | CONTACTS | `ContentProviderOperation` | Active |
| 21 | `update_contact` | CONTACTS | `ContentProviderOperation` | Active |
| 22 | `delete_contact` | CONTACTS | `ContentResolver.delete` | Active |
| 23 | `take_photo` | CAMERA | `CameraX` | Commented |
| 24 | `get_location` | LOCATION | `FusedLocationProviderClient` | Commented |
| 25 | `set_alarm` | BASIC | `AlarmManager.setAlarmClock` | Commented |
| 26 | `cancel_alarm_or_timer` | BASIC | `AlarmManager.cancel` | Commented |
| 27 | `stop_alarm` | BASIC | stop `Ringtone` / `MediaPlayer` | Commented |

## The 5 commented tools need a small server change

These tools are commented out of the server's `ListTools`, so MCP clients never see them. To reach parity, uncomment those 5 blocks and add matching tool descriptors. This is the one planned server edit, scheduled for the parity milestone. Detail and exact line ranges live in [`../research/02-tools/disabled-tools-roadmap.md`](../research/02-tools/disabled-tools-roadmap.md).

`take_photo` already has server-side image handling, so once it appears in `ListTools` and the phone returns a base64 data URL, images flow back to the MCP client without further server work.

## Build order (milestones)

### M1 — Easy wins

Goal: prove the loop end to end with low-risk tools.

| Tool | Notes |
|------|-------|
| `get_battery_level` | No permission; instant read. First tool to wire. |
| `copy_to_clipboard` | Needs window focus on API 29+; see focus note below. |
| `set_timer` | `AlarmManager` + `PendingIntent` + `BroadcastReceiver`. |
| `send_notification` | Channels + `POST_NOTIFICATIONS` runtime grant. |

### M2 — Audio and interactive

| Tool | Notes |
|------|-------|
| `find_phone` | Loops alarm audio; needs FGS audio + `WakeLock`; `DismissActivity` for the stop UI. |
| `send_message` | Returns `messageId` at once; stores state in Room; action buttons capture the reply. |
| `get_message_response` | Reads the Room row; returns `pending` / `responded` / `expired` / `dismissed`. |

### M3 — Telephony

| Tool | Notes |
|------|-------|
| `send_sms` | `SmsManager`; validate and normalize the number; off by default. |
| `make_call` | `ACTION_CALL` via full-screen-intent launch (see [05-permissions-foreground.md](05-permissions-foreground.md)); off by default. |
| `share_snippet` | Reuses SMS / clipboard / share-sheet paths. |

### M4 — Data

| Tool group | Notes |
|------------|-------|
| Snippets (8 tools) | Room + FTS; full CRUD, type filter, search, share. |
| Contacts (5 tools) | `ContactsContract` reads; `ContentProviderOperation` batch writes; map labels to TYPE constants. |

### M5 — Parity tools

| Tool | Notes |
|------|-------|
| `take_photo` | CameraX capture to JPEG; return `data:image/jpeg;base64,...`. Server already parses this. |
| `get_location` | `FusedLocationProviderClient.getCurrentLocation`; return lat/lng. |
| `set_alarm` | `AlarmManager.setAlarmClock`; return an `identifier`. |
| `cancel_alarm_or_timer` | Cancel the `PendingIntent` tied to the identifier. |
| `stop_alarm` | Stop the ringing `Ringtone` / `MediaPlayer`; pairs with `find_phone`. |

## Cross-cutting handler rules

1. **Validate inside the handler.** The server does no validation. Reject empty or malformed params with an error payload.
2. **Respect the 30-second budget.** Long work (looping audio, find_phone) returns an acknowledgement fast, not after the action ends.
3. **Return an `identifier` where a later tool cancels.** `set_timer` and `set_alarm` return one so `cancel_alarm_or_timer` can target it.
4. **Clipboard focus.** `copy_to_clipboard` only works with window focus on API 29+. If the app is backgrounded, bring an Activity forward, copy in `onWindowFocusChanged(true)`, then respond. Detail in [`../research/02-tools/device-tools.md`](../research/02-tools/device-tools.md).
5. **Off by default for telephony.** `send_sms` and `make_call` start disabled. The user enables them in `ToolsScreen`.

## Handler skeleton

```kotlin
class BatteryTool(private val context: Context) : ToolHandler {
    override val name = "get_battery_level"
    override suspend fun handle(params: JsonObject): JsonObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return success(
            "level" to JsonPrimitive(level),
            "isCharging" to JsonPrimitive(charging),
        )
    }
}
```

Register it once:

```kotlin
val router = ToolRouter(listOf(
    BatteryTool(context),
    ClipboardTool(context),
    TimerTool(context),
    // ... one line per tool
))
```

## Response shapes per tool

These are not defined in server code; the research docs propose consistent shapes. Use them as the contract for your handlers, then confirm against a live MCP call.

- Device tools: [`../research/02-tools/device-tools.md`](../research/02-tools/device-tools.md)
- Comms tools: [`../research/02-tools/comms-tools.md`](../research/02-tools/comms-tools.md)
- Snippets: [`../research/02-tools/snippets-tools.md`](../research/02-tools/snippets-tools.md)
- Contacts: [`../research/02-tools/contacts-tools.md`](../research/02-tools/contacts-tools.md)
