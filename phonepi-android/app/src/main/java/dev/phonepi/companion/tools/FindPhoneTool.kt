package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.findphone.FindPhoneService
import dev.phonepi.companion.findphone.FindPhoneSession
import dev.phonepi.companion.location.LocationReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class FindPhoneTool(private val context: Context) : ToolHandler {
    override val name = "find_phone"

    override suspend fun handle(params: JsonObject): JsonObject {
        val loop = params.optionalBool("loop", default = true)
        val showDismissUi = params.optionalBool("showDismissUI", default = true)
        val includeLocation = params.optionalBool("includeLocation", default = true)
        val sessionId = "find-${System.currentTimeMillis()}"

        if (FindPhoneSession.activeSessionId != null) {
            FindPhoneService.stop(context, FindPhoneSession.activeSessionId)
        }

        val location = if (includeLocation) LocationReader.getCurrent(context) else null
        FindPhoneService.start(context, sessionId, loop, showDismissUi)

        return success {
            put("message", "Find phone alert started")
            put("looping", loop)
            put("sessionId", sessionId)
            put("showDismissUI", showDismissUi)
            if (location != null) {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracyMeters", location.accuracyMeters)
                put("locationProvider", location.provider)
                put("locationAgeMs", location.ageMs)
                put("mapsUrl", location.mapsUrl)
            } else if (includeLocation) {
                put(
                    "locationNote",
                    "Location unavailable — grant ACCESS_FINE_LOCATION in system settings",
                )
            }
        }
    }
}
