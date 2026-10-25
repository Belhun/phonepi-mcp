package dev.phonepi.companion.privacy

object QuietHoldPolicy {
    const val CAP = 20

    fun droppedCount(sizeBeforeCap: Int, cap: Int = CAP): Int =
        (sizeBeforeCap - cap).coerceAtLeast(0)

    fun summaryText(held: Int, overflow: Int): String {
        val main = "$held cards were held"
        return if (overflow > 0) "$main · $overflow more held" else main
    }
}
