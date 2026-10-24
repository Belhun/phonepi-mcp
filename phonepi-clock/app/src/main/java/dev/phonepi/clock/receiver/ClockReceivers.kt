package dev.phonepi.clock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.phonepi.clock.AlarmIntents
import dev.phonepi.clock.ClockEngine
import dev.phonepi.clock.ring.RingingService

class AlarmFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID).orEmpty()
        val app = context.applicationContext
        when (intent.action) {
            AlarmIntents.ACTION_PRE -> {
                val pending = goAsync()
                ClockEngine.enqueue {
                    try {
                        ClockEngine.showUpcoming(app, alarmId)
                    } finally {
                        pending.finish()
                    }
                }
            }
            AlarmIntents.ACTION_SKIP -> {
                val pending = goAsync()
                ClockEngine.enqueue {
                    try {
                        ClockEngine.skipFromUi(app, alarmId)
                    } finally {
                        pending.finish()
                    }
                }
            }
            AlarmIntents.ACTION_SNOOZE -> {
                val pending = goAsync()
                ClockEngine.enqueue {
                    try {
                        ClockEngine.snoozeFromUi(app, alarmId.ifBlank { null })
                    } finally {
                        pending.finish()
                    }
                }
            }
            AlarmIntents.ACTION_DISMISS -> {
                val pending = goAsync()
                ClockEngine.enqueue {
                    try {
                        ClockEngine.dismissFromUi(app, alarmId.ifBlank { null }, "user")
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> {
                if (alarmId.isBlank()) return
                context.startForegroundService(
                    Intent(context, RingingService::class.java).putExtra(AlarmIntents.EXTRA_ALARM_ID, alarmId)
                )
            }
        }
    }
}

class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val reason = intent.action ?: "rearm"
        ClockEngine.enqueue {
            try {
                ClockEngine.rearm(context.applicationContext, reason)
            } finally {
                pending.finish()
            }
        }
    }
}
