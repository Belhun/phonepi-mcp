package dev.phonepi.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.update.UpdatePromptStore

/**
 * Trampoline from the update full-screen notification. Starts the system
 * installer confirmation intent and finishes. Never installs itself.
 */
class InstallBridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val confirm = UpdatePromptStore.confirmIntent
        if (confirm == null) {
            ActivityLog.logUpdate("Installer prompt expired — check for update in PhonePi", "error")
            finish()
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(confirm)
            ActivityLog.logUpdate("Opened system installer", "success")
        } catch (e: Exception) {
            ActivityLog.logUpdate("Installer did not start: ${e.message}", "error")
        }
        finish()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, InstallBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
