package dev.phonepi.companion.update

import android.content.Intent

/** Holds the system installer confirmation intent for the FSI trampoline. */
object UpdatePromptStore {
    @Volatile
    var confirmIntent: Intent? = null
}
