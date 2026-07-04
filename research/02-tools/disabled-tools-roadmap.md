# Disabled tools and parity roadmap

> **Subagent:** `ca050556` — **Complete**  
> **Source:** `server/src/index.ts`

## Summary

**5 tools** are commented out in `ListTools`. **22 tools** are active. Full parity = **27 tools** exposed via `listTools` + MCP JSON descriptors + phone app handlers.

---

## Disabled / commented tools (NOT in ListTools)

### 1. `take_photo`

**Status:** Commented in ListTools; **partially implemented** in CallTool (image parsing)

| Param | Schema |
|-------|--------|
| `camera` | string, enum: `["front", "back"]`, required |

**CallTool behavior:** If response JSON has `imageData` as `data:image/...;base64,...` URL, returns MCP `image` + `text` content.

**Android APIs:** `CAMERA` permission, `CameraX` or `Camera2`, base64 encode to data URL in response.

---

### 2. `get_location`

**Status:** Commented in ListTools only

| Param | Schema |
|-------|--------|
| *(none)* | `properties: {}` |

**Android APIs:** `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`, `FusedLocationProviderClient`.

**Inferred response:**

```json
{
  "status": "success",
  "latitude": 40.7128,
  "longitude": -74.0060,
  "accuracy": 12.5,
  "timestamp": 1719158400000
}
```

---

### 3. `set_alarm`

| Param | Schema |
|-------|--------|
| `time` | string, ISO 8601, required |

**Note:** `set_timer` is **enabled**; alarm lifecycle tools are disabled.

**Android APIs:** `AlarmManager.setAlarmClock()`, `PendingIntent`, parse with `Instant.parse()`.

---

### 4. `cancel_alarm_or_timer`

| Param | Schema |
|-------|--------|
| `identifier` | string, required |

**Android APIs:** `AlarmManager.cancel(PendingIntent)`, stop active `MediaPlayer`/`Ringtone`.

Requires `identifier` returned from `set_timer` / `set_alarm` responses.

---

### 5. `stop_alarm`

No params.

**Android APIs:** Stop active `Ringtone`/`MediaPlayer`, release audio focus, dismiss find-phone UI.

Does not cancel scheduled `AlarmManager` alarms (that's `cancel_alarm_or_timer`).

---

## Active tools (IN ListTools) — 22

| Category | Tools |
|----------|-------|
| Device | `get_battery_level`, `find_phone`, `copy_to_clipboard` |
| Snippets | `add_snippet`, `get_all_snippets`, `get_snippet_by_id`, `get_snippets_by_type`, `search_snippets`, `update_snippet`, `delete_snippet`, `share_snippet` |
| Comms | `send_sms`, `make_call`, `send_message`, `get_message_response`, `send_notification` |
| Contacts | `get_contacts`, `get_contact_by_id`, `add_contact`, `update_contact`, `delete_contact` |
| Time | `set_timer` only |

---

## Implementation asymmetries

| Tool | ListTools | CallTool handler | MCP JSON descriptor |
|------|-----------|------------------|---------------------|
| `take_photo` | Commented | Special image handling | Missing |
| `get_location` | Commented | Generic forward | Missing |
| `set_alarm` | Commented | Generic forward | Missing |
| `cancel_alarm_or_timer` | Commented | Generic forward | Missing |
| `stop_alarm` | Commented | Generic forward | Missing |
| `set_timer` | Active | Generic forward | Present |

`CallTool` does not block disabled tool names; manual `callTool("take_photo", ...)` could still reach the phone.

---

## Roadmap for full parity

### Phase 1 — Re-enable discovery (server only)

1. Uncomment all 5 tool blocks in `ListTools` (~lines 591–850).
2. Add 5 matching JSON descriptors under `mcps/user-phonepi-mcp/tools/`.
3. Verify Cursor picks them up via `listTools`.

### Phase 2 — Alarm/timer ecosystem

1. `set_alarm` — pairs with `set_timer`
2. `cancel_alarm_or_timer` — needs `identifier` contract
3. `stop_alarm` — dismiss ringing alarm / find-phone session

### Phase 3 — `take_photo`

1. Uncomment in ListTools.
2. Confirm phone returns `{ imageData: "data:image/jpeg;base64,..." }`.
3. Add MCP descriptor.

### Phase 4 — `get_location`

1. Uncomment in ListTools.
2. GPS permissions and response format.
3. Add MCP descriptor.

### Phase 5 — Docs and validation

1. Update `server/README.md` (lists alarms but only `set_timer` exposed).
2. End-to-end test each re-enabled tool.
3. Confirm `identifier` flow between set and cancel.

---

## Parity checklist

| # | Tool | Action |
|---|------|--------|
| 1 | `take_photo` | Uncomment ListTools + descriptor (CallTool logic exists) |
| 2 | `get_location` | Uncomment + descriptor |
| 3 | `set_alarm` | Uncomment + descriptor |
| 4 | `cancel_alarm_or_timer` | Uncomment + descriptor; verify `identifier` |
| 5 | `stop_alarm` | Uncomment + descriptor |

**Current:** 22/27 exposed. **Full parity:** 27/27 + phone validation.

Phone app source is not in this repo; re-enabling ListTools is the main server-side gap.
