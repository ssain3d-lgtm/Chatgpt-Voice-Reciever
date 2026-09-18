# MVP_PLAN — v0.1 범위와 단계

- 문서 상태: **Authoritative**
- 상위: [PRODUCT_SPEC.md](PRODUCT_SPEC.md) · [ARCHITECTURE.md](ARCHITECTURE.md) · 검증: [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md)

v0.1은 **매우 작게** 유지한다. 목표는 "Galaxy 실기기에서 한 turn이 끝까지 돈다"를 증명하는 것이다.

---

## 0. 순서 원칙

```text
Phase 0  설계 문서 확정              ← 이 저장소의 현재 단계
Phase 1  Galaxy Technical Spike      ← 다음 단계. 전체 구현이 아님
Phase 2  v0.1 구현                   ← Spike 3항목 통과 후에만
Phase 3  v0.1 Galaxy validation
Phase 4  v0.2 (experimental 항목)
```

**Phase 1 Spike를 건너뛰고 Phase 2를 시작하지 않는다.** 잘못된 Android 가정 위에 수천 줄을 쌓지 않기 위해서다.

---

## 1. Phase 1 — Galaxy Technical Spike

목표는 오직 세 가지를 검증하는 것이다. 각 항목은 **버려도 되는 코드**로 만든다(production 구조 불필요).

| # | 검증 항목 | 통과 기준 | 관련 테스트 | 실패 시 |
|---|---|---|---|---|
| S-1 | `VoiceInteractionService` + 화면 OFF Wake feasibility | Assistant 등록 성공, FGS 마이크가 화면 OFF에서 무음이 아님, Porcupine "헤이 지피티" 감지 | GV-01, GV-02, GV-03 | 아키텍처 재검토 (제품 성립 불가) |
| S-2 | `AudioRecord` → `SpeechRecognizer` pipeline feasibility | Mode P(`EXTRA_AUDIO_SOURCE`) 지원 여부 확정, 미지원 시 Mode H에서 첫 음절 손실 정도 측정 | GV-08, GV-09, GV-10 | Mode H 고정, pre-roll 설계 축소 |
| S-3 | 공식 ChatGPT 앱 Accessibility injection/send feasibility | composer editable 노드 발견, `ACTION_SET_TEXT` 반영, Send `ACTION_CLICK` 후 composer 비움 | GV-11, GV-12, GV-13, GV-21 | Plan B 기본화, Plan A 폐기 검토 |

Spike 산출물: 각 GV 항목의 Result 기록 + [ANDROID_CONSTRAINTS.md](ANDROID_CONSTRAINTS.md) 태그 갱신 + [RISK_REGISTER.md](RISK_REGISTER.md) 상태 갱신.

---

## 2. Phase 2 — v0.1 범위 (반드시 포함)

| 항목 | 내용 | 문서 |
|---|---|---|
| Assistant registration | VIS + VoiceInteractionSessionService + `RecognitionService` stub, ROLE_ASSISTANT 온보딩 | ANDROID_CONSTRAINTS §1–3 |
| Wake Word | `AudioCaptureService`(FGS) 단일 `AudioRecord` + `PorcupineWakeWordEngine` "헤이 지피티"(ko). 사용자 변경 UI 없음(모델 파일 교체만) | ARCHITECTURE §4, DECISIONS ADR-002/003 |
| STT | `AndroidSpeechRecognizerEngine`, Mode P 시도 → Mode H 폴백. 온디바이스는 `checkRecognitionSupport(ko-KR)` 결과 있을 때만 | ANDROID_CONSTRAINTS §8, ADR-004 |
| VAD | `SileroVadEngine`(또는 WebRTC) | ENDPOINT_ENGINE §3.1 |
| Endpoint | `EndpointDetector` v1: 3층, 고정 threshold(초기값), adaptive OFF, grace | ENDPOINT_ENGINE |
| State Machine | `SessionStateMachine` actor, TurnId, race 규칙 | ARCHITECTURE §6–7 |
| CommandParser | `보내/전송`, `취소`, `다시` | ENDPOINT_ENGINE §7 |
| ChatGPT Plan A | `ChatGptAccessibilityBridge` + Selector Stack + Verify + Idempotent Send + 보정 화면 | CHATGPT_BRIDGE §3 |
| Plan B fallback | `ShareIntentBridge` 자동 폴백 | CHATGPT_BRIDGE §4 |
| 실패 시 텍스트 보존 | 클립보드 + 알림 | ARCHITECTURE §8 |
| 잠금 상태 처리 | 큐잉 후 해제 시 전송 | ANDROID_CONSTRAINTS §6 |
| Session UI | VoiceInteractionSession 컴팩트 카드 | PRODUCT_SPEC §2.2, ADR-008 |
| Onboarding | 권한, 역할, Accessibility(Restricted Settings 안내), 배터리 최적화, Never sleeping, Bridge 보정 | PRODUCT_SPEC §8 |
| Debug logs | `DebugLog` ring buffer + 뷰어 + export | SECURITY_PRIVACY §5 |
| Galaxy validation | GALAXY_VALIDATION 전 항목 실행·기록 | GALAXY_VALIDATION |

---

## 3. v0.1 제외 (명시적)

```text
Follow-up mode                → v0.2 experimental, default OFF
TTS response reading          → 계획 없음
Windows implementation        → Phase 2 (별도 마일스톤, ADR-010)
custom Wake Word training UI  → v0.3+
full Bluetooth optimization   → v0.2 (v0.1은 기본 동작 확인만)
Whisper / 자체 STT 모델        → v0.3+
cloud endpoint LLM            → 계획 없음
adaptive endpoint thresholds  → v0.2
상시 대기 스케줄(야간 OFF)      → v0.2
SYSTEM_ALERT_WINDOW overlay   → 계획 없음
OpenAiApiBridge 구현           → 인터페이스만 예약
```

---

## 4. Phase 2 구현 순서 (권장)

Spike 통과 후, 의존 순서대로.

```text
1. core/  — SessionStateMachine, TurnId, EndpointDetector, EndingClassifier, CommandParser, ChatBridge 타입
            + 단위 테스트 (ENDPOINT_ENGINE §9 데이터셋 D1~D5)
2. app/audio + app/wakeword  — AudioCaptureService, RingBuffer, PorcupineWakeWordEngine
3. app/assistant  — VIS, SessionService, Session UI, RecognitionService stub, ROLE 온보딩
4. app/vad + app/speech  — VadEngine, SpeechEngine (Mode P/H)
5. app/chatgptbridge  — AccessibilityService, Plan A, Plan B, SelectorStack, 보정 화면
6. app/settings + app/debug  — DataStore, Onboarding, LogViewer
7. GALAXY_VALIDATION 전체 실행
```

`core/`는 Android 없이 JVM에서 먼저 테스트 가능해야 한다. 이는 Windows Phase 2의 전제이기도 하다.

---

## 5. v0.1 완료 기준

[PRODUCT_SPEC.md](PRODUCT_SPEC.md) §10의 S1~S10. 특히:

- S8 중복 전송 **0건** — 조정 불가.
- S9 질문 유실 **0건** — 조정 불가.
- 나머지는 실측값을 기록하고 목표를 재설정할 수 있다.

---

## 6. v0.2 후보 (v0.1 완료 후 재평가)

- Follow-up Mode 실험: Accessibility로 "Stop 버튼 소멸 / Send 재활성"을 응답 완료 신호로 사용할 수 있는지만 검증. default OFF.
- Adaptive threshold + 사용자 프리셋(빠르게/보통/여유).
- Bluetooth 라우팅 정책(차량/Buds).
- 상시 대기 스케줄.

---

## 7. Phase 2 (Windows) — 범위 밖

`core/`의 SessionStateMachine·EndpointDetector·CommandParser·ChatBridge 인터페이스를 JVM으로 재사용하거나 동일 스펙으로 포팅한다. Windows STT/브릿지(UIA 또는 브라우저 자동화)는 별도 검증. 이 저장소의 `windows/`는 v0.1 동안 README placeholder만 둔다.
