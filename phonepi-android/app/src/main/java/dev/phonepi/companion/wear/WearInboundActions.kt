package dev.phonepi.companion.wear

/**
 * Phone-side actions the watch can request. The watch never holds the PhonePi
 * token; this is what the companion runs after a Data Layer message arrives.
 *
 * [WearInboundActionsAdapter] forwards prompts through AssistantEvents
 * (`get_inbound_prompts` / wake hook) and quiet through WearQuietController.
 */
interface WearInboundActions {
    /** Forward [text] to the box as an inbound assistant prompt. */
    suspend fun submitPrompt(text: String, source: String): WearActionResult

    /** Quiet notifications on the phone for [minutes] (1..720). */
    suspend fun quietForMinutes(minutes: Int): WearActionResult
}

/**
 * Presence label shown on the watch complication. Reads the on-phone
 * presence snapshot only. Does not call Health Connect or usage APIs.
 */
fun interface PresenceLabelSource {
    fun currentLabel(): String?

    companion object {
        val Stub: PresenceLabelSource = PresenceLabelSource { null }

        fun fromSnapshot(enabled: Boolean, paused: Boolean, placeName: String): String? =
            PresenceWatchLabel.label(enabled, paused, placeName)
    }
}

/** Pure mapping so unit tests can cover the watch label without Android prefs. */
object PresenceWatchLabel {
    fun label(enabled: Boolean, paused: Boolean, placeName: String): String? {
        if (!enabled || paused) return null
        val name = placeName.trim()
        return name.takeIf { it.isNotEmpty() }
    }
}
