package dev.phonepi.companion.nav

data class SavedLocationDraft(
    val name: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val mapsUrl: String?,
)

data class SavedLocationRecord(
    val nameKey: String,
    val name: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val mapsUrl: String?,
)

sealed class LocationValidation {
    data class Ok(val record: SavedLocationRecord) : LocationValidation()
    data class Err(val message: String) : LocationValidation()
}

object SavedLocationRules {
    const val MAX_NAME = 64
    const val MAX_ADDRESS = 500
    const val MAX_URL = 2000

    fun nameKey(name: String): String = name.trim().lowercase()

    fun validate(draft: SavedLocationDraft): LocationValidation {
        val name = draft.name.trim()
        if (name.isEmpty()) return LocationValidation.Err("name is required")
        if (name.length > MAX_NAME) return LocationValidation.Err("name is too long")
        if (name.any { it.isISOControl() }) return LocationValidation.Err("name has invalid characters")

        val address = draft.address?.trim()?.takeIf { it.isNotEmpty() }
        if (address != null && address.length > MAX_ADDRESS) {
            return LocationValidation.Err("address is too long")
        }

        val lat = draft.latitude
        val lng = draft.longitude
        if ((lat == null) xor (lng == null)) {
            return LocationValidation.Err("latitude and longitude must be provided together")
        }
        if (lat != null && lng != null) {
            if (lat !in -90.0..90.0) return LocationValidation.Err("latitude is out of range")
            if (lng !in -180.0..180.0) return LocationValidation.Err("longitude is out of range")
        }

        val mapsUrl = draft.mapsUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (mapsUrl != null) {
            if (mapsUrl.length > MAX_URL) return LocationValidation.Err("mapsUrl is too long")
            if (!MapsLink.isAllowed(mapsUrl)) {
                return LocationValidation.Err("mapsUrl must be a Google Maps link")
            }
        }

        if (address == null && lat == null && mapsUrl == null) {
            return LocationValidation.Err("Provide an address, lat/lng, or a Maps URL")
        }

        return LocationValidation.Ok(
            SavedLocationRecord(
                nameKey = nameKey(name),
                name = name,
                address = address,
                latitude = lat,
                longitude = lng,
                mapsUrl = mapsUrl,
            ),
        )
    }

    fun navigationQuery(record: SavedLocationRecord): String? {
        if (record.latitude != null && record.longitude != null) {
            return "${record.latitude},${record.longitude}"
        }
        if (!record.address.isNullOrBlank()) return record.address
        val url = record.mapsUrl ?: return null
        return NavigationQuery.fromMapsUrl(url)?.query
    }
}
