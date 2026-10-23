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
        val servers = ServerPrefs.load(app)
        val title = when (trigger) {
            Trigger.BOOT -> "Boot"
            Trigger.PACKAGE_REPLACED -> "Update"
        }
        val hasSaved = saved != null && servers.servers.isNotEmpty()
        if (!shouldStart(trigger, ConnectionPrefs.reconnectOnBoot(app), hasSaved)) {
            val detail = when {
                !hasSaved -> "No saved host; not connecting"
                trigger == Trigger.BOOT -> "Reconnect on boot is off"
                else -> "Not connecting"
            }
            ActivityLog.logConnection(title, detail, "info")
            return
        }
        val first = ServerList.ordered(servers.servers).let { list ->
            servers.pinnedId?.let { id -> list.firstOrNull { it.id == id } } ?: list.firstOrNull()
        }
        ActivityLog.logConnection(
            title,
            "Reconnecting to ${first?.name ?: "saved server"}" +
                (first?.let { " (${it.host}:${it.port})" } ?: ""),
            "info",
        )
        ConnectionService.start(app)
    }
}
