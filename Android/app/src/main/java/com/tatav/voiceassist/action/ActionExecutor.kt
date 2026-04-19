package com.tatav.voiceassist.action

import android.util.Log
import com.tatav.voiceassist.intent.VoiceIntent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionExecutor @Inject constructor(
    private val whatsAppHandler: WhatsAppHandler
) {
    companion object {
        private const val TAG = "ActionExecutor"
    }

    data class Result(val success: Boolean, val message: String)

    suspend fun execute(intent: VoiceIntent): Result {
        Log.d(TAG, "Executing: action=${intent.action}, contact=${intent.contactName}")

        return when (intent.action) {
            VoiceIntent.Action.SEND_WHATSAPP_MESSAGE -> {
                val number = intent.contactNumber
                val message = intent.message
                if (number == null || message == null) {
                    Result(false, "Missing phone number or message.")
                } else {
                    whatsAppHandler.sendMessage(number, message)
                }
            }

            VoiceIntent.Action.CALL_WHATSAPP -> {
                val number = intent.contactNumber
                if (number == null) {
                    Result(false, "Missing phone number.")
                } else {
                    whatsAppHandler.makeCall(number)
                }
            }

            VoiceIntent.Action.PICK_UP_CALL -> {
                whatsAppHandler.answerCall()
            }

            VoiceIntent.Action.MAKE_CALL ->
                Result(false, "Phone calls are not supported yet.")

            VoiceIntent.Action.REJECT_CALL -> {
                whatsAppHandler.rejectCall()
            }

            VoiceIntent.Action.READ_NOTIFICATIONS ->
                Result(false, "Reading notifications is not supported yet.")

            VoiceIntent.Action.UNKNOWN ->
                Result(false, "Unknown action.")
        }
    }
}
