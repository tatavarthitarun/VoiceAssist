package com.tatav.voiceassist.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.getSystemService
import com.tatav.voiceassist.speech.SpeechEvent
import com.tatav.voiceassist.speech.SpeechManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ServiceState { IDLE, LISTENING, PROCESSING, CONFIRMING, EXECUTING }

@AndroidEntryPoint
class VoiceAssistService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceAssistService"

        private val _serviceActive = MutableStateFlow(false)
        val serviceActive: StateFlow<Boolean> = _serviceActive.asStateFlow()

        private val _state = MutableStateFlow(ServiceState.IDLE)
        val state: StateFlow<ServiceState> = _state.asStateFlow()
    }

    @Inject lateinit var speechManager: SpeechManager

    private lateinit var keyDetector: KeyEventDetector
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        _serviceActive.value = true

        keyDetector = KeyEventDetector(
            onDoubleTap = { onDoubleTapDetected() },
            onSingleTapFallback = {
                // Let system handle normal volume up
                val audio = getSystemService<AudioManager>()
                audio?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        )

        speechManager.initialize()

        scope.launch {
            speechManager.events.collect { handleSpeechEvent(it) }
        }

        scope.launch {
            speechManager.speak("VoiceAssist is ready.")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return if (_state.value == ServiceState.IDLE) {
            keyDetector.handleKeyEvent(event)
        } else {
            false
        }
    }

    private fun onDoubleTapDetected() {
        if (_state.value == ServiceState.IDLE) {
            Log.d(TAG, "Double-tap detected → LISTENING")
            _state.value = ServiceState.LISTENING
            speechManager.startListening()
        }
    }

    private fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Transcript -> {
                Log.d(TAG, "Transcript: ${event.text}")
                // M0: echo back what was heard via TTS
                scope.launch {
                    _state.value = ServiceState.PROCESSING
                    speechManager.speak("You said: ${event.text}")
                    _state.value = ServiceState.IDLE
                }
            }
            is SpeechEvent.Error -> {
                Log.w(TAG, "Speech error: ${event.message}")
                scope.launch {
                    speechManager.speak(event.message)
                    _state.value = ServiceState.IDLE
                }
            }
            is SpeechEvent.ListeningStarted -> {
                Log.d(TAG, "Listening started")
            }
            is SpeechEvent.ListeningDone -> {
                Log.d(TAG, "Listening done")
            }
            is SpeechEvent.SpeakingDone -> {
                Log.d(TAG, "Speaking done")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used in M0
    }

    override fun onInterrupt() {
        _state.value = ServiceState.IDLE
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _serviceActive.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _serviceActive.value = false
        scope.cancel()
        speechManager.destroy()
        super.onDestroy()
    }
}
