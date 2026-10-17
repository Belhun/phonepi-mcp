package dev.phonepi.companion.connection

/**
 * Permission flags the phone reports to the server. Only these keys are sent.
 * The JSON has no token, host, or other personal data.
 */
object PhonePermissionReport {
    val KEYS = listOf(
        "overlay",
        "post_notifications",
        "full_screen_intent",
        "notification_listener",
        "dnd_access",
        "exact_alarms",
    )

    data class Snapshot(
        val granted: Map<String, Boolean>,
        val missing: List<String>,
    )

    fun fromFlags(flags: Map<String, Boolean>): Snapshot {
        val granted = LinkedHashMap<String, Boolean>(KEYS.size)
        for (key in KEYS) {
            granted[key] = flags[key] == true
        }
        val missing = KEYS.filter { granted[it] != true }
        return Snapshot(granted, missing)
    }

    fun toJson(snapshot: Snapshot): String {
        val perms = KEYS.joinToString(",") { key ->
            "\"$key\":${snapshot.granted[key] == true}"
        }
        val missing = snapshot.missing.joinToString(",") { key -> "\"$key\"" }
        return """{"type":"permissions","permissions":{$perms},"missing":[$missing]}"""
    }
}
