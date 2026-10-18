package dev.phonepi.companion.textsend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Sends one JSON frame to the box when the phone socket is up. */
object PhoneUplink {
    @Volatile
    var send: ((String) -> Boolean)? = null

    fun attach(sender: (String) -> Boolean) {
        send = sender
    }
}

/**
 * Box send log lines. If the socket is down, they wait here and flush on connect.
 * The shared token is never included.
 */
object SendLogOutbox {
    private const val PREFS = "phonepi_send_text_outbox"
    private const val KEY = "lines"
    private val lock = Any()

    fun enqueue(context: Context, json: String) {
        if (PhoneUplink.send?.invoke(json) == true) return
        synchronized(lock) {
            val lines = read(context).toMutableList()
            lines += json
            write(context, lines.takeLast(100))
        }
    }

    fun flush(context: Context) {
        val sender = PhoneUplink.send ?: return
        synchronized(lock) {
            val kept = mutableListOf<String>()
            for (line in read(context)) {
                if (!sender(line)) kept += line
            }
            write(context, kept)
        }
    }

    private fun read(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val line = array.optString(index)
                if (line.isNotBlank()) add(line)
            }
        }
    }

    private fun write(context: Context, lines: List<String>) {
        val array = JSONArray()
        lines.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}

object SendTextEvents {
    fun emit(
        context: Context,
        outcome: String,
        mode: String,
        id: String,
        recipientName: String,
        number: String,
        message: String,
        confirmMethod: String?,
        error: String? = null,
        candidates: List<MaskedCandidate> = emptyList(),
    ) {
        val masked = SendTextPolicy.maskPhone(number)
        val detail = buildString {
            append(outcome)
            append(' ')
            append(mode)
            append(" to ")
            append(recipientName)
            append(' ')
            append(masked)
            if (!confirmMethod.isNullOrBlank()) {
                append(" via ")
                append(confirmMethod)
            }
            if (!error.isNullOrBlank()) {
                append(" (")
                append(error)
                append(')')
            }
            append(": ")
            append(message.take(500))
        }
        dev.phonepi.companion.log.ActivityLog.logTextSend(detail, outcome)
        val json = JSONObject()
            .put("type", "send_text_log")
            .put("id", id)
            .put("outcome", outcome)
            .put("mode", mode)
            .put("recipientName", recipientName)
            .put("numberMasked", masked)
            .put("message", message)
        if (!confirmMethod.isNullOrBlank()) json.put("confirmMethod", confirmMethod)
        if (!error.isNullOrBlank()) json.put("error", error)
        if (candidates.isNotEmpty()) {
            val array = JSONArray()
            for (candidate in candidates) {
                array.put(
                    JSONObject()
                        .put("name", candidate.name)
                        .put("numberMasked", candidate.numberMasked),
                )
            }
            json.put("candidates", array)
        }
        SendLogOutbox.enqueue(context, json.toString())
    }
}
