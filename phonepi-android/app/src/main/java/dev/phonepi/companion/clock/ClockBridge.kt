package dev.phonepi.companion.clock

import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

/**
 * Signature-permission call into PhonePi Clock.
 * Both apps must be signed by the same cert or Android denies CONTROL.
 */
object ClockBridge {
    const val AUTHORITY = "dev.phonepi.clock.provider"
    private const val PAYLOAD = "payload"
    private val URI: Uri = Uri.parse("content://$AUTHORITY")

    fun call(context: Context, method: String, payload: String): String? {
        val extras = Bundle().apply { putString(PAYLOAD, payload) }
        val result = try {
            context.contentResolver.call(URI, method, null, extras)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return result?.getString(PAYLOAD)
    }

    fun snapshotFrom(payload: String?): JSONObject? {
        if (payload.isNullOrBlank()) return null
        return try {
            JSONObject(payload).optJSONObject("snapshot")
        } catch (_: Exception) {
            null
        }
    }
}
