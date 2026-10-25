package dev.phonepi.wear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Keep in sync with `dev.phonepi.companion.wear.WearProtocol` on the phone.
 * The watch never stores or sends the PhonePi token.
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
)

object WearProtocol {
    const val IM_OK_TEXT = "I'm OK"
    private val json = Json { ignoreUnknownKeys = true }

    fun encodePrompt(text: String, source: String): ByteArray =
        buildJsonObject {
            put("text", text)
            put("source", source)
        }.toString().toByteArray(Charsets.UTF_8)

    fun encodeQuiet(minutes: Int): ByteArray =
        buildJsonObject { put("minutes", minutes) }.toString().toByteArray(Charsets.UTF_8)

    fun decodeAck(payloadUtf8: String): WearAck {
        val obj = parseObject(payloadUtf8)
        return WearAck(
            ok = obj["ok"]?.jsonPrimitive?.contentOrNull != "false",
            action = obj["action"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            nextAlarmEpochMs = obj["nextAlarmEpochMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            presenceLabel = obj["presenceLabel"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseObject(payloadUtf8: String): JsonObject {
        if (payloadUtf8.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(payloadUtf8).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }
}
