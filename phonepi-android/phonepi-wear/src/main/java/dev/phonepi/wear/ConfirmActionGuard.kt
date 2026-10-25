package dev.phonepi.wear

/**
 * Tile LaunchAction extras are untrusted. The confirm tap is the only way
 * Quiet / I'm OK is sent. Caller extras that look like skip/auto-send are ignored.
 */
object ConfirmActionGuard {
    val SKIP_EXTRA_KEYS = setOf(
        "skipConfirm",
        "skip_confirm",
        "auto",
        "send",
        "confirmed",
        "immediate",
        "autoConfirm",
        "auto_confirm",
        "bypass",
    )

    fun kindOf(kind: String?): String = kind.orEmpty()

    fun isKnownKind(kind: String): Boolean =
        kind == ConfirmActionActivity.KIND_QUIET || kind == ConfirmActionActivity.KIND_IM_OK

    fun ignoresSkipExtras(): Boolean = true
}
