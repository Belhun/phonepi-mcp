package dev.phonepi.companion.textsend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Pending and final text requests. The full number stays on the phone. */
object SendTextStore {
    private const val PREFS = "phonepi_send_text_records"
    private const val KEY = "records"
    private val lock = Any()

    fun all(context: Context): List<SendRecord> = synchronized(lock) { read(context) }

    fun find(context: Context, id: String): SendRecord? =
        synchronized(lock) { read(context).firstOrNull { it.id == id } }

    fun save(context: Context, record: SendRecord) = synchronized(lock) {
        val next = read(context).filterNot { it.id == record.id } + record
        write(context, next.takeLast(80))
    }

    private fun read(context: Context): List<SendRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SendRecord>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            out += SendRecord(
                id = obj.optString("id"),
                mode = obj.optString("mode"),
                recipientName = obj.optString("recipientName"),
                number = obj.optString("number"),
                message = obj.optString("message"),
                createdAt = obj.optLong("createdAt"),
                expiresAt = obj.optLong("expiresAt"),
                status = obj.optString("status", "pending"),
                confirmMethod = obj.optString("confirmMethod").ifBlank { null },
                handedToCarrier = obj.optBoolean("handedToCarrier", false),
            )
        }
        return out.filter { it.id.isNotBlank() }
    }

    private fun write(context: Context, records: List<SendRecord>) {
        val array = JSONArray()
        for (record in records) {
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("mode", record.mode)
                    .put("recipientName", record.recipientName)
                    .put("number", record.number)
                    .put("message", record.message)
                    .put("createdAt", record.createdAt)
                    .put("expiresAt", record.expiresAt)
                    .put("status", record.status)
                    .put("confirmMethod", record.confirmMethod ?: "")
                    .put("handedToCarrier", record.handedToCarrier),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
