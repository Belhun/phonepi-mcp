package dev.phonepi.companion.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class VolumeParseTest {
    @Test
    fun streamAliases() {
        assertEquals(VolumeStream.VOICE, VolumeStream.parse("call"))
        assertEquals(VolumeStream.VOICE, VolumeStream.parse("VOICE"))
        assertEquals(VolumeStream.MEDIA, VolumeStream.parse("music"))
        assertEquals(VolumeStream.ALARM, VolumeStream.parse("alarm"))
        assertNull(VolumeStream.parse("bluetooth"))
        assertNull(VolumeStream.parse(""))
    }

    @Test
    fun levelMaxOrInt() {
        assertEquals(VolumeLevelRequest.Max, VolumeParse.level("max", null))
        assertEquals(VolumeLevelRequest.Max, VolumeParse.level("MAX", 3))
        assertEquals(VolumeLevelRequest.Absolute(7), VolumeParse.level("7", null))
        assertEquals(VolumeLevelRequest.Absolute(4), VolumeParse.level(null, 4))
        assertNull(VolumeParse.level(null, null))
        assertNull(VolumeParse.level("-1", null))
    }

    @Test
    fun dndAndRingerNames() {
        assertEquals("off", VolumeParse.dndName(1))
        assertEquals("priority", VolumeParse.dndName(2))
        assertEquals("total_silence", VolumeParse.dndName(3))
        assertEquals("alarms", VolumeParse.dndName(4))
        assertFalse(VolumeParse.dndIsOn(1))
        assertTrue(VolumeParse.dndIsOn(2))
        assertEquals("silent", VolumeParse.ringerModeName(0))
        assertEquals("vibrate", VolumeParse.ringerModeName(1))
        assertEquals("normal", VolumeParse.ringerModeName(2))
    }

    @Test
    fun dndPolicyOnlyForRingAndNotification() {
        assertEquals(
            listOf(VolumeStream.RING, VolumeStream.NOTIFICATION),
            VolumeParse.streamsNeedingDndPolicy(maxAll = true, stream = null),
        )
        assertEquals(
            listOf(VolumeStream.RING),
            VolumeParse.streamsNeedingDndPolicy(maxAll = false, stream = VolumeStream.RING),
        )
        assertTrue(
            VolumeParse.streamsNeedingDndPolicy(maxAll = false, stream = VolumeStream.ALARM).isEmpty(),
        )
    }

    @Test
    fun nextMidnightIsLocalStartOfNextDay() {
        val zone = ZoneId.of("America/Los_Angeles")
        val afternoon = ZonedDateTime.of(2026, 10, 14, 15, 30, 0, 0, zone)
            .toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2026, 10, 15, 0, 0, 0, 0, zone)
            .toInstant().toEpochMilli()
        assertEquals(expected, MidnightVolume.nextMidnightEpochMillis(afternoon, zone))
        val exactlyMidnight = ZonedDateTime.of(2026, 10, 15, 0, 0, 0, 0, zone)
            .toInstant().toEpochMilli()
        val following = ZonedDateTime.of(2026, 10, 16, 0, 0, 0, 0, zone)
            .toInstant().toEpochMilli()
        assertEquals(following, MidnightVolume.nextMidnightEpochMillis(exactlyMidnight, zone))
    }
}
