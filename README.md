# ChatGPT Voice Receiver

A hands-free voice front-end for ChatGPT, designed first for Samsung Galaxy / modern Android.

> Working codename: **AURA**

## Product vision

The user should be able to interact with ChatGPT without touching the phone:

```text
"헤이 지피티"
  ↓
Wake Word detected → short feedback → listening starts
  ↓
natural speech
  ↓
Natural Endpoint detection (the app decides the user has finished)
  ↓
automatic submit — no "send" command required
  ↓
official ChatGPT app shows the answer
  ↓
"헤이 지피티" again for the next turn
```

The normal UX must **not** require saying "전송", "보내", or another artificial send command. Those commands are fallback controls only.

## Design principles

1. **Hands-free first** — wake, dictate, submit by voice.
2. **Natural endpointing** — detect that the user has finished speaking instead of relying on a fixed silence timer alone. False endpoints (cutting the user off mid-thought) are treated as the worse failure.
3. **Local wake word first** — idle microphone audio never leaves the device.
4. **System-like UX** — minimal assistant UI, short haptics/sounds, no macro-like full-screen automation.
5. **Bridge isolation** — ChatGPT app automation is replaceable. Wake word, STT, endpoint detection, and session logic must not depend on ChatGPT UI selectors.
6. **Device-first MVP** — Samsung Galaxy sideloaded APK stability is more important than Play Store distribution for v0.x.
7. **Fail visibly, never twice** — selector/STT/wake errors must be diagnosable; never silently drop a question, and never automatically resend after an ambiguous result.

## Authoritative design documents

The documents under `docs/` are the implementation baseline. [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) wins on conflict; anything tagged `DEVICE_TEST_REQUIRED` is updated from real-device results.

| Document | Role |
|---|---|
| [Product specification](docs/PRODUCT_SPEC.md) | Goals, core UX, non-goals, fallback commands, latency targets, known platform limitations, MVP success criteria |
| [Architecture](docs/ARCHITECTURE.md) | **Authoritative** component/data/audio flow, state machine, concurrency, TurnId, replacement interfaces, invariants |
| [Android constraints](docs/ANDROID_CONSTRAINTS.md) | What Android / One UI allows, tagged `CONFIRMED` / `DEVICE_TEST_REQUIRED` / `OPEN_QUESTION` with sources |
| [Natural endpoint engine](docs/ENDPOINT_ENGINE.md) | 3-layer EndpointDetector, Korean EndingClassifier, grace window, tunable initial values, evaluation |
| [ChatGPT bridge](docs/CHATGPT_BRIDGE.md) | ChatBridge interface; Plan A (Accessibility, experimental), Plan B (Share Intent), Plan C, future official bridge |
| [Security & privacy](docs/SECURITY_PRIVACY.md) | Local wake processing, retention, logging, Accessibility scope, clipboard, AccessKey, threat model |
| [MVP plan](docs/MVP_PLAN.md) | Galaxy Technical Spike → v0.1 scope → exclusions → v0.2 candidates |
| [Risk register](docs/RISK_REGISTER.md) | Critical/High/Medium risks with mitigation, fallback, validation |
| [Galaxy validation](docs/GALAXY_VALIDATION.md) | Real-device test plan (GV-01 … GV-22) with result slots |
| [Spike test guide](docs/SPIKE_TEST_GUIDE.md) | Step-by-step Galaxy procedure for the Phase 1 Technical Spike, and its Go/No-Go |
| [Decisions](docs/DECISIONS.md) | ADR-001 … ADR-015 |
| [Architecture review](docs/ARCHITECTURE_REVIEW.md) | Historical record of the external review that shaped the above (not authoritative) |

## Architecture summary (v0.1)

**Android native / Kotlin**

- **Assistant foundation:** `VoiceInteractionService` + `VoiceInteractionSessionService`; the app becomes the device's default assistant (`ROLE_ASSISTANT`).
- **Audio ownership:** one `AudioCaptureService` (foreground service, `microphone` type) owns a single `AudioRecord`; wake word, VAD, and the speech pipeline consume a ring buffer with pre-roll. Microphone handoff to `SpeechRecognizer` prefers `EXTRA_AUDIO_SOURCE` piping (API 33) and falls back to release-then-listen — recognizer support is `DEVICE_TEST_REQUIRED`.
- **Wake Word:** default **"헤이 지피티"** (optionally "Hey GPT"), local engine behind a `WakeWordEngine` interface. Porcupine is the initial candidate; the architecture does not depend on it. Licensing terms must be verified against current Picovoice terms.
- **STT:** Android `SpeechRecognizer`, on-device when `checkRecognitionSupport("ko-KR")` allows. STT final results are *segments*; they are not the send signal.
- **Natural Endpoint:** `EndpointDetector` (pure Kotlin) — VAD trailing silence + Korean ending-class classification + transcript stability, with a grace window. All thresholds (including the 3000 ms hard cap) are initial tunable values, not product constants.
- **Session control:** `SessionStateMachine` as a single serialized actor; every event carries a `TurnId`; stale events are dropped.
- **ChatGPT delivery:** isolated `ChatBridge`. Plan A is an Accessibility-based ChatGPT App Bridge — **`EXPERIMENTAL` / `DEVICE_TEST_REQUIRED` / `HIGH MAINTENANCE RISK`**, not "supported" until validated on a Galaxy. Plan B is a Share Intent fallback (same-conversation preservation not guaranteed). A future official OpenAI integration can replace Plan A behind the same interface.
- **UI:** `VoiceInteractionSession` UI; **`SYSTEM_ALERT_WINDOW` overlay is not part of the MVP.**
- **Follow-up mode:** excluded from v0.1 (v0.2 experimental, default OFF).
- **Persistence:** DataStore. **Logging:** in-memory structured debug events; transcript logging off by default.

## Project state

**Phase 1 — Galaxy Technical Spike.** This is *not* the v0.1 app, and it is deliberately
not trying to be: it is the smallest thing that can answer three questions on a real
Samsung Galaxy before thousands of lines get built on an unverified Android assumption.

```text
S-1  VoiceInteractionService + screen-off wake feasibility
S-2  AudioRecord → SpeechRecognizer pipeline feasibility
S-3  Official ChatGPT app Accessibility injection/send feasibility
```

Full v0.1 implementation starts only after all three pass ([MVP plan](docs/MVP_PLAN.md) §1),
and S-3 has an explicit NO-GO branch ([ChatGPT bridge](docs/CHATGPT_BRIDGE.md) §10).

### What exists

```text
core/   pure Kotlin/JVM, no Android dependency — TurnId, TurnGate (INV-3),
        SendGuard (INV-5), ChatBridge result types, WakeWordEngine / SpeechEngine
        contracts, DebugLog ring buffer, tunable SpikeConfig. Unit-tested.
app/    Android debug APK: assistant components (VIS / session / RecognitionService
        stub), AudioCaptureService (FGS microphone, the single AudioRecord),
        Porcupine behind WakeWordEngine, Mode P / Mode H speech probe, ChatGPT
        accessibility node inspector + injection/send probe, share-intent probe,
        and a debug dashboard that runs each spike independently.
```

**Not built yet** (v0.1, after the spike passes): the session state machine, the
Natural Endpoint engine and Korean ending classifier, the command parser, a VAD
implementation, follow-up mode, settings, onboarding, and `windows/`.

Build and run it: [docs/SPIKE_TEST_GUIDE.md](docs/SPIKE_TEST_GUIDE.md).

```bash
./gradlew :core:test        # pure Kotlin — runs without the Android SDK
./gradlew assembleDebug     # needs the Android SDK
# → app/build/outputs/apk/debug/app-debug.apk
```

No real-device results have been recorded yet. Every `GV-*` Result in
[Galaxy validation](docs/GALAXY_VALIDATION.md) is still `NOT TESTED`, and nothing in
these documents may be marked `PASS` until it has actually been run on hardware.

## Known platform limitations

- Third-party apps cannot use the DSP `AlwaysOnHotwordDetector` (system API since Android 12). Always-on wake word therefore keeps the app's microphone open, and **Android's privacy indicator may stay visible while idle**. This is a platform limitation, not a bug.
- The official ChatGPT Android app does not expose a documented public automation contract for third-party text injection. The app-bridge path is **best-effort and replaceable**, not the core architecture.
- Samsung One UI may put unused apps to sleep; onboarding asks the user to add the app to "Never sleeping apps". Behavior is verified on device, not assumed.

## Reference sources

- Android VoiceInteractionService: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
- Android VoiceInteractionSession: https://developer.android.com/reference/android/service/voice/VoiceInteractionSession
- Android RoleManager / ROLE_ASSISTANT: https://developer.android.com/reference/android/app/role/RoleManager
- Android SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android RecognizerIntent (EXTRA_AUDIO_SOURCE, EXTRA_SEGMENTED_SESSION): https://developer.android.com/reference/android/speech/RecognizerIntent
- Android background microphone / FGS restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android AccessibilityService: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Android audio input sharing: https://developer.android.com/media/platform/sharing-audio-input
- AOSP Android 12 release notes (AlwaysOnHotwordDetector @SystemApi): https://source.android.com/docs/whatsnew/android-12-release
- Samsung app management (sleeping apps): https://developer.samsung.com/mobile/app-management.html
- Picovoice Porcupine: https://picovoice.ai/docs/porcupine/
