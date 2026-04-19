package com.tatav.voiceassist.intent

data class VoiceIntent(
    val action: Action,
    val contactName: String? = null,
    val contactNumber: String? = null,
    val message: String? = null,
    val rawTranscript: String = ""
) {
    enum class Action {
        SEND_WHATSAPP_MESSAGE,
        CALL_WHATSAPP,
        MAKE_CALL,
        PICK_UP_CALL,
        REJECT_CALL,
        READ_NOTIFICATIONS,
        UNKNOWN
    }

    fun toConfirmationText(): String = when (action) {
        Action.SEND_WHATSAPP_MESSAGE -> "Sending WhatsApp message to $contactName: $message"
        Action.CALL_WHATSAPP -> "Calling $contactName on WhatsApp"
        Action.MAKE_CALL -> "Calling $contactName"
        Action.PICK_UP_CALL -> "Picking up the call"
        Action.REJECT_CALL -> "Rejecting the call"
        Action.READ_NOTIFICATIONS -> "Reading your notifications"
        Action.UNKNOWN -> "I didn't understand that"
    }
}
