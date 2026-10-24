package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.volume.MidnightVolume
import dev.phonepi.companion.volume.VolumeController
import dev.phonepi.companion.volume.VolumeParse
import dev.phonepi.companion.volume.VolumeStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class GetVolumesTool(private val context: Context) : ToolHandler {
    override val name = "get_volumes"

    override suspend fun handle(@Suppress("UNUSED_PARAMETER") params: JsonObject): JsonObject {
        val snap = VolumeController.snapshot(context)
        return success {
            putJsonObject("streams") {
                snap.streams.forEach { (stream, level) ->
                    putJsonObject(stream.key) {
                        put("current", level.current)
                        put("max", level.max)
                    }
                }
            }
            put("ringerMode", snap.ringerMode)
            put("dnd", snap.dnd)
            put("dndFilter", snap.dndFilter)
            put("notificationPolicyAccess", snap.notificationPolicyAccess)
            put("exactAlarmsAllowed", snap.exactAlarmsAllowed)
            put("midnightMaxEnabled", snap.midnightMaxEnabled)
        }
    }
}

class SetVolumeTool(private val context: Context) : ToolHandler {
    override val name = "phone_set_volume"

    override suspend fun handle(params: JsonObject): JsonObject {
        val maxAll = params.optionalBool("max_all", default = false) ||
            params.optionalBool("maxAll", default = false)
        val result = if (maxAll) {
            VolumeController.maxAll(context)
        } else {
            val stream = VolumeStream.parse(params.requireString("stream"))
                ?: return toolError(
                    "stream must be alarm, ring, notification, media, voice (or call), or system",
                )
            val levelRaw = params.optionalString("level")?.trim()
            val level = VolumeParse.level(levelRaw, params.requireInt("level"))
                ?: return toolError("level must be an integer >= 0 or 'max'")
            VolumeController.setStream(context, stream, level)
        }
        if (result.error != null && result.changed.isEmpty()) {
            return toolError(result.error)
        }
        return success {
            put("maxAll", maxAll)
            put("changed", result.changed.joinToString(", "))
            put("skipped", result.skipped.joinToString(", "))
            result.grantNeeded?.let { put("grantNeeded", it) }
            val snap = VolumeController.snapshot(context)
            put("ringerMode", snap.ringerMode)
            put("dnd", snap.dnd)
            put("midnightMaxEnabled", MidnightVolume.enabled(context))
        }
    }
}
