package dev.phonepi.companion.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQueryTest {
    @Test
    fun listsNewestFirstAndCapsTheLimit() {
        val photos = (1L..25L).map { photo(it, taken = it * 1_000L) }.shuffled()
        val newest = selectRecent(photos, limit = 5, sinceMs = null)
        assertEquals(listOf(25L, 24L, 23L, 22L, 21L), newest.map { it.id })

        val capped = selectRecent(photos, limit = 100, sinceMs = null)
        assertEquals(20, capped.size)
        assertEquals(25L, capped.first().id)

        val decision = resolveLimit(100)
        assertEquals(20, decision.limit)
        assertTrue(decision.capped)
        assertNull(decision.error)

        val defaults = resolveLimit(null)
        assertEquals(5, defaults.limit)
        assertFalse(defaults.capped)
        assertEquals("limit must be at least 1", resolveLimit(0).error)
    }

    @Test
    fun sinceKeepsPhotosAtOrAfterTheCutoff() {
        val photos = listOf(
            photo(1, taken = 1_000L),
            photo(2, taken = 5_000L),
            photo(3, taken = 9_000L),
        )
        val selected = selectRecent(photos, limit = 5, sinceMs = 5_000L)
        assertEquals(listOf(3L, 2L), selected.map { it.id })
    }

    @Test
    fun fallsBackToDateAddedWhenDateTakenIsMissing() {
        val olderTaken = photo(1, taken = 1_000L, added = 50_000L)
        val addedOnly = PhotoRecord(
            id = 2,
            displayName = "shot.jpg",
            dateTakenMs = null,
            dateAddedMs = 80_000L,
            sizeBytes = 10,
            width = 10,
            height = 10,
            mimeType = "image/jpeg",
        )
        val selected = selectRecent(listOf(olderTaken, addedOnly), limit = 5, sinceMs = null)
        assertEquals(2L, selected.first().id)
    }

    @Test
    fun columnMappingNormalizesSecondsAndDropsNonImages() {
        val image = photoFromColumns(
            RawPhotoColumns(
                id = 9,
                displayName = "/sdcard/DCIM/\u0007IMG 9.jpg",
                dateTakenRaw = 0,
                dateAddedRaw = 1_700_000_000L,
                sizeBytes = 42,
                width = 100,
                height = 80,
                mimeType = "image/jpeg",
            ),
        )
        assertEquals("IMG 9.jpg", image!!.displayName)
        assertNull(image.dateTakenMs)
        assertEquals(1_700_000_000_000L, image.dateAddedMs)
        assertEquals(42L, image.sizeBytes)

        assertNull(
            photoFromColumns(
                RawPhotoColumns(4, "clip.mp4", 1, 1, 1, 1, 1, "video/mp4"),
            ),
        )
        assertEquals(1_700_000_000_000L, epochToMillis(1_700_000_000_000L))
    }

    @Test
    fun denialRequiresTheSwitchAndTheOsGrant() {
        val off = denialFor(sharingEnabled = false, osGranted = true, permission = "READ_MEDIA_IMAGES")
        assertEquals("photo_sharing", off!!.grantNeeded)
        assertTrue(off.error.contains("Share recent photos"))

        val needsOs = denialFor(
            sharingEnabled = true,
            osGranted = false,
            permission = "READ_EXTERNAL_STORAGE",
        )
        assertEquals("READ_EXTERNAL_STORAGE", needsOs!!.grantNeeded)
        assertTrue(needsOs.error.contains("permission not granted"))

        assertNull(denialFor(sharingEnabled = true, osGranted = true, permission = "READ_MEDIA_IMAGES"))
    }

    private fun photo(id: Long, taken: Long, added: Long = taken) = PhotoRecord(
        id = id,
        displayName = "IMG_$id.jpg",
        dateTakenMs = taken,
        dateAddedMs = added,
        sizeBytes = 1_000L + id,
        width = 4000,
        height = 3000,
        mimeType = "image/jpeg",
    )
}
