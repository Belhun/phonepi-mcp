# Contact tools

> **Subagent:** `0165d960` — **Complete**  
> **Source:** `server/src/index.ts` (lines 866–1156). The Android companion app is not in this repo (closed-source Play Store app). Response shapes are inferred from server transport patterns and symmetric input/output design; not validated against shipped app code.

## Transport envelope (all 5 tools)

### MCP → server → phone

```json
{ "type": "request", "requestId": "req-<n>", "tool": "<tool_name>", "params": { } }
```

### Phone → server → MCP client

Phone sends `{ "type": "response", "requestId": "req-<n>", "data": { } }`; MCP client receives `{ "content": [ { "type": "text", "text": "<pretty-printed JSON of data>" } ] }`.

### Cross-cutting behavior

| Concern | Value |
|--------|--------|
| Timeout | 30 seconds (`index.ts` ~1297–1300) |
| Phone disconnected | `McpError`: `"Phone not connected..."` |
| Connection not open | `McpError`: `"Phone connection is not open"` |
| Server-side timeout/error | `{ status: "error", error: "...", timestamp?: number }` |
| Success convention (inferred) | `{ status: "success", ...payload }` |

---

## Shared types

### `PhoneNumber` (read / add)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `label` | string | yes | e.g. `"home"`, `"work"`, `"mobile"` |
| `number` | string | yes | Phone number |

### `PhoneNumber` (update only)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | no* | Data row ID for updating an existing number |
| `label` | string | yes | Label |
| `number` | string | yes | Number |

\*`id` is "required for updating existing numbers"; new numbers omit `id`.

### `Email` (read / add)

| Field | Type | Required |
|-------|------|----------|
| `label` | string | yes |
| `email` | string | yes |

### `Email` (update)

| Field | Type | Required |
|-------|------|----------|
| `id` | string | no* |
| `label` | string | yes |
| `email` | string | yes |

### `Address` (read / add / update)

| Field | Type | Required |
|-------|------|----------|
| `id` | string | no (update only) |
| `label` | string | yes |
| `street` | string | no |
| `city` | string | no |
| `region` | string | no |
| `postalCode` | string | no |
| `country` | string | no |

### `Contact` (full object)

| Field | Type | add | update | read (inferred) |
|-------|------|-----|--------|-----------------|
| `id` | string | — | **required** | yes |
| `name` | string | **required** | **required** | yes |
| `firstName` | string | no | no | yes |
| `middleName` | string | no | no | yes |
| `lastName` | string | no | no | yes |
| `phoneNumbers` | `PhoneNumber[]` | no | no | yes |
| `emails` | `Email[]` | no | no | yes |
| `addresses` | `Address[]` | no | no | yes |
| `company` | string | no | no | yes |
| `jobTitle` | string | no | no | yes |
| `note` | string | no | no | yes |

---

## Tool 1: `get_contacts`

Get all contacts from the phone. inputSchema: `{ "type": "object", "properties": {}, "required": [] }`. Params: none.

### Response format (inferred)

```json
{
  "status": "success",
  "contacts": [
    {
      "id": "123",
      "name": "Jane Doe",
      "firstName": "Jane",
      "middleName": "",
      "lastName": "Doe",
      "phoneNumbers": [ { "id": "456", "label": "mobile", "number": "+15551234567" } ],
      "emails": [ { "id": "789", "label": "work", "email": "jane@example.com" } ],
      "addresses": [
        { "id": "101", "label": "home", "street": "1 Main St", "city": "Springfield", "region": "IL", "postalCode": "62701", "country": "US" }
      ],
      "company": "Acme Corp",
      "jobTitle": "Engineer",
      "note": "Met at conference"
    }
  ],
  "count": 1
}
```

Error: `{ "status": "error", "error": "READ_CONTACTS permission denied" }`. Optional fields may be omitted or empty arrays when absent.

---

## Tool 2: `get_contact_by_id`

inputSchema requires `id` (string).

Found: `{ "status": "success", "contact": { /* Contact shape */ } }`  
Not found: `{ "status": "error", "error": "Contact not found: 123" }`

---

## Tool 3: `add_contact`

Add a new contact. inputSchema: top-level requires `contact`; `contact` requires `name`. Full nested schema:

```json
{
  "type": "object",
  "properties": {
    "contact": {
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "firstName": { "type": "string" },
        "middleName": { "type": "string" },
        "lastName": { "type": "string" },
        "phoneNumbers": {
          "type": "array",
          "items": { "type": "object", "properties": { "label": {"type":"string"}, "number": {"type":"string"} }, "required": ["label","number"] }
        },
        "emails": {
          "type": "array",
          "items": { "type": "object", "properties": { "label": {"type":"string"}, "email": {"type":"string"} }, "required": ["label","email"] }
        },
        "addresses": {
          "type": "array",
          "items": { "type": "object", "properties": { "label": {"type":"string"}, "street": {"type":"string"}, "city": {"type":"string"}, "region": {"type":"string"}, "postalCode": {"type":"string"}, "country": {"type":"string"} }, "required": ["label"] }
        },
        "company": { "type": "string" },
        "jobTitle": { "type": "string" },
        "note": { "type": "string" }
      },
      "required": ["name"]
    }
  },
  "required": ["contact"]
}
```

### Validation (Android-side)

- `contact` must be present; `contact.name` non-empty.
- Each `phoneNumbers[]` needs `label` + `number`; each `emails[]` needs `label` + `email`; each `addresses[]` needs `label` (parts optional).

### Response format (inferred)

```json
{
  "status": "success",
  "contact": {
    "id": "124",
    "name": "John Smith",
    "firstName": "John",
    "lastName": "Smith",
    "phoneNumbers": [ { "id": "501", "label": "mobile", "number": "+15559876543" } ],
    "emails": [],
    "addresses": [],
    "company": "Acme",
    "jobTitle": "Manager",
    "note": ""
  }
}
```

Return the created contact with server-assigned `id` and nested data-row `id`s.

---

## Tool 4: `update_contact`

Same `contact` object as `add_contact`, plus:

| Extra / different | Notes |
|-------------------|-------|
| `contact.id` | **required** — contact to update |
| `contact.name` | **required** — even if unchanged |
| `phoneNumbers[].id` | optional; present when updating existing row |
| `emails[].id` | optional |
| `addresses[].id` | optional |

Top-level `required`: `["contact"]`. `contact.required`: `["id", "name"]`.

### Ambiguity (document for implementers)

The schema does not define whether omitted arrays mean "leave unchanged" or "delete all", whether items without `id` are always inserts, or whether removed items should be deleted.

**Recommended semantics:**

| Field sent | Behavior |
|------------|----------|
| Scalar fields (`name`, `company`, etc.) | Upsert / overwrite that MIME row |
| Array field omitted | Leave existing rows unchanged |
| Array field present | **Replace** all rows of that type (delete unlisted, update by `id`, insert without `id`) |

Document chosen behavior in the Android handler. Response: `{ "status": "success", "contact": { /* full updated Contact */ } }`.

---

## Tool 5: `delete_contact`

inputSchema requires `id` (string).

Success: `{ "status": "success", "id": "123", "deleted": true }`  
Not found: `{ "status": "error", "error": "Contact not found: 123" }`

---

## Android implementation guide

### Permissions

```xml
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />
```

Request at runtime before any contact tool runs.

### ID mapping

| Schema field | Android source |
|--------------|----------------|
| `contact.id` | `ContactsContract.Contacts._ID`, serialized as string |
| `phoneNumbers[].id` | `Data._ID` where `MIMETYPE = Phone.CONTENT_ITEM_TYPE` |
| `emails[].id` | `Data._ID` where `MIMETYPE = Email.CONTENT_ITEM_TYPE` |
| `addresses[].id` | `Data._ID` where `MIMETYPE = StructuredPostal.CONTENT_ITEM_TYPE` |

Use **contact ID** (`Contacts._ID`) for `get_contact_by_id` and `delete_contact`, not raw contact ID.

### Core ContactsContract classes / URIs

| Purpose | API |
|---------|-----|
| Contact list | `ContactsContract.Contacts.CONTENT_URI` |
| All data for a contact | `ContactsContract.Data.CONTENT_URI` + `Data.CONTACT_ID = ?` |
| Entity aggregation | `ContactsContract.RawContacts.Entity.CONTENT_URI` |
| Batch writes | `ContentProviderOperation` + `applyBatch(ContactsContract.AUTHORITY, ops)` |
| Single contact URI | `ContentUris.withAppendedId(Contacts.CONTENT_URI, id)` |

### MIME type → schema field mapping

| MIME type | Class | Schema fields |
|-----------|-------|---------------|
| `StructuredName` | `StructuredName` | `firstName` → `GIVEN_NAME`, `middleName` → `MIDDLE_NAME`, `lastName` → `FAMILY_NAME`, `name` → `DISPLAY_NAME` |
| `Phone` | `Phone` | `number` → `NUMBER`; `label` ↔ `TYPE`/`LABEL` |
| `Email` | `Email` | `email` → `ADDRESS`; `label` ↔ `TYPE`/`LABEL` |
| `StructuredPostal` | `StructuredPostal` | `street` → `STREET`, `city` → `CITY`, `region` → `REGION`, `postalCode` → `POSTCODE`, `country` → `COUNTRY` |
| `Organization` | `Organization` | `company` → `COMPANY`, `jobTitle` → `TITLE` |
| `Note` | `Note` | `note` → `NOTE` |

Display name on read: prefer `Contacts.DISPLAY_NAME`; fall back to structured name parts.

### Label string ↔ Android `TYPE` constants

| Schema label | Phone `TYPE` | Email `TYPE` | Postal `TYPE` |
|--------------|--------------|--------------|-----------------|
| `"home"` | `Phone.TYPE_HOME` | `Email.TYPE_HOME` | `StructuredPostal.TYPE_HOME` |
| `"work"` | `Phone.TYPE_WORK` | `Email.TYPE_WORK` | `StructuredPostal.TYPE_WORK` |
| `"mobile"` | `Phone.TYPE_MOBILE` | — | — |
| `"other"` | `Phone.TYPE_OTHER` | `Email.TYPE_OTHER` | `StructuredPostal.TYPE_OTHER` |
| unknown | `TYPE_CUSTOM` + `LABEL` | same | same |

On read, map `TYPE` back to lowercase string; use `LABEL` when `TYPE == TYPE_CUSTOM`.

### `get_contacts` / `get_contact_by_id` algorithm

```
1. Check READ_CONTACTS
2. Query Contacts.CONTENT_URI
     projection: [_ID, DISPLAY_NAME]; sort: DISPLAY_NAME ASC
     (get_contact_by_id: selection "_ID = ?", args [id])
3. For each contact_id: query Data.CONTENT_URI selection "CONTACT_ID = ?"
4. Group Data rows by MIMETYPE into phoneNumbers, emails, addresses, etc.
5. Build Contact objects; stringify all IDs
6. Return { status: "success", contacts: [...] } or { contact: {...} }
```

Use `cursor.use { }`; large lists can be slow within the 30s timeout.

### `add_contact` algorithm

```
1. Check WRITE_CONTACTS
2. Build ArrayList<ContentProviderOperation>:
   a. newInsert(RawContacts.CONTENT_URI).withValue(ACCOUNT_TYPE, null).withValue(ACCOUNT_NAME, null)
   b. newInsert(Data.CONTENT_URI).withValueBackReference(RAW_CONTACT_ID, 0)
        StructuredName: DISPLAY_NAME=name, GIVEN_NAME, MIDDLE_NAME, FAMILY_NAME
   c. phoneNumbers[]: insert Data, MIMETYPE=Phone, TYPE/LABEL, NUMBER
   d. emails[]: insert Data, MIMETYPE=Email
   e. addresses[]: insert Data, MIMETYPE=StructuredPostal
   f. company/jobTitle: insert Organization
   g. note: insert Note
3. applyBatch(AUTHORITY, ops)
4. Parse result URIs for contact_id and data _IDs
5. Re-read contact and return full Contact
```

If only `name` is given, still write `StructuredName.DISPLAY_NAME`.

### `update_contact` algorithm

```
1. Check WRITE_CONTACTS
2. Validate contact.id exists
3. Resolve raw_contact_id(s) for contact_id (multi-account: update all or primary — document choice)
4. Scalar/org/note: find existing Data row by MIMETYPE or insert if missing
5. Each array type:
     - replace semantics: delete existing rows of that MIME not in payload ids
     - rows with id: newUpdate(Data URI with _ID)
     - rows without id: newInsert linked to raw_contact_id
6. applyBatch
7. Re-read and return updated Contact
```

Update URI: `ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)`.

### `delete_contact` algorithm

```
1. Check WRITE_CONTACTS
2. Parse id to Long; validate contact exists
3. contentResolver.delete(ContentUris.withAppendedId(Contacts.CONTENT_URI, id), null, null)
4. If delete count == 0 → error "Contact not found"
5. Return { status: "success", id, deleted: true }
```

Alternative: delete all `RawContacts` where `CONTACT_ID = ?`.

### Error handling checklist

| Condition | Response |
|-----------|----------|
| Permission denied | `{ status: "error", error: "READ_CONTACTS permission denied" }` |
| Invalid / missing `id` | `{ status: "error", error: "Invalid contact id" }` |
| Contact not found | `{ status: "error", error: "Contact not found: <id>" }` |
| `applyBatch` failure | `{ status: "error", error: "<exception message>" }` |
| Malformed params | `{ status: "error", error: "Missing required field: contact.name" }` |

Always reply with matching `requestId` within 30 seconds.

### Security notes

- Contacts are sensitive PII; only honor requests on the authenticated local WebSocket session.
- `WRITE_CONTACTS` allows destructive changes; no server-side validation beyond schema.
- Synced contacts (Google, etc.) are modified on-device; deletions propagate to the account provider.
- No pagination on `get_contacts`; very large address books may need internal chunking.

---

## Handler registration (suggested)

```kotlin
when (tool) {
    "get_contacts"       -> contactRepository.getAll()
    "get_contact_by_id"  -> contactRepository.getById(params.id)
    "add_contact"        -> contactRepository.add(params.contact)
    "update_contact"     -> contactRepository.update(params.contact)
    "delete_contact"     -> contactRepository.delete(params.id)
}
```

---

## Gaps vs. this repo

| Item | Status |
|------|--------|
| inputSchema | Fully defined in `index.ts` |
| Response JSON shape | **Not defined in repo**; inferred above |
| Android implementation | **Not in repo**; Play Store app only |
| Update merge semantics | **Undefined** in schema |
| Pagination / search | Not exposed |

To validate response shapes against production, capture WebSocket traffic from the official app or inspect returned JSON through a connected MCP session.
