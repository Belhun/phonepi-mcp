# Assistant notifications and inbound prompts

Companion **0.3.4** (versionCode **16**). Alex can answer the assistant from the phone or a Galaxy Watch 7 without a watch app. Phone notifications already mirror to Wear OS. He can also start a prompt himself from a Quick Settings tile, a home-screen shortcut, or Pixel Quick Tap.

Bots never talk to the phone directly. They use the loopback API. Never print or log `PHONEPI_WS_TOKEN`, `PHONEPI_WAKE_HOOK_URL`, or `PHONEPI_WAKE_HOOK_SECRET`.

## Post a notification (`POST /api/command`)

`send_notification` is an allowlisted write. Token required. 20/min.

```json
{
  "command": "send_notification",
  "arguments": {
    "title": "Leave soon?",
    "body": "Dinner in 20. Head out?",
    "requestId": "notif-dinner-20",
    "actions": ["OK", "Snooze 1h", "No"],
    "priority": "high"
  }
}
```

- `title` 1–120 characters. `body` 1–1000.
- `actions` is up to 3 short labels (OK, Snooze 1h, Yes, No, or anything ≤32 characters). Reply is always included (Android `RemoteInput`; voice or keyboard on the watch).
- `requestId` (8–80 letters, digits, `_` `.` `-`) makes retries idempotent. The same id does not post a second notification.
- Default `priority` is `high`. `high` and `urgent` use the existing PhonePi urgent channel so the heads-up mirrors to the watch.
- HTTP 200 is not "Alex answered". Read `get_notification_replies`.

## Read replies (`POST /api/tool`)

`get_notification_replies` is read-only. No token. 60/min. Box copy at `~/.phonepi/assistant/replies/`. Works when the phone is offline.

Arguments: `since` (exclusive cursor on `ts`), `until`, `messageId`, `limit` (default 50, cap 200).

Each row: `id`, `messageId`, `action`, `replyText`, `ts`. A button tap has `action` equal to the label and `replyText` null. A spoken or typed reply has `action` `"reply"` and the text in `replyText`.

## Inbound prompts (`POST /api/tool`)

`get_inbound_prompts` is read-only. No token. 60/min. Box copy at `~/.phonepi/assistant/prompts/`.

Alex starts a prompt from:

- Quick Settings tile **Ask assistant**
- Home-screen shortcut **Ask assistant** (long-press the PhonePi icon)
- Pixel Quick Tap (see below)
- A later Wear OS relay using action `dev.phonepi.companion.action.ASK_ASSISTANT` (`source` `watch_relay`)

Each row: `id`, `text`, `source` (`qs_tile` | `shortcut` | `quick_tap` | `watch_relay` | `app`), `ts`.

## Wake hook (bots do not wait 10 minutes)

When a reply or inbound prompt is stored, the server POSTs `{ "event": "inbound_prompt"|"notification_reply", "id", "ts" }` to `PHONEPI_WAKE_HOOK_URL`. No message text in the body. Bots then call `get_inbound_prompts` or `get_notification_replies`.

| Env | Meaning |
| --- | --- |
| `PHONEPI_WAKE_HOOK_URL` | `http` or `https` URL. Unset or empty disables the hook. |
| `PHONEPI_WAKE_HOOK_SECRET` | Optional. Sent as `X-PhonePi-Wake-Secret`. |
| `PHONEPI_WAKE_HOOK_TIMEOUT_MS` | Optional. Default 2000. Clamp 200–10000. |

Retries: 3 attempts, backoff 200 ms then 800 ms. Duplicate `id`s that already succeeded are skipped. A hook failure never fails the phone request. `GET /api/health` shows `wakeHookEnabled` and `hookFailed` (the hook-failed counter). It never includes the URL or the secret. Implementation: `server/src/wake-hook.ts`.

Put the URL and secret in `/home/box/.phonepi/env` (mode 600). Never commit them.

## What Alex does on the phone

This feature does not change DND, volumes, or other phone settings.

1. Install companion **0.3.4** (or in-app update from 0.3.3). Notifications posting permission is the same grant PhonePi already asks on Connect.
2. Keep PhonePi connected.
3. **Quick Settings tile:** pull down the shade → edit tiles → add **Ask assistant**.
4. **Home shortcut:** long-press the PhonePi icon → drag **Ask assistant** to the home screen.
5. **Pixel Quick Tap** (double tap the back of the Pixel):
   1. Open **Settings**.
   2. **System**.
   3. **Gestures**.
   4. **Quick Tap**.
   5. Turn on **Use Quick Tap**.
   6. Choose **Open app**.
   7. Pick **Ask assistant** (the extra PhonePi launcher entry), not the main PhonePi icon.
   On some Pixel builds the picker lists app shortcuts: **PhonePi → Ask assistant**.
6. Watch: no watch app. When a bot posts `send_notification`, the card appears on the Galaxy Watch 7. Tap a button or use Reply (voice or keyboard). The phone forwards the result.

Speak or type in the Ask assistant screen and tap **Send**. That is an inbound prompt, not a text message.
