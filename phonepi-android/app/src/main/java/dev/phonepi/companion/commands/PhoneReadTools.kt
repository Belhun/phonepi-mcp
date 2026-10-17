package dev.phonepi.companion.commands

import dev.phonepi.companion.photos.PhotoLimits

/** Read-only tools that are rate-limited but are not phone_* commands. */
object PhoneReadTools {
    val RATE_LIMITS: Map<String, Int> = mapOf(
        "get_notifications" to 60,
        "list_notification_apps" to 60,
        "get_volumes" to 60,
        "get_recent_photos" to PhotoLimits.LIST_PER_MINUTE,
    )

    fun maxPerMinute(name: String): Int? = RATE_LIMITS[name]
}
