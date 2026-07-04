package dev.phonepi.companion.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dev.phonepi.companion.telephony.PhoneNumberMatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SmsRow(
    val id: String,
    val threadId: String,
    val address: String,
    val body: String,
    val date: Long,
    val read: Boolean,
    val direction: String,
)

data class SmsQueryResult(
    val messages: List<SmsRow>,
    val matchedAddresses: Set<String>,
    val threadIds: Set<String>,
)

class SmsRepository(private val context: Context) {

    fun listConversation(
        phoneNumbers: List<String>,
        sinceMs: Long,
        limit: Int,
        includeSent: Boolean,
    ): SmsQueryResult {
        if (phoneNumbers.isEmpty()) {
            return SmsQueryResult(emptyList(), emptySet(), emptySet())
        }

        val patterns = PhoneNumberMatch.likePatterns(*phoneNumbers.toTypedArray())
        if (patterns.isEmpty()) {
            return SmsQueryResult(emptyList(), emptySet(), emptySet())
        }

        val uris = buildList {
            add(Telephony.Sms.Inbox.CONTENT_URI)
            if (includeSent) add(Telephony.Sms.Sent.CONTENT_URI)
        }

        val byId = linkedMapOf<String, SmsRow>()
        val matchedAddresses = linkedSetOf<String>()
        val threadIds = linkedSetOf<String>()

        for (uri in uris) {
            queryUri(uri, patterns, sinceMs, limit * 2).forEach { row ->
                if (byId.put(row.id, row) == null) {
                    matchedAddresses += row.address
                    if (row.threadId.isNotEmpty()) threadIds += row.threadId
                }
            }
        }

        // Pull the full thread when we know the thread id — catches address-format mismatches.
        for (threadId in threadIds.toList()) {
            queryThread(threadId, sinceMs, includeSent).forEach { row ->
                byId[row.id] = row
                matchedAddresses += row.address
            }
        }

        val messages = byId.values
            .sortedByDescending { it.date }
            .take(limit)
            .sortedBy { it.date }

        return SmsQueryResult(messages, matchedAddresses, threadIds)
    }

    /** Recent sender addresses when a contact query returned nothing (helps debug number mismatches). */
    fun recentSenderAddresses(sinceMs: Long, limit: Int = 50): List<String> {
        val seen = linkedSetOf<String>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(sinceMs.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            while (cursor.moveToNext() && seen.size < limit) {
                cursor.getString(addressIdx)?.trim()?.takeIf { it.isNotEmpty() }?.let { seen += it }
            }
        }
        return seen.toList()
    }

    fun phonesFromContact(contact: JsonObject): List<String> {
        val phones = contact["phoneNumbers"]?.jsonArray ?: return emptyList()
        val seen = linkedSetOf<String>()
        val results = mutableListOf<String>()
        for (entry in phones) {
            val number = entry.jsonObject["number"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: continue
            val key = PhoneNumberMatch.digitsOnly(number)
            if (key.isNotEmpty() && seen.add(key)) results += number
        }
        return results
    }

    /** Inbox senders whose reverse contact lookup matches [contactId]. */
    fun discoverSendersForContact(
        contactId: String,
        sinceMs: Long,
        lookup: (String) -> dev.phonepi.companion.contacts.ContactRepository.PhoneLookupResult?,
    ): List<String> {
        val matches = linkedSetOf<String>()
        for (address in recentSenderAddresses(sinceMs, limit = 100)) {
            val owner = lookup(address) ?: continue
            if (owner.contactId == contactId) matches += address
        }
        return matches.toList()
    }

    fun toJson(row: SmsRow, contactName: String?): JsonObject = buildJsonObject {
        put("id", row.id)
        put("threadId", row.threadId)
        put("address", row.address)
        put("body", row.body)
        put("date", row.date)
        put("read", row.read)
        put("direction", row.direction)
        contactName?.let { put("contactName", it) }
    }

    private fun queryUri(
        uri: Uri,
        addressPatterns: List<String>,
        sinceMs: Long,
        fetchLimit: Int,
    ): List<SmsRow> {
        val addressClause = addressPatterns.joinToString(" OR ") { "${Telephony.Sms.ADDRESS} LIKE ?" }
        val selection = "($addressClause) AND ${Telephony.Sms.DATE} >= ?"
        val args = addressPatterns.map { it } + sinceMs.toString()

        return readRows(uri, selection, args.toTypedArray(), "${Telephony.Sms.DATE} DESC", fetchLimit)
    }

    private fun queryThread(
        threadId: String,
        sinceMs: Long,
        includeSent: Boolean,
    ): List<SmsRow> {
        val selection = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} >= ?"
        val args = arrayOf(threadId, sinceMs.toString())
        val rows = readRows(
            Telephony.Sms.CONTENT_URI,
            selection,
            args,
            "${Telephony.Sms.DATE} ASC",
            limit = 500,
        )
        return if (includeSent) {
            rows
        } else {
            rows.filter { it.direction == "incoming" }
        }
    }

    private fun readRows(
        uri: Uri,
        selection: String,
        args: Array<String>,
        sortOrder: String,
        limit: Int,
    ): List<SmsRow> {
        val results = mutableListOf<SmsRow>()
        context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
            ),
            selection,
            args,
            sortOrder,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (cursor.moveToNext()) {
                val type = cursor.getInt(typeIdx)
                if (type !in ALLOWED_TYPES) continue

                results += SmsRow(
                    id = cursor.getString(idIdx) ?: continue,
                    threadId = cursor.getString(threadIdx) ?: "",
                    address = cursor.getString(addressIdx) ?: "",
                    body = cursor.getString(bodyIdx) ?: "",
                    date = cursor.getLong(dateIdx),
                    read = cursor.getInt(readIdx) != 0,
                    direction = if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "outgoing" else "incoming",
                )
                if (results.size >= limit) break
            }
        }
        return results
    }

    companion object {
        private val ALLOWED_TYPES = setOf(
            Telephony.Sms.MESSAGE_TYPE_INBOX,
            Telephony.Sms.MESSAGE_TYPE_SENT,
        )
    }
}
