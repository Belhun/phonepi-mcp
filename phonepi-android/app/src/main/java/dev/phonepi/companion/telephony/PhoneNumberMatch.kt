package dev.phonepi.companion.telephony

object PhoneNumberMatch {
    fun digitsOnly(raw: String): String = raw.filter { it.isDigit() }

    /** Patterns for SQL LIKE matching against Telephony.Sms address fields. */
    fun likePatterns(vararg phones: String): List<String> {
        val patterns = linkedSetOf<String>()
        for (phone in phones) {
            val digits = digitsOnly(phone)
            if (digits.isEmpty()) continue
            patterns += "%$digits%"
            if (digits.length >= 10) patterns += "%${digits.takeLast(10)}"
            if (digits.length >= 7) patterns += "%${digits.takeLast(7)}"
            if (digits.length == 11 && digits.startsWith("1")) {
                patterns += "%${digits.drop(1)}%"
            }
        }
        return patterns.toList()
    }

    fun matches(stored: String, target: String): Boolean {
        val a = digitsOnly(stored)
        val b = digitsOnly(target)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length >= 10 && b.length >= 10 && a.takeLast(10) == b.takeLast(10)) return true
        if (a.length >= 7 && b.length >= 7 && a.takeLast(7) == b.takeLast(7)) return true
        return a.endsWith(b) || b.endsWith(a)
    }
}
