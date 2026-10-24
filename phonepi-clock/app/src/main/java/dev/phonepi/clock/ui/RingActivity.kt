package dev.phonepi.clock.ui

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.phonepi.clock.AlarmIntents
import dev.phonepi.clock.ClockEngine

private const val TAG = "PhonePiClock"

class RingActivity : ComponentActivity() {
    private val alarmId: String?
        get() = intent.getStringExtra(AlarmIntents.EXTRA_ALARM_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "RingActivity start")
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show()
    }

    private fun show() {
        setContent {
            ClockTheme {
                RingScreen(
                    time = "Alarm",
                    label = alarmId?.let { "PhonePi Clock" } ?: "Alarm",
                    onSnooze = { snooze() },
                    onStop = { stop() },
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            snooze()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun snooze() {
        ClockEngine.enqueue {
            ClockEngine.snoozeFromUi(applicationContext, alarmId)
            runOnUiThread { finish() }
        }
    }

    private fun stop() {
        ClockEngine.enqueue {
            ClockEngine.dismissFromUi(applicationContext, alarmId, "user")
            runOnUiThread { finish() }
        }
    }
}
