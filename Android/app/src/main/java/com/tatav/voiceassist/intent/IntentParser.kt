package com.tatav.voiceassist.intent

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntentParser @Inject constructor() {

    private val patterns: List<Pair<Regex, (MatchResult) -> VoiceIntent>> = listOf(
        // "send whatsapp message to <contact> saying <message>"
        """(?:send|text)\s+(?:a\s+)?(?:whatsapp|what's app)\s+(?:message\s+)?to\s+(.+?)\s+(?:saying|message|that)\s+(.+)"""
            .toRegex(RegexOption.IGNORE_CASE) to { m: MatchResult ->
            VoiceIntent(
                action = VoiceIntent.Action.SEND_WHATSAPP_MESSAGE,
                contactName = m.groupValues[1].trim(),
                message = m.groupValues[2].trim()
            )
        },

        // "send whatsapp message to <contact>" (no message body — service will ask)
        """(?:send|text)\s+(?:a\s+)?(?:whatsapp|what's app)\s+(?:message\s+)?to\s+(.+)"""
            .toRegex(RegexOption.IGNORE_CASE) to { m: MatchResult ->
            VoiceIntent(
                action = VoiceIntent.Action.SEND_WHATSAPP_MESSAGE,
                contactName = m.groupValues[1].trim(),
                message = null
            )
        },

        // "whatsapp call <contact>"
        """(?:whatsapp|what's app)\s+(?:call|ring)\s+(.+)"""
            .toRegex(RegexOption.IGNORE_CASE) to { m: MatchResult ->
            VoiceIntent(
                action = VoiceIntent.Action.CALL_WHATSAPP,
                contactName = m.groupValues[1].trim()
            )
        },

        // "call <contact>" / "dial <contact>" / "ring <contact>" / "phone <contact>"
        """(?:call|dial|ring|phone)\s+(.+)"""
            .toRegex(RegexOption.IGNORE_CASE) to { m: MatchResult ->
            VoiceIntent(
                action = VoiceIntent.Action.MAKE_CALL,
                contactName = m.groupValues[1].trim()
            )
        },

        // "pick up" / "answer" / "accept"
        """(?:pick\s*up|answer|accept)(?:\s+the\s+call)?"""
            .toRegex(RegexOption.IGNORE_CASE) to { _: MatchResult ->
            VoiceIntent(action = VoiceIntent.Action.PICK_UP_CALL)
        },

        // "reject" / "decline" / "hang up" / "ignore"
        """(?:reject|decline|hang\s*up|ignore)(?:\s+the\s+call)?"""
            .toRegex(RegexOption.IGNORE_CASE) to { _: MatchResult ->
            VoiceIntent(action = VoiceIntent.Action.REJECT_CALL)
        },

        // "read notifications" / "check my notifs"
        """(?:read|check)\s+(?:my\s+)?(?:notifications|notifs)"""
            .toRegex(RegexOption.IGNORE_CASE) to { _: MatchResult ->
            VoiceIntent(action = VoiceIntent.Action.READ_NOTIFICATIONS)
        },
    )

    fun parse(transcript: String): VoiceIntent {
        for ((regex, builder) in patterns) {
            regex.find(transcript)?.let {
                return builder(it).copy(rawTranscript = transcript)
            }
        }
        return VoiceIntent(action = VoiceIntent.Action.UNKNOWN, rawTranscript = transcript)
    }
}
