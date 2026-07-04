package dev.phonepi.companion.telephony

import android.content.Context
import android.provider.CallLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CallLogRow(
    val id: String,
    val number: String,
    val cachedName: String?,
    val type: Int,
    val date: Long,
    val durationSeconds: Int,
    val phoneAccountId: String?,
)

class CallLogRepository(private val context: Context) {

    fun listRecent(
        sinceMs: Long,
        limit: Int,
        typeFilter: Set<Int>? = null,
        numberPatterns: List<String>? = null,
    ): List<CallLogRow> {
        val selectionParts = mutableListOf("${CallLog.Calls.DATE} >= ?")
        val args = mutableListOf(sinceMs.toString())

        if (typeFilter != null && typeFilter.isNotEmpty()) {
            selectionParts += typeFilter.joinToString(" OR ", "(", ")") {
                "${CallLog.Calls.TYPE} = ?"
            }
            typeFilter.forEach { args += it.toString() }
        }

        if (numberPatterns != null && numberPatterns.isNotEmpty()) {
            selectionParts += numberPatterns.joinToString(" OR ", "(", ")") {
                "${CallLog.Calls.NUMBER} LIKE ?"
            }
            numberPatterns.forEach { args += it }
        }

        val selection = selectionParts.joinToString(" AND ")
        val rows = mutableListOf<CallLogRow>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID,
            ),
            selection,
            args.toTypedArray(),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val accountIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (cursor.moveToNext() && rows.size < limit) {
                rows += CallLogRow(
                    id = cursor.getString(idIdx) ?: continue,
                    number = cursor.getString(numberIdx)?.trim().orEmpty(),
                    cachedName = cursor.getString(nameIdx)?.trim()?.takeIf { it.isNotEmpty() },
                    type = cursor.getInt(typeIdx),
                    date = cursor.getLong(dateIdx),
                    durationSeconds = cursor.getInt(durationIdx).coerceAtLeast(0),
                    phoneAccountId = cursor.getString(accountIdx)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return rows
    }

    fun toJson(
        row: CallLogRow,
        contactName: String? = null,
        contactId: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", row.id)
        put("number", row.number)
        put("type", callTypeLabel(row.type))
        put("date", row.date)
        put("durationSeconds", row.durationSeconds)
        val name = contactName ?: row.cachedName
        name?.let { put("name", it) }
        contactId?.let { put("contactId", it) }
        row.phoneAccountId?.let { put("phoneAccountId", it) }
    }
}

fun callTypeLabel(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "incoming"
    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
    CallLog.Calls.MISSED_TYPE -> "missed"
    CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
    CallLog.Calls.REJECTED_TYPE -> "rejected"
    CallLog.Calls.BLOCKED_TYPE -> "blocked"
    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_externally"
    else -> "unknown"
}

fun parseCallTypeFilter(raw: String): Int? = when (raw.trim().lowercase()) {
    "incoming" -> CallLog.Calls.INCOMING_TYPE
    "outgoing" -> CallLog.Calls.OUTGOING_TYPE
    "missed" -> CallLog.Calls.MISSED_TYPE
    "voicemail" -> CallLog.Calls.VOICEMAIL_TYPE
    "rejected" -> CallLog.Calls.REJECTED_TYPE
    "blocked" -> CallLog.Calls.BLOCKED_TYPE
    "answered_externally" -> CallLog.Calls.ANSWERED_EXTERNALLY_TYPE
    else -> null
}
