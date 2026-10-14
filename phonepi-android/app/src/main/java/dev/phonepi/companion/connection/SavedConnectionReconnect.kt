package dev.phonepi.companion.connection

import android.content.Context
import dev.phonepi.companion.log.ActivityLog

/**
 * Restarts [ConnectionService] from a saved host. Used after reboot and after
 * the APK is replaced (self-update). Package replace always reconnects when a
 * host is saved; boot still honors the connect-screen toggle.
 */
object SavedConnectionReconnect {
    enum class Trigger { BOOT, PACKAGE_REPLACED }

    fun shouldStart(
        trigger: Trigger,
        bootToggleOn: Boolean,
        hasSavedHost: Boolean,
    ): Boolean {
        if (!hasSavedHost) return false
        return when (trigger) {
            Trigger.BOOT -> bootToggleOn
            Trigger.PACKAGE_REPLACED -> true
        }
    }

    fun startIfSaved(context: Context, trigger: Trigger) {
        val app = context.applicationContext
        val saved = ConnectionPrefs.getLastSuccessful(app)
        val title = when (trigger) {
            Trigger.BOOT -> "Boot"
            Trigger.PACKAGE_REPLACED -> "Update"
        }
        if (!shouldStart(trigger, ConnectionPrefs.reconnectOnBoot(app), saved != null)) {
            val detail = when {
                saved == null -> "No saved host; not connecting"
                trigger == Trigger.BOOT -> "Reconnect on boot is off"
                else -> "Not connecting"
            }
            ActivityLog.logConnection(title, detail, "info")
            return
        }
        val endpoint = checkNotNull(saved)
        ActivityLog.logConnection(
            title,
            "Reconnecting to ${endpoint.host}:${endpoint.port}",
            "info",
        )
        ConnectionService.start(app, endpoint.host, endpoint.port, endpoint.wsToken)
    }
}
