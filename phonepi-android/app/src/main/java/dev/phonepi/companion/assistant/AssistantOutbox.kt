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
internal interface AssistantOutboxStore {
    fun read(): List<String>
    fun write(lines: List<String>)
}

internal class MemoryOutboxStore : AssistantOutboxStore {
    private val lock = Any()
    private val lines = mutableListOf<String>()

    override fun read(): List<String> = synchronized(lock) { lines.toList() }

    override fun write(lines: List<String>) {
        synchronized(lock) {
            this.lines.clear()
            this.lines += lines
        }
    }
}

object AssistantOutbox {
    private const val PREFS = "phonepi_assistant_outbox"
    private const val KEY = "lines"
    private val ID_FIELD = Regex(""""id"\s*:\s*"([^"]+)"""")
    private val lock = Any()
    private val sentAt = ArrayDeque<Long>()
    private val retryAttempts = mutableMapOf<String, Int>()

    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var jitterMs: () -> Int = { Random.nextInt(0, 1_000) }
    internal var scheduler: AssistantOutboxScheduler = AndroidOutboxScheduler
    internal var storeOverride: AssistantOutboxStore? = null

    fun enqueue(context: Context, json: String) {
        enqueueLine(json, context)
    }

    internal fun enqueueLine(json: String, context: Context? = null) {
        val store = store(context)
        synchronized(lock) {
            val lines = store.read().toMutableList()
            val id = idOf(json)
            if (id != null) {
                lines.removeAll { idOf(it) == id }
            }
            lines += json
            store.write(lines.takeLast(100))
        }
        flushBound(context)
    }

    fun ack(
        context: Context,
        id: String,
        ok: Boolean,
        retryable: Boolean = false,
        retryAfterSec: Int = AssistantOutboxPolicy.DEFAULT_RETRY_AFTER_SEC,
    ) {
        ackId(id, ok, retryable, retryAfterSec, context)
    }

    internal fun ackId(
        id: String,
        ok: Boolean,
        retryable: Boolean = false,
        retryAfterSec: Int = AssistantOutboxPolicy.DEFAULT_RETRY_AFTER_SEC,
        context: Context? = null,
    ) {
        if (id.isBlank()) return
        val store = store(context)
        if (!AssistantOutboxPolicy.shouldDrop(ok, retryable)) {
            val delay = synchronized(lock) {
                val attempt = retryAttempts[id] ?: 0
                retryAttempts[id] = attempt + 1
                AssistantOutboxPolicy.retryDelayMs(
                    retryAfterSec,
                    attempt,
                    jitterMs(),
                )
            }
            scheduler.cancel("retry:$id")
            scheduler.schedule("retry:$id", delay) {
                sendOne(context, id)
            }
            return
        }
        synchronized(lock) {
            val kept = store.read().filter { idOf(it) != id }
            store.write(kept)
            retryAttempts.remove(id)
        }
        scheduler.cancel("retry:$id")
    }

    fun flush(context: Context) {
        flushBound(context)
    }

    internal fun flushBound(context: Context? = null) {
        val sender = PhoneUplink.send ?: return
        val store = store(context)
        val batch: List<String>
        val delayMs: Long
        synchronized(lock) {
            pruneWindowLocked()
            val remaining = store.read()
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
            scheduler.schedule("flush", delayMs) { flushBound(context) }
        }
    }

    fun clear(context: Context) {
        clearBound(context)
    }

    internal fun clearBound(context: Context? = null) {
        synchronized(lock) {
            store(context).write(emptyList())
            retryAttempts.clear()
            sentAt.clear()
        }
        scheduler.cancel("flush")
    }

    internal fun resetForTest(
        store: AssistantOutboxStore? = null,
        scheduler: AssistantOutboxScheduler = AndroidOutboxScheduler,
    ) {
        synchronized(lock) {
            retryAttempts.clear()
            sentAt.clear()
            storeOverride = store
            this.scheduler = scheduler
            nowMs = { System.currentTimeMillis() }
            jitterMs = { Random.nextInt(0, 1_000) }
        }
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

    private fun sendOne(context: Context?, id: String) {
        val sender = PhoneUplink.send ?: return
        val store = store(context)
        var retryDelayMs: Long? = null
        val line = synchronized(lock) {
            pruneWindowLocked()
            if (sentAt.size >= AssistantOutboxPolicy.FLUSH_BATCH) {
                retryDelayMs = AssistantOutboxPolicy.nextFlushDelayMs(sentAt.toList(), nowMs())
                null
            } else {
                val match = store.read().firstOrNull { idOf(it) == id }
                if (match != null) sentAt.addLast(nowMs())
                match
            }
        }
        val delay = retryDelayMs
        if (delay != null) {
            scheduler.cancel("retry:$id")
            scheduler.schedule("retry:$id", delay) { sendOne(context, id) }
            return
        }
        if (line != null) sender(line)
    }

    private fun store(context: Context?): AssistantOutboxStore {
        storeOverride?.let { return it }
        return PrefsOutboxStore(requireNotNull(context).applicationContext)
    }

    private fun pruneWindowLocked() {
        val cutoff = nowMs() - AssistantOutboxPolicy.WINDOW_MS
        while (sentAt.isNotEmpty() && sentAt.first() < cutoff) {
            sentAt.removeFirst()
        }
    }

    private fun idOf(line: String): String? {
        val parsed = runCatching {
            JSONObject(line).optString("id").takeIf { it.isNotBlank() }
        }.getOrNull()
        if (parsed != null) return parsed
        return ID_FIELD.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private class PrefsOutboxStore(private val context: Context) : AssistantOutboxStore {
        override fun read(): List<String> {
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

        override fun write(lines: List<String>) {
            val array = JSONArray()
            lines.forEach { array.put(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, array.toString())
                .apply()
        }
    }
}

private object AndroidOutboxScheduler : AssistantOutboxScheduler {
    private val postedLock = Any()
    private val posted = HashMap<String, Runnable>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(token: String, delayMs: Long, run: () -> Unit) {
        val task = run
        val runnable = object : Runnable {
            override fun run() {
                synchronized(postedLock) {
                    if (posted[token] === this) posted.remove(token)
                }
                task()
            }
        }
        synchronized(postedLock) {
            posted.remove(token)?.let { handler.removeCallbacks(it) }
            posted[token] = runnable
        }
        handler.postDelayed(runnable, delayMs)
    }

    override fun cancel(token: String) {
        val runnable = synchronized(postedLock) { posted.remove(token) }
        runnable?.let { handler.removeCallbacks(it) }
    }
}
