package com.tatav.voiceassist.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class SpeechManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "SpeechManager"
    }

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun initialize() {
        if (recognizer != null) return

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun startListening() {
        val vibrator = context.getSystemService<Vibrator>()
        vibrator?.vibrate(
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        )

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    suspend fun speak(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready, skipping: $text")
            return
        }
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) {
                        _events.tryEmit(SpeechEvent.SpeakingDone)
                        cont.resume(Unit)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) {
                        _events.tryEmit(SpeechEvent.Error("TTS error"))
                        cont.resume(Unit)
                    }
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    suspend fun speakAndListen(text: String) {
        speak(text)
        startListening()
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _events.tryEmit(SpeechEvent.ListeningStarted)
        }

        override fun onResults(results: Bundle?) {
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            _events.tryEmit(SpeechEvent.ListeningDone)
            if (text.isNotBlank()) {
                _events.tryEmit(SpeechEvent.Transcript(text))
            } else {
                _events.tryEmit(SpeechEvent.Error("I didn't catch that."))
            }
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
                SpeechRecognizer.ERROR_AUDIO -> "Audio error."
                SpeechRecognizer.ERROR_NETWORK -> "Network error."
                else -> "Speech recognition error."
            }
            _events.tryEmit(SpeechEvent.Error(msg))
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            _events.tryEmit(SpeechEvent.ListeningDone)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
