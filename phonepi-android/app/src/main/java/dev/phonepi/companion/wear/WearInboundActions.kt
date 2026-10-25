package dev.phonepi.companion.wear

/**
 * Phone-side actions the watch can request. The watch never holds the PhonePi
 * token; this is what the companion runs after a Data Layer message arrives.
 *
 * Integration (cursor/phonepi-notif-actions): implement this interface with
 * that branch's inbound-prompt store and quiet helper, then point
 * [WearInboundActionsAdapter] at it. See the adapter's file-level comment.
 */
interface WearInboundActions {
    /** Forward [text] to the box as an inbound assistant prompt. */
    suspend fun submitPrompt(text: String, source: String): WearActionResult

    /** Quiet notifications on the phone for [minutes] (1..720). */
    suspend fun quietForMinutes(minutes: Int): WearActionResult
}

/**
 * Presence label shown on the watch complication. Stub until
 * cursor/phonepi-presence lands. Do not call Health Connect or usage APIs here.
 */
fun interface PresenceLabelSource {
    fun currentLabel(): String?

    companion object {
        /** INTEGRATION: replace with the presence module when that branch merges. */
        val Stub: PresenceLabelSource = PresenceLabelSource { null }
    }
}
