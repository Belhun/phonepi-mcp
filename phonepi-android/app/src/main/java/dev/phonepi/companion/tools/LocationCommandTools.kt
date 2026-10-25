package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.data.PhonePiDatabase
import dev.phonepi.companion.data.SavedLocationEntity
import dev.phonepi.companion.nav.MapsLink
import dev.phonepi.companion.nav.NavigationLauncher
import dev.phonepi.companion.nav.NavigationPlan
import dev.phonepi.companion.nav.NavigationQuery
import dev.phonepi.companion.nav.SavedLocationDraft
import dev.phonepi.companion.nav.SavedLocationRecord
import dev.phonepi.companion.nav.SavedLocationRules
import dev.phonepi.companion.presence.PresenceEngine
import dev.phonepi.companion.presence.PresencePrefs
import dev.phonepi.companion.nav.LocationValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ListLocationsTool(context: Context) : ToolHandler {
    private val app = context.applicationContext
    private val dao = PhonePiDatabase.get(context).savedLocationDao()
    override val name = "phone_list_locations"

    override suspend fun handle(params: JsonObject): JsonObject {
        val items = dao.getAll()
        val marks = PresencePrefs.marks(app)
        return success {
            put("count", items.size)
            put("locations", locationsArray(items, marks))
        }
    }
}

class AddLocationTool(context: Context) : ToolHandler {
    private val app = context.applicationContext
    private val dao = PhonePiDatabase.get(context).savedLocationDao()
    override val name = "phone_add_location"

    override suspend fun handle(params: JsonObject): JsonObject {
        val draft = SavedLocationDraft(
            name = params.requireString("name") ?: return toolError("name is required"),
            address = params.optionalString("address"),
            latitude = params.optionalDouble("latitude"),
            longitude = params.optionalDouble("longitude"),
            mapsUrl = params.optionalString("mapsUrl") ?: params.optionalString("maps_url"),
        )
        val validated = SavedLocationRules.validate(draft)
        if (validated is LocationValidation.Err) return toolError(validated.message)
        val record = (validated as LocationValidation.Ok).record
        val now = System.currentTimeMillis()
        val existing = dao.getByKey(record.nameKey)
        dao.upsert(
            SavedLocationEntity(
                nameKey = record.nameKey,
                name = record.name,
                address = record.address,
                latitude = record.latitude,
                longitude = record.longitude,
                mapsUrl = record.mapsUrl,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        val presence = params["presence"]?.jsonPrimitive?.booleanOrNull
        if (presence != null) PresencePrefs.setMarked(app, record.nameKey, presence)
        PresenceEngine.nudge(app)
        return success {
            put("location", record.toJson(PresencePrefs.isMarked(app, record.nameKey)))
            put("updated", existing != null)
        }
    }
}

class RemoveLocationTool(context: Context) : ToolHandler {
    private val app = context.applicationContext
    private val dao = PhonePiDatabase.get(context).savedLocationDao()
    override val name = "phone_remove_location"

    override suspend fun handle(params: JsonObject): JsonObject {
        val name = params.requireString("name") ?: return toolError("name is required")
        val key = SavedLocationRules.nameKey(name)
        val existing = dao.getByKey(key) ?: return toolError("No saved location named \"$name\"")
        dao.deleteByKey(key)
        PresencePrefs.setMarked(app, key, false)
        PresenceEngine.nudge(app)
        return success {
            put("removed", existing.name)
        }
    }
}

class NavigateTool(context: Context) : ToolHandler {
    private val appContext = context.applicationContext
    private val dao = PhonePiDatabase.get(context).savedLocationDao()
    override val name = "phone_navigate"

    override suspend fun handle(params: JsonObject): JsonObject {
        val plan = try {
            resolvePlan(params)
        } catch (e: IllegalArgumentException) {
            return toolError(e.message ?: "Invalid destination")
        } ?: return toolError(
            "Provide a saved name, address, lat/lng, or a Google Maps URL",
        )
        val result = withContext(Dispatchers.Main) {
            NavigationLauncher.launch(appContext, plan)
        }
        if (result.error != null && !result.started && !result.notificationPosted) {
            return toolError(result.error)
        }
        return success {
            put("query", plan.query)
            put("display", plan.display)
            put("uri", result.uri)
            put("started", result.started)
            put("notificationPosted", result.notificationPosted)
            put("method", result.method)
            put("overlayGranted", result.overlayGranted)
            put("inForeground", result.inForeground)
            plan.fromSavedName?.let { put("savedName", it) }
            plan.sourceUrl?.let { put("mapsUrl", it) }
            result.grantNeeded?.let { put("grantNeeded", it) }
            put(
                "note",
                when (result.method) {
                    "full_screen_notification" ->
                        "Maps was not started from the background (startActivity is not trusted without Display over other apps). " +
                            "Tap the PhonePi navigation notification, or grant Appear on top. " +
                            "Maps owns the Start now prompt on Android Auto."
                    else ->
                        "Maps owns the Start now prompt on Android Auto. PhonePi does not bypass it."
                },
            )
        }
    }

    private suspend fun resolvePlan(params: JsonObject): NavigationPlan? {
        val name = params.requireString("name")
        if (name != null) {
            val saved = dao.getByKey(SavedLocationRules.nameKey(name))
                ?: throw IllegalArgumentException("No saved location named \"$name\"")
            val query = SavedLocationRules.navigationQuery(saved.toRecord())
                ?: throw IllegalArgumentException("Saved location \"$name\" has no address, coordinates, or Maps URL")
            val fromUrl = saved.mapsUrl?.let { NavigationQuery.fromMapsUrl(it) }
            return NavigationPlan(
                query = query,
                display = saved.name,
                sourceUrl = fromUrl?.sourceUrl ?: saved.mapsUrl,
                fromSavedName = saved.name,
            )
        }
        val mapsUrl = params.requireString("mapsUrl") ?: params.requireString("maps_url")
        if (mapsUrl != null) {
            return NavigationQuery.fromMapsUrl(mapsUrl)
                ?: throw IllegalArgumentException("mapsUrl must be a Google Maps link")
        }
        val lat = params.optionalDouble("latitude")
        val lng = params.optionalDouble("longitude")
        if (lat != null && lng != null) {
            return NavigationQuery.fromCoordinates(lat, lng)
                ?: throw IllegalArgumentException("latitude and longitude are out of range")
        }
        val address = params.requireString("address") ?: params.requireString("query")
        if (address != null) {
            return if (MapsLink.isAllowed(address)) {
                NavigationQuery.fromMapsUrl(address)
            } else {
                NavigationQuery.fromAddress(address)
            }
        }
        return null
    }
}

private fun SavedLocationEntity.toRecord(): SavedLocationRecord =
    SavedLocationRecord(
        nameKey = nameKey,
        name = name,
        address = address,
        latitude = latitude,
        longitude = longitude,
        mapsUrl = mapsUrl,
    )

private fun SavedLocationRecord.toJson(presence: Boolean = false): JsonObject = buildJsonObject {
    put("name", name)
    address?.let { put("address", it) }
    latitude?.let { put("latitude", it) }
    longitude?.let { put("longitude", it) }
    mapsUrl?.let { put("mapsUrl", it) }
    put("presence", presence)
}

private fun SavedLocationEntity.toJson(presence: Boolean): JsonObject = toRecord().toJson(presence)

private fun locationsArray(items: List<SavedLocationEntity>, marks: Set<String>): JsonArray = buildJsonArray {
    items.forEach { add(it.toJson(it.nameKey in marks)) }
}
