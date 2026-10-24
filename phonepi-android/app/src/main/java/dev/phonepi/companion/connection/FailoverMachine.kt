package dev.phonepi.companion.connection

/**
 * Timings for automatic server failover.
 * - Leave the primary after [maxFailures] failed attempts, or once [windowMs] has passed
 *   since the link went down (the deadline also cancels an attempt that is still hanging).
 * - While on a fallback, probe the primary every [reprobeMs] and move back when it answers.
 * - Any single open that has not finished the handshake after [attemptTimeoutMs] is a failure.
 */
data class FailoverConfig(
    val maxFailures: Int = 3,
    val windowMs: Long = 15_000L,
    val reprobeMs: Long = 3 * 60_000L,
    val attemptTimeoutMs: Long = 10_000L,
)

sealed interface FailoverDecision {
    data object Stay : FailoverDecision
    data class SwitchTo(
        val server: ServerEntry,
        val from: ServerEntry?,
        val reason: String,
        /** First hop after a working link opens at once; later hops keep the backoff. */
        val immediate: Boolean,
    ) : FailoverDecision
}

/**
 * Pure failover state machine (no Android, no sockets). The WebSocketManager feeds it
 * events and acts on its decisions. Only one server is active at a time.
 *
 * Auto-failover runs when [autoFailover] is on and no server is pinned by a manual switch.
 */
class FailoverMachine(
    servers: List<ServerEntry>,
    autoFailover: Boolean = true,
    pinnedId: String? = null,
    val config: FailoverConfig = FailoverConfig(),
) {
    var servers: List<ServerEntry> = ServerList.ordered(servers)
        private set
    var autoFailover: Boolean = autoFailover
        private set
    var pinnedId: String? = pinnedId?.takeIf { id -> this.servers.any { it.id == id } }
        private set
    var activeId: String? = null
        private set
    var connected: Boolean = false
        private set
    var stintStartedAt: Long = 0L
        private set
    var stintFailures: Int = 0
        private set
    /** Server hops since the last good connection. 0 means this outage just started. */
    var hopsSinceConnect: Int = 0
        private set
    var lastProbeAt: Long = 0L
        private set

    val primary: ServerEntry? get() = servers.firstOrNull()
    val active: ServerEntry? get() = servers.firstOrNull { it.id == activeId }
    val failoverEnabled: Boolean get() = autoFailover && pinnedId == null && servers.size > 1
    val onPrimary: Boolean get() = activeId != null && activeId == primary?.id

    /** Service start: pinned server if any, else the primary. */
    fun start(now: Long): ServerEntry? {
        val first = pinnedId?.let { id -> servers.firstOrNull { it.id == id } } ?: primary
        activeId = first?.id
        beginStint(now)
        hopsSinceConnect = 0
        return first
    }

    fun updateServers(next: List<ServerEntry>, autoFailover: Boolean, pinnedId: String?) {
        servers = ServerList.ordered(next)
        this.autoFailover = autoFailover
        this.pinnedId = pinnedId?.takeIf { id -> servers.any { it.id == id } }
        if (activeId != null && servers.none { it.id == activeId }) activeId = primary?.id
    }

    fun onConnected(now: Long) {
        connected = true
        stintFailures = 0
        hopsSinceConnect = 0
        stintStartedAt = now
        if (!onPrimary) lastProbeAt = now
    }

    /** A live link dropped. The outage clock starts now on the same server. */
    fun onLinkLost(now: Long) {
        connected = false
        hopsSinceConnect = 0
        beginStint(now)
    }

    /** An open failed or timed out before the handshake finished. */
    fun onFailure(now: Long): FailoverDecision {
        connected = false
        stintFailures += 1
        if (!failoverEnabled) return FailoverDecision.Stay
        val elapsed = now - stintStartedAt
        val tooMany = stintFailures >= config.maxFailures
        val tooLong = hopsSinceConnect == 0 && elapsed >= config.windowMs
        if (!tooMany && !tooLong) return FailoverDecision.Stay
        val why = if (tooMany) "$stintFailures failed attempts" else "no link after ${elapsed / 1000}s"
        return hop(now, why)
    }

    /**
     * When the outage deadline fires, or null when none applies. Only the first server
     * of an outage gets the 15 s deadline; later hops rely on the failure count so a
     * both-servers-down outage still backs off to the 5 min cap.
     */
    fun deadlineAt(): Long? {
        if (!failoverEnabled || connected || hopsSinceConnect != 0) return null
        return stintStartedAt + config.windowMs
    }

    fun onDeadline(now: Long): FailoverDecision {
        val at = deadlineAt() ?: return FailoverDecision.Stay
        if (now < at) return FailoverDecision.Stay
        return hop(now, "no link after ${(now - stintStartedAt) / 1000}s")
    }

    fun shouldProbePrimary(now: Long): Boolean =
        failoverEnabled && connected && !onPrimary && now - lastProbeAt >= config.reprobeMs

    fun onProbeResult(healthy: Boolean, now: Long): FailoverDecision {
        lastProbeAt = now
        if (!healthy || !failoverEnabled || onPrimary) return FailoverDecision.Stay
        val target = primary ?: return FailoverDecision.Stay
        val from = active
        activeId = target.id
        connected = false
        hopsSinceConnect = 0
        beginStint(now)
        return FailoverDecision.SwitchTo(target, from, "primary is healthy again", immediate = true)
    }

    /** Manual override from the Connect screen. Pins the server until auto is chosen again. */
    fun pin(id: String, now: Long): ServerEntry? {
        val target = servers.firstOrNull { it.id == id } ?: return null
        pinnedId = target.id
        activeId = target.id
        connected = false
        hopsSinceConnect = 0
        beginStint(now)
        return target
    }

    fun unpin() {
        pinnedId = null
    }

    fun setAuto(enabled: Boolean) {
        autoFailover = enabled
    }

    private fun hop(now: Long, why: String): FailoverDecision {
        val list = servers
        val from = active
        val index = list.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        val next = list[(index + 1) % list.size]
        if (next.id == activeId) return FailoverDecision.Stay
        val firstHop = hopsSinceConnect == 0
        activeId = next.id
        hopsSinceConnect += 1
        beginStint(now)
        return FailoverDecision.SwitchTo(next, from, why, immediate = firstHop)
    }

    private fun beginStint(now: Long) {
        stintStartedAt = now
        stintFailures = 0
    }
}
