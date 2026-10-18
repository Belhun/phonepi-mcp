# Safe texts

Companion **0.3.2** (versionCode **14**). Bots ask the phone to text one person. The phone sends carrier SMS with `SmsManager`. The box never sends the text.

## Why this path

Two options were on the table.

The Google Messages bridge (`gm_send_message`, mautrix/gmessages) can send RCS and fall back to SMS inside Google's own app. That client is an unofficial protocol. The session lives on the box. A send from the box can leave without the phone in the loop. This feature does not add a send command there. Loopback HTTP already rejects `gm_*`.

Handing a draft to the default messaging app (`ACTION_SENDTO` / `smsto:`) is a normal Android intent. Google Messages may then use RCS or SMS. The person can still edit the draft, and they must tap Send inside that app. A spoken "send" cannot finish that screen. An assistant-signed text cannot finish it either. The exact message would not be locked.

`SmsManager.sendTextMessage` is the Android API for carrier SMS. PhonePi sends only after its own rules pass: one recipient, the kill switch, the mode, the prefix, and confirmation when the text reads as Alex. There is no sanctioned API that lets a third-party app send RCS and still keep the confirm, the prefix, and the exact body on the phone. SMS is the path that meets those rules. The message shows up as SMS, not RCS.

## Two modes

`request_send_text` takes `mode`. The default is `as_alex`.

| Mode | What the other person sees | Confirm |
| --- | --- | --- |
| `as_alex` | The exact message, as if Alex wrote it | Alex taps Send, or says send while driving. 5 minute expiry. |
| `attributed` | The phone puts `[Alex's assistant Grok]: ` on the front, then the message | No tap, but only if **Allow assistant-signed texts without confirm** is on |

The bot cannot set the prefix, remove it, or pass `confirmed` / `skipConfirm`. Those fields are HTTP 400. A message that already contains the prefix is still `as_alex` unless `mode` is `attributed`. Only the phone adds the prefix, and it adds it only for `attributed`.

Both modes still require one resolved recipient, the exact text, the rate limit, the kill switch, and a log line. HTTP 200 does not mean the text was sent. Poll `get_send_status`.

## Rules for bots

Send only when Alex, in that moment, names the person and the message.

Do not text on your own idea. Do not text a reply. Do not text an acknowledgement. Do not text a group. Do not change the message after you ask.

If the name matches more than one contact, or none, the phone returns `needs_clarification` and sends nothing. Candidates are names plus masked numbers. Ask Alex which one, then make a new request with a single name or number.

Cancel and expiry are final. Do not retry that id. A new text is a new request, and only if Alex asks again.

## What Alex turns on

Both switches start off. **Allow bots to request texts** is the kill switch. Off rejects every new request and cancels pending texts that have not reached the carrier.

**Allow assistant-signed texts without confirm** starts off. Off rejects `mode: attributed`. It does not turn that request into a silent `as_alex` send.

**Driving mode** forces the spoken prompt. The phone also speaks when Android Auto is in car mode, a connected Bluetooth device reports car audio or hands-free (`BLUETOOTH_CONNECT`), or Activity Recognition says `IN_VEHICLE` (confidence at least 50, fresh for 10 minutes).

Spoken prompt: `Text to Mom: <message>. Say send or cancel.` Only a clear "send" sends. "Cancel", silence, a timeout, or anything else sends nothing and leaves the tap notification. PhonePi asks for audio focus with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` and drops it when it is done.

## Limits

| Mode | Per minute | Per day |
| --- | --- | --- |
| `as_alex` | 5 | 30 |
| `attributed` | 5 | 20 |

The caps are separate. The message is at most 1000 characters. The prefix does not count against that cap. Pending `as_alex` texts expire 5 minutes after the request.

## Logs

The phone Recent activity log records each request, confirm, cancel, expiry, and send result.

The box appends `~/.phonepi/sent-texts.jsonl` (override with `PHONEPI_SEND_LOG`). Each line has a timestamp, recipient name, masked number, message, outcome, confirm method, and mode. A full number in a phone frame is masked before it is written. The shared token is never written.
