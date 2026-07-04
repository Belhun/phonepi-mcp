package dev.phonepi.companion.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

enum class PermissionTier(
    val label: String,
    val rationale: String,
    val permissions: List<String>,
) {
    // MESSAGING tier removed — carrier SMS tools disabled; use Google Messages gm_* on desktop.
    TELEPHONY(
        label = "Phone calls",
        rationale = "Needed to place calls and read recent call history from tools like make_call and get_recent_calls.",
        permissions = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
        ),
    ),
    CONTACTS(
        label = "Contacts",
        rationale = "Needed to search and edit contacts.",
        permissions = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        ),
    ),
    LOCATION(
        label = "Location",
        rationale = "Needed for get_location.",
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
    ),
}

fun PermissionTier.missingPermissions(context: Context): List<String> =
    permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

fun PermissionTier.isGranted(context: Context): Boolean =
    missingPermissions(context).isEmpty()
