package dev.phonepi.companion.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Read-only notification listener. Captures posted and active notifications.
 * Must never cancel, snooze, reply, or otherwise act on a notification.
 */
class PhonePiNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        val inbox = NotificationInboxHolder.instance
        inbox.markConnected(true)
        val snapshot = try {
            activeNotifications?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        inbox.replaceActive(snapshot.map { NotificationMapper.fromStatusBar(it, packageManager) })
        NotificationShare.rememberFromInbox()
    }

    override fun onListenerDisconnected() {
        NotificationInboxHolder.instance.markConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val notification = sbn ?: return
        NotificationInboxHolder.instance.posted(NotificationMapper.fromStatusBar(notification, packageManager))
        NotificationShare.rememberFromInbox()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val key = sbn?.key ?: return
        NotificationInboxHolder.instance.removed(key)
    }
}
