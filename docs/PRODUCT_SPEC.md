# PRODUCT_SPEC — ChatGPT Voice Receiver (codename AURA)

- 문서 상태: **Authoritative** (v0.1 기준)
- 최종 갱신: 2026-09-18
- 관련 문서: [ARCHITECTURE.md](ARCHITECTURE.md) · [ANDROID_CONSTRAINTS.md](ANDROID_CONSTRAINTS.md) · [MVP_PLAN.md](MVP_PLAN.md) · [RISK_REGISTER.md](RISK_REGISTER.md)

이 문서는 "무엇을 만드는가"를 정의한다. "어떻게 만드는가"는 [ARCHITECTURE.md](ARCHITECTURE.md)가 정의한다.

---

## 1. 제품 목표

Samsung Galaxy / 최신 Android(One UI 7/8, Android 15/16)에서 사용하는 **개인용 hands-free ChatGPT voice interface**.

사용자는 폰을 만지지 않고, 자연스럽게 말하는 것만으로 **공식 ChatGPT Android 앱**에 질문을 전달하고 답변을 확인한다.

핵심 가치는 세 가지다.

| 가치 | 의미 |
|---|---|
| Hands-free | Wake Word → 말하기 → 자동 전송. 손과 시선이 필요 없다 |
| Natural Endpoint | 사용자가 말을 끝냈는지를 앱이 판단한다. "전송"이라고 말할 필요가 없다 |
| Official ChatGPT App | 별도 챗 UI를 만들지 않는다. 답변은 공식 앱(로그인된 계정, 기존 대화)에서 본다 |

이 제품은 **개인용(personal, sideloaded APK)** 이다. Play Store 배포·다수 사용자 지원은 목표가 아니다.

---

## 2. 핵심 UX (Normal Interaction)

```text
"헤이 지피티"

→ Wake Word 감지
→ 짧은 feedback (haptic + 짧은 사운드 + 세션 UI "듣고 있습니다…")
→ 음성 입력 시작

"지금 MiniMax H3에서 새로 나온 FastH3가
 기존 방식하고 뭐가 다른지 확인해봐."

→ 사용자가 자연스럽게 말을 끝냄
→ 앱이 발화 종료(Natural Endpoint)를 판단
→ 별도의 "전송" 명령 없이 질문 확정
→ 세션 UI "처리 중…"
→ 공식 ChatGPT Android 앱에 질문 전달
→ 자동 Send
→ ChatGPT 앱에서 답변 확인
→ IDLE
```

답변 확인 후 다음 질문:

```text
"헤이 지피티"
→ 다음 질문
```

v0.1에서는 **매 질문마다 Wake Word가 필요**하다. Follow-up Mode(Wake Word 없이 연속 질문)는 v0.1 범위 밖이다(§9).

### 2.1 사용자가 경험해야 하는 것

- Wake Word 뒤에 **바로** 말해도 첫 음절이 잘리지 않아야 한다. (아키텍처상 pre-roll ring buffer로 대응. 실기기 검증 필요 — [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md) GV-09)
- 문장 중간에 생각하느라 잠깐 멈춰도 **끊기지 않아야** 한다. (false endpoint는 이 제품에서 가장 나쁜 실패 중 하나)
- 말이 끝나면 **1초 안팎**에 "처리 중…"으로 넘어가는 느낌이어야 한다. (구체 수치는 §5 참조)
- 같은 질문이 **두 번 전송되는 일은 절대 없어야** 한다. 전송이 안 된 경우는 사용자에게 보이게 실패한다.

### 2.2 세션 UI

기본 UI는 `VoiceInteractionSession` 창(하단 컴팩트 카드)이다. 작고 짧게 유지한다.

```text
✦ GPT

듣고 있습니다…

FastH3하고 기존 H3의 차이를…
```

Endpoint 확정 이후:

```text
✦ GPT

처리 중…
```

실패 시:

```text
✦ GPT

ChatGPT에 전달하지 못했습니다.
질문은 복사해 두었습니다.
```

`SYSTEM_ALERT_WINDOW` 기반 overlay는 v0.1 아키텍처에서 제외한다([DECISIONS.md](DECISIONS.md) ADR-008).

---

## 3. Non-goals (v0.1)

다음은 **만들지 않는다**.

- ChatGPT 답변을 읽어주는 TTS
- 자체 챗 UI / OpenAI API로 답변을 받아서 표시하는 기능
- Follow-up Mode(연속 대화) — v0.2 experimental, default OFF
- Windows 구현 — Phase 2
- 사용자 지정 Wake Word 트레이닝 UI — 모델 파일 교체만 허용
- Bluetooth/Buds 최적화 — 기본 동작 확인만, 튜닝은 v0.2
- Whisper 등 자체 STT 모델 탑재
- Cloud LLM을 이용한 endpoint 판정
- Play Store 배포, 다중 사용자, 계정 관리
- ChatGPT 앱 외의 다른 서비스(Gemini, Claude 등) 브릿지

---

## 4. Fallback Commands

정상 UX에서는 요구하지 않는다. Natural Endpoint가 실패하거나 사용자가 명시적으로 제어하고 싶을 때만 쓴다.

| 명령 | 동작 | 비고 |
|---|---|---|
| `보내` / `전송` / `송부` | 즉시 endpoint 확정. 명령어는 전송 텍스트에서 제거 | late endpoint 복구용 |
| `취소` | 현재 turn 폐기, IDLE로 | |
| `다시` | 현재 transcript 폐기, LISTENING 재시작 | |

명령어 인식은 transcript의 **마지막 어절**에서만 수행한다. 문장 중간의 "취소"(예: "예약 취소하는 방법 알려줘")를 명령으로 오인하면 안 된다. 상세는 [ENDPOINT_ENGINE.md](ENDPOINT_ENGINE.md) §7.

---

## 5. Expected Latency (목표, 실측 후 재설정)

아래 수치는 **설계 목표**이며 제품 상수가 아니다. 실기기([GALAXY_VALIDATION.md](GALAXY_VALIDATION.md))에서 측정한 뒤 갱신한다.

| 구간 | 목표 | 태그 |
|---|---|---|
| Wake Word 발화 종료 → feedback | ≤ 300 ms | DEVICE_TEST_REQUIRED |
| feedback → STT 수신 시작 | 0 ms (pre-roll 파이프) 또는 ≤ 300 ms (handoff fallback) | DEVICE_TEST_REQUIRED |
| 발화 종료 → endpoint 확정 | FINAL 어미 기준 600~800 ms + grace 500 ms (초기값) | DEVICE_TUNABLE |
| endpoint 확정 → ChatGPT Send 완료 (warm) | ≤ 2 s | DEVICE_TEST_REQUIRED |
| endpoint 확정 → ChatGPT Send 완료 (cold) | ≤ 5 s | DEVICE_TEST_REQUIRED |

Endpoint threshold 초기값과 hard cap(3000 ms 시작값, 3000/4000/5000 비교)은 [ENDPOINT_ENGINE.md](ENDPOINT_ENGINE.md)에서 정의한다.

---

## 6. Privacy Expectations

- Wake Word 감지는 **전부 로컬**에서 처리한다. IDLE 상태에서 오디오가 클라우드로 나가지 않는다.
- STT는 Wake Word **이후** 발화 구간만 처리한다. Android `SpeechRecognizer`는 기기 설정에 따라 온라인 인식일 수 있다(사용자에게 명시).
- 오디오 원본은 저장하지 않는다. Ring buffer는 메모리에만 존재하고 STT로 넘긴 뒤 폐기한다.
- Transcript 원문 로깅은 기본 OFF. Debug 빌드에서 사용자가 켠 경우만 기록.
- Accessibility Service 권한은 ChatGPT 앱(`com.openai.chatgpt`)의 UI에만 사용한다. 다른 앱 화면은 읽지 않는다.
- 상세는 [SECURITY_PRIVACY.md](SECURITY_PRIVACY.md).

---

## 7. Known Android Limitations (제품 제약으로 수용)

아래는 bug가 아니라 **platform limitation**이다. 온보딩에서 사용자에게 설명한다.

| 제약 | 설명 | 태그 |
|---|---|---|
| **Privacy indicator 상시 표시** | 3rd-party 앱은 DSP 기반 `AlwaysOnHotwordDetector`를 사용할 수 없다(Android 12부터 `@SystemApi`). 로컬 커스텀 Wake Word를 항상 감지하려면 앱이 마이크를 실제로 열어야 하므로 **녹색 마이크 인디케이터가 대기 중에도 표시될 수 있다** | CONFIRMED (API 제한) / 인디케이터 UX는 DEVICE_TEST_REQUIRED |
| 배터리 소모 | 상시 `AudioRecord` + Wake Word 추론. 수치는 실측 후 확정 | DEVICE_TEST_REQUIRED |
| Bixby 대체 | Galaxy에서 기본 어시스턴트를 Bixby에서 이 앱으로 바꿔야 한다 | CONFIRMED |
| 잠금 상태 | 잠금 화면 위에서 듣기·전송 준비까지는 가능할 수 있으나, ChatGPT 앱 화면은 잠금 해제 없이 볼 수 없다 | DEVICE_TEST_REQUIRED |
| ChatGPT 앱 자동화 | 공식 automation contract가 없다. Accessibility 기반 브릿지는 ChatGPT 앱 업데이트로 언제든 깨질 수 있다 | HIGH RISK |
| Samsung 백그라운드 정책 | Sleeping apps / Deep sleeping apps 정책에 의해 FGS가 제한될 수 있다. "Never sleeping apps" 등록이 필요할 수 있다 | DEVICE_TEST_REQUIRED |
| Android 13+ Restricted Settings | 사이드로드 APK는 Accessibility 활성화 전에 "제한된 설정 허용" 절차가 필요할 수 있다 | COMMUNITY_REPORTED / DEVICE_TEST_REQUIRED |

---

## 8. Onboarding에서 사용자가 해야 할 설정 (초안)

각 항목의 실제 화면 경로·동작은 One UI 버전에 따라 다를 수 있다(DEVICE_TEST_REQUIRED).

1. 마이크 권한 허용
2. 기본 디지털 어시스턴트 앱을 이 앱으로 변경 (`ROLE_ASSISTANT`)
3. Accessibility Service 활성화 (Android 13+ 사이드로드 시 "제한된 설정 허용" 선행)
4. 배터리 최적화 제외
5. Samsung Device care → "Never sleeping apps"에 추가
6. (선택) 화면 꺼짐 상태 Wake Word 사용 여부
7. Bridge calibration: ChatGPT 앱을 열어 composer/Send 위치를 한 번 확인

---

## 9. Follow-up Mode (v0.1 제외)

v0.1 UX는 다음으로 고정한다.

```text
헤이 지피티 → 질문 → 자동 전송 → 답변 → IDLE
```

Follow-up Mode(답변 후 일정 시간 Wake Word 없이 다음 질문 허용)는 **v0.2 experimental, default OFF**. ChatGPT 응답 완료 시점을 안정적으로 감지할 수 있을 때만 실험한다([DECISIONS.md](DECISIONS.md) ADR-009).

---

## 10. MVP Success Criteria (v0.1)

실기기 Galaxy에서 아래를 모두 만족하면 v0.1 완료로 본다. 측정 절차는 [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md).

| # | 기준 | 목표 (초기) |
|---|---|---|
| S1 | Assistant 등록 후 재부팅 없이 72시간 동안 Wake Word 반응 | 반응 실패 0회 (3시간 간격 점검) |
| S2 | 화면 ON 상태 Wake Word 감지율 | ≥ 90 % (10회 중 9회) |
| S3 | 일반 대화·기술 영상 노출 시 Wake Word 오탐 | ≤ 1회/시간 |
| S4 | 화면 OFF 상태에서 Wake → 전송 성공 | 성공 |
| S5 | 20개 한국어 질문 세트에서 false endpoint(문장 중간에 끊김) | ≤ 10 % |
| S6 | 동일 세트에서 late endpoint(hard cap까지 대기) | ≤ 20 % |
| S7 | ChatGPT Plan A 전송 성공률 (warm start) | ≥ 90 % |
| S8 | 중복 전송 | **0건** |
| S9 | Plan A 실패 시 Plan B 또는 클립보드 fallback으로 질문 유실 | 0건 |
| S10 | 12시간 대기 배터리 소모 | 실측 후 목표 설정 (초기 관찰값 기록) |

S7·S10은 실기기 결과에 따라 목표를 조정한다. S8은 조정하지 않는다.

---

## 11. 용어

| 용어 | 정의 |
|---|---|
| Wake Word | 앱을 깨우는 키워드. 기본 "헤이 지피티" |
| Natural Endpoint | 사용자가 말을 끝냈다고 앱이 판단한 시점 |
| EndpointDetector | Natural Endpoint를 결정하는 pure Kotlin 컴포넌트 |
| ChatBridge | 확정된 질문을 ChatGPT에 전달하는 교체 가능한 인터페이스 |
| ChatGPT App Bridge | ChatBridge의 Plan A 구현. 공식 ChatGPT 앱 UI를 Accessibility로 조작 |
| TurnId | Wake Word 1회 = turn 1개. 모든 이벤트에 부여되는 식별자 |
| VoiceInteractionService / VoiceInteractionSession | Android assistant 기반 컴포넌트 |
