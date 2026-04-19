package com.tatav.voiceassist.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.getSystemService
import com.tatav.voiceassist.data.SettingsRepository
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
    }

    @Inject lateinit var speechManager: SpeechManager
    @Inject lateinit var conversationManager: ConversationManager
    @Inject lateinit var settingsRepository: SettingsRepository

    private lateinit var keyDetector: KeyEventDetector
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        _serviceActive.value = true

        conversationManager.attachScope(scope)

        speechManager.initialize()

        scope.launch {
            speechManager.events.collect { handleSpeechEvent(it) }
        }

        // Create and update KeyEventDetector whenever settings change
        scope.launch {
            settingsRepository.settings.collect { settings ->
                keyDetector = KeyEventDetector(
                    thresholdMs = settings.doubleTapSpeedMs.toLong(),
                    onDoubleTap = { onDoubleTapDetected() },
                    onSingleTapFallback = {
                        val audio = getSystemService<AudioManager>()
                        audio?.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                    }
                )
            }
        }

        scope.launch {
            speechManager.speak("VoiceAssist is ready.")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::keyDetector.isInitialized) return false
        return when (conversationManager.state.value) {
            ServiceState.IDLE, ServiceState.CONFIRMING -> keyDetector.handleKeyEvent(event)
            ServiceState.PROCESSING, ServiceState.EXECUTING -> true // consume key events
            else -> false
        }
    }

    private fun onDoubleTapDetected() {
        when (conversationManager.state.value) {
            ServiceState.IDLE -> conversationManager.startListening()
            ServiceState.CONFIRMING -> conversationManager.relistenForConfirmation()
            else -> {}
        }
    }

    private fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Transcript -> {
                Log.d(TAG, "Transcript: ${event.text}")
                scope.launch { conversationManager.handleTranscript(event.text) }
            }
            is SpeechEvent.Error -> {
                Log.w(TAG, "Speech error: ${event.message}")
                scope.launch {
                    speechManager.speak(event.message)
                    conversationManager.reset()
                }
            }
            is SpeechEvent.ListeningStarted -> Log.d(TAG, "Listening started")
            is SpeechEvent.ListeningDone -> Log.d(TAG, "Listening done")
            is SpeechEvent.SpeakingDone -> Log.d(TAG, "Speaking done")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Used in M2+ for WhatsApp/Phone automation
    }

    override fun onInterrupt() {
        conversationManager.reset()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _serviceActive.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _serviceActive.value = false
        if (::keyDetector.isInitialized) keyDetector.destroy()
        scope.cancel()
        speechManager.destroy()
        super.onDestroy()
    }
}
