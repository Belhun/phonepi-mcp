package dev.phonepi.companion.textsend

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sends one JSON frame to the box when the phone socket is up. */
object PhoneUplink {
    @Volatile
    var send: ((String) -> Boolean)? = null

    fun attach(sender: (String) -> Boolean) {
        send = sender
    }
}

/**
 * Box send log lines. If the socket is down, they wait here and flush on connect.
 * 0.3.3: a line that could not be handed to the server is also retried after
 * 2 s, 5 s and 10 s (see [SendRetryPolicy]) through whichever server is active,
 * then an error is shown in Recent activity (the line stays queued for the next connect).
 * Each line carries a stable `requestId`, reused on every retry. A line is sent at most
 * once from the phone: retries and flush both remove it under one lock.
 * The shared token is never included.
 */
object SendLogOutbox {
    private const val PREFS = "phonepi_send_text_outbox"
    private const val KEY = "lines"
    private val lock = Any()
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun newRequestId(): String = "req-" + UUID.randomUUID().toString()

    fun enqueue(context: Context, json: String) {
        val app = context.applicationContext
        val requestId = requestIdOf(json) ?: newRequestId()
        val line = withRequestId(json, requestId)
        synchronized(lock) {
            if (PhoneUplink.send?.invoke(line) == true) return
            val lines = read(app).toMutableList()
            lines += line
            write(app, lines.takeLast(100))
        }
        // First attempt clearly did not reach a server (no live socket). Retry on schedule.
        retryScope.launch {
            val result = SendRetrier { delay(it) }.run(
                requestId = requestId,
                attempt = { trySendQueued(app, requestId) },
                stillPending = { isQueued(app, requestId) },
            )
            if (result is SendResult.GaveUp) {
                ActivityLog.logTextSend(
                    "Could not reach the PhonePi server after ${result.attempts} tries " +
                        "(retried at 2 s, 5 s, 10 s). Saved; will send on next connect.",
                    "error",
                )
            }
        }
    }

    fun flush(context: Context) {
        val sender = PhoneUplink.send ?: return
        synchronized(lock) {
            val kept = mutableListOf<String>()
            for (line in read(context)) {
                if (!sender(line)) kept += line
            }
            write(context, kept)
        }
    }

    /** Send the queued line with [requestId] once; remove it only when the socket took it. */
    private fun trySendQueued(context: Context, requestId: String): SendOutcome {
        val sender = PhoneUplink.send ?: return SendOutcome.NOT_SENT
        synchronized(lock) {
            val lines = read(context)
            val line = lines.firstOrNull { requestIdOf(it) == requestId }
                ?: return SendOutcome.HANDED_OFF
            if (!sender(line)) return SendOutcome.NOT_SENT
            write(context, lines.filter { it !== line })
            return SendOutcome.HANDED_OFF
        }
    }

    private fun isQueued(context: Context, requestId: String): Boolean = synchronized(lock) {
        read(context).any { requestIdOf(it) == requestId }
    }

    private fun requestIdOf(line: String): String? =
        runCatching { JSONObject(line).optString("requestId") }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun withRequestId(line: String, requestId: String): String =
        runCatching { JSONObject(line).put("requestId", requestId).toString() }.getOrDefault(line)

    private fun read(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val line = array.optString(index)
                if (line.isNotBlank()) add(line)
            }
        }
    }

    private fun write(context: Context, lines: List<String>) {
        val array = JSONArray()
        lines.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}

object SendTextEvents {
    fun emit(
        context: Context,
        outcome: String,
        mode: String,
        id: String,
        recipientName: String,
        number: String,
        message: String,
        confirmMethod: String?,
        error: String? = null,
        candidates: List<MaskedCandidate> = emptyList(),
    ) {
        val masked = SendTextPolicy.maskPhone(number)
        val detail = buildString {
            append(outcome)
            append(' ')
            append(mode)
            append(" to ")
            append(recipientName)
            append(' ')
            append(masked)
            if (!confirmMethod.isNullOrBlank()) {
                append(" via ")
                append(confirmMethod)
            }
            if (!error.isNullOrBlank()) {
                append(" (")
                append(error)
                append(')')
            }
            append(": ")
            append(message.take(500))
        }
        dev.phonepi.companion.log.ActivityLog.logTextSend(detail, outcome)
        val json = JSONObject()
            .put("type", "send_text_log")
            .put("id", id)
            .put("requestId", SendLogOutbox.newRequestId())
            .put("outcome", outcome)
            .put("mode", mode)
            .put("recipientName", recipientName)
            .put("numberMasked", masked)
            .put("message", message)
        if (!confirmMethod.isNullOrBlank()) json.put("confirmMethod", confirmMethod)
        if (!error.isNullOrBlank()) json.put("error", error)
        if (candidates.isNotEmpty()) {
            val array = JSONArray()
            for (candidate in candidates) {
                array.put(
                    JSONObject()
                        .put("name", candidate.name)
                        .put("numberMasked", candidate.numberMasked),
                )
            }
            json.put("candidates", array)
        }
        SendLogOutbox.enqueue(context, json.toString())
    }
}
