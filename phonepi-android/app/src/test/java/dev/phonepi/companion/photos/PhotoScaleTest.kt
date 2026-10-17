package dev.phonepi.companion.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoScaleTest {
    @Test
    fun downscalesTheLongEdgeAndLeavesSmallImagesAlone() {
        assertEquals(1600 to 1200, scaledSize(4000, 3000, 1600))
        assertEquals(800 to 600, scaledSize(800, 600, 1600))
        assertEquals(1200 to 1600, scaledSize(3000, 4000, 1600))
    }

    @Test
    fun sampleSizeStaysAPowerOfTwo() {
        assertEquals(2, inSampleSize(4000, 3000, 1600))
        assertEquals(4, inSampleSize(8000, 6000, 1600))
        assertEquals(1, inSampleSize(800, 600, 1600))
    }

    @Test
    fun attemptsStartAtTheRequestedEdgeThenStepDown() {
        val attempts = encodeAttempts(4000, 3000, DimensionChoice.Default, 80, PhotoFormat.JPEG)
        assertEquals(1600, attempts.first().maxEdge)
        assertEquals(80, attempts.first().quality)
        assertEquals(PhotoFormat.JPEG, attempts.first().format)
        assertTrue(attempts.any { it.maxEdge == 1600 && it.quality == 70 })
        assertTrue(attempts.indexOfFirst { it.maxEdge < 1600 } > attempts.indexOfLast { it.maxEdge == 1600 })

        val full = encodeAttempts(800, 600, DimensionChoice.Full, 80, PhotoFormat.JPEG)
        assertEquals(800, full.first().maxEdge)

        val tiny = encodeAttempts(4000, 3000, DimensionChoice.Pixels(64), 40, PhotoFormat.WEBP)
        assertEquals(listOf(EncodeAttempt(64, 40, PhotoFormat.WEBP)), tiny)
    }

    @Test
    fun base64BudgetFitsWithoutPaddingPastTheCap() {
        assertEquals(6, maxRawBytesForBase64(8))
        assertEquals(0, maxRawBytesForBase64(3))
        val raw = maxRawBytesForBase64(900_000)
        val encoded = ((raw + 2) / 3) * 4
        assertTrue(encoded <= 900_000)
        val oneMore = ((raw + 1 + 2) / 3) * 4
        assertTrue(oneMore > 900_000)
    }
}
