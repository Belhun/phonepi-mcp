package dev.phonepi.companion.findphone

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.phonepi.companion.R
import dev.phonepi.companion.ui.FindPhoneDismissActivity
import dev.phonepi.companion.util.NotificationHelper

class FindPhoneService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlert(intent.getStringExtra(EXTRA_SESSION_ID))
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val loop = intent.getBooleanExtra(EXTRA_LOOP, true)
                val showDismissUi = intent.getBooleanExtra(EXTRA_SHOW_DISMISS_UI, true)
                startAlert(id, loop, showDismissUi)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startAlert(sessionId: String, loop: Boolean, showDismissUi: Boolean) {
        stopPlayback()
        this.sessionId = sessionId
        FindPhoneSession.markStarted(sessionId)

        acquireWakeLock()
        startVibration()
        startForegroundNotification(sessionId)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@FindPhoneService, uri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            isLooping = loop
            prepare()
            start()
        }

        if (showDismissUi) {
            startActivity(
                FindPhoneDismissActivity.intent(this, sessionId).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    private fun startForegroundNotification(sessionId: String) {
        NotificationHelper.ensureChannels(this)
        val stopIntent = Intent(this, FindPhoneService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val stopPending = PendingIntent.getService(
            this,
            sessionId.hashCode(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FIND_PHONE)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.find_phone_notification_title))
            .setContentText(getString(R.string.find_phone_notification_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(0, getString(R.string.find_phone_stop), stopPending)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonepi:findphone").apply {
            acquire(MAX_ALERT_MS)
        }
    }

    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 500, 500), 0)
        }
    }

    private fun stopVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        } ?: return
        vibrator.cancel()
    }

    private fun stopAlert(requestedSessionId: String?) {
        if (!FindPhoneSession.isActive(requestedSessionId)) {
            stopSelf()
            return
        }
        FindPhoneSession.markStopped(requestedSessionId ?: sessionId)
        stopPlayback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPlayback() {
        stopVibration()
        mediaPlayer?.run {
            try {
                if (isPlaying) stop()
            } catch (_: Exception) {
            }
            release()
        }
        mediaPlayer = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        stopPlayback()
        FindPhoneSession.markStopped(sessionId)
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4100
        private const val MAX_ALERT_MS = 10 * 60 * 1000L
        private const val ACTION_START = "dev.phonepi.companion.findphone.START"
        private const val ACTION_STOP = "dev.phonepi.companion.findphone.STOP"
        const val EXTRA_SESSION_ID = "sessionId"
        private const val EXTRA_LOOP = "loop"
        private const val EXTRA_SHOW_DISMISS_UI = "showDismissUI"

        fun start(context: Context, sessionId: String, loop: Boolean, showDismissUi: Boolean) {
            val intent = Intent(context, FindPhoneService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_LOOP, loop)
                putExtra(EXTRA_SHOW_DISMISS_UI, showDismissUi)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context, sessionId: String?) {
            val intent = Intent(context, FindPhoneService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startService(intent)
        }
    }
}
