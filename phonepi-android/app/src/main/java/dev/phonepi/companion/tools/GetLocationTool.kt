package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.location.LocationReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class GetLocationTool(private val context: Context) : ToolHandler {
    override val name = "get_location"

    override suspend fun handle(params: JsonObject): JsonObject {
        if (!LocationReader.hasPermission(context)) {
            return toolError(
                "Location permission denied — grant ACCESS_FINE_LOCATION in Android settings",
            )
        }
        val location = LocationReader.getCurrent(context)
            ?: return toolError("Could not get a location fix — enable GPS or try outdoors")
        return success {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracyMeters", location.accuracyMeters)
            put("provider", location.provider)
            put("ageMs", location.ageMs)
            put("mapsUrl", location.mapsUrl)
        }
    }
}
