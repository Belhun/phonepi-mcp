package dev.phonepi.clock.ring

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.phonepi.clock.AlarmIntents
import dev.phonepi.clock.ClockApp
import dev.phonepi.clock.ClockEngine
import dev.phonepi.clock.R
import dev.phonepi.clock.RingSpec

class RingingService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var silence: Runnable? = null
    private var rampStep = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AlarmIntents.EXTRA_ALARM_ID).orEmpty()
        Log.i(TAG, "RingingService start")
        startInForeground(ringingNotification(this, "Alarm", alarmId.ifBlank { null }))
        if (alarmId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ClockEngine.enqueue {
            val spec = ClockEngine.presentRing(applicationContext, alarmId)
            handler.post {
                if (spec == null) {
                    stop(applicationContext)
                } else {
                    play(spec)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseAudio()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun play(spec: RingSpec) {
        startInForeground(ringingNotification(this, spec.label.ifBlank { "Alarm" }, spec.alarmId))
        val pm = getSystemService(PowerManager::class.java)
        wakeLock?.releaseSafe()
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonepi-clock:ring").apply {
            val hold = if (spec.silenceAfterMs > 0) spec.silenceAfterMs + 15_000 else 60 * 60_000L
            acquire(hold)
        }
        if (spec.vibrate) vibrate()
        if (spec.soundType != "silent") startPlayer(spec)
        silence?.let { handler.removeCallbacks(it) }
        if (spec.silenceAfterMs > 0) {
            val task = Runnable { ClockEngine.enqueue { ClockEngine.silence(applicationContext, spec.alarmId) } }
            silence = task
            handler.postDelayed(task, spec.silenceAfterMs)
        }
        try {
            startActivity(
                Intent(this, dev.phonepi.clock.ui.RingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(AlarmIntents.EXTRA_ALARM_ID, spec.alarmId)
            )
        } catch (_: Exception) {
            // The full-screen intent is the lock-screen path.
        }
    }

    private fun startPlayer(spec: RingSpec) {
        player?.release()
        val created = MediaPlayer()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            created.setAudioAttributes(attrs)
            created.isLooping = true
            when (spec.soundType) {
                "file" -> created.setDataSource(spec.soundUri)
                "ringtone" -> created.setDataSource(this, Uri.parse(spec.soundUri))
                else -> created.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            }
            created.prepare()
        } catch (_: Exception) {
            try {
                created.reset()
                created.setAudioAttributes(attrs)
                created.isLooping = true
                created.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                created.prepare()
            } catch (_: Exception) {
                created.release()
                return
            }
        }
        val start = if (spec.rampSeconds > 0) 0.05f else 1f
        created.setVolume(start, start)
        val audio = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
        focus = request
        audio.requestAudioFocus(request)
        created.start()
        player = created
        if (spec.rampSeconds > 0) ramp(spec.rampSeconds)
    }

    private fun ramp(seconds: Int) {
        rampStep = 0
        val task = object : Runnable {
            override fun run() {
                rampStep += 1
                val gain = (rampStep.toFloat() / seconds).coerceIn(0.05f, 1f)
                player?.setVolume(gain, gain)
                if (rampStep < seconds) handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(task, 1000)
    }

    private fun vibrate() {
        val vib = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator = vib
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 400, 500), 0))
    }

    private fun releaseAudio() {
        silence?.let { handler.removeCallbacks(it) }
        silence = null
        handler.removeCallbacksAndMessages(null)
        player?.run {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        player = null
        vibrator?.cancel()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        wakeLock?.releaseSafe()
        wakeLock = null
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun PowerManager.WakeLock.releaseSafe() {
        try {
            if (isHeld) release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "PhonePiClock"
        private const val NOTIF_ID = 42
        private var instance: RingingService? = null

        fun stop(context: Context) {
            instance?.handler?.post { instance?.releaseAudio() }
            context.stopService(Intent(context, RingingService::class.java))
        }

        fun ringingNotification(context: Context, label: String, alarmId: String?): Notification {
            val builder = NotificationCompat.Builder(context, ClockApp.CHANNEL_RING)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(label.ifBlank { "Alarm" })
                .setContentText("PhonePi Clock")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
            if (!alarmId.isNullOrBlank()) {
                builder.setFullScreenIntent(AlarmIntents.ring(context, alarmId), true)
                builder.addAction(0, "Snooze", AlarmIntents.snooze(context, alarmId))
                builder.addAction(0, "Stop", AlarmIntents.dismiss(context, alarmId))
            }
            return builder.build()
        }
    }
}
