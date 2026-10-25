package dev.phonepi.wear.complication

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.phonepi.wear.NextAlarmActivity
import dev.phonepi.wear.NextAlarmFormat
import dev.phonepi.wear.PresenceCache

/**
 * Next-alarm complication. Reads [AlarmManager.getNextAlarmClock] (the Clock
 * app on cursor/phonepi-clock-p1 publishes through that system API). Presence
 * is appended when the phone has pushed a label. Watch Face Push is not used;
 * this is a data source, not a watch face.
 */
class NextAlarmComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val trigger = getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
            ?: PresenceCache.nextAlarmEpochMs(this)
        val presence = PresenceCache.label(this)
        val now = System.currentTimeMillis()
        val short = NextAlarmFormat.shortText(trigger, now)
        val long = NextAlarmFormat.longText(trigger, presence, now)
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, NextAlarmActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(short).build(),
                    contentDescription = PlainComplicationText.Builder(long).build(),
                ).setTapAction(tap).build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(long).build(),
                    contentDescription = PlainComplicationText.Builder(long).build(),
                ).setTapAction(tap).build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sample = "6:30 AM"
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(sample).build(),
                    contentDescription = PlainComplicationText.Builder(sample).build(),
                ).build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("$sample · home").build(),
                    contentDescription = PlainComplicationText.Builder("$sample · home").build(),
                ).build()
            else -> null
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, NextAlarmComplicationService::class.java),
            ).requestUpdateAll()
        }
    }
}
