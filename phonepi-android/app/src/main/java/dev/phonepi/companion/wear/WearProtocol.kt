package dev.phonepi.companion.wear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wear Data Layer contract. The watch never sees the PhonePi token, TS_AUTHKEY,
 * send-gateway token, or bridge key. Keep [dev.phonepi.wear.WearProtocol] in
 * the watch module in sync with this file.
 */
object WearPaths {
    const val PREFIX = "/phonepi"
    const val PROMPT = "/phonepi/prompt"
    const val QUIET = "/phonepi/quiet"
    const val IM_OK = "/phonepi/im-ok"
    const val NEXT_ALARM = "/phonepi/next-alarm"
    const val ACK = "/phonepi/ack"
    const val STATE = "/phonepi/state"

    const val PHONE_CAPABILITY = "phonepi_phone"
    const val WEAR_CAPABILITY = "phonepi_wear"
}

data class WearAck(
    val ok: Boolean,
    val action: String,
    val message: String = "",
    val nextAlarmEpochMs: Long? = null,
    val presenceLabel: String? = null,
) {
    fun encode(): ByteArray = WearProtocol.encodeAck(this)
}

sealed class WearInboundMessage {
    data class SubmitPrompt(val text: String, val source: String) : WearInboundMessage()
    data class QuietFor(val minutes: Int) : WearInboundMessage()
    data object ImOk : WearInboundMessage()
    data object NextAlarm : WearInboundMessage()
    data class Unknown(val path: String) : WearInboundMessage()
}

data class WearActionResult(val ok: Boolean, val message: String)

object WearProtocol {
    const val MAX_PROMPT_CHARS = 2000
    const val MIN_QUIET_MINUTES = 1
    const val MAX_QUIET_MINUTES = 12 * 60
    const val IM_OK_TEXT = "I'm OK"

    private val json = Json { ignoreUnknownKeys = true }

    private val allowedSources = setOf("ask", "voice", "keyboard", "im_ok", "tile")

    /** Keys the phone must never copy from a watch payload into a server frame. */
    private val secretKeys = setOf(
        "token",
        "wstoken",
        "ws_token",
        "phonepi_ws_token",
        "ts_authkey",
        "authkey",
        "authorization",
        "bridgekey",
        "bridge_key",
        "send_gateway_token",
    )

    fun parse(path: String, payloadUtf8: String): WearInboundMessage {
        return when (path) {
            WearPaths.PROMPT -> {
                val obj = parseObject(payloadUtf8)
                val text = obj.string("text").orEmpty()
                val source = normalizeSource(obj.string("source"))
                WearInboundMessage.SubmitPrompt(text, source)
            }
            WearPaths.QUIET -> {
                val obj = parseObject(payloadUtf8)
                val minutes = obj.int("minutes") ?: -1
                WearInboundMessage.QuietFor(minutes)
            }
            WearPaths.IM_OK -> WearInboundMessage.ImOk
            WearPaths.NEXT_ALARM -> WearInboundMessage.NextAlarm
            else -> WearInboundMessage.Unknown(path)
        }
    }

    fun encodePrompt(text: String, source: String): ByteArray =
        buildJsonObject {
            put("text", text)
            put("source", normalizeSource(source))
        }.toString().toByteArray(Charsets.UTF_8)

    fun encodeQuiet(minutes: Int): ByteArray =
        buildJsonObject { put("minutes", minutes) }.toString().toByteArray(Charsets.UTF_8)

    fun encodeAck(ack: WearAck): ByteArray =
        buildJsonObject {
            put("ok", ack.ok)
            put("action", ack.action)
            put("message", ack.message)
            ack.nextAlarmEpochMs?.let { put("nextAlarmEpochMs", it) }
            ack.presenceLabel?.let { put("presenceLabel", it) }
        }.toString().toByteArray(Charsets.UTF_8)

    fun decodeAck(payloadUtf8: String): WearAck {
        val obj = parseObject(payloadUtf8)
        return WearAck(
            ok = obj["ok"]?.jsonPrimitive?.contentOrNull != "false",
            action = obj.string("action").orEmpty(),
            message = obj.string("message").orEmpty(),
            nextAlarmEpochMs = obj["nextAlarmEpochMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            presenceLabel = obj.string("presenceLabel"),
        )
    }

    fun sanitizePrompt(text: String): String? {
        val trimmed = text.trim().replace("\u0000", "")
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_PROMPT_CHARS)
    }

    fun validateQuietMinutes(minutes: Int): Int? =
        minutes.takeIf { it in MIN_QUIET_MINUTES..MAX_QUIET_MINUTES }

    fun normalizeSource(raw: String?): String {
        val source = raw?.trim()?.lowercase().orEmpty()
        return if (source in allowedSources) source else "ask"
    }

    fun inboundPromptFrame(id: String, text: String, source: String, createdAt: Long): String =
        buildJsonObject {
            put("type", "inbound_prompt")
            put("id", id)
            put("text", text)
            put("source", source)
            put("createdAt", createdAt)
        }.toString()

    fun payloadContainsSecretKey(payloadUtf8: String): Boolean {
        val obj = parseObject(payloadUtf8)
        return obj.keys.any { it.lowercase() in secretKeys }
    }

    private fun parseObject(payloadUtf8: String): JsonObject {
        if (payloadUtf8.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(payloadUtf8).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key]?.jsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }
}
