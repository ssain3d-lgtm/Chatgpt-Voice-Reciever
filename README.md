# ChatGPT Voice Receiver

A hands-free voice front-end for ChatGPT, designed first for Samsung Galaxy / modern Android.

> Working codename: **AURA**

## Product vision

The user should be able to interact with ChatGPT without touching the phone:

```text
"GPT"
  ↓
listening starts
  ↓
natural speech
  ↓
natural endpoint detection
  ↓
automatic submit
  ↓
official ChatGPT app shows the answer
  ↓
"GPT" again for the next turn
```

The normal UX must **not** require saying "전송", "보내", or another artificial send command. Those commands are fallback controls only.

## Design principles

1. **Hands-free first** — wake, dictate, submit, and follow up by voice.
2. **Natural endpointing** — detect that the user has finished speaking instead of relying on a fixed silence timer alone.
3. **Local wake word first** — idle microphone audio should not be continuously uploaded to a cloud service.
4. **System-like UX** — minimal assistant UI, short haptics/sounds, no macro-like full-screen automation.
5. **Bridge isolation** — ChatGPT app automation is replaceable. Wake word, STT, endpoint detection, and session logic must not depend on ChatGPT UI selectors.
6. **Device-first MVP** — Samsung Galaxy sideloaded APK stability is more important than Play Store distribution for v0.x.
7. **Fail visibly** — selector/STT/wake errors must be diagnosable; never silently drop a question.

## Authoritative design documents

- [Product specification](docs/PRODUCT_SPEC.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Android constraints](docs/ANDROID_CONSTRAINTS.md)
- [Natural endpoint engine](docs/ENDPOINT_ENGINE.md)
- [ChatGPT bridge](docs/CHATGPT_BRIDGE.md)
- [Security & privacy](docs/SECURITY_PRIVACY.md)
- [MVP plan](docs/MVP_PLAN.md)
- [Risk register](docs/RISK_REGISTER.md)

## Recommended MVP

**Android native / Kotlin**

- Assistant role: `VoiceInteractionService`
- Wake word: local engine behind `WakeWordEngine` interface (Porcupine is the initial candidate)
- STT: `SpeechRecognizer`, preferring on-device recognition when available
- Endpointing: VAD + transcript stability + Korean completion heuristics + adaptive silence
- ChatGPT delivery: isolated `ChatBridge`; v0.1 experimental implementation may use user-enabled accessibility automation for the official ChatGPT app
- UI: `VoiceInteractionSession` first; overlay only when truly necessary
- Persistence: DataStore
- Logging: structured debug events; transcript logging off by default

## Project state

**Design phase. No production-ready implementation yet.**

The first engineering milestone is to prove, on a real Galaxy device:

```text
wake word
→ STT
→ endpoint confirmed
→ ChatGPT foreground/open
→ question injected
→ send triggered
→ return to idle
```

## Important limitation

The official ChatGPT Android app does not expose a documented public automation contract for arbitrary third-party text injection. Therefore the app-bridge path must be treated as **best-effort and replaceable**, not as the core architecture.

## Reference sources

- Android VoiceInteractionService: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
- Android RoleManager / ROLE_ASSISTANT: https://developer.android.com/reference/android/app/role/RoleManager
- Android SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android background microphone / FGS restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android AccessibilityService: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Android audio input sharing: https://developer.android.com/media/platform/sharing-audio-input
- Picovoice Porcupine Android: https://picovoice.ai/docs/quick-start/porcupine-android/
