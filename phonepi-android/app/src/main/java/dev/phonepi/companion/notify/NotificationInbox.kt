package dev.phonepi.companion.notify

/**
 * In-memory notification buffer: the last [recentCap] posted items plus
 * whatever is currently active. Never dismisses, replies, or snoozes.
 */
class NotificationInbox(private val recentCap: Int = RECENT_NOTIFICATION_CAP) {
    private val recent = ArrayDeque<NotificationRecord>()
    private val recentByKey = mutableMapOf<String, NotificationRecord>()
    private val active = mutableMapOf<String, NotificationRecord>()
    private val seen = linkedMapOf<String, SeenNotificationApp>()
    var listenerConnected: Boolean = false
        private set

    @Synchronized
    fun markConnected(connected: Boolean) {
        listenerConnected = connected
    }

    @Synchronized
    fun posted(record: NotificationRecord) {
        remember(record)
        upsertRecent(record.withActive(true))
        active[record.key] = record.withActive(true)
    }

    @Synchronized
    fun removed(key: String) {
        active.remove(key)
        val existing = recentByKey[key] ?: return
        val inactive = existing.withActive(false)
        recentByKey[key] = inactive
        val index = recent.indexOfFirst { it.key == key }
        if (index >= 0) {
            recent[index] = inactive
        }
    }

    @Synchronized
    fun replaceActive(records: List<NotificationRecord>) {
        active.clear()
        records.forEach { record ->
            remember(record)
            val live = record.withActive(true)
            active[record.key] = live
            upsertRecent(live)
        }
    }

    @Synchronized
    fun query(query: NotificationQuery, policy: NotificationSharePolicy): NotificationQueryResult {
        val merged = mergedRecords()
        var omittedUnshared = 0
        val wantedPackage = query.packageName?.trim()?.takeIf { it.isNotEmpty() }
        val packageNotShared = wantedPackage != null && !policy.allows(wantedPackage)
        val matched = ArrayList<NotificationRecord>()
        for (record in merged) {
            if (wantedPackage != null && record.packageName != wantedPackage) continue
            if (query.sinceMs != null && record.postedAt < query.sinceMs) continue
            if (!policy.allows(record.packageName)) {
                omittedUnshared += 1
                continue
            }
            matched.add(record)
        }
        matched.sortByDescending { it.postedAt }
        val limit = query.limit.coerceAtLeast(0)
        val sliced = if (limit == 0) emptyList() else matched.take(limit)
        return NotificationQueryResult(
            notifications = sliced,
            omittedUnsharedCount = omittedUnshared,
            truncated = matched.size > sliced.size,
            matchedBeforeLimit = matched.size,
            packageNotShared = packageNotShared,
        )
    }

    @Synchronized
    fun listApps(policy: NotificationSharePolicy): List<ListedNotificationApp> {
        return seen.values
            .sortedByDescending { it.lastPostedAt }
            .map { app ->
                ListedNotificationApp(
                    packageName = app.packageName,
                    appName = app.appName,
                    lastPostedAt = app.lastPostedAt,
                    seenCount = app.seenCount,
                    shared = policy.allows(app.packageName),
                )
            }
    }

    @Synchronized
    fun seenApps(): List<SeenNotificationApp> = seen.values.toList()

    @Synchronized
    fun restoreSeen(apps: List<SeenNotificationApp>) {
        seen.clear()
        apps.sortedBy { it.lastPostedAt }.forEach { seen[it.packageName] = it }
    }

    @Synchronized
    fun recentCount(): Int = recent.size

    @Synchronized
    fun activeCount(): Int = active.size

    private fun mergedRecords(): List<NotificationRecord> {
        val byKey = LinkedHashMap<String, NotificationRecord>()
        recent.forEach { byKey[it.key] = it.withActive(it.key in active) }
        active.forEach { (key, record) -> byKey[key] = record.withActive(true) }
        return byKey.values.toList()
    }

    private fun upsertRecent(record: NotificationRecord) {
        val existing = recentByKey[record.key]
        if (existing != null) {
            val index = recent.indexOfFirst { it.key == record.key }
            if (index >= 0) recent.removeAt(index)
        }
        recent.addLast(record)
        recentByKey[record.key] = record
        while (recent.size > recentCap) {
            val dropped = recent.removeFirst()
            if (recentByKey[dropped.key] === dropped) {
                recentByKey.remove(dropped.key)
            }
        }
    }

    private fun remember(record: NotificationRecord) {
        val previous = seen[record.packageName]
        val appName = if (record.appName.hidden) {
            previous?.appName?.takeIf { it.isNotBlank() && it != HIDDEN_BY_ANDROID }
                ?: record.packageName
        } else {
            record.appName.value
        }
        seen[record.packageName] = SeenNotificationApp(
            packageName = record.packageName,
            appName = appName,
            lastPostedAt = maxOf(previous?.lastPostedAt ?: 0L, record.postedAt),
            seenCount = (previous?.seenCount ?: 0) + 1,
        )
    }
}

data class ListedNotificationApp(
    val packageName: String,
    val appName: String,
    val lastPostedAt: Long,
    val seenCount: Int,
    val shared: Boolean,
)

/** Process-wide inbox the listener writes and the read tools query. */
object NotificationInboxHolder {
    val instance = NotificationInbox()
}
