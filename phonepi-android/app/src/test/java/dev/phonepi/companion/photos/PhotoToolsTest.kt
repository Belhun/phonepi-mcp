package dev.phonepi.companion.photos

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PhotoToolsTest {
    @Test
    fun listsMetadataWithoutBytesAndCapsAtTwenty() = runBlocking {
        val library = FakeLibrary((1L..25L).map { photo(it, it * 1_000L) })
        val tool = GetRecentPhotosTool(openGate(), library)
        val page = tool.handle(buildJsonObject { })
        assertEquals("success", page.status())
        assertEquals("5", page.field("count"))
        assertEquals("5", page.field("limit"))
        assertFalse(page.containsKey("base64"))
        assertFalse(page.containsKey("limitCapped"))
        val ids = page.photoIds()
        assertEquals(listOf("25", "24", "23", "22", "21"), ids)
        assertNull(page["photos"]!!.jsonArrayFirst().get("base64"))

        val capped = tool.handle(buildJsonObject { put("limit", 100) })
        assertEquals("20", capped.field("count"))
        assertEquals("true", capped.field("limitCapped"))
        assertEquals(2, library.scans)
    }

    @Test
    fun sinceFiltersAndABadLimitDoesNotScan() = runBlocking {
        val library = FakeLibrary(listOf(photo(1, 1_000L), photo(2, 9_000L)))
        val tool = GetRecentPhotosTool(openGate(), library)
        val filtered = tool.handle(buildJsonObject { put("since", 5_000L); put("limit", 5) })
        assertEquals(listOf("2"), filtered.photoIds())

        val bad = tool.handle(buildJsonObject { put("limit", 0) })
        assertEquals("error", bad.status())
        assertTrue(bad.field("error")!!.contains("limit"))
        assertEquals(1, library.scans)
    }

    @Test
    fun permissionDeniedSkipsTheLibrary() = runBlocking {
        val library = FakeLibrary(listOf(photo(1, 1_000L)))
        val sharingOff = PhotoGate {
            denialFor(sharingEnabled = false, osGranted = true, permission = "READ_MEDIA_IMAGES")
        }
        val listed = GetRecentPhotosTool(sharingOff, library).handle(buildJsonObject { })
        assertEquals("error", listed.status())
        assertEquals("photo_sharing", listed.field("grantNeeded"))
        assertEquals(0, library.scans)

        val needsGrant = PhotoGate {
            denialFor(sharingEnabled = true, osGranted = false, permission = "READ_MEDIA_IMAGES")
        }
        val photo = GetPhotoTool(needsGrant, library).handle(buildJsonObject { put("id", "1") })
        assertEquals("error", photo.status())
        assertEquals("READ_MEDIA_IMAGES", photo.field("grantNeeded"))
        assertTrue(photo.field("error")!!.contains("permission not granted"))
        assertEquals(0, library.finds)
        assertEquals(0, library.encodes)
        assertFalse(photo.containsKey("base64"))
    }

    @Test
    fun securityExceptionBecomesGrantNeeded() = runBlocking {
        val library = FakeLibrary(emptyList(), explode = true)
        val result = GetRecentPhotosTool(openGate(), library).handle(buildJsonObject { })
        assertEquals("error", result.status())
        assertEquals("READ_MEDIA_IMAGES", result.field("grantNeeded"))
    }

    @Test
    fun getPhotoRequiresAnIdAndARealRow() = runBlocking {
        val library = FakeLibrary(listOf(photo(7, 5_000L)))
        val tool = GetPhotoTool(openGate(), library)
        val missing = tool.handle(buildJsonObject { })
        assertEquals("id is required", missing.field("error"))
        val bad = tool.handle(buildJsonObject { put("id", "nope") })
        assertTrue(bad.field("error")!!.contains("MediaStore"))
        val absent = tool.handle(buildJsonObject { put("id", 99) })
        assertEquals("Photo not found", absent.field("error"))
        assertEquals(0, library.encodes)
    }

    @Test
    fun downscalesUntilTheJsonFitsAndOmitsExif() = runBlocking {
        val library = FakeLibrary(listOf(photo(7, 5_000L, width = 4000, height = 3000))) { attempt ->
            if (attempt.maxEdge >= 1600 && attempt.quality >= 80) 800_000 else 2_000
        }
        val result = GetPhotoTool(openGate(), library).handle(buildJsonObject { put("id", "7") })
        assertEquals("success", result.status())
        assertEquals("7", result.field("id"))
        assertEquals("IMG_7.jpg", result.field("displayName"))
        assertEquals("false", result.field("exifIncluded"))
        assertTrue(result.field("exifNote")!!.contains("GPS"))
        assertFalse(result.containsKey("gps"))
        assertFalse(result.containsKey("latitude"))
        val encoding = result["encoding"]!!.jsonObject
        assertEquals("1600", encoding.field("width"))
        assertEquals("1200", encoding.field("height"))
        assertEquals("70", encoding.field("quality"))
        assertEquals("2000", encoding.field("byteLength"))
        assertEquals("true", encoding.field("cappedToFit"))
        assertEquals("false", encoding.field("full"))
        assertEquals("image/jpeg", encoding.field("mimeType"))
        val raw = Base64.getDecoder().decode(result.field("base64"))
        assertEquals(2_000, raw.size)
        assertTrue(result.toString().encodeToByteArray().size <= PhotoLimits.MAX_JSON_UTF8_BYTES)
        assertEquals(2, library.encodes)
        assertEquals(1600, library.attempts[0].maxEdge)
        assertEquals(80, library.attempts[0].quality)
        assertEquals(70, library.attempts[1].quality)
    }

    @Test
    fun fullUsesTheOriginalEdgeWhenItAlreadyFits() = runBlocking {
        val library = FakeLibrary(listOf(photo(3, 9_000L, width = 800, height = 600))) { 100 }
        val result = GetPhotoTool(openGate(), library).handle(
            buildJsonObject {
                put("id", 3)
                put("maxDimension", "full")
                put("format", "webp")
            },
        )
        assertEquals("success", result.status())
        val encoding = result["encoding"]!!.jsonObject
        assertEquals("800", encoding.field("width"))
        assertEquals("600", encoding.field("height"))
        assertEquals("800", encoding.field("maxDimension"))
        assertEquals("true", encoding.field("full"))
        assertEquals("false", encoding.field("cappedToFit"))
        assertEquals("image/webp", encoding.field("mimeType"))
        assertEquals(1, library.encodes)
    }

    @Test
    fun oversizedPhotoReturnsAnErrorInsteadOfAHugeFrame() = runBlocking {
        val library = FakeLibrary(listOf(photo(4, 1_000L))) { 800_000 }
        val result = GetPhotoTool(openGate(), library).handle(
            buildJsonObject {
                put("id", "4")
                put("maxDimension", 64)
                put("quality", 40)
            },
        )
        assertEquals("error", result.status())
        assertTrue(result.field("error")!!.contains("1 MB"))
        assertFalse(result.containsKey("base64"))
        assertEquals(1, library.encodes)
    }

    @Test
    fun rejectsBadDimensionQualityAndFormatBeforeEncoding() = runBlocking {
        val library = FakeLibrary(listOf(photo(4, 1_000L)))
        val tool = GetPhotoTool(openGate(), library)
        assertTrue(tool.handle(buildJsonObject { put("id", 4); put("maxDimension", "huge") }).field("error")!!.contains("maxDimension"))
        assertTrue(tool.handle(buildJsonObject { put("id", 4); put("quality", 10) }).field("error")!!.contains("quality"))
        assertTrue(tool.handle(buildJsonObject { put("id", 4); put("format", "png") }).field("error")!!.contains("format"))
        assertEquals(0, library.encodes)
    }

    private fun openGate() = PhotoGate { null }

    private fun photo(
        id: Long,
        taken: Long,
        width: Int = 4000,
        height: Int = 3000,
    ) = PhotoRecord(
        id = id,
        displayName = "IMG_$id.jpg",
        dateTakenMs = taken,
        dateAddedMs = taken,
        sizeBytes = 10_000L,
        width = width,
        height = height,
        mimeType = "image/jpeg",
    )

    private fun JsonObject.status(): String? = field("status")

    private fun JsonObject.field(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.photoIds(): List<String> {
        val photos = this["photos"] as kotlinx.serialization.json.JsonArray
        return photos.map { it.jsonObject.field("id")!! }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArrayFirst(): JsonObject =
        (this as kotlinx.serialization.json.JsonArray).first().jsonObject
}

private class FakeLibrary(
    private val photos: List<PhotoRecord>,
    private val explode: Boolean = false,
    private val encodeSize: (EncodeAttempt) -> Int = { 32 },
) : PhotoLibrary {
    var scans = 0
    var finds = 0
    var encodes = 0
    val attempts = mutableListOf<EncodeAttempt>()

    override fun scan(scanCap: Int, sinceMs: Long?): List<PhotoRecord> {
        scans += 1
        if (explode) throw SecurityException("denied")
        return photos.take(scanCap)
    }

    override fun find(id: Long): PhotoRecord? {
        finds += 1
        if (explode) throw SecurityException("denied")
        return photos.find { it.id == id }
    }

    override fun encode(id: Long, attempt: EncodeAttempt): EncodedPhoto? {
        encodes += 1
        attempts.add(attempt)
        val record = photos.find { it.id == id } ?: return null
        val (w, h) = scaledSize(record.width, record.height, attempt.maxEdge)
        val n = encodeSize(attempt)
        return EncodedPhoto(
            bytes = ByteArray(n) { index -> (index % 251).toByte() },
            width = w,
            height = h,
            mimeType = attempt.format.mimeType(),
        )
    }
}
