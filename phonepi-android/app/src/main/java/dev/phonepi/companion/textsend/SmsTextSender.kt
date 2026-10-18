package dev.phonepi.companion.textsend

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

sealed class SmsSendOutcome {
    data object Sent : SmsSendOutcome()
    data class Failed(val error: String) : SmsSendOutcome()
    data object Unconfirmed : SmsSendOutcome()
}

object SmsSendWaiters {
    private class Parts(
        val expected: Int,
        val deferred: CompletableDeferred<SmsSendOutcome>,
    ) {
        var ok: Int = 0
    }

    private val pending = ConcurrentHashMap<String, Parts>()

    fun begin(id: String, parts: Int): CompletableDeferred<SmsSendOutcome> {
        val deferred = CompletableDeferred<SmsSendOutcome>()
        pending[id] = Parts(parts, deferred)
        return deferred
    }

    fun onPart(id: String, resultCode: Int) {
        val parts = pending[id] ?: return
        if (resultCode != android.app.Activity.RESULT_OK) {
            pending.remove(id)
            parts.deferred.complete(SmsSendOutcome.Failed(errorName(resultCode)))
            return
        }
        parts.ok += 1
        if (parts.ok >= parts.expected) {
            pending.remove(id)
            parts.deferred.complete(SmsSendOutcome.Sent)
        }
    }

    fun cancel(id: String) {
        pending.remove(id)
    }

    private fun errorName(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SMS generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "SMS no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "SMS null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "SMS radio off"
        else -> "SMS send failed ($code)"
    }
}

class SmsTextSender(private val context: Context) {
    suspend fun send(id: String, number: String, body: String): SmsSendOutcome {
        val sms = manager()
        val parts = sms.divideMessage(body)
        if (parts.isNullOrEmpty()) return SmsSendOutcome.Failed("Message was empty after split")
        val deferred = SmsSendWaiters.begin(id, parts.size)
        try {
            if (parts.size == 1) {
                sms.sendTextMessage(number, null, body, sentIntent(id, 0), null)
            } else {
                val intents = ArrayList<PendingIntent>(parts.size)
                parts.forEachIndexed { index, _ -> intents += sentIntent(id, index) }
                sms.sendMultipartTextMessage(number, null, parts, intents, null)
            }
        } catch (error: SecurityException) {
            SmsSendWaiters.cancel(id)
            return SmsSendOutcome.Failed("SEND_SMS permission not granted")
        } catch (error: Exception) {
            SmsSendWaiters.cancel(id)
            return SmsSendOutcome.Failed(error.message ?: "SMS send failed")
        }
        return withTimeoutOrNull(8_000) { deferred.await() } ?: SmsSendOutcome.Unconfirmed
    }

    private fun manager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun sentIntent(id: String, part: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(context, SendTextSentReceiver::class.java).apply {
            action = SendTextSentReceiver.ACTION_SENT
            putExtra(SendTextSentReceiver.EXTRA_ID, id)
            putExtra(SendTextSentReceiver.EXTRA_PART, part)
        }
        return PendingIntent.getBroadcast(context, (id + ":" + part).hashCode(), intent, flags)
    }
}

class SendTextSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        SmsSendWaiters.onPart(id, resultCode)
    }

    companion object {
        const val ACTION_SENT = "dev.phonepi.companion.action.SEND_TEXT_SENT"
        const val EXTRA_ID = "id"
        const val EXTRA_PART = "part"
    }
}
