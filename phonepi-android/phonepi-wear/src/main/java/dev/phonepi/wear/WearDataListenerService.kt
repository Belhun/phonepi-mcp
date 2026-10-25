package dev.phonepi.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.phonepi.wear.complication.NextAlarmComplicationService

class WearDataListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearPaths.ACK) return
        val payload = String(messageEvent.data, Charsets.UTF_8)
        PresenceCache.lastAck = WearProtocol.decodeAck(payload)
        Log.i(TAG, "ack action=${PresenceCache.lastAck?.action} ok=${PresenceCache.lastAck?.ok}")
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            for (event in buffer) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != WearPaths.STATE) continue
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val label = map.getString("presenceLabel")
                val alarm = map.getLong("nextAlarmEpochMs", -1L)
                PresenceCache.update(this, label, alarm)
                NextAlarmComplicationService.requestUpdate(this)
            }
        }
    }

    companion object {
        private const val TAG = "PhonePiWear"
    }
}
