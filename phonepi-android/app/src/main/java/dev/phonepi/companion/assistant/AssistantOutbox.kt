package dev.phonepi.companion.assistant

import android.content.Context
import dev.phonepi.companion.textsend.PhoneUplink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prompt/reply frames stay queued until the box sends assistant_ack {id, ok}.
 * Retries reuse the same id so the server can dedupe. Never includes the token.
 */
object AssistantOutbox {
    private const val PREFS = "phonepi_assistant_outbox"
    private const val KEY = "lines"
    private val lock = Any()

    fun enqueue(context: Context, json: String) {
        val app = context.applicationContext
        synchronized(lock) {
            val lines = read(app).toMutableList()
            val id = idOf(json)
            if (id != null) {
                lines.removeAll { idOf(it) == id }
            }
            lines += json
            write(app, lines.takeLast(100))
        }
        flush(app)
    }

    fun ack(context: Context, id: String, ok: Boolean) {
        if (id.isBlank()) return
        val app = context.applicationContext
        synchronized(lock) {
            val kept = read(app).filter { idOf(it) != id }
            write(app, kept)
        }
        if (!ok) flush(app)
    }

    fun flush(context: Context) {
        val sender = PhoneUplink.send ?: return
        synchronized(lock) {
            for (line in read(context)) {
                sender(line)
            }
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            write(app, emptyList())
        }
    }

    fun parseAck(text: String): Pair<String, Boolean>? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.optString("type") != "assistant_ack") return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        return id to obj.optBoolean("ok", false)
    }

    private fun idOf(line: String): String? =
        runCatching { JSONObject(line).optString("id") }.getOrNull()?.takeIf { it.isNotBlank() }

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
