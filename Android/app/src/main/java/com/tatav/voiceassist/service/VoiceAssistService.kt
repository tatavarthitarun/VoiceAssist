package com.tatav.voiceassist.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.getSystemService
import com.tatav.voiceassist.action.AccessibilityBridge
import com.tatav.voiceassist.action.WhatsAppHandler
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
class VoiceAssistService : AccessibilityService(), AccessibilityBridge {

    companion object {
        private const val TAG = "VoiceAssistService"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        private val _serviceActive = MutableStateFlow(false)
        val serviceActive: StateFlow<Boolean> = _serviceActive.asStateFlow()
    }

    @Inject lateinit var speechManager: SpeechManager
    @Inject lateinit var conversationManager: ConversationManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var whatsAppHandler: WhatsAppHandler

    private lateinit var keyDetector: KeyEventDetector
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        _serviceActive.value = true

        // Register this service as the accessibility bridge for WhatsApp automation
        whatsAppHandler.attachBridge(this)

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
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == WHATSAPP_PACKAGE || pkg == WHATSAPP_BUSINESS_PACKAGE) {
                    val className = event.className?.toString() ?: ""
                    Log.d(TAG, "WhatsApp activity: $className")
                    whatsAppHandler.currentWhatsAppActivity = className

                    // Auto-detect incoming WhatsApp VoIP call (full-screen)
                    if ((className.contains("VoipActivity", ignoreCase = true)
                                || className.contains("voipcalling", ignoreCase = true))
                        && conversationManager.state.value == ServiceState.IDLE
                    ) {
                        Log.d(TAG, "Incoming WhatsApp VoIP call screen detected")
                        val callerName = whatsAppHandler.extractCallerName(this) ?: "unknown"
                        Log.d(TAG, "Caller name extracted: $callerName")
                        conversationManager.handleIncomingCall(callerName)
                    }
                }
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if ((pkg == WHATSAPP_PACKAGE || pkg == WHATSAPP_BUSINESS_PACKAGE)
                    && conversationManager.state.value == ServiceState.IDLE
                ) {
                    // Check notification text for incoming call indicators
                    val allText = event.text?.mapNotNull { it?.toString() } ?: emptyList()
                    val combined = allText.joinToString(" ").lowercase()
                    if (combined.contains("incoming voice call")
                        || combined.contains("incoming video call")
                        || combined.contains("incoming call")
                    ) {
                        Log.d(TAG, "Incoming WhatsApp call notification detected: $allText")
                        // Extract caller name: typically the first text element that isn't the call type
                        val callerName = allText.firstOrNull { line ->
                            val lower = line.lowercase()
                            !lower.contains("incoming voice call")
                                    && !lower.contains("incoming video call")
                                    && !lower.contains("incoming call")
                                    && line.isNotBlank()
                        } ?: "unknown"
                        Log.d(TAG, "Caller name from notification: $callerName")
                        conversationManager.handleIncomingCall(callerName)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Heads-up notification popup triggers content changes — detect call buttons
                if (conversationManager.state.value == ServiceState.IDLE) {
                    val pkg = event.packageName?.toString()
                    if (pkg == WHATSAPP_PACKAGE || pkg == WHATSAPP_BUSINESS_PACKAGE) {
                        // Check all windows for call notification elements
                        val hasCallNotification = getAllRootNodes().any { root ->
                            whatsAppHandler.hasIncomingCallNotificationButtons(root)
                        }
                        if (hasCallNotification) {
                            Log.d(TAG, "Incoming WhatsApp call heads-up popup detected via content change")
                            val callerName = getAllRootNodes().firstNotNullOfOrNull { root ->
                                whatsAppHandler.extractCallerNameFromNotification(root)
                            } ?: "unknown"
                            Log.d(TAG, "Caller name from heads-up: $callerName")
                            conversationManager.handleIncomingCall(callerName)
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        conversationManager.reset()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _serviceActive.value = false
        whatsAppHandler.detachBridge()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _serviceActive.value = false
        whatsAppHandler.detachBridge()
        if (::keyDetector.isInitialized) keyDetector.destroy()
        scope.cancel()
        speechManager.destroy()
        super.onDestroy()
    }

    // ── AccessibilityBridge implementation ───────────────────────────────────

    override fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    override fun getAllRootNodes(): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        for (window in windows) {
            window.root?.let { nodes.add(it) }
        }
        return nodes
    }

    override fun doGlobalAction(action: Int): Boolean = performGlobalAction(action)

    override fun dispatchSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        Log.d(TAG, "Dispatching swipe gesture: ($startX,$startY) → ($endX,$endY), duration=${durationMs}ms")
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.d(TAG, "Swipe gesture COMPLETED")
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                Log.w(TAG, "Swipe gesture CANCELLED")
            }
        }, null)
        Log.d(TAG, "dispatchGesture returned: $result")
        return result
    }
}
