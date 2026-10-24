package dev.phonepi.companion.photos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * OS photo grant plus an in-app switch. The switch defaults off.
 * Turning it off does not revoke the system permission; the tools refuse anyway.
 */
object PhotoAccess {
    private const val PREFS = "phonepi_photos"
    private const val KEY_ENABLED = "share_recent"

    fun sharingEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setSharingEnabled(context: Context, on: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, on)
            .apply()
    }

    /** Permission to request in the system dialog. */
    fun permissionToRequest(): String =
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasOsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && granted(context, Manifest.permission.READ_MEDIA_IMAGES)) {
            return true
        }
        // Android 14 "Select photos" grants this without READ_MEDIA_IMAGES.
        if (Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT <= 32 && granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            return true
        }
        return false
    }

    fun denial(context: Context): PhotoDenial? = denialFor(
        sharingEnabled = sharingEnabled(context),
        osGranted = hasOsPermission(context),
        permission = permissionToRequest(),
    )

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
