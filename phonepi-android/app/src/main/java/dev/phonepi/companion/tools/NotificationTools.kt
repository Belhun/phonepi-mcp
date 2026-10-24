package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.notify.DEFAULT_NOTIFICATION_LIMIT
import dev.phonepi.companion.notify.HIDDEN_BY_ANDROID
import dev.phonepi.companion.notify.NotificationAccess
import dev.phonepi.companion.notify.NotificationInboxHolder
import dev.phonepi.companion.notify.NotificationQuery
import dev.phonepi.companion.notify.NotificationShare
import dev.phonepi.companion.notify.RECENT_NOTIFICATION_CAP
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GetNotificationsTool(private val context: Context) : ToolHandler {
    override val name = "get_notifications"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!NotificationAccess.isGranted(context)) {
            return toolError(
                "Notification access not granted — open PhonePi → Tool permissions and grant Notification access. " +
                    "Nothing is read until that system setting is on.",
            )
        }
        val requestedLimit = params.requireInt("limit") ?: DEFAULT_NOTIFICATION_LIMIT
        if (requestedLimit < 1) {
            return toolError("limit must be at least 1")
        }
        val limit = requestedLimit.coerceAtMost(RECENT_NOTIFICATION_CAP)
        val sinceMs = params.optionalLong("since")
        val packageName = params.requireString("package") ?: params.requireString("packageName")
        val inbox = NotificationInboxHolder.instance
        val policy = NotificationShare.policy()
        val result = inbox.query(
            NotificationQuery(packageName = packageName, sinceMs = sinceMs, limit = limit),
            policy,
        )
        val notes = buildList {
            if (!inbox.listenerConnected) {
                add(
                    "Notification access is granted but the listener is not connected yet. " +
                        "Toggle Notification access off and on, or reopen PhonePi.",
                )
            }
            if (policy.shareAll.not() && policy.allowedPackages.isEmpty()) {
                add(
                    "No apps are shared yet. In PhonePi, pick apps under Notification sharing " +
                        "or turn on Share all. Unshared notifications are omitted, not redacted.",
                )
            }
            if (result.packageNotShared && packageName != null) {
                add("Package $packageName is not on the share allowlist.")
            }
            if (result.omittedUnsharedCount > 0) {
                add("${result.omittedUnsharedCount} notification(s) omitted because their app is not shared.")
            }
            if (result.truncated) {
                add("Result capped at $limit of ${result.matchedBeforeLimit} matching shared notifications.")
            }
            if (requestedLimit > RECENT_NOTIFICATION_CAP) {
                add("limit was reduced to $RECENT_NOTIFICATION_CAP (in-memory cap).")
            }
        }
        return success {
            put("count", result.notifications.size)
            put("omittedUnsharedCount", result.omittedUnsharedCount)
            put("shareAll", policy.shareAll)
            put("listenerConnected", inbox.listenerConnected)
            put("since", sinceMs ?: 0L)
            put("limit", limit)
            packageName?.let { put("package", it) }
            put(
                "notifications",
                buildJsonArray {
                    result.notifications.forEach { record ->
                        add(
                            buildJsonObject {
                                put("key", record.key)
                                put("package", record.packageName)
                                put("appName", record.appName.value)
                                put("title", record.title.value)
                                put("text", record.text.value)
                                put("time", record.timeIso)
                                put("postedAt", record.postedAt)
                                put("isActive", record.isActive)
                                put("notificationId", record.notificationId)
                                val hidden = record.hiddenFields()
                                if (hidden.isNotEmpty()) {
                                    put(
                                        "hiddenFields",
                                        buildJsonArray {
                                            hidden.forEach { field ->
                                                add(
                                                    buildJsonObject {
                                                        put("field", field.field)
                                                        put("reason", field.reason)
                                                        put("placeholder", HIDDEN_BY_ANDROID)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
            if (notes.isNotEmpty()) {
                put("note", notes.joinToString(" "))
            }
        }
    }
}

class ListNotificationAppsTool(private val context: Context) : ToolHandler {
    override val name = "list_notification_apps"

    override suspend fun handle(@Suppress("UNUSED_PARAMETER") params: JsonObject): JsonObject {
        if (!NotificationAccess.isGranted(context)) {
            return toolError(
                "Notification access not granted — open PhonePi → Tool permissions and grant Notification access.",
            )
        }
        val policy = NotificationShare.policy()
        val apps = NotificationInboxHolder.instance.listApps(policy)
        return success {
            put("count", apps.size)
            put("shareAll", policy.shareAll)
            put("listenerConnected", NotificationInboxHolder.instance.listenerConnected)
            put(
                "apps",
                buildJsonArray {
                    apps.forEach { app ->
                        add(
                            buildJsonObject {
                                put("package", app.packageName)
                                put("appName", app.appName)
                                put("shared", app.shared)
                                put("lastPostedAt", app.lastPostedAt)
                                put("seenCount", app.seenCount)
                            },
                        )
                    }
                },
            )
            if (apps.isEmpty()) {
                put(
                    "note",
                    "No apps have posted a notification since PhonePi gained Notification access in this session. " +
                        "Bodies are never included here.",
                )
            }
        }
    }
}
