# DECISIONS — Architecture Decision Records

- 문서 상태: **Authoritative** (append-only. 결정이 바뀌면 새 ADR을 추가하고 기존 ADR을 Superseded로 표시)
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) · 리뷰 원문: [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md)

Status 값: `Accepted` / `Proposed` / `Superseded by ADR-xxx` / `Deprecated`

---

## ADR-001 Assistant foundation: VoiceInteractionService

**Status:** Accepted (2026-09-18)

**Context:**
3rd-party 앱이 "상시 살아 있는 프로세스 + 백그라운드 마이크 + 잠금 위 UI + 백그라운드에서 앱 실행"을 정책적으로 얻어야 한다. 일반 FGS는 Android 14+에서 백그라운드 시작 시 마이크 접근이 제한되고, 백그라운드 액티비티 시작(BAL)도 차단된다.

**Decision:**
`VoiceInteractionService` + `VoiceInteractionSessionService` + `VoiceInteractionSession`을 기반으로 하고, `AudioCaptureService`(FGS, `microphone` 타입)를 VIS `onReady()`에서 시작한다. `RecognitionService` stub을 함께 선언한다. 사용자는 기본 어시스턴트를 이 앱으로 변경한다(`ROLE_ASSISTANT`).

**Alternatives:**
- FGS 단독: while-in-use 마이크 예외 없음, BAL 예외 없음, 잠금 UI 불가. 기각.
- AccessibilityService 단독: 마이크·UI 용도 아님, 정책 위험. 기각.
- ChatGPT 앱 자체를 어시스턴트로 설정해 그 음성 모드 사용: 별개 UX(실시간 음성), 요구와 다름. 참고만.

**Consequences:**
- Bixby를 대체해야 한다.
- DSP Wake 경로는 여전히 불가(`AlwaysOnHotwordDetector` @SystemApi).
- 이 기반이 실기기에서 실패하면 제품이 성립하지 않는다(fallback 없음).

**Validation:** GV-01, GV-03, GV-05. Spike S-1.

---

## ADR-002 Wake Word: "헤이 지피티", Porcupine behind WakeWordEngine

**Status:** Accepted (2026-09-18)

**Context:**
README 초안의 기본 Wake Word "GPT"는 3음절 약어이며 기술 대화·YouTube·ChatGPT 관련 콘텐츠에서 오탐 가능성이 높다. 한국어를 공식 지원하는 로컬 엔진이 필요하다.

**Decision:**
- 기본 Wake Word는 **"헤이 지피티"**(ko). 후보로 "Hey GPT"(en) 병행 가능. 향후 사용자 지정(모델 파일 교체).
- v0.1 구현 후보는 Porcupine. 단 **아키텍처는 Porcupine에 종속되지 않는다** — `WakeWordEngine` 인터페이스 뒤에 둔다.
- Porcupine 관련 사실 표기:
  - Multiple keyword detection: `SUPPORTED BY SDK` (공식 문서: 다중 키워드 동시 감지)
  - Korean: `SUPPORTED BY SDK` (공식 문서 언어 목록)
  - Licensing / pricing / 무료 플랜 조건 / custom `.ppn` 사용 조건: **`MUST BE VERIFIED AGAINST CURRENT PICOVOICE TERMS`**. "추가 비용 없이"와 같은 표현은 쓰지 않는다.

**Alternatives:**
- "GPT" 단독: 오탐. 기각.
- openWakeWord: 한국어 지원 약함(미검증). sherpa-onnx KWS: 중/영 중심. 교체 후보로 유지.

**Consequences:**
- 사용자는 매 turn "헤이 지피티"를 말해야 한다(v0.1).
- AccessKey 관리 필요([SECURITY_PRIVACY.md](SECURITY_PRIVACY.md) §9).
- 라이선스 조건이 맞지 않으면 엔진 교체(R-13).

**Validation:** GV-02. Picovoice 현행 약관 확인.

---

## ADR-003 Audio ownership: single AudioRecord in AudioCaptureService

**Status:** Accepted (2026-09-18)

**Context:**
Wake Word 엔진과 `SpeechRecognizer`가 각자 마이크를 열면 Android 10+ 오디오 입력 공유 규칙에 따라 한쪽이 무음을 받거나, 닫고 여는 사이 첫 음절이 잘린다. 3rd-party는 DSP hotword 경로가 없으므로 앱이 마이크를 직접 열어야 한다.

**Decision:**
- `AudioCaptureService`(FGS)가 **단일 `AudioRecord`**(16 kHz mono, `VOICE_RECOGNITION`)를 소유한다. `WakeWordEngine`, `VadEngine`, Speech pipeline은 `RingBuffer` 소비자다.
- Wake → STT 전환은 **Mode P**(`RecognizerIntent.EXTRA_AUDIO_SOURCE`, API 33, pre-roll 포함 pipe)를 우선 검토한다. recognizer 지원 여부는 `DEVICE_TEST_REQUIRED`.
- 미지원 기기는 **Mode H**(Wake → `AudioRecord` release → 피드백 → `startListening` → 종료 후 재개).
- Pre-roll ring buffer(300 ms 초기값)를 설계에 포함한다.
- Privacy indicator 상시 표시는 platform limitation으로 수용한다.

**Alternatives:**
- Wake/STT 각자 마이크 오픈: race, 첫 음절 손실. 기각.
- `AlwaysOnHotwordDetector`: @SystemApi. 불가.

**Consequences:**
- 배터리·인디케이터 비용(R-03, R-06).
- Mode P 미지원 시 pre-roll 이점 상실, UX 피드백 타이밍으로 보완.

**Validation:** GV-09, GV-10. Spike S-2.

---

## ADR-004 STT: Android SpeechRecognizer, on-device when available

**Status:** Accepted (2026-09-18)

**Context:**
추가 모델 없이 한국어 품질을 확보해야 한다. `SpeechRecognizer`는 공식적으로 continuous recognition 용도가 아니며, 자체 endpointing으로 세션을 끝낸다.

**Decision:**
- v0.1 기본 `AndroidSpeechRecognizerEngine`(`SpeechEngine` 인터페이스 뒤).
- `createOnDeviceSpeechRecognizer()`(API 31)는 `isOnDeviceRecognitionAvailable()` + `checkRecognitionSupport("ko-KR")`(API 33) 결과가 있을 때만. 없으면 온라인 인식.
- `EXTRA_SEGMENTED_SESSION`(API 33) + Mode P 조합을 시도하고, 미지원 시 Final을 segment로 취급하고 즉시 재시작.
- **STT final은 발화 종료가 아니다.** `SpeechEvent.Final` = segment 결과. 전송 시점은 `EndpointDetector`가 결정한다.

**Alternatives:**
- Whisper 온디바이스: 모델 크기·배터리. v0.3+.
- Picovoice Cheetah: 한국어 스트리밍 품질 미검증.

**Consequences:**
- recognizer 힌트(silence 길이 등)는 무시될 수 있음. 의존하지 않는다.
- 온라인 의존 가능성을 온보딩에 명시.

**Validation:** GV-08, GV-10.

---

## ADR-005 Natural Endpoint: 3-layer EndpointDetector, tunable thresholds

**Status:** Accepted (2026-09-18)

**Context:**
정상 UX에서 "전송" 명령을 요구하지 않는다. 단어 목록 기반 판정은 한국어에서 양면적 어휘("그", "하고", "일단") 때문에 오분류가 잦다. 사용자는 문장 중간에서 자주 생각하므로 false endpoint가 특히 나쁘다.

**Decision:**
- Layer 0 VAD 무음 + Layer 1 한국어 `EndingClassifier`(마지막 어절 어미 형태) + Layer 2 transcript stability의 3층.
- `EndingClass` = FINAL_IMPERATIVE / FINAL_QUESTION / FINAL_DECLARATIVE / CONNECTIVE / FILLER / UNKNOWN.
- 규칙은 외부 데이터(`endpoint/ko/endings.yaml` 검토)로. `EndpointDetector`는 pure Kotlin.
- Endpoint 후보 후 **grace window**(500 ms 초기값). 그 사이 speech 재개 시 LISTENING 복귀.
- 초기값: FINAL 600~800, UNKNOWN 1100, CONNECTIVE 1800, FILLER 2200, hard cap 3000 ms. **모두 `DEFAULT_INITIAL_VALUE` / `DEVICE_TUNABLE` / `NOT A PRODUCT CONSTANT`.** hard cap은 실기기에서 3000 / 4000 / 5000 비교 후 결정.
- Fallback command(`보내`/`전송`/`송부`, `취소`, `다시`)는 `CommandParser`가 마지막 어절에서만 인식.

**Alternatives:**
- 단어 목록 + 고정 silence: 오분류. 기각.
- Cloud LLM 판정: 지연·프라이버시·비용. 기각.
- recognizer final 신호 사용: 구현체 의존, 우리 제어 불가. 기각.

**Consequences:**
- 영문 명사로 끝나는 문장은 대기가 길어진다.
- 단위 테스트용 데이터셋 유지 필요([ENDPOINT_ENGINE.md](ENDPOINT_ENGINE.md) §9).

**Validation:** GV-19.

---

## ADR-006 ChatBridge abstraction

**Status:** Accepted (2026-09-18)

**Context:**
공식 ChatGPT Android 앱은 3rd-party 텍스트 주입을 위한 문서화된 automation contract를 제공하지 않는다. 어떤 전달 방식도 보장이 아니며, OpenAI가 향후 deep link / intent / automation API / integration contract를 제공할 수 있다.

**Decision:**
- `ChatBridge` 인터페이스(`prepare` / `send` / `healthCheck`)와 단계별 결과 타입(`Sent` / `InjectedButNotSent` / `PrepareFailed` / `InjectionFailed` / `SendFailed` / `BridgeUnavailable`)을 `core`에 둔다.
- 구현체: `ChatGptAccessibilityBridge`(Plan A), `ShareIntentBridge`(Plan B), web fallback(Plan C), `OpenAiApiBridge`(예약), `FutureOfficialChatGptBridge`(예약).
- ChatGPT UI 지식은 구현체 안에만(INV-4). SM·Endpoint·오디오는 브릿지 교체에 영향받지 않는다.
- **Missing send is better than duplicate send.** 애매한 결과에 자동 재전송 금지(INV-5).

**Alternatives:**
- 브릿지 없이 Accessibility 코드를 SM에 직접 결합: 교체 불가. 기각.

**Consequences:**
- 결과 타입이 세분화되어 SM의 실패 처리가 명확해진다.
- 새 공식 경로가 생기면 구현체 하나로 교체.

**Validation:** GV-16, GV-17.

---

## ADR-007 Accessibility Plan A — EXPERIMENTAL, not SUPPORTED

**Status:** Accepted (2026-09-18)

**Context:**
리뷰는 Accessibility 주입을 "SUPPORTED WITH LIMITATIONS"이자 "같은 대화를 유지하는 유일한 경로"로 표현했다. 그러나 Android Accessibility API의 존재와, 현재 ChatGPT 앱 composer가 editable 노드·`ACTION_SET_TEXT`·Send 노드·`ACTION_CLICK`을 실제로 지원하는지는 별개 문제다. `startAssistantActivity` API 존재와 그것으로 ChatGPT를 실행했을 때의 UX도 별개다.

**Decision:**
- Plan A `ChatGptAccessibilityBridge` 상태: **`EXPERIMENTAL` / `DEVICE_TEST_REQUIRED` / `HIGH MAINTENANCE RISK`**. Galaxy 실기기 검증 전 `SUPPORTED` 표기 금지.
- 표현: "현재 확인 가능한 방법 중, 공식 ChatGPT Android UI의 현재 conversation을 best-effort로 유지하면서 자동 입력/전송을 시도할 수 있는 가장 현실적인 방식". "유일한 경로" 표현 금지.
- `startAssistantActivity` API availability: `CONFIRMED`. ChatGPT 실행 UX: `DEVICE_TEST_REQUIRED`. Warm-start 대화 유지: `DEVICE_TEST_REQUIRED`. Cold-start 대화 유지: `NOT GUARANTEED`.
- Selector Stack 6단계(semantic → state → contentDescription → hierarchy → relative location → calibrated coordinate). 고정 절대 좌표 금지. `ACTION_SET_TEXT` 후 composer text 재확인 필수. `turnId` + `sentFlag` + composer 상태로 중복 전송 방지.
- Plan B(Share Intent, same conversation `NOT GUARANTEED`) 자동 폴백. Plan C는 emergency.
- Android 13+ Restricted Settings를 온보딩에 반영(`COMMUNITY_REPORTED` / `DEVICE_TEST_REQUIRED`).

**Alternatives:**
- OpenAI API 브릿지 기본: 공식 앱 UI·기존 대화 요구와 상충. 인터페이스만 예약.
- Web `?q=` 기본: 앱 UI 아님, 자동 제출 차단 가능성. emergency만.

**Consequences:**
- ChatGPT 업데이트마다 깨질 수 있다(R-16). 자가진단·배지·보정 화면 필수.
- Spike S-3 실패 시 Plan B가 기본이 되고 "같은 대화" 목표를 낮춘다.

**Validation:** GV-11, GV-12, GV-13, GV-14, GV-15, GV-20, GV-21. Spike S-3.

---

## ADR-008 UI: VoiceInteractionSession only, no SYSTEM_ALERT_WINDOW overlay in v0.1

**Status:** Accepted (2026-09-18)

**Context:**
README 초안은 "VoiceInteractionSession first; overlay only when truly necessary"였다. 오버레이 권한은 추가 온보딩 부담이 있고, Android 15부터 BAL 예외도 오버레이가 실제로 보일 때만 적용된다.

**Decision:**
v0.1 UI는 `VoiceInteractionSession` 창(하단 컴팩트 카드) **단일**. `SYSTEM_ALERT_WINDOW` overlay는 v0.1 아키텍처에서 **제외**. 알림은 FGS 필수 알림과 실패 알림만.

**Alternatives:**
- SYSTEM_ALERT_WINDOW 오버레이: 권한 부담. 기각.
- Bubble: 대화 앱 전용. 기각.
- Samsung Edge panel: Samsung SDK 의존. 기각.

**Consequences:**
- 세션 UI는 `showSession()` 경로로만 표시.
- 세션 표시 실패 시 heads-up 알림으로 상태 표시.

**Validation:** GV-03, GV-04.

---

## ADR-009 Follow-up Mode excluded from v0.1

**Status:** Accepted (2026-09-18)

**Context:**
"답변 후 5~10초 안에 Wake Word 없이 다음 질문"은 ChatGPT 응답 완료 시점을 알아야 한다. 현재 그 수단이 정의되지 않았고, 열린 마이크 창은 오탐·프라이버시 위험을 키운다.

**Decision:**
- v0.1 UX: `헤이 지피티 → 질문 → 자동 전송 → 답변 → IDLE`. 다음 질문은 다시 Wake Word.
- Follow-up Mode는 **v0.2 experimental, default OFF**. Accessibility로 "Stop 버튼 소멸 / Send 재활성"을 완료 신호로 쓸 수 있는지 검증된 뒤에만 실험.

**Alternatives:**
- 고정 5~10 s 창: 완료 감지 없이 열면 오탐. 기각.

**Consequences:**
- 연속 질문 시 Wake Word 반복(수용).

**Validation:** v0.2에서 별도 GV 추가.

---

## ADR-010 Windows: Phase 2, core module as shared contract

**Status:** Accepted (2026-09-18)

**Context:**
같은 개념(Wake, Endpoint, State Machine, Bridge)을 Windows에서도 쓰고 싶다. KMP 전면 도입은 v0.1에 과투자다.

**Decision:**
- `core/`를 **pure Kotlin/JVM, Android 의존 금지**로 만든다: `SessionStateMachine`, `EndpointDetector`, `EndingClassifier`, `CommandParser`, `ChatBridge` 인터페이스·결과 타입, 설정 스키마, `DebugLog`.
- Windows는 Phase 2에서 `core`를 JVM으로 재사용하거나 동일 스펙([ENDPOINT_ENGINE.md](ENDPOINT_ENGINE.md), [CHATGPT_BRIDGE.md](CHATGPT_BRIDGE.md))으로 포팅. `WakeWordEngine` / `SpeechEngine` / `ChatBridge` 구현만 플랫폼별.
- v0.1 동안 `windows/`는 placeholder.

**Alternatives:**
- KMP 전면: 과투자. 기각.
- Windows 먼저: Galaxy가 우선 목표. 기각.

**Consequences:**
- `core`의 단위 테스트가 Android 없이 실행 가능해야 한다(설계 강제).
- Windows STT/브릿지(UIA 또는 브라우저 자동화)는 별도 검증.

**Validation:** `core` JVM 테스트 통과(구현 시).

---

## ADR-011 Numeric parameters are tunable configuration, not constants

**Status:** Accepted (2026-09-18)

**Context:**
리뷰의 threshold/hard cap/grace/pre-roll/타임아웃 값은 실험 시작값이다. 코드 상수로 굳으면 실기기 튜닝이 코드 변경이 된다.

**Decision:**
모든 endpoint·타이밍 파라미터는 `core.settings`에서 읽는다. 문서에는 `DEFAULT_INITIAL_VALUE` / `DEVICE_TUNABLE` / `NOT A PRODUCT CONSTANT` 태그를 붙인다. 실측값은 [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md) §6 규칙으로 문서에 반영한다.

**Consequences:** INV-8.

---

## ADR-012 Evidence tagging policy

**Status:** Accepted (2026-09-18)

**Context:**
API 존재를 근거로 실기기 동작을 확정하거나, 커뮤니티 보고를 공식 근거처럼 쓰면 구현 단계에서 수천 줄을 버리게 된다.

**Decision:**
- `CONFIRMED`는 공식 문서(Android Developers > AOSP > Samsung Developers > 라이브러리 공식 문서)가 있을 때만.
- 커뮤니티 보고만 있으면 `COMMUNITY_REPORTED` + `DEVICE_TEST_REQUIRED`.
- API 존재와 그 API로 얻는 UX는 별도 태그.
- Pricing/licensing은 현행 약관 확인 전 확정 표현 금지.
- 불확실하면 `DEVICE_TEST_REQUIRED`로 낮춘다.

**Consequences:** [ANDROID_CONSTRAINTS.md](ANDROID_CONSTRAINTS.md) 전체가 이 정책을 따른다.
