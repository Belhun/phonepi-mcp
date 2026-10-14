package dev.phonepi.companion.tools

import android.content.Context
import dev.phonepi.companion.update.UpdateController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class PushUpdateTool(private val context: Context) : ToolHandler {
    override val name = "phone_push_update"

    override suspend fun handle(params: JsonObject): JsonObject {
        val force = params.optionalBool("force", default = true)
        val result = UpdateController.install(context.applicationContext, force)
        if (result.error != null && !result.promptShown) {
            return toolError(result.error)
        }
        return success {
            put("promptShown", result.promptShown)
            put("method", result.method)
            put("downloaded", result.downloaded)
            put("verified", result.verified)
            put("skipped", result.skipped)
            put("currentVersionCode", result.currentVersionCode)
            result.serverVersionCode?.let { put("serverVersionCode", it) }
            result.serverVersionName?.let { put("serverVersionName", it) }
            put("notificationPosted", result.notificationPosted)
            put("overlayGranted", result.overlayGranted)
            put("inForeground", result.inForeground)
            result.grantNeeded?.let { put("grantNeeded", result.grantNeeded) }
            put(
                "note",
                if (result.promptShown) {
                    "Alex still has to tap Install. PhonePi never installs silently."
                } else {
                    result.error ?: "Installer prompt was not shown."
                },
            )
        }
    }
}
