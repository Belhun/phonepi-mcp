package dev.phonepi.companion.volume

/**
 * AudioManager stream ids (kept as ints so unit tests do not need the SDK).
 * STREAM_VOICE_CALL=0, SYSTEM=1, RING=2, MUSIC=3, ALARM=4, NOTIFICATION=5.
 */
enum class VolumeStream(
    val key: String,
    val audioStream: Int,
    val needsDndPolicy: Boolean,
) {
    ALARM("alarm", 4, false),
    RING("ring", 2, true),
    NOTIFICATION("notification", 5, true),
    MEDIA("media", 3, false),
    VOICE("voice", 0, false),
    SYSTEM("system", 1, false),
    ;

    companion object {
        fun parse(raw: String?): VolumeStream? {
            val key = raw?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return null
            return when (key) {
                "call", "voice", "voice_call" -> VOICE
                "music" -> MEDIA
                else -> entries.find { it.key == key }
            }
        }
    }
}

sealed class VolumeLevelRequest {
    data object Max : VolumeLevelRequest()
    data class Absolute(val value: Int) : VolumeLevelRequest()
}

object VolumeParse {
    fun level(raw: String?, intValue: Int?): VolumeLevelRequest? {
        val text = raw?.trim().orEmpty()
        if (text.equals("max", ignoreCase = true)) return VolumeLevelRequest.Max
        val n = intValue ?: text.toIntOrNull() ?: return null
        if (n < 0) return null
        return VolumeLevelRequest.Absolute(n)
    }

    fun ringerModeName(mode: Int): String = when (mode) {
        0 -> "silent"
        1 -> "vibrate"
        2 -> "normal"
        else -> "unknown"
    }

    fun dndName(filter: Int): String = when (filter) {
        1 -> "off"
        2 -> "priority"
        3 -> "total_silence"
        4 -> "alarms"
        else -> "unknown"
    }

    fun dndIsOn(filter: Int): Boolean = filter != 1

    fun streamsNeedingDndPolicy(maxAll: Boolean, stream: VolumeStream?): List<VolumeStream> {
        val targets = if (maxAll) VolumeStream.entries.toList() else listOfNotNull(stream)
        return targets.filter { it.needsDndPolicy }
    }
}
