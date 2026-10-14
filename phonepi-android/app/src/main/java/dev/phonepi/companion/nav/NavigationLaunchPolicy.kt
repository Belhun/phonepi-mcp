package dev.phonepi.companion.nav

/**
 * Decide whether a [android.content.Context.startActivity] return can be
 * believed. Android 10+ can swallow a background activity start without
 * throwing, which is what Pixel 9 showed as started:true with no Maps UI.
 *
 * SYSTEM_ALERT_WINDOW ("Display over other apps") is a documented BAL
 * exemption. The app being in the foreground is the other trusted case.
 * Otherwise always post the full-screen notification and never report
 * started:true from a background startActivity.
 */
data class NavigationLaunchDecision(
    val trustActivityStart: Boolean,
    val postFullScreenNotification: Boolean,
    val methodIfTrustedStart: String,
)

object NavigationLaunchPolicy {
    const val METHOD_ACTIVITY = "activity"
    const val METHOD_OVERLAY = "overlay"
    const val METHOD_FULL_SCREEN_NOTIFICATION = "full_screen_notification"
    const val METHOD_NONE = "none"

    fun decide(overlayGranted: Boolean, inForeground: Boolean): NavigationLaunchDecision {
        val trust = overlayGranted || inForeground
        return NavigationLaunchDecision(
            trustActivityStart = trust,
            postFullScreenNotification = !trust,
            methodIfTrustedStart = when {
                inForeground -> METHOD_ACTIVITY
                overlayGranted -> METHOD_OVERLAY
                else -> METHOD_FULL_SCREEN_NOTIFICATION
            },
        )
    }

    fun method(
        trustedStartSucceeded: Boolean,
        notificationPosted: Boolean,
        overlayGranted: Boolean,
        inForeground: Boolean,
    ): String = when {
        trustedStartSucceeded && inForeground -> METHOD_ACTIVITY
        trustedStartSucceeded && overlayGranted -> METHOD_OVERLAY
        notificationPosted -> METHOD_FULL_SCREEN_NOTIFICATION
        else -> METHOD_NONE
    }

    fun grantNeeded(
        overlayGranted: Boolean,
        fullScreenIntentOk: Boolean,
        usedNotification: Boolean,
    ): String? {
        val missing = mutableListOf<String>()
        if (!overlayGranted) {
            missing.add("Display over other apps (Settings → Apps → PhonePi → Appear on top)")
        }
        if (usedNotification && !fullScreenIntentOk) {
            missing.add("full-screen intents (Settings → Apps → PhonePi → Full screen notifications)")
        }
        return missing.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}
