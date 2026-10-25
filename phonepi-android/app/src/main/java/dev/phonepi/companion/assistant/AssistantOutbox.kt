package dev.phonepi.companion.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.phonepi.companion.textsend.PhoneUplink
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class AssistantAckResult(
    val id: String,
    val ok: Boolean,
    val retryable: Boolean,
    val retryAfterSec: Int = AssistantOutboxPolicy.DEFAULT_RETRY_AFTER_SEC,
)

object AssistantAckPolicy {
    fun shouldDrop(ok: Boolean, retryable: Boolean): Boolean =
        AssistantOutboxPolicy.shouldDrop(ok, retryable)
}

/**
 * Prompt/reply frames stay queued until the box sends assistant_ack {id, ok}.
 * Retries reuse the same id so the server can dedupe. Never includes the token.
 * A retryable ok:false schedules one retry of that row after retryAfterSec.
 * Flush sends at most 20 frames per minute.
 */
object AssistantOutbox {
    private const val PREFS = "phonepi_assistant_outbox"
    private const val KEY = "lines"
    private val lock = Any()
    private val sentAt = ArrayDeque<Long>()
    private val retryAttempts = mutableMapOf<String, Int>()

    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var jitterMs: () -> Int = { Random.nextInt(0, 1_000) }
    internal var scheduler: AssistantOutboxScheduler = AndroidOutboxScheduler

    fun enqueue(context: Context, json: String) {
        val app = context.applicationContext
        synchronized(lock) {
            val lines = read(app).toMutableList()
            val id = idOf(json)
            if (id != null) {
                lines.removeAll { idOf(it) == id }
            }
            lines += json
            write(app, lines.takeLast(100))
        }
        flush(app)
    }

    fun ack(
        context: Context,
        id: String,
        ok: Boolean,
        retryable: Boolean = false,
        retryAfterSec: Int = AssistantOutboxPolicy.DEFAULT_RETRY_AFTER_SEC,
    ) {
        if (id.isBlank()) return
        val app = context.applicationContext
        if (!AssistantOutboxPolicy.shouldDrop(ok, retryable)) {
            val attempt = synchronized(lock) { retryAttempts[id] ?: 0 }
            val delay = AssistantOutboxPolicy.retryDelayMs(
                retryAfterSec,
                attempt,
                jitterMs(),
            )
            synchronized(lock) { retryAttempts[id] = attempt + 1 }
            scheduler.cancel("retry:$id")
            scheduler.schedule("retry:$id", delay) {
                sendOne(app, id)
            }
            return
        }
        synchronized(lock) {
            val kept = read(app).filter { idOf(it) != id }
            write(app, kept)
            retryAttempts.remove(id)
        }
        scheduler.cancel("retry:$id")
    }

    fun flush(context: Context) {
        val app = context.applicationContext
        val sender = PhoneUplink.send ?: return
        val batch: List<String>
        val delayMs: Long
        synchronized(lock) {
            pruneWindowLocked()
            val remaining = read(app)
            batch = AssistantOutboxPolicy.takeFlushBatch(remaining, sentAt.size)
            val t = nowMs()
            batch.forEach { sentAt.addLast(t) }
            delayMs = if (remaining.size > batch.size) {
                AssistantOutboxPolicy.nextFlushDelayMs(sentAt.toList(), t)
            } else {
                0L
            }
        }
        for (line in batch) {
            sender(line)
        }
        if (delayMs > 0L) {
            scheduler.cancel("flush")
            scheduler.schedule("flush", delayMs) { flush(app) }
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            write(app, emptyList())
            retryAttempts.clear()
            sentAt.clear()
        }
        scheduler.cancel("flush")
    }

    fun parseAck(text: String): AssistantAckResult? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.optString("type") != "assistant_ack") return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        val retryAfter = obj.optInt("retryAfterSec", AssistantOutboxPolicy.DEFAULT_RETRY_AFTER_SEC)
        return AssistantAckResult(
            id = id,
            ok = obj.optBoolean("ok", false),
            retryable = obj.optBoolean("retryable", false),
            retryAfterSec = retryAfter.coerceAtLeast(1),
        )
    }

    private fun sendOne(context: Context, id: String) {
        val sender = PhoneUplink.send ?: return
        val line = synchronized(lock) {
            pruneWindowLocked()
            if (sentAt.size >= AssistantOutboxPolicy.FLUSH_BATCH) {
                val delay = AssistantOutboxPolicy.nextFlushDelayMs(sentAt.toList(), nowMs())
                scheduler.cancel("retry:$id")
                scheduler.schedule("retry:$id", delay) { sendOne(context, id) }
                return
            }
            val match = read(context).firstOrNull { idOf(it) == id } ?: return
            sentAt.addLast(nowMs())
            match
        }
        sender(line)
    }

    private fun pruneWindowLocked() {
        val cutoff = nowMs() - AssistantOutboxPolicy.WINDOW_MS
        while (sentAt.isNotEmpty() && sentAt.first() < cutoff) {
            sentAt.removeFirst()
        }
    }

    private fun idOf(line: String): String? =
        runCatching { JSONObject(line).optString("id") }.getOrNull()?.takeIf { it.isNotBlank() }

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

private object AndroidOutboxScheduler : AssistantOutboxScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private val posted = mutableMapOf<String, Runnable>()

    override fun schedule(token: String, delayMs: Long, run: () -> Unit) {
        cancel(token)
        val runnable = Runnable {
            posted.remove(token)
            run()
        }
        posted[token] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel(token: String) {
        posted.remove(token)?.let { handler.removeCallbacks(it) }
    }
}
