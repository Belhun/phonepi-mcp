package dev.phonepi.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

data class WearSendResult(val ok: Boolean, val message: String)

/**
 * Talks to the phone companion only. Never reads a PhonePi token, env secret,
 * or SharedPreferences from the companion's connection store. Does not fall
 * back to any connected node that lacks [WearPaths.PHONE_CAPABILITY].
 */
object WearPhoneClient {
    private const val TAG = "PhonePiWear"

    suspend fun send(context: Context, path: String, payload: ByteArray): WearSendResult {
        val app = context.applicationContext
        val node = findPhone(app)
            ?: return WearSendResult(false, "Phone not paired. Open PhonePi on the phone.")
        return try {
            Wearable.getMessageClient(app).sendMessage(node.id, path, payload).await()
            WearSendResult(true, "Sent")
        } catch (e: Exception) {
            Log.i(TAG, "send failed path=$path: ${e.javaClass.simpleName}")
            WearSendResult(false, "Could not reach the phone")
        }
    }

    private suspend fun findPhone(context: Context): Node? {
        val cap = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
        }.getOrNull()
        val fromCap = cap?.nodes.orEmpty()
        return fromCap.maxByOrNull { if (it.isNearby) 1 else 0 }
    }
}
