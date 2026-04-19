# VoiceAssist

AI-powered voice assistant for visually impaired Android users. Control your phone entirely through voice — no screen interaction required.

## What It Does

VoiceAssist runs as an Android accessibility service in the background. Double-tap the volume up button to activate, speak your command, and it handles the rest.

**Currently working (M0–M2):**

- 🎤 Voice activation via double-tap volume up
- 🧠 Natural language intent parsing (regex-based)
- 👤 Fuzzy contact resolution from your address book
- 💬 Send WhatsApp messages — "Send WhatsApp message to Amma saying I'll be late"
- 📞 Make WhatsApp calls — "WhatsApp call Amma"
- 📲 Answer incoming WhatsApp calls — auto-detects, announces caller, swipe-to-answer via voice command
- ✅ Confirmation flow with cancel/repeat support
- 🔊 Full TTS feedback for every action

## Architecture

```
Voice Command → STT → IntentParser → ContactResolver → Confirmation → ActionExecutor → WhatsAppHandler
```

- **Trigger Layer** — KeyEventDetector (double-tap volume up)
- **Speech Layer** — Android SpeechRecognizer + TextToSpeech
- **Processing Layer** — IntentParser (regex), ContactResolver (fuzzy match)
- **Execution Layer** — ActionExecutor → WhatsAppHandler (deep links + accessibility automation)

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- MVVM + Hilt DI
- Coroutines + Flow
- Android AccessibilityService
- Android SpeechRecognizer / TTS

## Project Structure

```
com.tatav.voiceassist/
├── action/          # ActionExecutor, WhatsAppHandler, AccessibilityBridge
├── contacts/        # ContactResolver (fuzzy matching)
├── data/            # SettingsRepository (DataStore)
├── di/              # Hilt AppModule
├── intent/          # IntentParser, VoiceIntent
├── service/         # VoiceAssistService, ConversationManager, KeyEventDetector
├── speech/          # SpeechManager, SpeechEvent
└── ui/              # Compose screens (Splash, Setup, Home, Settings)
```

## Roadmap

| Milestone | Status | Description |
|-----------|--------|-------------|
| M0 | ✅ Done | Project setup, accessibility service, STT/TTS pipeline |
| M1 | ✅ Done | Intent engine — parse commands into structured intents |
| M2 | ✅ Done | WhatsApp automation — send message, make call, answer call |
| M3 | 🔲 Next | Phone call handling — caller ID, pick up, reject |
| M4 | 🔲 | SMS — read and send |
| M5 | 🔲 | Notifications — read, dismiss, quick-reply |
| M6 | 🔲 | UPI payments — GPay send money |
| M7 | 🔲 | Polish — error handling, battery optimization, beta |

## Requirements

- Android 13+ (API 33)
- Accessibility service permission
- Microphone permission
- Contacts permission

## Setup

1. Clone and build in Android Studio
2. Install on device
3. Grant permissions in the setup wizard
4. Enable VoiceAssist accessibility service in Settings → Accessibility
5. Double-tap volume up to start talking

## How WhatsApp Automation Works

- **Send message**: Opens chat via `wa.me` deep link with pre-filled text → accessibility clicks the send button
- **Make call**: Uses ContactsContract MIME type intent for saved contacts, deep link + accessibility fallback for unsaved
- **Answer call**: Auto-detects incoming WhatsApp VoIP screen → announces caller via TTS → listens for "pick up" → dispatches swipe gesture to answer

## License

MIT
