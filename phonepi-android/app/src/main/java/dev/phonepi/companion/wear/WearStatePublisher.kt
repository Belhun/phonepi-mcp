package dev.phonepi.companion.wear

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Pushes next-alarm + presence (stub) to the watch via DataClient.
 * Never writes a token into the DataMap.
 */
object WearStatePublisher {
    private const val TAG = "PhonePiWear"

    fun publish(context: Context, presence: PresenceLabelSource = WearBridge.presence) {
        val app = context.applicationContext
        val next = app.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        val request = PutDataMapRequest.create(WearPaths.STATE)
        request.dataMap.putLong("updatedAt", System.currentTimeMillis())
        request.dataMap.putLong("nextAlarmEpochMs", next?.triggerTime ?: -1L)
        request.dataMap.putString("presenceLabel", presence.currentLabel().orEmpty())
        request.setUrgent()
        try {
            Wearable.getDataClient(app).putDataItem(request.asPutDataRequest())
        } catch (e: Exception) {
            Log.i(TAG, "state publish skipped: ${e.javaClass.simpleName}")
        }
    }
}
