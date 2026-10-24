package dev.phonepi.companion.notify

import java.time.Instant

const val HIDDEN_BY_ANDROID = "(hidden by Android)"
const val RECENT_NOTIFICATION_CAP = 200
const val DEFAULT_NOTIFICATION_LIMIT = 50

/** A text field that Android either exposed or withheld from the listener. */
data class ExposedField(
    val value: String,
    val hidden: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun of(raw: String?, fieldName: String): ExposedField {
            val trimmed = raw?.trim().orEmpty()
            return if (trimmed.isEmpty()) {
                ExposedField(
                    value = HIDDEN_BY_ANDROID,
                    hidden = true,
                    reason = "Android did not expose $fieldName to the notification listener",
                )
            } else {
                ExposedField(value = trimmed, hidden = false)
            }
        }
    }
}

data class HiddenField(
    val field: String,
    val reason: String,
)

data class NotificationRecord(
    val key: String,
    val packageName: String,
    val appName: ExposedField,
    val title: ExposedField,
    val text: ExposedField,
    val postedAt: Long,
    val isActive: Boolean,
    val notificationId: Int = 0,
) {
    val timeIso: String get() = Instant.ofEpochMilli(postedAt).toString()

    fun hiddenFields(): List<HiddenField> = buildList {
        if (appName.hidden) add(HiddenField("appName", appName.reason ?: "hidden"))
        if (title.hidden) add(HiddenField("title", title.reason ?: "hidden"))
        if (text.hidden) add(HiddenField("text", text.reason ?: "hidden"))
    }

    fun withActive(active: Boolean): NotificationRecord = copy(isActive = active)
}

data class SeenNotificationApp(
    val packageName: String,
    val appName: String,
    val lastPostedAt: Long,
    val seenCount: Int = 1,
)

data class NotificationSharePolicy(
    val shareAll: Boolean = false,
    val allowedPackages: Set<String> = emptySet(),
) {
    fun allows(packageName: String): Boolean =
        shareAll || packageName in allowedPackages
}

data class NotificationQuery(
    val packageName: String? = null,
    val sinceMs: Long? = null,
    val limit: Int = DEFAULT_NOTIFICATION_LIMIT,
)

data class NotificationQueryResult(
    val notifications: List<NotificationRecord>,
    val omittedUnsharedCount: Int,
    val truncated: Boolean,
    val matchedBeforeLimit: Int,
    val packageNotShared: Boolean,
)
