package dev.phonepi.companion.commands

import dev.phonepi.companion.photos.PhotoLimits

/**
 * Allowlisted phone actions. Unknown names never run. Future phone actions
 * register here with a rate limit; the matching ToolHandler uses the same name.
 */
data class PhoneCommandSpec(
    val name: String,
    val kind: Kind,
    val maxPerMinute: Int,
) {
    enum class Kind { READ, WRITE, ACTION }
}

object PhoneCommands {
    val ALL: List<PhoneCommandSpec> = listOf(
        PhoneCommandSpec("phone_list_locations", PhoneCommandSpec.Kind.READ, 60),
        PhoneCommandSpec("phone_add_location", PhoneCommandSpec.Kind.WRITE, 20),
        PhoneCommandSpec("phone_remove_location", PhoneCommandSpec.Kind.WRITE, 20),
        PhoneCommandSpec("phone_navigate", PhoneCommandSpec.Kind.ACTION, 6),
        PhoneCommandSpec("phone_push_update", PhoneCommandSpec.Kind.WRITE, 6),
        PhoneCommandSpec("phone_set_volume", PhoneCommandSpec.Kind.WRITE, 20),
        // Read of image bytes. Token-gated because pixels are more sensitive than battery.
        PhoneCommandSpec(
            "get_photo",
            PhoneCommandSpec.Kind.READ,
            PhotoLimits.PHOTO_PER_MINUTE,
        ),
        PhoneCommandSpec("set_alarm", PhoneCommandSpec.Kind.ACTION, 10),
        PhoneCommandSpec("set_timer", PhoneCommandSpec.Kind.ACTION, 10),
        PhoneCommandSpec("dismiss_alarm", PhoneCommandSpec.Kind.ACTION, 10),
        PhoneCommandSpec("dismiss_timer", PhoneCommandSpec.Kind.ACTION, 10),
        PhoneCommandSpec("pause_location_logging", PhoneCommandSpec.Kind.WRITE, 10),
        PhoneCommandSpec("clear_location_history", PhoneCommandSpec.Kind.WRITE, 4),
        PhoneCommandSpec("pause_presence_logging", PhoneCommandSpec.Kind.WRITE, 10),
        PhoneCommandSpec("clear_presence_history", PhoneCommandSpec.Kind.WRITE, 4),
        PhoneCommandSpec("pause_usage_logging", PhoneCommandSpec.Kind.WRITE, 10),
        PhoneCommandSpec("clear_usage_history", PhoneCommandSpec.Kind.WRITE, 4),
        PhoneCommandSpec("pause_health_sync", PhoneCommandSpec.Kind.WRITE, 10),
        PhoneCommandSpec("clear_health_cache", PhoneCommandSpec.Kind.WRITE, 4),
        PhoneCommandSpec("clear_assistant_events", PhoneCommandSpec.Kind.WRITE, 4),
        // 10/min is a shared backstop. The text budget enforces 5/min and the daily cap per mode.
        PhoneCommandSpec("request_send_text", PhoneCommandSpec.Kind.ACTION, 10),
        PhoneCommandSpec("get_send_status", PhoneCommandSpec.Kind.READ, 60),
        PhoneCommandSpec("send_notification", PhoneCommandSpec.Kind.ACTION, 20),
    )

    val byName: Map<String, PhoneCommandSpec> = ALL.associateBy { it.name }

    fun isAllowlisted(name: String): Boolean = name in byName
}

class CommandRateLimiter(
    private val windowMs: Long = 60_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val hits = mutableMapOf<String, MutableList<Long>>()

    @Synchronized
    fun allow(name: String, maxPerWindow: Int): Result {
        val t = now()
        val cutoff = t - windowMs
        val recent = hits.getOrPut(name) { mutableListOf() }
        recent.removeAll { it <= cutoff }
        if (recent.size >= maxPerWindow) {
            val oldest = recent.firstOrNull() ?: t
            val retryAfterSec = ((oldest + windowMs - t + 999) / 1000).coerceAtLeast(1)
            return Result.Denied(retryAfterSec)
        }
        recent.add(t)
        return Result.Allowed
    }

    sealed class Result {
        data object Allowed : Result()
        data class Denied(val retryAfterSec: Long) : Result()
    }
}

object CommandGate {
    private val limiter = CommandRateLimiter()

    fun check(tool: String): String? {
        val max = PhoneCommands.byName[tool]?.maxPerMinute
            ?: PhoneReadTools.maxPerMinute(tool)
            ?: return null
        return when (val result = limiter.allow(tool, max)) {
            CommandRateLimiter.Result.Allowed -> null
            is CommandRateLimiter.Result.Denied ->
                "Rate limited: $tool. Try again in ${result.retryAfterSec}s"
        }
    }
}
