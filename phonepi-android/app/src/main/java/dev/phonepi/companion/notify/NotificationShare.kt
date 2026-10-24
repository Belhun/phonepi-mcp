package dev.phonepi.companion.notify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Allowlist of apps whose notification bodies may leave the phone.
 * Default: share nothing until Alex picks apps (or turns on Share all).
 */
object NotificationShare {
    private const val PREFS = "phonepi_notification_share"
    private const val KEY_BLOB = "state_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class UiState(
        val shareAll: Boolean = false,
        val allowedPackages: Set<String> = emptySet(),
        val seenApps: List<SeenNotificationApp> = emptyList(),
    ) {
        fun policy(): NotificationSharePolicy =
            NotificationSharePolicy(shareAll = shareAll, allowedPackages = allowedPackages)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = load()
        _state.value = loaded
        NotificationInboxHolder.instance.restoreSeen(loaded.seenApps)
    }

    fun policy(): NotificationSharePolicy = _state.value.policy()

    fun setShareAll(enabled: Boolean) {
        update { it.copy(shareAll = enabled) }
    }

    fun setAllowed(packageName: String, allowed: Boolean) {
        update { current ->
            val next = current.allowedPackages.toMutableSet()
            if (allowed) next.add(packageName) else next.remove(packageName)
            current.copy(allowedPackages = next)
        }
    }

    fun rememberFromInbox() {
        val apps = NotificationInboxHolder.instance.seenApps()
        if (apps.isEmpty()) return
        update { current ->
            val byPkg = current.seenApps.associateBy { it.packageName }.toMutableMap()
            apps.forEach { app ->
                val previous = byPkg[app.packageName]
                byPkg[app.packageName] = SeenNotificationApp(
                    packageName = app.packageName,
                    appName = app.appName.ifBlank { previous?.appName ?: app.packageName },
                    lastPostedAt = maxOf(previous?.lastPostedAt ?: 0L, app.lastPostedAt),
                    seenCount = app.seenCount,
                )
            }
            current.copy(seenApps = byPkg.values.sortedByDescending { it.lastPostedAt })
        }
    }

    private fun update(transform: (UiState) -> UiState) {
        val next = transform(_state.value)
        _state.value = next
        persist(next)
    }

    private fun load(): UiState {
        if (!::prefs.isInitialized) return UiState()
        val raw = prefs.getString(KEY_BLOB, null) ?: return UiState()
        return try {
            val stored = json.decodeFromString(Stored.serializer(), raw)
            UiState(
                shareAll = stored.shareAll,
                allowedPackages = stored.allowed.toSet(),
                seenApps = stored.seen.map {
                    SeenNotificationApp(it.packageName, it.appName, it.lastPostedAt, it.seenCount)
                },
            )
        } catch (_: Exception) {
            UiState()
        }
    }

    private fun persist(state: UiState) {
        if (!::prefs.isInitialized) return
        val stored = Stored(
            shareAll = state.shareAll,
            allowed = state.allowedPackages.sorted(),
            seen = state.seenApps.map {
                StoredApp(it.packageName, it.appName, it.lastPostedAt, it.seenCount)
            },
        )
        prefs.edit().putString(KEY_BLOB, json.encodeToString(Stored.serializer(), stored)).apply()
    }

    @Serializable
    private data class Stored(
        val shareAll: Boolean = false,
        val allowed: List<String> = emptyList(),
        val seen: List<StoredApp> = emptyList(),
    )

    @Serializable
    private data class StoredApp(
        val packageName: String,
        val appName: String,
        val lastPostedAt: Long,
        val seenCount: Int = 1,
    )
}
