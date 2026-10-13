package dev.phonepi.companion.commands

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
        val spec = PhoneCommands.byName[tool] ?: return null
        return when (val result = limiter.allow(spec.name, spec.maxPerMinute)) {
            CommandRateLimiter.Result.Allowed -> null
            is CommandRateLimiter.Result.Denied ->
                "Rate limited: ${spec.name}. Try again in ${result.retryAfterSec}s"
        }
    }
}
