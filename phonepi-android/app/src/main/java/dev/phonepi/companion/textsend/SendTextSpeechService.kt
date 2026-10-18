package dev.phonepi.companion.textsend

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Speaks one pending text and listens for "send" or "cancel".
 * Anything else leaves the tap notification in place and sends nothing.
 */
class SendTextSpeechService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var focus: AudioFocusRequest? = null
    private var pendingId: String = ""
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        val record = SendTextStore.find(this, id)
        if (record == null || record.status != "pending" || record.handedToCarrier) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        pendingId = id
        finished = false
        startInForeground(record)
        if (!requestFocus()) {
            finishUnclear("audio focus denied")
            return START_NOT_STICKY
        }
        speak(record)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonFocus()
        super.onDestroy()
    }

    private fun startInForeground(record: SendRecord) {
        val notification = androidx.core.app.NotificationCompat.Builder(this, dev.phonepi.companion.util.NotificationHelper.CHANNEL_SEND_TEXT)
            .setSmallIcon(dev.phonepi.companion.R.drawable.ic_stat_link)
            .setContentTitle("Text to ${record.recipientName}")
            .setContentText("Say send or cancel")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, SPEECH_NOTIF_ID, notification, type)
    }

    private fun requestFocus(): Boolean {
        val audio = getSystemService(AudioManager::class.java) ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focus = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private fun speak(record: SendRecord) {
        val prompt = "Text to ${record.recipientName}: ${record.message}. Say send or cancel."
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                finishUnclear("text to speech failed")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            engine.language = Locale.getDefault()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    main.post { listen() }
                }

                @Deprecated("legacy")
                override fun onError(utteranceId: String?) {
                    main.post { finishUnclear("text to speech failed") }
                }
            })
            val params = Bundle()
            engine.speak(prompt, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE)
        }
    }

    private fun listen() {
        if (finished) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishUnclear("speech recognition unavailable")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        this.recognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                val why = if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    "silence"
                } else {
                    "speech error $error"
                }
                finishUnclear(why)
            }

            override fun onResults(results: Bundle?) {
                val said = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                when (SendTextPolicy.interpretSpoken(said)) {
                    SpokenChoice.SEND -> finishSend()
                    SpokenChoice.CANCEL -> finishCancel()
                    SpokenChoice.UNCLEAR -> finishUnclear("unclear speech")
                }
            }
        })
        val listen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
        recognizer.startListening(listen)
        main.postDelayed({ finishUnclear("timeout") }, 12_000)
    }

    private fun finishSend() {
        if (finished) return
        finished = true
        SendTextController.confirmAsync(applicationContext, pendingId, "spoken")
        shutdown()
    }

    private fun finishCancel() {
        if (finished) return
        finished = true
        SendTextController.cancel(applicationContext, pendingId)
        shutdown()
    }

    private fun finishUnclear(reason: String) {
        if (finished) return
        finished = true
        val record = SendTextStore.find(this, pendingId)
        if (record != null && record.status == "pending") {
            SendTextEvents.emit(
                context = applicationContext,
                outcome = "pending",
                mode = record.mode,
                id = record.id,
                recipientName = record.recipientName,
                number = record.number,
                message = record.message,
                confirmMethod = null,
                error = "$reason. Nothing was sent. The Send notification is still there.",
            )
        }
        shutdown()
    }

    private fun shutdown() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        abandonFocus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val UTTERANCE = "phonepi-send-prompt"
        private const val SPEECH_NOTIF_ID = 41001

        fun start(context: Context, id: String) {
            val intent = Intent(context, SendTextSpeechService::class.java).apply {
                putExtra(EXTRA_ID, id)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SendTextSpeechService::class.java))
        }
    }
}
