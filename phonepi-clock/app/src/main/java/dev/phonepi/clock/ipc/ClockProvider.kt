package dev.phonepi.clock.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dev.phonepi.clock.ClockEngine

/**
 * Same-signature IPC for the PhonePi companion.
 * Authority dev.phonepi.clock.provider. Caller must hold
 * dev.phonepi.clock.permission.CONTROL (signature).
 * call(method, extras["payload"] = JSON) -> extras["payload"] = JSON.
 */
class ClockProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val payload = extras?.getString(PAYLOAD) ?: "{}"
        val result = ClockEngine.handle(context!!, method, payload)
        return Bundle().apply { putString(PAYLOAD, result) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "dev.phonepi.clock.provider"
        const val PAYLOAD = "payload"
        val URI: Uri = Uri.parse("content://$AUTHORITY")
    }
}
