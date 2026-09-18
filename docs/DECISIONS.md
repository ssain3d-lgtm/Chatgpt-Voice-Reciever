# DECISIONS — Architecture Decision Records

- 문서 상태: **Authoritative** (append-only. 결정이 바뀌면 새 ADR을 추가하고 기존 ADR을 Superseded로 표시)
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) · 리뷰 원문: [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md)

Status 값: `Accepted` / `Proposed` / `Superseded by ADR-xxx` / `Deprecated`

> **읽는 사람(사람이든 에이전트든)에게:** 이 파일은 append-only라 **틀린 것으로 판명된 ADR도 남아 있다.** 어떤 ADR을 근거로 쓰기 전에 반드시 그 ADR의 **Status를 먼저 확인**한다. `SUPERSEDED`로 표시된 ADR의 본문은 역사적 기록일 뿐이며 구현 근거가 아니다. 현재 superseded: **ADR-014 → ADR-016**.

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

---

## ADR-013 Endpoint VAD is activated by Wake, not run in IDLE

**Status:** Accepted (2026-09-18)

**Context:**
[ARCHITECTURE.md](ARCHITECTURE.md) §1/§2/§4.2는 RingBuffer 소비자로 `WakeWordEngine`, `VadEngine`, Speech pipeline을 나란히 그렸고, [SECURITY_PRIVACY.md](SECURITY_PRIVACY.md) §2도 IDLE PCM이 `VadEngine`에 전달되는 것처럼 읽혔다. 반면 [RISK_REGISTER.md](RISK_REGISTER.md) R-03 Mitigation은 "VAD는 Wake 이후에만 활성(초기 정책)"이라고 적었다. **같은 저장소 안에서 서로 다르게 읽힌다.**

기술적으로도 IDLE VAD는 소비자가 없다. `VadEvent`의 유일한 소비자는 `EndpointDetector`이고, `EndpointDetector`는 `reset(turnId)` 이후에만 의미가 있다. IDLE에는 turn이 없다.

**Decision:**
v0.1과 Technical Spike에서 **Endpoint용 VAD는 IDLE에서 돌리지 않는다.**

```text
IDLE           : AudioRecord active · WakeWordEngine active · Endpoint VAD INACTIVE · SpeechEngine INACTIVE
Wake detected  : ARMED/LISTENING → Endpoint VAD ACTIVE · SpeechEngine ACTIVE
turn 종료       : Endpoint VAD INACTIVE · SpeechEngine INACTIVE
```

Porcupine(Wake Word)은 항상 PCM을 받는다. 마이크 자체는 IDLE에도 열려 있다(DSP 경로 없음, ADR-003).

**Alternatives:**
- IDLE에서도 VAD 상시 구동: 소비자 없음, 배터리만 소모. 기각.
- Wake Word를 VAD로 게이팅(VAD가 speech를 감지할 때만 Porcupine 실행): 2단계 게이팅은 전력 이득이 있을 수 있으나 첫 음절 손실·추가 지연·튜닝 부담이 생기고, Porcupine 자체가 경량이다. v0.1 범위 밖. `OPEN_QUESTION`.

**Consequences:**
- [ARCHITECTURE.md](ARCHITECTURE.md) §4.5가 이 정책의 authoritative 위치다. INV-7 문구도 이에 맞춰 갱신했다.
- 상시 추론이 Porcupine 하나로 줄어 R-03(배터리) 측정 대상이 단순해진다.
- Spike S-1은 "IDLE에 VAD 없이도 Wake가 동작하는가"만 보면 된다.

**Validation:** GV-06(배터리). Spike S-1.

---

## ADR-014 VoiceInteractionSessionService process separation — SUPERSEDED, DO NOT IMPLEMENT

**Status:** **SUPERSEDED by [ADR-016](#adr-016-세션-프로세스-분리는-공식-권장-spike에서만-한시적으로-단일-프로세스) (2026-09-18). Historical record only.**

```text
DO NOT IMPLEMENT THIS ADR.
The investigation behind it was incorrect.
Its central claim — "official Android documentation does not recommend a
separate process for VoiceInteractionSessionService" — is FALSE.
The official VoiceInteractionService reference recommends exactly that.
Use ADR-016 instead.
```

무엇이 유지되고 무엇이 폐기되었는가:

| | |
|---|---|
| 유지 | **Spike는 단일 프로세스**라는 결론. 단, 근거는 "공식 권장이 없어서"가 아니라 "공유 `DebugLog`를 위한 한시적 예외"다 |
| 폐기 | 근거 전부. "공식 문서에 권장 없음", "v0.1도 단일 프로세스", "(a)/(b) 관측 시에만 재검토" 조건 |

정확한 내용은 [ADR-016](#adr-016-세션-프로세스-분리는-공식-권장-spike에서만-한시적으로-단일-프로세스)에 있다. v0.1은 `android:process=":session"`으로 **분리한다**.

<details>
<summary>원문 보존 (틀린 주장 포함 — 인용하지 말 것)</summary>

> 아래는 2026-09-18 최초 작성분이다. **틀린 문장에는 ❌를 붙였다.** 이 블록의 어떤 문장도 구현·인용 근거로 쓰지 않는다.

**Context:**
`VoiceInteractionService`(VIS)는 어시스턴트 역할을 가진 동안 시스템이 상시 바인딩한다. 따라서 가능한 한 가볍게 유지해야 한다는 요구가 있다. 흔히 제안되는 방법은 세션/UI 쪽을 별도 프로세스로 빼는 것이다.

**조사 결과 (이 표 전체가 틀렸다):**

| 항목 | 원래 태그 | 판정 |
|---|---|---|
| Android 공식 문서가 별도 프로세스를 **요구**하는가 | ~~`CONFIRMED (요구하지 않음)`~~ | ❌ **WRONG.** 공식 레퍼런스가 "that service should run in a separate process from this one"이라고 명시한다 |
| AOSP 참조 구현이 프로세스를 나누는가 | ~~`CONFIRMED (나누지 않음)`~~ | ❌ 샘플이 나누지 않는다는 사실은 권장의 부재를 뜻하지 않는다. 논증 자체가 무효 |
| 별도 프로세스가 One UI에서 VIS 생존성을 개선하는가 | ~~`DEVICE_TEST_REQUIRED`~~ | ⚠️ 이 항목만 유효. 단, 분리 여부의 **전제 조건이 아니다** — 공식 권장이므로 관측과 무관하게 분리한다 |
| 별도 프로세스가 VIS 메모리 압력을 줄이는가 | `CONFIRMED (일반론)` | ✅ 유효 |

**원래의 Decision:** ❌ "Technical Spike와 v0.1에서는 `android:process`를 쓰지 않는다(단일 프로세스)." — v0.1 부분이 틀렸다.

**원래의 재검토 조건:** ❌ "GV-07에서 (a) LMK/OOM 재생성 또는 (b) RSS 증가 후 FGS 사망이 관측되면 분리를 도입한다." — **폐기**. 공식 권장은 관측을 기다릴 사안이 아니다.

**유효했던 부분:** `AudioCaptureService`를 별도 프로세스로 빼는 것은 기각. 마이크 소유 컴포넌트를 VIS에서 떼면 while-in-use 예외 적용이 불투명해진다. 이 판단은 ADR-016에도 그대로 옮겼다.

**이 ADR이 남긴 교훈:** "공식 문서에 없다"는 **문서를 끝까지 읽었을 때만** 할 수 있는 주장이다. 근거 부재를 근거로 쓴 것은 ADR-012 위반이다.

</details>

---

## ADR-015 ChatGPT App Bridge Go / No-Go rule

**Status:** Accepted (2026-09-18)

**Context:**
제품 목표는 **fully hands-free**다. "질문이 ChatGPT 앱에 들어갔다"가 아니라 "사용자가 화면을 만지지 않고 전송까지 끝났다"가 성공이다. Spike S-3이 실패했을 때 무엇을 하고 무엇을 하지 않을지가 [MVP_PLAN.md](MVP_PLAN.md)에 "Plan B 기본화 / Plan A 폐기 검토"로만 적혀 있어, **자동 Send 가능 여부**라는 결정적 분기가 명시되지 않았다.

**Decision:**
S-3 결과에 따른 분기를 아래로 고정한다. 판정 기준의 상세는 [CHATGPT_BRIDGE.md](CHATGPT_BRIDGE.md) §10.

```text
S-3 Accessibility 성공
  → Official ChatGPT App Bridge(Plan A) 방식으로 v0.1 계속 진행

S-3 Accessibility 실패
  → Plan B Share Intent 테스트를 수행한다 (건너뛰지 않는다)

Plan B가 자동 Send까지 가능
  → degraded bridge로 v0.1 계속 가능 (같은 대화 유지는 포기, hands-free는 유지)

Plan B도 사용자 탭을 요구
  → 원래 제품 목표인 fully hands-free ChatGPT App Bridge = NO-GO
```

**NO-GO일 때 하지 않을 것:**
- 전체 v0.1을 그대로 구현하지 않는다.
- 임의로 OpenAI API 버전으로 전환하지 않는다. 그것은 [PRODUCT_SPEC.md](PRODUCT_SPEC.md) §1의 "공식 앱, 기존 대화" 요구를 바꾸는 **제품 결정**이며, 사용자가 선택한다.

**NO-GO일 때 할 것:** 결과를 보고하고 아래 세 선택지를 제시한다.

| 선택지 | 내용 | 무엇을 포기하는가 |
|---|---|---|
| 1. OpenAI API Bridge | `OpenAiApiBridge` 구현, 자체 최소 답변 표시 | 공식 앱 UI·기존 대화·구독 UI |
| 2. 공식 integration 대기 | Bridge 외 파이프라인(S-1/S-2)만 v0.1로 완성, `FutureOfficialChatGptBridge` 자리만 유지 | 지금 당장의 종단 UX |
| 3. 반자동 degraded mode | 자동 입력까지만. Send는 사용자 탭 1회 | fully hands-free |

**Consequences:** S-3이 NO-GO면 Phase 2(v0.1 구현)는 **사용자 결정 전까지 시작하지 않는다**.

**Validation:** GV-11, GV-12, GV-13, GV-21. Spike S-3.

---

## ADR-016 세션 프로세스 분리는 공식 권장. Spike에서만 한시적으로 단일 프로세스

**Status:** Accepted (2026-09-18) — supersedes ADR-014

**Context:**
[ADR-014](#adr-014-voiceinteractionsessionservice-process-separation--not-adopted-in-the-spike)는 "Android 공식 문서와 AOSP 가이드 어디에도 `android:process` 요구·권장이 없다"고 적었다. **이 조사는 틀렸다.** `VoiceInteractionService` 공식 레퍼런스의 클래스 개요가 그 반대를 명시한다.

> "The current `VoiceInteractionService` that has been selected by the user is **kept always running** by the system, to allow it to do things like listen for hotwords in the background to instigate voice interactions. Because this service is always running, **it should be kept as lightweight as possible.** Heavy-weight operations (including showing UI) should be implemented in the associated `android.service.voice.VoiceInteractionSessionService` when an actual voice interaction is taking place, **and that service should run in a separate process from this one.**"
>
> — Android Developers, [`VoiceInteractionService`](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) 클래스 개요

즉 프로세스 분리는 `DEVICE_TEST_REQUIRED`한 열린 질문이 아니라 **`CONFIRMED`된 공식 권장**이다. ADR-014가 "근거 없음"을 이유로 기각한 것은 [ADR-012](#adr-012-evidence-tagging-policy)(근거 우선순위: Android Developers 최상위) 위반이기도 하다.

**Decision:**

| 단계 | 프로세스 | 근거 |
|---|---|---|
| Technical Spike (현재) | **단일 프로세스** (한시적) | S-1/S-2/S-3 로그를 한 `DebugLog` 링버퍼에서 읽는 것이 Spike의 산출물 자체다. 프로세스를 나누면 로그·캡처 상태·Bridge 상태가 경계로 쪼개져 IPC 병합 코드가 필요하고, 그 코드는 검증하려는 세 가설과 무관하다 |
| v0.1 | **별도 프로세스 (`android:process=":session"`)** | 공식 권장. 아래 조건으로 진입 |

**v0.1 전에 반드시 수행할 것 (선택이 아니라 예정된 작업):**

```text
1. GptVoiceInteractionSessionService 에 android:process=":session" 선언
2. DebugLog 를 프로세스 경계 너머로 병합할 수단 마련
   (bound service / Messenger, 또는 세션 프로세스 로그를 logcat 으로만 수집)
3. AudioCaptureService 는 VIS 프로세스에 남긴다 — 마이크 while-in-use 예외는
   "VIS를 제공하는 앱이 시작한 FGS"에 걸리므로, 마이크 소유 컴포넌트를 VIS에서
   떼어내는 것은 별개의 위험이다 (ANDROID_CONSTRAINTS §4)
4. 분리 전후로 GV-07(72h 생존)을 각각 측정해 비교
```

**한시적 단일 프로세스가 허용되는 범위:** Spike 한정. v0.1 구현 착수 시 위 1~4를 수행하기 전에는 `app.assistant`에 UI·무거운 의존성을 더 추가하지 않는다.

**Consequences:**
- [ANDROID_CONSTRAINTS.md](ANDROID_CONSTRAINTS.md) §2의 해당 행을 `CONFIRMED (공식 권장)`으로 정정했다.
- Spike APK의 단일 프로세스는 **의도된 한시적 상태**이며, 실기기 결과와 무관하게 v0.1에서 분리한다. ADR-014가 적은 "(a)/(b) 관측 시에만 재검토"라는 조건은 **폐기**한다 — 공식 권장은 관측을 기다릴 사안이 아니다.
- 이 ADR은 ADR-012의 실패 사례이기도 하다. "공식 문서에 없다"는 **문서를 끝까지 읽었을 때만** 할 수 있는 주장이다.

**Validation:** v0.1 구현 시 위 1~4. GV-07.
