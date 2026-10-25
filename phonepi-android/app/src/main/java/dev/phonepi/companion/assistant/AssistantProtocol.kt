package dev.phonepi.companion.assistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class AssistantNotificationRequest(
    val requestId: String,
    val title: String,
    val body: String,
    val actions: List<String>,
    val priorityHigh: Boolean,
)

sealed class AssistantParseResult {
    data class Ok(val request: AssistantNotificationRequest) : AssistantParseResult()
    data class Err(val error: String) : AssistantParseResult()
}

object AssistantProtocol {
    const val KEY_TEXT_REPLY = "phonepi_assistant_reply"
    const val MAX_ACTIONS = 3
    const val TITLE_MAX = 120
    const val BODY_MAX = 1000
    const val URGENT_TITLE_MAX = 80
    const val URGENT_BODY_MAX = 200
    const val URGENT_TITLE_PREFIX = "From assistant:"
    const val ACTION_MAX = 32
    const val PROMPT_MAX = 1000
    const val ACTION_REPLY = "reply"
    private val ID_RE = Regex("^[A-Za-z0-9._-]{8,80}$")

    val PROMPT_SOURCES = setOf("qs_tile", "shortcut", "quick_tap", "watch_relay", "app")

    fun parseNotification(params: JsonObject): AssistantParseResult {
        val priority = params["priority"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "high"
        if (priority != "default" && priority != "high" && priority != "urgent") {
            return AssistantParseResult.Err("priority must be default, high, or urgent")
        }
        val urgent = priority == "urgent"
        val titleMax = if (urgent) URGENT_TITLE_MAX else TITLE_MAX
        val bodyMax = if (urgent) URGENT_BODY_MAX else BODY_MAX
        var title = requiredText(params, "title", titleMax)
            ?: return AssistantParseResult.Err(
                if (urgent) "urgent title must be 1 to $URGENT_TITLE_MAX characters"
                else "title must be 1 to 120 characters",
            )
        val body = requiredText(params, "body", bodyMax)
            ?: return AssistantParseResult.Err(
                if (urgent) "urgent body must be 1 to $URGENT_BODY_MAX characters"
                else "body must be 1 to 1000 characters",
            )
        val unsafe = forbiddenText("$title $body")
        if (unsafe != null) return AssistantParseResult.Err(unsafe)
        if (urgent && !title.startsWith(URGENT_TITLE_PREFIX)) {
            title = "$URGENT_TITLE_PREFIX $title".take(URGENT_TITLE_MAX)
        }
        val actions = parseActions(params)
        if (actions.size > MAX_ACTIONS) {
            return AssistantParseResult.Err("at most $MAX_ACTIONS action buttons")
        }
        if (actions.any { it.length > ACTION_MAX }) {
            return AssistantParseResult.Err("action labels must be at most $ACTION_MAX characters")
        }
        val requestId = params["requestId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val id = if (requestId.isEmpty()) newId("notif") else requestId
        if (!ID_RE.matches(id)) {
            return AssistantParseResult.Err("requestId must be 8-80 letters, digits, _ . or -")
        }
        val priorityHigh = priority == "high" || priority == "urgent"
        return AssistantParseResult.Ok(
            AssistantNotificationRequest(
                requestId = id,
                title = title,
                body = body,
                actions = actions,
                priorityHigh = priorityHigh,
            ),
        )
    }

    fun forbiddenText(text: String): String? {
        if (Regex("https?://|www\\.|\\bbit\\.ly\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "text must not contain links"
        }
        if (Regex(
                "\\b(password|passwd|passcode|pin\\b|api[_\\s-]?key|secret|token|credential)\\b",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(text)
        ) {
            return "text must not ask for credentials"
        }
        return null
    }

    fun parseActions(params: JsonObject): List<String> {
        params["actions"]?.jsonArray?.let { array ->
            return array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_ACTIONS + 1)
        }
        params["responseOptions"]?.jsonArray?.let { array ->
            return array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_ACTIONS + 1)
        }
        val raw = params["actions"]?.jsonPrimitive?.contentOrNull
            ?: params["responseOptions"]?.jsonPrimitive?.contentOrNull
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ACTIONS + 1)
    }

    fun normalizeSource(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value in PROMPT_SOURCES) value else "app"
    }

    fun clipPrompt(text: String): String? {
        val clipped = text.trim()
        if (clipped.isEmpty()) return null
        return clipped.take(PROMPT_MAX)
    }

    fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    fun isId(value: String): Boolean = ID_RE.matches(value)

    private fun requiredText(params: JsonObject, key: String, max: Int): String? {
        val text = params[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty() || text.length > max) return null
        return text
    }
}
