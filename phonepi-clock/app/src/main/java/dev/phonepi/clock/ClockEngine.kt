package dev.phonepi.clock

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.phonepi.clock.data.AlarmEntity
import dev.phonepi.clock.data.ClockDatabase
import dev.phonepi.clock.data.EventEntity
import dev.phonepi.clock.data.InstanceEntity
import dev.phonepi.clock.data.RequestEntity
import dev.phonepi.clock.ring.RingingService
import dev.phonepi.clock.schedule.DayMask
import dev.phonepi.clock.schedule.RequestLedger
import dev.phonepi.clock.schedule.nextRing
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

data class RingSpec(
    val alarmId: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val soundType: String,
    val soundUri: String,
    val vibrate: Boolean,
    val rampSeconds: Int,
    val silenceAfterMs: Long,
)

object ClockEngine {
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonepi-clock")
    }
    private val requestIdPattern =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun enqueue(block: () -> Unit) {
        io.execute(block)
    }

    fun rearmAsync(context: Context, reason: String) {
        val app = context.applicationContext
        enqueue { rearm(app, reason) }
    }

    fun rearm(context: Context, reason: String) {
        synchronized(lock) { rearmLocked(context.applicationContext, reason) }
    }

    fun handle(context: Context, method: String, payload: String): String {
        synchronized(lock) {
            val app = context.applicationContext
            val json = parseObject(payload)
            val write = isWrite(method)
            if (write) {
                val requestId = json.optString("requestId")
                if (!requestIdPattern.matches(requestId)) {
                    return error("requestId must be a uuid")
                }
                val ledger = loadLedger(app)
                val now = System.currentTimeMillis()
                ledger.replay(requestId, now)?.let { return withReplayed(it) }
                val body = dispatch(app, method, json)
                ledger.remember(requestId, now, body)
                saveLedger(app, ledger)
                return body
            }
            return dispatch(app, method, json)
        }
    }

    fun list(context: Context): List<AlarmEntity> =
        synchronized(lock) { db(context).alarms().all() }

    fun get(context: Context, id: String): AlarmEntity? =
        synchronized(lock) { db(context).alarms().get(id) }

    fun saveFromUi(context: Context, existingId: String?, fields: JSONObject, createdBy: String): String {
        synchronized(lock) {
            val app = context.applicationContext
            val body = if (existingId == null) {
                createLocked(app, fields, createdBy)
            } else {
                fields.put("id", existingId)
                updateLocked(app, fields)
            }
            return body
        }
    }

    fun setEnabledFromUi(context: Context, id: String, enabled: Boolean) {
        synchronized(lock) {
            val app = context.applicationContext
            setEnabledLocked(app, id, enabled, "app")
        }
    }

    fun deleteFromUi(context: Context, id: String) {
        synchronized(lock) { deleteLocked(context.applicationContext, id, "app") }
    }

    fun skipFromUi(context: Context, id: String) {
        synchronized(lock) { skipLocked(context.applicationContext, id, skip = true, endedBy = "app") }
    }

    fun snoozeFromUi(context: Context, alarmId: String?) {
        synchronized(lock) { snoozeLocked(context.applicationContext, alarmId, "user") }
    }

    fun dismissFromUi(context: Context, alarmId: String?, endedBy: String) {
        synchronized(lock) { dismissLocked(context.applicationContext, alarmId, endedBy) }
    }

    fun presentRing(context: Context, alarmId: String): RingSpec? {
        synchronized(lock) {
            val app = context.applicationContext
            val alarm = db(app).alarms().get(alarmId) ?: return null
            if (!alarm.enabled && db(app).instances().snoozed(alarmId) == null &&
                db(app).instances().ringing(alarmId) == null
            ) {
                return null
            }
            val now = System.currentTimeMillis()
            val open = db(app).instances().ringing(alarmId)
                ?: db(app).instances().snoozed(alarmId)
                ?: db(app).instances().scheduled(alarmId)
            val instance = if (open == null) {
                val created = InstanceEntity(
                    alarmId = alarmId,
                    scheduledAt = now,
                    state = "ringing",
                    snoozeCount = 0,
                    firedAt = now,
                    endedAt = null,
                    endedBy = null,
                )
                val id = db(app).instances().insert(created)
                created.copy(id = id)
            } else {
                val ringing = open.copy(state = "ringing", firedAt = open.firedAt ?: now)
                db(app).instances().update(ringing)
                ringing
            }
            log(app, "rang", alarmId, alarm.label)
            publish(app)
            return RingSpec(
                alarmId = alarm.id,
                label = alarm.label,
                hour = alarm.hour,
                minute = alarm.minute,
                soundType = alarm.soundType,
                soundUri = alarm.soundUri,
                vibrate = alarm.vibrate,
                rampSeconds = alarm.rampSeconds,
                silenceAfterMs = alarm.silenceAfterMinutes.coerceAtLeast(0) * 60_000L,
            )
        }
    }

    fun silence(context: Context, alarmId: String) {
        synchronized(lock) {
            finishRing(context.applicationContext, alarmId, "missed", "silence_after")
        }
    }

    private fun dispatch(context: Context, method: String, json: JSONObject): String {
        return when (method) {
            "clock_list_alarms", "list" -> listPayload(context)
            "clock_get_alarm", "get" -> getPayload(context, json.optString("id"))
            "clock_health", "health" -> healthPayload(context)
            "clock_history", "history" -> historyPayload(context, json.optInt("limit", 50))
            "clock_create_alarm", "create" -> createLocked(context, json, json.optString("createdBy").ifBlank { "bot" })
            "clock_update_alarm", "update" -> updateLocked(context, json)
            "clock_delete_alarm", "delete" -> deleteLocked(context, json.optString("id"), "bot")
            "clock_enable_alarm", "enable" -> setEnabledLocked(context, json.optString("id"), true, "bot")
            "clock_disable_alarm", "disable" -> setEnabledLocked(context, json.optString("id"), false, "bot")
            "clock_skip_next", "skip_next" -> skipLocked(context, json.optString("id"), true, "bot")
            "clock_unskip", "unskip" -> skipLocked(context, json.optString("id"), false, "bot")
            "clock_snooze", "snooze" -> snoozeLocked(context, json.optString("id").ifBlank { null }, "bot")
            "clock_dismiss", "dismiss" -> dismissLocked(context, json.optString("id").ifBlank { null }, "bot")
            else -> error("Unknown clock method")
        }
    }

    private fun createLocked(context: Context, json: JSONObject, createdBy: String): String {
        val hour = json.optInt("hour", -1)
        val minute = json.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return error("hour and minute are required")
        val days = readDays(json)
        if (days == null) return error("days must be a bitmask, weekday names, weekdays, weekends, or daily")
        val label = json.optString("label").take(100)
        val now = System.currentTimeMillis()
        val alarm = AlarmEntity(
            id = UUID.randomUUID().toString(),
            hour = hour,
            minute = minute,
            daysMask = days,
            label = label,
            enabled = if (json.has("enabled")) json.optBoolean("enabled") else true,
            deleteAfterRing = json.optBoolean("deleteAfterRing", false),
            soundType = readSoundType(json),
            soundUri = readSoundUri(json),
            soundName = readSoundName(json),
            vibrate = if (json.has("vibrate")) json.optBoolean("vibrate") else true,
            vibratePattern = "0,500,400,500",
            rampSeconds = json.optInt("rampSeconds", 0).coerceIn(0, 600),
            snoozeMinutes = json.optInt("snoozeMinutes", 10).coerceIn(1, 120),
            maxSnoozes = json.optInt("maxSnoozes", 0).coerceIn(0, 20),
            silenceAfterMinutes = json.optInt("silenceAfterMinutes", 15).coerceIn(0, 120),
            skipNext = false,
            preAlarmMinutes = json.optInt("preAlarmMinutes", 30).coerceIn(0, 180),
            createdBy = createdBy.take(40).ifBlank { "app" },
            createdAt = now,
            updatedAt = now,
            version = 1,
        )
        if (alarm.soundType !in SOUND_TYPES) return error("sound type must be default, ringtone, file, or silent")
        db(context).alarms().upsert(alarm)
        log(context, "created", alarm.id, alarm.label)
        rearmLocked(context, "create")
        val stored = db(context).alarms().get(alarm.id) ?: alarm
        return ok {
            put("alarm", alarmJson(context, stored))
            put("nextRing", nextIso(stored) ?: JSONObject.NULL)
            put("createdBy", stored.createdBy)
            put("snapshot", snapshot(context))
        }
    }

    private fun updateLocked(context: Context, json: JSONObject): String {
        val id = json.optString("id")
        val current = db(context).alarms().get(id) ?: return error("alarm not found")
        if (json.has("ifVersion") && json.optInt("ifVersion") != current.version) {
            return error("version conflict", "conflict")
        }
        val days = if (json.has("days")) readDays(json) else current.daysMask
        if (days == null) return error("days must be a bitmask, weekday names, weekdays, weekends, or daily")
        val hour = if (json.has("hour")) json.optInt("hour") else current.hour
        val minute = if (json.has("minute")) json.optInt("minute") else current.minute
        if (hour !in 0..23 || minute !in 0..59) return error("hour or minute is out of range")
        val soundType = if (json.has("sound") || json.has("soundType")) readSoundType(json) else current.soundType
        if (soundType !in SOUND_TYPES) return error("sound type must be default, ringtone, file, or silent")
        val updated = current.copy(
            hour = hour,
            minute = minute,
            daysMask = days,
            label = if (json.has("label")) json.optString("label").take(100) else current.label,
            enabled = if (json.has("enabled")) json.optBoolean("enabled") else current.enabled,
            deleteAfterRing = if (json.has("deleteAfterRing")) json.optBoolean("deleteAfterRing") else current.deleteAfterRing,
            soundType = soundType,
            soundUri = if (json.has("sound") || json.has("soundUri")) readSoundUri(json) else current.soundUri,
            soundName = if (json.has("sound") || json.has("soundName")) readSoundName(json) else current.soundName,
            vibrate = if (json.has("vibrate")) json.optBoolean("vibrate") else current.vibrate,
            rampSeconds = if (json.has("rampSeconds")) json.optInt("rampSeconds").coerceIn(0, 600) else current.rampSeconds,
            snoozeMinutes = if (json.has("snoozeMinutes")) json.optInt("snoozeMinutes").coerceIn(1, 120) else current.snoozeMinutes,
            maxSnoozes = if (json.has("maxSnoozes")) json.optInt("maxSnoozes").coerceIn(0, 20) else current.maxSnoozes,
            silenceAfterMinutes = if (json.has("silenceAfterMinutes")) json.optInt("silenceAfterMinutes").coerceIn(0, 120) else current.silenceAfterMinutes,
            preAlarmMinutes = if (json.has("preAlarmMinutes")) json.optInt("preAlarmMinutes").coerceIn(0, 180) else current.preAlarmMinutes,
            updatedAt = System.currentTimeMillis(),
            version = current.version + 1,
        )
        db(context).alarms().upsert(updated)
        log(context, "edited", updated.id, updated.label)
        rearmLocked(context, "update")
        return ok {
            put("alarm", alarmJson(context, updated))
            put("nextRing", nextIso(updated) ?: JSONObject.NULL)
            put("createdBy", updated.createdBy)
            put("snapshot", snapshot(context))
        }
    }

    private fun deleteLocked(context: Context, id: String, endedBy: String): String {
        if (id.isBlank() || id == "*") return error("delete needs one alarm id")
        val alarm = db(context).alarms().get(id) ?: return error("alarm not found")
        cancelPending(context, id)
        db(context).instances().deleteForAlarm(id)
        db(context).events().deleteForAlarm(id)
        db(context).alarms().delete(id)
        log(context, "deleted", id, endedBy)
        RingingService.stop(context)
        rearmLocked(context, "delete")
        return ok {
            put("deleted", id)
            put("snapshot", snapshot(context))
        }
    }

    private fun setEnabledLocked(context: Context, id: String, enabled: Boolean, actor: String): String {
        val alarm = db(context).alarms().get(id) ?: return error("alarm not found")
        val updated = alarm.copy(enabled = enabled, updatedAt = System.currentTimeMillis(), version = alarm.version + 1)
        db(context).alarms().upsert(updated)
        log(context, if (enabled) "enabled" else "disabled", id, actor)
        if (!enabled) RingingService.stop(context)
        rearmLocked(context, "enabled")
        return ok {
            put("alarm", alarmJson(context, updated))
            put("nextRing", nextIso(updated) ?: JSONObject.NULL)
            put("snapshot", snapshot(context))
        }
    }

    private fun skipLocked(context: Context, id: String, skip: Boolean, endedBy: String): String {
        val alarm = db(context).alarms().get(id) ?: return error("alarm not found")
        val updated = alarm.copy(
            skipNext = skip,
            updatedAt = System.currentTimeMillis(),
            version = alarm.version + 1,
        )
        db(context).alarms().upsert(updated)
        log(context, if (skip) "skip_next" else "unskip", id, endedBy)
        rearmLocked(context, "skip")
        val stored = db(context).alarms().get(id) ?: updated
        return ok {
            put("alarm", alarmJson(context, stored))
            put("nextRing", nextIso(stored) ?: JSONObject.NULL)
            put("snapshot", snapshot(context))
        }
    }

    private fun snoozeLocked(context: Context, alarmId: String?, endedBy: String): String {
        val id = alarmId?.takeIf { it.isNotBlank() } ?: db(context).instances().anyRinging()?.alarmId
        if (id.isNullOrBlank()) return error("nothing is ringing")
        val alarm = db(context).alarms().get(id) ?: return error("alarm not found")
        val instance = db(context).instances().ringing(id) ?: return error("that alarm is not ringing")
        if (alarm.maxSnoozes > 0 && instance.snoozeCount >= alarm.maxSnoozes) {
            return error("snooze limit reached")
        }
        val at = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
        db(context).instances().update(
            instance.copy(state = "snoozed", scheduledAt = at, snoozeCount = instance.snoozeCount + 1)
        )
        log(context, "snoozed", id, endedBy)
        RingingService.stop(context)
        cancelPending(context, id)
        scheduleAt(context, alarm, at)
        publish(context)
        return ok {
            put("alarm", alarmJson(context, alarm))
            put("nextRing", iso(at))
            put("snoozeCount", instance.snoozeCount + 1)
            put("snapshot", snapshot(context))
        }
    }

    private fun dismissLocked(context: Context, alarmId: String?, endedBy: String): String {
        val id = alarmId?.takeIf { it.isNotBlank() }
            ?: db(context).instances().anyRinging()?.alarmId
        if (id.isNullOrBlank()) return error("nothing is ringing")
        finishRing(context, id, "dismissed", endedBy)
        val alarm = db(context).alarms().get(id)
        return ok {
            if (alarm != null) put("alarm", alarmJson(context, alarm))
            put("endedBy", endedBy)
            put("snapshot", snapshot(context))
        }
    }

    private fun finishRing(context: Context, alarmId: String, state: String, endedBy: String) {
        val now = System.currentTimeMillis()
        val instance = db(context).instances().ringing(alarmId) ?: db(context).instances().snoozed(alarmId)
        if (instance != null) {
            db(context).instances().update(instance.copy(state = state, endedAt = now, endedBy = endedBy))
        }
        val alarm = db(context).alarms().get(alarmId)
        if (alarm != null && alarm.daysMask == 0) {
            if (alarm.deleteAfterRing) {
                db(context).alarms().delete(alarmId)
            } else {
                db(context).alarms().upsert(
                    alarm.copy(enabled = false, updatedAt = now, version = alarm.version + 1)
                )
            }
        }
        log(context, state, alarmId, endedBy)
        RingingService.stop(context)
        rearmLocked(context, "finish")
    }

    private fun rearmLocked(context: Context, reason: String) {
        val database = db(context)
        val now = System.currentTimeMillis()
        val zoneNow = ZonedDateTime.now()
        var soonest: Long? = null
        var exactDenied = false
        for (alarm in database.alarms().all()) {
            cancelPending(context, alarm.id)
            if (!alarm.enabled) continue
            val ringing = database.instances().ringing(alarm.id)
            if (ringing != null) {
                if (shouldResumeRing(reason)) {
                    context.startForegroundService(Intent(context, RingingService::class.java).putExtra(AlarmIntents.EXTRA_ALARM_ID, alarm.id))
                }
                continue
            }
            val snooze = database.instances().snoozed(alarm.id)
            if (snooze != null) {
                val age = now - snooze.scheduledAt
                if (snooze.scheduledAt > now) {
                    if (!scheduleAt(context, alarm, snooze.scheduledAt)) exactDenied = true
                    soonest = minOf(soonest ?: snooze.scheduledAt, snooze.scheduledAt)
                    continue
                }
                if (age <= MISSED_WINDOW_MS) {
                    database.instances().update(snooze.copy(state = "ringing", firedAt = now))
                    context.startForegroundService(Intent(context, RingingService::class.java).putExtra(AlarmIntents.EXTRA_ALARM_ID, alarm.id))
                    continue
                }
                database.instances().update(snooze.copy(state = "missed", endedAt = now, endedBy = "missed"))
                notifyMissed(context, alarm)
                log(context, "missed", alarm.id, "snooze expired")
            }
            var current = database.alarms().get(alarm.id) ?: alarm
            if (current.skipNext) {
                val skipped = nextRing(current.hour, current.minute, current.daysMask, false, zoneNow)
                log(context, "skipped", current.id, skipped?.toString() ?: "one-time")
                current = current.copy(
                    skipNext = false,
                    enabled = current.enabled && current.daysMask != 0,
                    updatedAt = now,
                    version = current.version + 1,
                )
                database.alarms().upsert(current)
            }
            if (!current.enabled) continue
            val scheduled = database.instances().scheduled(current.id)
            var caughtUpRing = false
            if (scheduled != null && scheduled.scheduledAt <= now) {
                val age = now - scheduled.scheduledAt
                if (age <= MISSED_WINDOW_MS) {
                    database.instances().update(scheduled.copy(state = "ringing", firedAt = now))
                    context.startForegroundService(Intent(context, RingingService::class.java).putExtra(AlarmIntents.EXTRA_ALARM_ID, current.id))
                    caughtUpRing = true
                } else {
                    database.instances().update(scheduled.copy(state = "missed", endedAt = now, endedBy = "missed"))
                    notifyMissed(context, current)
                    log(context, "missed", current.id, "phone was off")
                }
            }
            // A one-time alarm that is ringing now must not also arm tomorrow.
            if (caughtUpRing && current.daysMask == 0) continue
            val next = nextRing(current.hour, current.minute, current.daysMask, false, zoneNow) ?: continue
            val at = next.toInstant().toEpochMilli()
            database.instances().replaceScheduled(current.id)
            database.instances().insert(
                InstanceEntity(
                    alarmId = current.id,
                    scheduledAt = at,
                    state = "scheduled",
                    snoozeCount = 0,
                    firedAt = null,
                    endedAt = null,
                    endedBy = null,
                )
            )
            if (!scheduleAt(context, current, at)) exactDenied = true
            schedulePre(context, current, at, now)
            soonest = minOf(soonest ?: at, at)
        }
        val health = selfTest(context, soonest, exactDenied)
        maybeWarn(context, health)
        publish(context)
    }

    private fun scheduleAt(context: Context, alarm: AlarmEntity, at: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        val info = AlarmManager.AlarmClockInfo(at, AlarmIntents.show(context))
        return try {
            am.setAlarmClock(info, AlarmIntents.fire(context, alarm.id))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun schedulePre(context: Context, alarm: AlarmEntity, at: Long, now: Long) {
        if (alarm.preAlarmMinutes <= 0) return
        val preAt = at - alarm.preAlarmMinutes * 60_000L
        if (preAt <= now) {
            if (at > now) notifyUpcoming(context, alarm, at)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preAt, AlarmIntents.pre(context, alarm.id))
        } catch (_: SecurityException) {
            // The alarm itself still uses setAlarmClock.
        }
    }

    fun showUpcoming(context: Context, alarmId: String) {
        synchronized(lock) {
            val alarm = db(context).alarms().get(alarmId) ?: return
            val scheduled = db(context).instances().scheduled(alarmId) ?: return
            notifyUpcoming(context, alarm, scheduled.scheduledAt)
            log(context, "pre_alarm", alarmId, alarm.label)
        }
    }

    private fun notifyUpcoming(context: Context, alarm: AlarmEntity, at: Long) {
        val text = "${fmt(alarm.hour, alarm.minute)} ${alarm.label}".trim()
        val notification = NotificationCompat.Builder(context, ClockApp.CHANNEL_UPCOMING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Upcoming alarm")
            .setContentText(text)
            .setWhen(at)
            .setAutoCancel(true)
            .addAction(0, "Skip", AlarmIntents.skip(context, alarm.id))
            .build()
        notify(context, UPCOMING_BASE + (alarm.id.hashCode() and 0xffff), notification)
    }

    private fun notifyMissed(context: Context, alarm: AlarmEntity) {
        val notification = NotificationCompat.Builder(context, ClockApp.CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Missed alarm")
            .setContentText("${fmt(alarm.hour, alarm.minute)} ${alarm.label}".trim())
            .setAutoCancel(true)
            .build()
        notify(context, MISSED_BASE + (alarm.id.hashCode() and 0xffff), notification)
    }

    private fun maybeWarn(context: Context, health: JSONObject) {
        val status = health.optString("status")
        val detail = health.optString("detail")
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("clock-health", Context.MODE_PRIVATE)
        val previous = prefs.getString("detail", "")
        if (status != "degraded") {
            prefs.edit().remove("detail").apply()
            context.getSystemService(NotificationManager::class.java).cancel(HEALTH_ID)
            return
        }
        if (detail == previous) return
        prefs.edit().putString("detail", detail).apply()
        val notification = NotificationCompat.Builder(context, ClockApp.CHANNEL_HEALTH)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("PhonePi Clock needs a look")
            .setContentText(detail)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notify(context, HEALTH_ID, notification)
    }

    private fun selfTest(context: Context, expectedAt: Long?, exactDenied: Boolean): JSONObject {
        val am = context.getSystemService(AlarmManager::class.java)
        val next = am.nextAlarmClock
        val exact = !exactDenied && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
        val pm = context.getSystemService(PowerManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val notifications = nm.areNotificationsEnabled()
        val fullScreen = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
        val batteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
        val systemPkg = next?.showIntent?.creatorPackage
        var status = "ok"
        var detail = ""
        if (!exact) {
            status = "degraded"
            detail = "Exact alarms are not allowed"
        } else if (expectedAt != null && next == null) {
            status = "degraded"
            detail = "AlarmManager has no next alarm"
        } else if (expectedAt != null && next != null && systemPkg != context.packageName &&
            kotlin.math.abs(next.triggerTime - expectedAt) <= 120_000
        ) {
            status = "degraded"
            detail = "Another clock has an alarm within 2 minutes of PhonePi Clock"
        } else if (expectedAt != null && next != null && systemPkg != context.packageName && next.triggerTime < expectedAt) {
            status = "watching"
            detail = "Another app has an earlier alarm. PhonePi Clock is still scheduled."
        } else if (expectedAt != null && next != null && next.triggerTime != expectedAt && systemPkg == context.packageName) {
            status = "degraded"
            detail = "The next alarm time does not match the schedule"
        }
        return JSONObject().apply {
            put("status", status)
            put("exactAlarm", exact)
            put("notifications", notifications)
            put("fullScreenIntent", fullScreen)
            put("batteryOptimized", batteryOptimized)
            put("nextAlarmAt", expectedAt?.let { iso(it) } ?: JSONObject.NULL)
            put("systemNextAlarmAt", next?.let { iso(it.triggerTime) } ?: JSONObject.NULL)
            put("systemNextPackage", systemPkg ?: JSONObject.NULL)
            put("detail", detail)
        }
    }

    private fun cancelPending(context: Context, alarmId: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(AlarmIntents.fire(context, alarmId))
        am.cancel(AlarmIntents.pre(context, alarmId))
    }

    private fun publish(context: Context) {
        val json = snapshot(context).toString()
        val intent = Intent(ACTION_SNAPSHOT)
            .setPackage("dev.phonepi.companion")
            .putExtra("snapshot", json)
        context.sendBroadcast(intent, "dev.phonepi.clock.permission.CONTROL")
    }

    private fun snapshot(context: Context): JSONObject {
        val alarms = db(context).alarms().all()
        val zoneNow = ZonedDateTime.now()
        var nextAt: Long? = null
        var nextAlarm: AlarmEntity? = null
        val array = JSONArray()
        for (alarm in alarms) {
            val next = if (alarm.enabled) nextIso(alarm) else null
            array.put(alarmJson(context, alarm))
            if (alarm.enabled) {
                val at = nextRing(alarm.hour, alarm.minute, alarm.daysMask, alarm.skipNext, zoneNow)
                    ?.toInstant()?.toEpochMilli()
                if (at != null && (nextAt == null || at < nextAt!!)) {
                    nextAt = at
                    nextAlarm = alarm
                }
            }
        }
        return JSONObject().apply {
            put("alarms", array)
            put("nextAlarm", if (nextAlarm == null || nextAt == null) JSONObject.NULL else JSONObject().apply {
                put("id", nextAlarm.id)
                put("at", iso(nextAt))
                put("label", nextAlarm.label)
            })
            put("health", selfTest(context, nextAt, exactDenied = false))
            put("updatedAt", iso(System.currentTimeMillis()))
        }
    }

    private fun listPayload(context: Context): String = ok {
        val snap = snapshot(context)
        put("alarms", snap.getJSONArray("alarms"))
        put("nextAlarm", snap.get("nextAlarm"))
        put("health", snap.getJSONObject("health"))
        put("stale", false)
        put("snapshot", snap)
    }

    private fun getPayload(context: Context, id: String): String {
        val alarm = db(context).alarms().get(id) ?: return error("alarm not found")
        val instances = JSONArray()
        for (row in db(context).instances().recent(id, 10)) instances.put(instanceJson(row))
        return ok {
            put("alarm", alarmJson(context, alarm))
            put("instances", instances)
            put("nextRing", nextIso(alarm) ?: JSONObject.NULL)
            put("stale", false)
            put("snapshot", snapshot(context))
        }
    }

    private fun healthPayload(context: Context): String {
        val snap = snapshot(context)
        return ok {
            put("health", snap.getJSONObject("health"))
            put("stale", false)
            put("snapshot", snap)
        }
    }

    private fun historyPayload(context: Context, limit: Int): String {
        val events = JSONArray()
        for (event in db(context).events().recent(limit.coerceIn(1, 200))) {
            events.put(JSONObject().apply {
                put("at", iso(event.atMs))
                put("kind", event.kind)
                put("alarmId", event.alarmId)
                put("detail", event.detail)
            })
        }
        return ok {
            put("events", events)
            put("stale", false)
        }
    }

    private fun alarmJson(context: Context, alarm: AlarmEntity): JSONObject {
        return JSONObject().apply {
            put("id", alarm.id)
            put("hour", alarm.hour)
            put("minute", alarm.minute)
            put("days", alarm.daysMask)
            put("label", alarm.label)
            put("enabled", alarm.enabled)
            put("deleteAfterRing", alarm.deleteAfterRing)
            put("sound", JSONObject().apply {
                put("type", alarm.soundType)
                put("uri", alarm.soundUri)
                put("name", alarm.soundName)
            })
            put("vibrate", alarm.vibrate)
            put("rampSeconds", alarm.rampSeconds)
            put("snoozeMinutes", alarm.snoozeMinutes)
            put("maxSnoozes", alarm.maxSnoozes)
            put("silenceAfterMinutes", alarm.silenceAfterMinutes)
            put("skipNext", alarm.skipNext)
            put("preAlarmMinutes", alarm.preAlarmMinutes)
            put("createdBy", alarm.createdBy)
            put("createdAt", alarm.createdAt)
            put("updatedAt", alarm.updatedAt)
            put("version", alarm.version)
            put("nextRing", nextIso(alarm) ?: JSONObject.NULL)
        }
    }

    private fun instanceJson(row: InstanceEntity): JSONObject = JSONObject().apply {
        put("id", row.id)
        put("alarmId", row.alarmId)
        put("scheduledAt", row.scheduledAt)
        put("state", row.state)
        put("snoozeCount", row.snoozeCount)
        put("firedAt", row.firedAt ?: JSONObject.NULL)
        put("endedAt", row.endedAt ?: JSONObject.NULL)
        put("endedBy", row.endedBy ?: JSONObject.NULL)
    }

    private fun nextIso(alarm: AlarmEntity): String? {
        if (!alarm.enabled && !alarm.skipNext) return null
        val next = nextRing(alarm.hour, alarm.minute, alarm.daysMask, alarm.skipNext, ZonedDateTime.now())
        return next?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun log(context: Context, kind: String, alarmId: String, detail: String) {
        db(context).events().insert(
            EventEntity(
                atMs = System.currentTimeMillis(),
                kind = kind,
                alarmId = alarmId,
                detail = detail.take(200),
            )
        )
    }

    private fun loadLedger(context: Context): RequestLedger {
        val ledger = RequestLedger()
        ledger.restore(db(context).requests().all().map {
            RequestLedger.Entry(it.requestId, it.atMs, it.body)
        })
        return ledger
    }

    private fun saveLedger(context: Context, ledger: RequestLedger) {
        val dao = db(context).requests()
        dao.clear()
        dao.insert(ledger.snapshot().map { RequestEntity(it.requestId, it.atMs, it.body) })
    }

    private fun db(context: Context) = ClockDatabase.get(context)

    private fun shouldResumeRing(reason: String): Boolean {
        return reason == "process" || reason.contains("BOOT") || reason.contains("boot") || reason == "package"
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun readDays(json: JSONObject): Int? {
        if (!json.has("days")) return 0
        val raw = json.get("days")
        return when (raw) {
            is Number -> DayMask.parse(raw.toInt())
            is String -> DayMask.parse(raw)
            is JSONArray -> DayMask.parse((0 until raw.length()).map { raw.get(it).toString() })
            else -> null
        }
    }

    private fun readSoundType(json: JSONObject): String {
        val sound = json.optJSONObject("sound")
        return sound?.optString("type")?.ifBlank { null }
            ?: json.optString("soundType").ifBlank { "default" }
    }

    private fun readSoundUri(json: JSONObject): String {
        val sound = json.optJSONObject("sound")
        return sound?.optString("uri") ?: json.optString("soundUri")
    }

    private fun readSoundName(json: JSONObject): String {
        val sound = json.optJSONObject("sound")
        return sound?.optString("name") ?: json.optString("soundName")
    }

    private fun parseObject(payload: String): JSONObject {
        return try {
            JSONObject(payload.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun ok(build: JSONObject.() -> Unit): String =
        JSONObject().apply {
            put("status", "success")
            put("replayed", false)
            build()
        }.toString()

    private fun error(message: String, code: String? = null): String =
        JSONObject().apply {
            put("status", "error")
            put("error", message)
            put("replayed", false)
            if (code != null) put("code", code)
        }.toString()

    private fun withReplayed(body: String): String {
        return try {
            JSONObject(body).put("replayed", true).toString()
        } catch (_: Exception) {
            body
        }
    }

    private fun isWrite(method: String): Boolean = method !in READS

    private fun iso(at: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun fmt(hour: Int, minute: Int): String =
        "%d:%02d".format(if (hour % 12 == 0) 12 else hour % 12, minute) + if (hour < 12) " AM" else " PM"

    const val ACTION_SNAPSHOT = "dev.phonepi.clock.action.SNAPSHOT"
    private val READS = setOf(
        "clock_list_alarms", "list",
        "clock_get_alarm", "get",
        "clock_health", "health",
        "clock_history", "history",
    )
    private val SOUND_TYPES = setOf("default", "ringtone", "file", "silent")
    private const val MISSED_WINDOW_MS = 30L * 60L * 1000L
    private const val UPCOMING_BASE = 2000
    private const val MISSED_BASE = 3000
    private const val HEALTH_ID = 7
}
