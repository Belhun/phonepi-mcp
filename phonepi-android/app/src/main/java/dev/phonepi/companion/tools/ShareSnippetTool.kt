package dev.phonepi.companion.tools

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.phonepi.companion.data.PhonePiDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class ShareSnippetTool(private val context: Context) : ToolHandler {
    private val dao = PhonePiDatabase.get(context).snippetDao()

    override val name = "share_snippet"

    override suspend fun handle(params: JsonObject): JsonObject {
        val id = params.requireInt("id")?.toLong()
            ?: return toolError("Missing required parameter: id")
        val snippet = dao.getById(id) ?: return toolError("Snippet not found: id=$id")
        val method = params.optionalString("method") ?: "default"
        val text = "${snippet.title}\n\n${snippet.content}"

        when (method) {
            "clipboard" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("phonepi", text))
            }
            "sms" -> {
                val recipient = params.requireString("recipient")
                    ?: return toolError("recipient is required for sms method")
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$recipient")).apply {
                    putExtra("sms_body", text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            else -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Share snippet").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }

        return success {
            put("id", id)
            put("method", method)
        }
    }
}
