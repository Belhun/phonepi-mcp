package dev.phonepi.companion.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val provider: String,
    val ageMs: Long,
) {
    val mapsUrl: String
        get() = "https://maps.google.com/?q=$latitude,$longitude"
}

object LocationReader {
  private const val FRESH_MS = 120_000L
  private const val TIMEOUT_MS = 8_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun getCurrent(context: Context): LocationSnapshot? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }.filter { provider ->
            try {
                lm.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }
        if (providers.isEmpty()) return null

        val now = System.currentTimeMillis()
        val cached = providers.mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.takeIf { now - it.time < FRESH_MS }
        if (cached != null) return toSnapshot(cached, now)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in providers) {
                val fresh = requestCurrentLocationApi30(context, lm, provider)
                if (fresh != null) return toSnapshot(fresh, now)
            }
        }

        return withTimeoutOrNull(TIMEOUT_MS) {
            waitForLocationUpdate(lm, providers.first())
        }?.let { toSnapshot(it, now) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocationApi30(
        context: Context,
        lm: LocationManager,
        provider: String,
    ): Location? =
        suspendCancellableCoroutine { cont ->
            lm.getCurrentLocation(
                provider,
                null,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                cont.resume(location)
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun waitForLocationUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }

    private fun toSnapshot(location: Location, now: Long) = LocationSnapshot(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = location.accuracy,
        provider = location.provider ?: "unknown",
        ageMs = now - location.time,
    )
}
