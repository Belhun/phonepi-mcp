package dev.phonepi.companion.tools

import android.content.ClipData
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class ClipboardTool(private val context: Context) : ToolHandler {
    override val name = "copy_to_clipboard"

    override suspend fun handle(params: JsonObject): JsonObject {
        val text = params.requireString("text") ?: return toolError("Missing required parameter: text")
        return withContext(Dispatchers.Main) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("phonepi", text))
                success {
                    put("message", "Text copied to clipboard")
                    put("length", text.length)
                }
            } catch (e: Exception) {
                toolError(
                    e.message ?: "App not in foreground; clipboard write blocked (Android 10+)",
                )
            }
        }
    }
}
