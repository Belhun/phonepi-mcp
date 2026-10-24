package dev.phonepi.companion.textsend

/**
 * Rules for a bot text. No Android APIs. The phone UI and SmsManager call
 * these before anything leaves the device.
 */
object SendTextPolicy {
    const val ATTRIBUTION_PREFIX = "[Alex's assistant Grok]: "
    const val MESSAGE_MAX = 1000
    const val TTL_MS = 5 * 60 * 1000L
    const val AS_ALEX_PER_MINUTE = 5
    const val AS_ALEX_PER_DAY = 30
    const val ATTRIBUTED_PER_MINUTE = 5
    const val ATTRIBUTED_PER_DAY = 20
    const val MODE_AS_ALEX = "as_alex"
    const val MODE_ATTRIBUTED = "attributed"

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun carrierText(mode: String, message: String): String {
        if (mode != MODE_ATTRIBUTED) return message
        return if (message.startsWith(ATTRIBUTION_PREFIX)) {
            message
        } else {
            ATTRIBUTION_PREFIX + message
        }
    }

    fun maskPhone(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return "****"
        if (digits.length <= 4) return "*".repeat(digits.length)
        val tail = digits.takeLast(4)
        val stars = "*".repeat(digits.length - 4)
        return if (number.trim().startsWith("+")) "+$stars$tail" else "$stars$tail"
    }

    fun isDirectNumber(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.any { it.isLetter() }) return false
        val digits = trimmed.count { it.isDigit() }
        if (digits !in 7..15) return false
        return trimmed.all { it.isDigit() || it == '+' || it == '-' || it == '(' || it == ')' || it == '.' || it == ' ' }
    }

    fun normalizeNumber(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    fun drivingDetected(
        toggle: Boolean,
        androidAuto: Boolean,
        bluetoothCarAudio: Boolean,
        inVehicle: Boolean,
    ): Boolean = toggle || androidAuto || bluetoothCarAudio || inVehicle

    fun interpretSpoken(raw: String?): SpokenChoice {
        val cleaned = raw.orEmpty()
            .lowercase()
            .replace(Regex("[^a-z\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return SpokenChoice.UNCLEAR
        if (cleaned in CANCEL_PHRASES) return SpokenChoice.CANCEL
        if (cleaned in SEND_PHRASES) return SpokenChoice.SEND
        return SpokenChoice.UNCLEAR
    }

    fun resolveRecipient(raw: String, contacts: List<ContactNumber>): RecipientResolution {
        val recipient = raw.trim()
        if (recipient.isEmpty()) {
            return RecipientResolution.Rejected("recipient must be one contact name or one phone number")
        }
        if (recipient.contains(',') || recipient.contains(';') || recipient.contains('\n') ||
            Regex("\\band\\b", RegexOption.IGNORE_CASE).containsMatchIn(recipient)
        ) {
            return RecipientResolution.Rejected("one recipient only. No groups, lists, or multiple numbers.")
        }
        if (isDirectNumber(recipient)) {
            val number = normalizeNumber(recipient)
            val owners = contacts.filter { sameNumber(it.number, number) }
            val name = if (owners.map { it.name.lowercase() }.distinct().size == 1) {
                owners.first().name
            } else {
                number
            }
            return RecipientResolution.One(name = name, number = number)
        }
        val query = collapse(recipient)
        val exact = contacts.filter { collapse(it.name) == query }
        val matched = if (exact.isNotEmpty()) {
            exact
        } else {
            val tokens = query.split(' ').filter { it.isNotEmpty() }
            contacts.filter { contact ->
                val nameTokens = collapse(contact.name).split(' ').filter { it.isNotEmpty() }
                tokens.all { token -> nameTokens.any { it == token } }
            }
        }
        val rows = matched
            .map { ContactNumber(it.contactId, it.name, normalizeNumber(it.number)) }
            .distinctBy { "${it.contactId}:${it.number}" }
            .filter { it.number.count { ch -> ch.isDigit() } >= 7 }
        val numbers = rows.map { it.number }.distinct()
        if (numbers.size == 1 && rows.map { it.contactId }.distinct().size == 1) {
            return RecipientResolution.One(name = rows.first().name, number = numbers.first())
        }
        return RecipientResolution.Clarify(
            rows.map { MaskedCandidate(it.name, maskPhone(it.number)) }.distinct(),
        )
    }

    /**
     * as_alex sends only after tap or a clear spoken send, and only while
     * the request is still pending and inside the 5 minute window.
     * attributed sends only when the carrier text already has the prefix.
     * Cancel and expiry never send, and they stick.
     */
    fun decide(record: SendRecord, action: SendAction, now: Long): SendDecision {
        if (record.status != "pending") {
            return SendDecision.Keep(record.status)
        }
        if (action !is SendAction.Expire && now >= record.expiresAt) {
            return SendDecision.Close("expired")
        }
        return when (action) {
            SendAction.Expire -> SendDecision.Close("expired")
            SendAction.Cancel -> SendDecision.Close("cancelled")
            is SendAction.Confirm -> {
                if (record.mode != MODE_AS_ALEX) return SendDecision.Keep("pending")
                if (action.method != "tap" && action.method != "spoken") {
                    return SendDecision.Keep("pending")
                }
                SendDecision.Send(record.message, action.method)
            }
            is SendAction.Attributed -> {
                if (record.mode != MODE_ATTRIBUTED) return SendDecision.Keep("pending")
                val body = carrierText(MODE_ATTRIBUTED, record.message)
                if (!body.startsWith(ATTRIBUTION_PREFIX)) return SendDecision.Close("failed")
                if (action.carrierText != body) return SendDecision.Close("failed")
                SendDecision.Send(body, "attributed")
            }
        }
    }

    private fun sameNumber(left: String, right: String): Boolean {
        val a = left.filter { it.isDigit() }
        val b = right.filter { it.isDigit() }
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length >= 10 && b.length >= 10) return a.takeLast(10) == b.takeLast(10)
        return false
    }

    private fun collapse(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private val SEND_PHRASES = setOf("send", "send it", "yes send", "please send")
    private val CANCEL_PHRASES = setOf(
        "cancel",
        "cancel it",
        "don't send",
        "dont send",
        "do not send",
    )
}

enum class SpokenChoice { SEND, CANCEL, UNCLEAR }

data class ContactNumber(
    val contactId: String,
    val name: String,
    val number: String,
)

data class MaskedCandidate(
    val name: String,
    val numberMasked: String,
)

sealed class RecipientResolution {
    data class One(val name: String, val number: String) : RecipientResolution()
    data class Clarify(val candidates: List<MaskedCandidate>) : RecipientResolution()
    data class Rejected(val error: String) : RecipientResolution()
}

data class SendRecord(
    val id: String,
    val mode: String,
    val recipientName: String,
    val number: String,
    val message: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String = "pending",
    val confirmMethod: String? = null,
    val handedToCarrier: Boolean = false,
)

sealed class SendAction {
    data class Confirm(val method: String) : SendAction()
    data object Cancel : SendAction()
    data object Expire : SendAction()
    data class Attributed(val carrierText: String) : SendAction()
}

sealed class SendDecision {
    data class Send(val carrierText: String, val confirmMethod: String) : SendDecision()
    data class Close(val status: String) : SendDecision()
    data class Keep(val status: String) : SendDecision()
}

class SendTextBudget(
    private val now: () -> Long,
    initial: Map<String, List<Long>> = emptyMap(),
) {
    private val hits: MutableMap<String, MutableList<Long>> = mutableMapOf()

    init {
        for ((mode, stamps) in initial) {
            hits[mode] = stamps.toMutableList()
        }
    }

    fun allow(mode: String): BudgetResult {
        val perMinute = if (mode == SendTextPolicy.MODE_ATTRIBUTED) {
            SendTextPolicy.ATTRIBUTED_PER_MINUTE
        } else {
            SendTextPolicy.AS_ALEX_PER_MINUTE
        }
        val perDay = if (mode == SendTextPolicy.MODE_ATTRIBUTED) {
            SendTextPolicy.ATTRIBUTED_PER_DAY
        } else {
            SendTextPolicy.AS_ALEX_PER_DAY
        }
        val t = now()
        val recent = (hits[mode] ?: mutableListOf()).filter { it > t - DAY_MS }.toMutableList()
        val minuteHits = recent.filter { it > t - MINUTE_MS }
        if (minuteHits.size >= perMinute) {
            hits[mode] = recent
            val oldest = minuteHits.first()
            val retry = ((oldest + MINUTE_MS - t + 999) / 1000).coerceAtLeast(1)
            return BudgetResult.Denied(retry, "minute")
        }
        if (recent.size >= perDay) {
            hits[mode] = recent
            val oldest = recent.first()
            val retry = ((oldest + DAY_MS - t + 999) / 1000).coerceAtLeast(1)
            return BudgetResult.Denied(retry, "day")
        }
        recent.add(t)
        hits[mode] = recent
        return BudgetResult.Allowed
    }

    fun export(): Map<String, List<Long>> = hits.mapValues { it.value.toList() }

    sealed class BudgetResult {
        data object Allowed : BudgetResult()
        data class Denied(val retryAfterSec: Long, val scope: String) : BudgetResult()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
