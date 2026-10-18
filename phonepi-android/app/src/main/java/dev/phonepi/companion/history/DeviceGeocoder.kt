package dev.phonepi.companion.history

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale

data class PlaceLabel(val placeName: String?, val address: String?)

/**
 * Android's platform Geocoder. No third-party geocoding SDK and no paid API key.
 */
object DeviceGeocoder {
    fun reverse(context: Context, latitude: Double, longitude: Double): PlaceLabel? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
        val address = lookup(geocoder, latitude, longitude) ?: return null
        val line = address.getAddressLine(0)?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
        val place = placeName(address)?.take(120)
        if (place == null && line == null) return null
        return PlaceLabel(placeName = place, address = line)
    }

    @Suppress("DEPRECATION")
    private fun lookup(geocoder: Geocoder, latitude: Double, longitude: Double): Address? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            } else {
                geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun placeName(address: Address): String? {
        val premises = address.premises?.trim()?.takeIf { it.isNotEmpty() }
        if (premises != null && premises.any { it.isLetter() }) return premises
        val feature = address.featureName?.trim()?.takeIf { it.isNotEmpty() }
        val street = listOfNotNull(address.subThoroughfare, address.thoroughfare)
            .joinToString(" ")
            .trim()
        if (feature != null && feature != address.subThoroughfare && feature.any { it.isLetter() }) {
            if (street.isNotEmpty() && feature == address.thoroughfare) return null
            return feature
        }
        return null
    }
}
