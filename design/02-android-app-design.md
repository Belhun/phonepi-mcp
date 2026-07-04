# Android app design

Module layout, the tool routing core, and the data layer.

## Layered structure

```
phonepi-android/
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/dev/<you>/phonepi/
        │   ├── PhonePiApp.kt            # Application, channels, DI graph
        │   ├── connection/
        │   │   ├── ConnectionService.kt # connectedDevice foreground service
        │   │   ├── WebSocketManager.kt  # OkHttp socket, reconnect, keepalive
        │   │   └── ConnectionState.kt   # sealed connection states
        │   ├── protocol/
        │   │   ├── WsMessage.kt         # sealed message types + serializers
        │   │   ├── ToolRequest.kt
        │   │   ├── ToolResponse.kt
        │   │   └── ToolRouter.kt        # tool name -> handler
        │   ├── tools/
        │   │   ├── ToolHandler.kt       # interface
        │   │   ├── BatteryTool.kt
        │   │   ├── ClipboardTool.kt
        │   │   ├── TimerTool.kt
        │   │   ├── NotificationTool.kt
        │   │   ├── FindPhoneTool.kt
        │   │   ├── SmsTool.kt
        │   │   ├── CallTool.kt
        │   │   ├── MessageTool.kt
        │   │   ├── SnippetTool.kt
        │   │   ├── ContactsTool.kt
        │   │   ├── PhotoTool.kt
        │   │   └── LocationTool.kt
        │   ├── data/
        │   │   ├── SnippetDb.kt         # Room database + DAO + FTS
        │   │   ├── MessageStore.kt      # interactive message state
        │   │   └── Settings.kt          # DataStore: host, port, enabled tools
        │   ├── permissions/
        │   │   └── PermissionTier.kt    # tool group -> permission set
        │   └── ui/
        │       ├── MainActivity.kt      # Compose host
        │       ├── ConnectScreen.kt     # host/port, connect toggle, status
        │       ├── ToolsScreen.kt       # per-tool enable switches
        │       └── DismissActivity.kt   # find_phone full-screen dismiss
        └── res/xml/network_security_config.xml
```

A single Gradle module is enough for v1. Split into `core`, `connection`, and `feature` modules later if build times grow.

## The tool handler contract

Every tool implements one interface. The router owns a name-to-handler map.

```kotlin
interface ToolHandler {
    val name: String
    suspend fun handle(params: JsonObject): JsonObject  // returns the "data" payload
}
```

```kotlin
class ToolRouter(handlers: List<ToolHandler>) {
    private val map = handlers.associateBy { it.name }

    suspend fun dispatch(request: ToolRequest): ToolResponse {
        val handler = map[request.tool]
            ?: return ToolResponse.error(request.requestId, "Unknown tool: ${request.tool}")
        return try {
            val data = handler.handle(request.params)
            ToolResponse(request.requestId, data)
        } catch (e: SecurityException) {
            ToolResponse.error(request.requestId, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            ToolResponse.error(request.requestId, e.message ?: "Tool failed")
        }
    }
}
```

Benefits:

- New tool = new class + one registry line.
- One catch block turns any failure into a clean error payload. The socket never dies on a tool bug.
- Handlers stay testable. Feed a `JsonObject`, assert the returned `JsonObject`.

## Response envelope

Every handler returns a `data` object that starts with `status`.

```json
{ "status": "success", "level": 87 }
```

```json
{ "status": "error", "error": "SEND_SMS permission not granted" }
```

Helper builders keep this consistent:

```kotlin
fun success(vararg pairs: Pair<String, JsonElement>) =
    buildJsonObject { put("status", "success"); pairs.forEach { put(it.first, it.second) } }

fun error(message: String) =
    buildJsonObject { put("status", "error"); put("error", message) }
```

## Tool gating

Each handler belongs to a permission tier and an enable switch. The router checks both before running a dangerous tool.

```kotlin
enum class ToolTier { BASIC, MESSAGING, CONTACTS, TELEPHONY, LOCATION, CAMERA }
```

| Tier | Tools | Dangerous permissions |
|------|-------|-----------------------|
| BASIC | battery, clipboard, timer, find_phone, notification, snippets, message | none / `POST_NOTIFICATIONS` |
| MESSAGING | send_sms | `SEND_SMS` |
| TELEPHONY | make_call | `CALL_PHONE` |
| CONTACTS | contact CRUD | `READ_CONTACTS`, `WRITE_CONTACTS` |
| LOCATION | get_location | `ACCESS_FINE_LOCATION` |
| CAMERA | take_photo | `CAMERA` |

If a tool's tier is disabled or its permission is missing, the handler returns an error payload, never a crash. Telephony tiers default to off; the user turns them on in `ToolsScreen`.

## Data layer

### Snippets (Room + FTS)

Snippets need create, read, filter by type, full-text search, update, delete, and share. Room with an FTS4/FTS5 table fits.

```kotlin
@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val type: String,          // note | todo | bookmark | snippet | draft
    val tags: String = "",     // comma-separated
    val createdAt: Long,
    val updatedAt: Long,
)
```

A parallel FTS table indexes `title`, `content`, and `tags` for `search_snippets`. Schema and tool mapping live in [`../research/02-tools/snippets-tools.md`](../research/02-tools/snippets-tools.md).

### Interactive messages

`send_message` returns a `messageId` right away, then `get_message_response` polls for the user's choice. Store message state locally.

```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val title: String,
    val body: String,
    val responseOptions: String?, // JSON array
    val requireResponse: Boolean,
    val expiresAt: Long?,         // null = never
    val state: String,            // pending | responded | expired | dismissed
    val response: String? = null,
    val respondedAt: Long? = null,
)
```

Notification action buttons write the chosen option back to this row. An `AlarmManager` or `WorkManager` job flips state to `expired` when `expiresAt` passes.

### Contacts

Contacts are not stored locally. They read and write the system provider through `ContactsContract` and `ContentProviderOperation` batches. Full mapping in [`../research/02-tools/contacts-tools.md`](../research/02-tools/contacts-tools.md).

### Settings

`DataStore` holds host, port (default 11041), and the per-tool enable map. No secrets in v1; the future version adds an encrypted token store.

## Dependency choices

| Need | Library | Note |
|------|---------|------|
| WebSocket | OkHttp | Stable, simple `WebSocketListener` |
| JSON | kotlinx.serialization | Sealed types map to the `type` field |
| Async | Coroutines | Structured concurrency for handlers |
| Local DB | Room (+ FTS) | Snippets and messages |
| Prefs | DataStore | Host, port, tool switches |
| UI | Jetpack Compose | Small surface: connect, tools, dismiss |
| DI | Manual or Hilt | Manual is fine at this size |

The official MCP Kotlin SDK is not used. It speaks MCP transports, not this custom WebSocket. See [`../research/03-platform-options/kotlin-native.md`](../research/03-platform-options/kotlin-native.md).
