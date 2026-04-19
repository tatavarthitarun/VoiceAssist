package com.tatav.voiceassist.service

import android.util.Log
import com.tatav.voiceassist.action.ActionExecutor
import com.tatav.voiceassist.contacts.ContactResolver
import com.tatav.voiceassist.intent.IntentParser
import com.tatav.voiceassist.intent.VoiceIntent
import com.tatav.voiceassist.speech.SpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationManager @Inject constructor(
    private val intentParser: IntentParser,
    private val contactResolver: ContactResolver,
    private val speechManager: SpeechManager,
    private val actionExecutor: ActionExecutor,
) {
    companion object {
        private const val TAG = "ConversationManager"
        private const val CONFIRMATION_TIMEOUT_MS = 10_000L
        private const val INCOMING_CALL_TIMEOUT_MS = 10_000L

        private val CANCEL_WORDS = setOf("cancel", "stop", "never mind", "nevermind")
        private val REPEAT_WORDS = setOf("repeat", "what", "say again", "help")
        private val PICK_UP_WORDS = setOf("pick up", "answer", "accept", "yes")
        private val REJECT_WORDS = setOf("reject", "decline", "ignore", "no", "hang up")
    }

    private val _state = MutableStateFlow(ServiceState.IDLE)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    @Volatile private var pendingIntent: VoiceIntent? = null
    private var confirmationTimeoutJob: Job? = null
    @Volatile private var lastPrompt: String = ""
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var incomingCallMode: Boolean = false
    @Volatile private var incomingCallerName: String? = null
    private var incomingCallTimeoutJob: Job? = null

    fun attachScope(coroutineScope: CoroutineScope) {
        scope = coroutineScope
    }

    /**
     * Called when VoiceAssistService detects an incoming WhatsApp VoIP call.
     * Announces the caller, starts listening for pick-up/reject keywords,
     * and bypasses the normal parse → confirm flow entirely.
     */
    fun handleIncomingCall(callerName: String) {
        if (_state.value in listOf(ServiceState.EXECUTING, ServiceState.PROCESSING)) {
            Log.d(TAG, "Ignoring incoming call detection — currently in ${_state.value}")
            return
        }
        Log.d(TAG, "Incoming call detected from: $callerName")
        incomingCallMode = true
        incomingCallerName = callerName
        _state.value = ServiceState.LISTENING
        speechManager.vibrate()

        val s = scope
        if (s == null) {
            Log.w(TAG, "Scope not attached!")
            return
        }
        s.launch {
            val prompt = "Incoming WhatsApp call from $callerName. Say pick up or reject."
            lastPrompt = prompt
            speechManager.speakAndListen(prompt)
        }
        startIncomingCallTimeout()
    }

    private fun startIncomingCallTimeout() {
        incomingCallTimeoutJob?.cancel()
        val s = scope ?: return
        incomingCallTimeoutJob = s.launch {
            delay(INCOMING_CALL_TIMEOUT_MS)
            if (incomingCallMode && _state.value == ServiceState.LISTENING) {
                Log.d(TAG, "Incoming call response timed out — re-announcing")
                val name = incomingCallerName ?: "unknown"
                val prompt = "Incoming WhatsApp call from $name. Say pick up or reject."
                lastPrompt = prompt
                speechManager.speakAndListen(prompt)
                // Restart timeout for one more attempt
                startIncomingCallTimeout()
            }
        }
    }

    private fun clearIncomingCallState() {
        incomingCallMode = false
        incomingCallerName = null
        incomingCallTimeoutJob?.cancel()
        incomingCallTimeoutJob = null
    }

    suspend fun handleTranscript(text: String) {
        val lower = text.trim().lowercase()

        // ── Incoming call fast-path: keyword matching, no parse/confirm ──
        if (incomingCallMode && _state.value == ServiceState.LISTENING) {
            incomingCallTimeoutJob?.cancel()
            when {
                matchesKeyword(lower, PICK_UP_WORDS) -> {
                    Log.d(TAG, "Incoming call: pick-up keyword detected: '$text'")
                    _state.value = ServiceState.EXECUTING
                    clearIncomingCallState()
                    try {
                        val result = actionExecutor.execute(
                            VoiceIntent(action = VoiceIntent.Action.PICK_UP_CALL, rawTranscript = text)
                        )
                        speechManager.speak(result.message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to answer call", e)
                        speechManager.speak("Something went wrong answering the call.")
                    }
                    _state.value = ServiceState.IDLE
                    return
                }
                matchesKeyword(lower, REJECT_WORDS) -> {
                    Log.d(TAG, "Incoming call: reject keyword detected: '$text'")
                    _state.value = ServiceState.EXECUTING
                    clearIncomingCallState()
                    try {
                        val result = actionExecutor.execute(
                            VoiceIntent(action = VoiceIntent.Action.REJECT_CALL, rawTranscript = text)
                        )
                        speechManager.speak(result.message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reject call", e)
                        speechManager.speak("Something went wrong rejecting the call.")
                    }
                    _state.value = ServiceState.IDLE
                    return
                }
                matchesKeyword(lower, CANCEL_WORDS) -> {
                    Log.d(TAG, "Incoming call: cancel keyword detected: '$text'")
                    clearIncomingCallState()
                    speechManager.speak("Cancelled.")
                    _state.value = ServiceState.IDLE
                    return
                }
                else -> {
                    // Didn't understand — re-prompt
                    val name = incomingCallerName ?: "unknown"
                    val prompt = "Say pick up to answer, or reject to decline the call from $name."
                    lastPrompt = prompt
                    speechManager.speakAndListen(prompt)
                    startIncomingCallTimeout()
                    return
                }
            }
        }

        // Cancel from any active state (#2)
        if (_state.value != ServiceState.IDLE && matchesKeyword(lower, CANCEL_WORDS)) {
            Log.d(TAG, "Cancel keyword detected: '$text'")
            confirmationTimeoutJob?.cancel()
            pendingIntent = null
            speechManager.speak("Cancelled.")
            _state.value = ServiceState.IDLE
            return
        }

        // Repeat/help during CONFIRMING or LISTENING (#3)
        if (_state.value in listOf(ServiceState.CONFIRMING, ServiceState.LISTENING)
            && matchesKeyword(lower, REPEAT_WORDS)
        ) {
            Log.d(TAG, "Repeat keyword detected: '$text'")
            if (lastPrompt.isNotBlank()) {
                speechManager.speakAndListen(lastPrompt)
            } else {
                speechManager.speakAndListen("I have nothing to repeat.")
            }
            return
        }

        when (_state.value) {
            ServiceState.LISTENING -> onListeningTranscript(text)
            ServiceState.CONFIRMING -> handleConfirmation(text)
            else -> {}
        }
    }

    fun startListening() {
        Log.d(TAG, "Starting listening flow")
        _state.value = ServiceState.LISTENING
        speechManager.vibrate()
        val s = scope
        if (s == null) {
            Log.w(TAG, "Scope not attached!")
            return
        }
        s.launch {
            speechManager.speak("Listening")
            speechManager.startListening()
        }
    }

    fun relistenForConfirmation() {
        speechManager.vibrate()
        val s = scope
        if (s == null) {
            Log.w(TAG, "Scope not attached!")
            return
        }
        s.launch { speechManager.startListening() }
    }

    fun reset() {
        confirmationTimeoutJob?.cancel()
        pendingIntent = null
        clearIncomingCallState()
        _state.value = ServiceState.IDLE
    }

    private suspend fun onListeningTranscript(text: String) {
        val pending = pendingIntent
        when {
            // Waiting for message body
            pending != null && pending.action == VoiceIntent.Action.SEND_WHATSAPP_MESSAGE
                    && pending.message == null && pending.contactNumber != null -> {
                pendingIntent = pending.copy(message = text)
                _state.value = ServiceState.CONFIRMING
                startConfirmationTimeout()
                val prompt = "${pendingIntent!!.toConfirmationText()}. Say yes or cancel."
                lastPrompt = prompt
                speechManager.speakAndListen(prompt)
            }
            // Disambiguation: user clarified contact name
            pending != null && pending.contactNumber == null -> {
                pendingIntent = null
                val clarified = pending.copy(contactName = text.trim())
                processCommand(clarified)
            }
            // Normal new command
            else -> processCommand(text)
        }
    }

    private suspend fun processCommand(transcript: String) {
        _state.value = ServiceState.PROCESSING
        val intent = intentParser.parse(transcript)
        // Intent logging (#5)
        Log.d(TAG, "Parsed intent: action=${intent.action}, contact=${intent.contactName}, message=${intent.message}")
        processCommand(intent)
    }

    private suspend fun processCommand(intent: VoiceIntent) {
        _state.value = ServiceState.PROCESSING

        if (intent.action == VoiceIntent.Action.UNKNOWN) {
            speechManager.speak("I don't understand that command.")
            _state.value = ServiceState.IDLE
            return
        }

        // ── Time-sensitive actions: skip confirmation, execute immediately ──
        if (intent.action == VoiceIntent.Action.PICK_UP_CALL || intent.action == VoiceIntent.Action.REJECT_CALL) {
            Log.d(TAG, "Time-sensitive action ${intent.action} — skipping confirmation")
            _state.value = ServiceState.EXECUTING
            try {
                val result = actionExecutor.execute(intent)
                speechManager.speak(result.message)
            } catch (e: Exception) {
                Log.e(TAG, "Execution failed for ${intent.action}", e)
                speechManager.speak("Something went wrong. ${e.message ?: ""}")
            }
            _state.value = ServiceState.IDLE
            return
        }

        // Actions that don't need contact resolution
        if (intent.contactName == null) {
            pendingIntent = intent
            _state.value = ServiceState.CONFIRMING
            startConfirmationTimeout()
            val prompt = "${intent.toConfirmationText()}. Say yes or cancel."
            lastPrompt = prompt
            speechManager.speakAndListen(prompt)
            return
        }

        // WhatsApp message without body — ask for it
        if (intent.action == VoiceIntent.Action.SEND_WHATSAPP_MESSAGE && intent.message == null) {
            speechManager.speak("Looking up ${intent.contactName}.")
            val result = contactResolver.resolve(intent.contactName)
            // Contact resolution logging (#5)
            Log.d(TAG, "Contact resolution for '${intent.contactName}': $result")
            when (result) {
                is ContactResolver.Result.Found -> {
                    val resolved = intent.copy(contactName = result.name, contactNumber = result.number)
                    pendingIntent = resolved
                    _state.value = ServiceState.LISTENING
                    val prompt = "What should I say to ${result.name}?"
                    lastPrompt = prompt
                    speechManager.speakAndListen(prompt)
                }
                is ContactResolver.Result.Ambiguous -> {
                    val names = result.matches.joinToString(", ") { it.displayName }
                    pendingIntent = intent
                    _state.value = ServiceState.LISTENING
                    val prompt = "I found multiple contacts: $names. Which one?"
                    lastPrompt = prompt
                    speechManager.speakAndListen(prompt)
                }
                is ContactResolver.Result.NotFound -> {
                    speechManager.speak("I couldn't find ${intent.contactName} in your contacts.")
                    _state.value = ServiceState.IDLE
                }
            }
            return
        }

        // Resolve contact
        speechManager.speak("Looking up ${intent.contactName}.")
        val result = contactResolver.resolve(intent.contactName)
        Log.d(TAG, "Contact resolution for '${intent.contactName}': $result")
        when (result) {
            is ContactResolver.Result.Found -> {
                val resolved = intent.copy(contactName = result.name, contactNumber = result.number)
                pendingIntent = resolved
                _state.value = ServiceState.CONFIRMING
                startConfirmationTimeout()
                val prompt = "${resolved.toConfirmationText()}. Say yes or cancel."
                lastPrompt = prompt
                speechManager.speakAndListen(prompt)
            }
            is ContactResolver.Result.Ambiguous -> {
                val names = result.matches.joinToString(", ") { it.displayName }
                pendingIntent = intent
                _state.value = ServiceState.LISTENING
                val prompt = "I found multiple contacts: $names. Which one?"
                lastPrompt = prompt
                speechManager.speakAndListen(prompt)
            }
            is ContactResolver.Result.NotFound -> {
                speechManager.speak("I couldn't find ${intent.contactName} in your contacts.")
                _state.value = ServiceState.IDLE
            }
        }
    }

    private suspend fun handleConfirmation(text: String) {
        confirmationTimeoutJob?.cancel()
        val lower = text.lowercase()
        when {
            lower.containsAny("yes", "yeah", "yep", "sure", "okay", "confirm", "send", "do it", "go ahead") -> {
                _state.value = ServiceState.EXECUTING
                Log.d(TAG, "Confirmed intent: ${pendingIntent?.action}")
                val intent = pendingIntent!!
                pendingIntent = null
                try {
                    val result = actionExecutor.execute(intent)
                    speechManager.speak(result.message)
                } catch (e: Exception) {
                    Log.e(TAG, "Execution failed", e)
                    speechManager.speak("Something went wrong. ${e.message ?: ""}")
                }
                _state.value = ServiceState.IDLE
            }
            lower.containsAny("cancel", "no", "nah", "nope", "stop", "never mind", "forget it") -> {
                speechManager.speak("Cancelled.")
                pendingIntent = null
                _state.value = ServiceState.IDLE
            }
            else -> {
                val prompt = "Say yes to confirm or cancel."
                lastPrompt = prompt
                speechManager.speakAndListen(prompt)
            }
        }
    }

    private fun matchesKeyword(text: String, keywords: Set<String>): Boolean {
        return keywords.any { keyword ->
            Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    }

    private fun startConfirmationTimeout() {
        confirmationTimeoutJob?.cancel()
        val s = scope
        if (s == null) {
            Log.w(TAG, "Scope not attached!")
            return
        }
        confirmationTimeoutJob = s.launch {
            delay(CONFIRMATION_TIMEOUT_MS)
            if (_state.value == ServiceState.CONFIRMING) {
                Log.d(TAG, "Confirmation timed out")
                speechManager.speak("Timed out. Cancelled.")
                pendingIntent = null
                _state.value = ServiceState.IDLE
            }
        }
    }
}

private fun String.containsAny(vararg words: String) = words.any { this.contains(it) }
