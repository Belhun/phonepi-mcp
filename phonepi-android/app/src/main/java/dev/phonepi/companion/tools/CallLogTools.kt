package dev.phonepi.companion.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.phonepi.companion.contacts.ContactRepository
import dev.phonepi.companion.telephony.CallLogRepository
import dev.phonepi.companion.telephony.PhoneNumberMatch
import dev.phonepi.companion.telephony.parseCallTypeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GetRecentCallsTool(private val context: Context) : ToolHandler {
    private val callLogRepo = CallLogRepository(context)
    private val contactRepo = ContactRepository(context)

    override val name = "get_recent_calls"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return toolError(
                "READ_CALL_LOG permission not granted — open PhonePi and grant Phone calls access",
            )
        }

        val requestedLimit = params.requireInt("limit") ?: DEFAULT_LIMIT
        if (requestedLimit < 1) {
            return toolError("limit must be at least 1")
        }
        val limit = requestedLimit
        val exceedsSoftCap = limit > SOFT_CAP
        val sinceMs = resolveSinceMs(params)
        val includeContactNames = params.optionalBool("includeContactNames", default = true)
        if (includeContactNames &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return toolError(
                "READ_CONTACTS permission not granted — needed when includeContactNames is true",
            )
        }

        val typeFilter = resolveTypeFilter(params)
        if (typeFilter == null && hasTypeParams(params)) {
            return toolError(
                "Invalid call type — use incoming, outgoing, missed, voicemail, rejected, blocked, or answered_externally",
            )
        }

        val number = params.requireString("number")
        val numberPatterns = number?.let { PhoneNumberMatch.likePatterns(it) }

        val rows = withContext(Dispatchers.IO) {
            callLogRepo.listRecent(sinceMs, limit, typeFilter, numberPatterns)
        }

        val enriched = withContext(Dispatchers.IO) {
            rows.map { row ->
                val lookup = if (includeContactNames && row.number.isNotEmpty()) {
                    contactRepo.lookupByPhone(row.number)
                } else {
                    null
                }
                callLogRepo.toJson(
                    row,
                    contactName = lookup?.displayName ?: row.cachedName,
                    contactId = lookup?.contactId,
                )
            }
        }

        return success {
            put("count", enriched.size)
            put("since", sinceMs)
            number?.let { put("number", it) }
            put(
                "calls",
                buildJsonArray {
                    enriched.forEach { add(it) }
                },
            )
            if (enriched.size >= limit) {
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
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val SOFT_CAP = 200
    }

    private fun resolveSinceMs(params: JsonObject): Long {
        params.optionalLong("since")?.let { return it }
        val hours = params.requireInt("hours") ?: 168
        return System.currentTimeMillis() - hours.coerceAtLeast(1) * 3_600_000L
    }

    private fun hasTypeParams(params: JsonObject): Boolean =
        params.requireString("type") != null || params["types"] != null

    private fun resolveTypeFilter(params: JsonObject): Set<Int>? {
        val fromArray = params["types"]?.jsonArray?.mapNotNull { element ->
            parseCallTypeFilter(element.jsonPrimitive.content)
        }?.toSet()
        if (fromArray != null) {
            return fromArray.takeIf { it.isNotEmpty() }
        }

        val single = params.requireString("type") ?: return null
        val parsed = parseCallTypeFilter(single) ?: return null
        return setOf(parsed)
    }
}
