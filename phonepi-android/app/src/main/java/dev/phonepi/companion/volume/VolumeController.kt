package dev.phonepi.companion.volume

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import dev.phonepi.companion.log.ActivityLog

data class StreamLevel(val current: Int, val max: Int)

data class VolumeSnapshot(
    val streams: Map<VolumeStream, StreamLevel>,
    val ringerMode: String,
    val ringerModeRaw: Int,
    val dnd: String,
    val dndFilter: Int,
    val notificationPolicyAccess: Boolean,
    val exactAlarmsAllowed: Boolean,
    val midnightMaxEnabled: Boolean,
)

data class VolumeSetResult(
    val changed: List<String>,
    val skipped: List<String>,
    val grantNeeded: String? = null,
    val error: String? = null,
)

object VolumeController {
    fun snapshot(context: Context): VolumeSnapshot {
        val audio = audio(context)
        val dndFilter = interruptionFilter(context)
        val streams = VolumeStream.entries.associateWith { stream ->
            StreamLevel(
                current = audio.getStreamVolume(stream.audioStream),
                max = audio.getStreamMaxVolume(stream.audioStream),
            )
        }
        return VolumeSnapshot(
            streams = streams,
            ringerMode = VolumeParse.ringerModeName(audio.ringerMode),
            ringerModeRaw = audio.ringerMode,
            dnd = VolumeParse.dndName(dndFilter),
            dndFilter = dndFilter,
            notificationPolicyAccess = hasNotificationPolicyAccess(context),
            exactAlarmsAllowed = MidnightVolume.exactAlarmsAllowed(context),
            midnightMaxEnabled = MidnightVolume.enabled(context),
        )
    }

    fun setStream(
        context: Context,
        stream: VolumeStream,
        level: VolumeLevelRequest,
    ): VolumeSetResult = apply(context, listOf(stream), level, maxAll = false)

    fun maxAll(context: Context): VolumeSetResult =
        apply(context, VolumeStream.entries.toList(), VolumeLevelRequest.Max, maxAll = true)

    fun hasNotificationPolicyAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun notificationPolicySettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    fun interruptionFilter(context: Context): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter
    }

    private fun apply(
        context: Context,
        streams: List<VolumeStream>,
        level: VolumeLevelRequest,
        maxAll: Boolean,
    ): VolumeSetResult {
        val audio = audio(context)
        val dndOn = VolumeParse.dndIsOn(interruptionFilter(context))
        val policyOk = hasNotificationPolicyAccess(context)
        val needsPolicy = dndOn && streams.any { it.needsDndPolicy } && !policyOk
        val grant = if (needsPolicy) {
            "Do Not Disturb access (Settings → Apps → Special app access → Do Not Disturb). " +
                "Needed to change ring/notification volume while DND is on. Alarm volume still works."
        } else {
            null
        }
        if (maxAll) {
            try {
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (_: SecurityException) {
                // DND without policy access can refuse this.
            }
        }
        val changed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (stream in streams) {
            if (stream.needsDndPolicy && dndOn && !policyOk) {
                skipped.add(stream.key)
                continue
            }
            val max = audio.getStreamMaxVolume(stream.audioStream)
            val target = when (level) {
                VolumeLevelRequest.Max -> max
                is VolumeLevelRequest.Absolute -> level.value.coerceIn(0, max)
            }
            try {
                audio.setStreamVolume(stream.audioStream, target, 0)
                changed.add("${stream.key}=$target/$max")
            } catch (e: SecurityException) {
                skipped.add(stream.key)
            }
        }
        val error = when {
            changed.isEmpty() && grant != null ->
                "DND is on. Grant Do Not Disturb access to change ring/notification volume."
            changed.isEmpty() && skipped.isNotEmpty() ->
                "Could not change ${skipped.joinToString(", ")}"
            else -> null
        }
        ActivityLog.logConnection(
            "Volume",
            buildString {
                append(if (maxAll) "max_all" else streams.joinToString { it.key })
                if (changed.isNotEmpty()) append(" set ${changed.joinToString()}")
                if (skipped.isNotEmpty()) append(" skipped ${skipped.joinToString()}")
            },
            if (error == null) "success" else "error",
        )
        return VolumeSetResult(
            changed = changed,
            skipped = skipped,
            grantNeeded = grant,
            error = error,
        )
    }

    private fun audio(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
