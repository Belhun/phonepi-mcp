package dev.phonepi.companion.wear

import android.content.Context
import org.json.JSONArray

/**
 * Queues inbound_prompt frames when the phone socket is down. Flushed from
 * [dev.phonepi.companion.connection.ConnectionService] on connect. Frames never
 * include the PhonePi token.
 */
object WearPromptOutbox {
    private const val PREFS = "phonepi_wear_outbox"
    private const val KEY = "frames"
    private val lock = Any()

    fun enqueue(context: Context, frame: String) {
        if (frame.isBlank()) return
        synchronized(lock) {
            val frames = read(context).toMutableList()
            frames += frame
            write(context, frames.takeLast(50))
        }
    }

    fun flush(context: Context) {
        val sender = dev.phonepi.companion.textsend.PhoneUplink.send ?: return
        synchronized(lock) {
            val kept = mutableListOf<String>()
            for (frame in read(context)) {
                if (!sender(frame)) kept += frame
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

    private fun write(context: Context, frames: List<String>) {
        val array = JSONArray()
        frames.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
