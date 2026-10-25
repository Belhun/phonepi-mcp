package dev.phonepi.companion.wear

import android.content.Context
import dev.phonepi.companion.privacy.PhonePiQuiet

/**
 * Phone-side quiet used by the watch Quiet 1h action.
 * Holds PhonePi's own non-urgent notifications. Presence reports "quiet".
 * Does not change system Do Not Disturb.
 */
object WearQuietController {
    fun quietFor(context: Context, minutes: Int): WearActionResult =
        PhonePiQuiet.quietFor(context, minutes)

    fun restore(context: Context) {
        PhonePiQuiet.restore(context)
    }
}
