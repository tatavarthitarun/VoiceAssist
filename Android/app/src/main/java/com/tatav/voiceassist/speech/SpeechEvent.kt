package com.tatav.voiceassist.speech

sealed class SpeechEvent {
    data class Transcript(val text: String) : SpeechEvent()
    data object ListeningStarted : SpeechEvent()
    data object ListeningDone : SpeechEvent()
    data object SpeakingDone : SpeechEvent()
    data class Error(val message: String) : SpeechEvent()
}
