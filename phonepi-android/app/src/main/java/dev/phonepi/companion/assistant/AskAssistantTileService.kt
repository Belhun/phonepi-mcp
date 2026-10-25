package dev.phonepi.companion.assistant

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: Ask assistant. Opens the prompt; does not change settings. */
class AskAssistantTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = getString(dev.phonepi.companion.R.string.ask_assistant_short)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = AskAssistantActivity.intent(this, "qs_tile")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
            return
        }
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
