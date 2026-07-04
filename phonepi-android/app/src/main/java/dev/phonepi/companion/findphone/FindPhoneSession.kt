package dev.phonepi.companion.findphone

/** Tracks the active find-phone alarm session so [stop_alarm] and the dismiss UI can stop it. */
object FindPhoneSession {
    @Volatile
    var activeSessionId: String? = null
        private set

    fun markStarted(sessionId: String) {
        activeSessionId = sessionId
    }

    fun markStopped(sessionId: String?) {
        if (sessionId == null || sessionId == activeSessionId) {
            activeSessionId = null
        }
    }

    fun isActive(sessionId: String?): Boolean =
        activeSessionId != null && (sessionId == null || sessionId == activeSessionId)
}
