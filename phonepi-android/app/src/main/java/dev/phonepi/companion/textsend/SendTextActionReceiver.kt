package dev.phonepi.companion.textsend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.companion.ui.MainActivity

class SendTextActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        when (intent.action) {
            ACTION_SEND -> SendTextController.confirmAsync(app, id, "tap") { pending.finish() }
            ACTION_CANCEL -> {
                SendTextController.cancel(app, id)
                pending.finish()
            }
            ACTION_OPEN -> {
                val open = Intent(app, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(open)
                pending.finish()
            }
            else -> pending.finish()
        }
    }

    companion object {
        const val ACTION_SEND = "dev.phonepi.companion.action.SEND_TEXT_SEND"
        const val ACTION_CANCEL = "dev.phonepi.companion.action.SEND_TEXT_CANCEL"
        const val ACTION_OPEN = "dev.phonepi.companion.action.SEND_TEXT_OPEN"
        const val EXTRA_ID = "id"
    }
}
