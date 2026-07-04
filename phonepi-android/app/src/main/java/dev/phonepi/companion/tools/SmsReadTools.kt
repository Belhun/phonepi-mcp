package dev.phonepi.companion.tools

// Disabled — carrier SMS read superseded by Google Messages gm_get_conversation / gm_get_messages.

/*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.phonepi.companion.contacts.ContactRepository
import dev.phonepi.companion.sms.SmsRepository
import dev.phonepi.companion.telephony.PhoneNumberMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetSmsMessagesTool(private val context: Context) : ToolHandler {
    private val smsRepo = SmsRepository(context)
    private val contactRepo = ContactRepository(context)

    override val name = "get_sms_messages"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return toolError("READ_SMS permission not granted — open PhonePi and grant SMS & SIMs")
        }

        val from = params.requireString("from")
        val contactName = params.requireString("contactName")
        val contactId = params.requireString("contactId")
        if (from == null && contactName == null && contactId == null) {
            return toolError("Provide from, contactName, or contactId")
        }
        if ((contactName != null || contactId != null) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return toolError("READ_CONTACTS permission not granted — needed to look up a contact by name")
        }

        val requestedLimit = params.requireInt("limit") ?: DEFAULT_LIMIT
        if (requestedLimit < 1) {
            return toolError("limit must be at least 1")
        }
        val limit = requestedLimit
        val exceedsSoftCap = limit > SOFT_CAP
        val includeSent = params.optionalBool("includeSent", default = true)
        val sinceMs = resolveSinceMs(params)

        val resolved = withContext(Dispatchers.IO) {
            resolvePhones(from, contactName, contactId)
        }
        if (resolved.error != null) return resolved.error

        var phones = dedupePhones(resolved.phones)
        if (phones.isEmpty() && resolved.contactId == null && from == null) {
            return toolError("No phone numbers found for this contact")
        }

        var discoveredSmsAddresses = emptyList<String>()
        var queryResult = withContext(Dispatchers.IO) {
            smsRepo.listConversation(phones, sinceMs, limit, includeSent)
        }

        if (queryResult.messages.isEmpty() && resolved.contactId != null) {
            discoveredSmsAddresses = withContext(Dispatchers.IO) {
                smsRepo.discoverSendersForContact(resolved.contactId, sinceMs) { address ->
                    contactRepo.lookupByPhone(address)
                }
            }
            if (discoveredSmsAddresses.isNotEmpty()) {
                phones = dedupePhones(phones + discoveredSmsAddresses)
                queryResult = withContext(Dispatchers.IO) {
                    smsRepo.listConversation(phones, sinceMs, limit, includeSent)
                }
            }
        }

        val recentSenders = if (queryResult.messages.isEmpty()) {
            withContext(Dispatchers.IO) { smsRepo.recentSenderAddresses(sinceMs) }
        } else {
            emptyList()
        }

        return success {
            put("count", queryResult.messages.size)
            put("since", sinceMs)
            resolved.contactName?.let { put("contactName", it) }
            resolved.contactId?.let { put("contactId", it) }
            if (resolved.contactPhones.isNotEmpty()) {
                put("contactPhoneNumbers", buildJsonArray {
                    resolved.contactPhones.forEach { add(JsonPrimitive(it)) }
                })
            }
            put("phoneNumbersUsed", buildJsonArray { phones.forEach { add(JsonPrimitive(it)) } })
            if (discoveredSmsAddresses.isNotEmpty()) {
                put("discoveredSmsAddresses", buildJsonArray {
                    discoveredSmsAddresses.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (queryResult.matchedAddresses.isNotEmpty()) {
                put("matchedAddresses", buildJsonArray {
                    queryResult.matchedAddresses.forEach { add(JsonPrimitive(it)) }
                })
            }
            put(
                "messages",
                buildJsonArray {
                    queryResult.messages.forEach { row ->
                        add(smsRepo.toJson(row, resolved.contactName))
                    }
                },
            )
            if (queryResult.messages.size >= limit) {
                put("truncated", true)
                put(
                    "note",
                    "Result capped at the requested limit of $limit; narrow the time window or raise limit",
                )
            }
            if (exceedsSoftCap) {
                put("softCapExceeded", true)
                put(
                    "softCapNote",
                    "limit ($limit) is above the $SOFT_CAP soft cap — allowed because it was explicitly requested; large results may be slow",
                )
            }
            if (queryResult.messages.isEmpty()) {
                put(
                    "hint",
                    buildEmptyHint(resolved.contactPhones, discoveredSmsAddresses, recentSenders),
                )
                if (recentSenders.isNotEmpty()) {
                    put("recentInboxSenders", buildJsonArray {
                        recentSenders.take(12).forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }
    }

    private fun buildEmptyHint(
        contactPhones: List<String>,
        discovered: List<String>,
        recent: List<String>,
    ): String {
        val parts = mutableListOf(
            "No SMS matched in this time window.",
        )
        if (contactPhones.isNotEmpty() && discovered.isEmpty()) {
            parts += "The contact card lists ${contactPhones.joinToString()} but no recent inbox sender reverse-matched to this contact."
        }
        if (recent.isNotEmpty()) {
            parts += "Recent inbox senders: ${recent.take(5).joinToString()}."
        }
        parts += "RCS/chat (Google Messages) often is not stored as SMS. Try passing from with a number from recentInboxSenders."
        return parts.joinToString(" ")
    }

    private fun dedupePhones(phones: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<String>()
        for (phone in phones) {
            val key = PhoneNumberMatch.digitsOnly(phone)
            if (key.isNotEmpty() && seen.add(key)) out += phone
        }
        return out
    }

    private fun resolveSinceMs(params: JsonObject): Long {
        params.optionalLong("since")?.let { return it }
        val hours = params.requireInt("hours") ?: 24
        return System.currentTimeMillis() - hours * 3_600_000L
    }

    private data class ResolvedPhones(
        val phones: List<String>,
        val contactPhones: List<String>,
        val contactName: String?,
        val contactId: String?,
        val error: JsonObject?,
    )

    private fun resolvePhones(
        from: String?,
        contactName: String?,
        contactId: String?,
    ): ResolvedPhones {
        if (from != null) {
            return ResolvedPhones(listOf(from), emptyList(), null, null, null)
        }

        if (contactId != null) {
            val contact = contactRepo.getById(contactId)
                ?: return ResolvedPhones(emptyList(), emptyList(), null, null, toolError("Contact not found: $contactId"))
            val contactPhones = smsRepo.phonesFromContact(contact)
            val name = contact.requireString("name")
            return ResolvedPhones(contactPhones, contactPhones, name, contactId, null)
        }

        val query = contactName ?: return ResolvedPhones(emptyList(), emptyList(), null, null, toolError("contactName is empty"))
        val matches = contactRepo.search(query)
        if (matches.isEmpty()) {
            return ResolvedPhones(emptyList(), emptyList(), null, null, toolError("No contact found matching \"$query\""))
        }
        if (matches.size > 1) {
            return ResolvedPhones(
                emptyList(),
                emptyList(),
                null,
                null,
                buildJsonObject {
                    put("status", "error")
                    put("error", "Multiple contacts match \"$query\"; pick one by contactId or refine the name")
                    put("matches", buildJsonArray { matches.forEach { add(it) } })
                },
            )
        }

        val contact = matches.first()
        val contactPhones = smsRepo.phonesFromContact(contact)
        val id = contact.requireString("id")
        return ResolvedPhones(contactPhones, contactPhones, contact.requireString("name"), id, null)
    }

    companion object {
        private const val DEFAULT_LIMIT = 100
        private const val SOFT_CAP = 200
    }
}
*/
