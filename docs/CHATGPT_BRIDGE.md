# CHATGPT_BRIDGE — ChatBridge와 ChatGPT App Bridge 설계

- 문서 상태: **Authoritative** — 단, Plan A는 `EXPERIMENTAL`
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) §9.5 · 결정: [DECISIONS.md](DECISIONS.md) ADR-006, ADR-007 · 위험: [RISK_REGISTER.md](RISK_REGISTER.md) R-01, R-08 · 검증: [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md) GV-11~16, GV-21

---

## 1. 요구사항과 현실

요구: **공식 ChatGPT Android 앱, 로그인된 계정, 가능하면 현재 열려 있는 대화에 이어서** 질문을 넣고 자동 Send.

현실: 공식 ChatGPT Android 앱은 3rd-party 텍스트 주입을 위한 **문서화된 public automation contract를 제공하지 않는다**. 따라서 어떤 방식도 "보장"이 아니다.

Accessibility 기반 주입은 **"현재 확인 가능한 방법 중, 공식 ChatGPT Android UI의 현재 conversation을 best-effort로 유지하면서 자동 입력/전송을 시도할 수 있는 가장 현실적인 방식"** 이다. "유일한 경로"라고 표현하지 않는다. OpenAI가 향후 deep link / intent / Android automation API / official integration contract를 제공하면 `ChatBridge` 뒤에서 교체한다.

---

## 2. ChatBridge 인터페이스

```kotlin
interface ChatBridge {
    suspend fun prepare(turnId: TurnId): BridgeReadiness
    suspend fun send(turnId: TurnId, text: String): ChatBridgeResult
    suspend fun healthCheck(): BridgeHealth
}

sealed interface BridgeReadiness {
    data object Ready : BridgeReadiness
    data class NotReady(val reason: String, val recoverable: Boolean) : BridgeReadiness
}

sealed interface ChatBridgeResult {
    data class Sent(val turnId: TurnId, val composerVerified: Boolean) : ChatBridgeResult
    data class InjectedButNotSent(val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class PrepareFailed(val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class InjectionFailed(val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class SendFailed(val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class BridgeUnavailable(val turnId: TurnId, val reason: String) : ChatBridgeResult
}

data class BridgeHealth(
    val available: Boolean,             // 서비스 활성, 대상 앱 설치
    val lastSuccessfulSelector: String?,
    val consecutiveFailures: Int,
    val targetAppVersion: String?,
)
```

계약:

- `prepare`는 대상 앱을 foreground로 올리고 입력 가능 상태를 확보한다. 텍스트를 넣지 않는다.
- `send`는 같은 `turnId`로 **한 번만** 호출된다. 구현체는 `turnId`별 `sent` 플래그를 유지하고, 같은 `turnId`로 재호출되면 즉시 `SendFailed("duplicate call")`을 반환한다.
- 어떤 결과도 SM이 자동 재시도 근거로 쓰지 않는다. `InjectedButNotSent`와 `SendFailed`는 **사용자 확인**으로 넘긴다.
- ChatGPT UI 지식(패키지명, contentDescription 문자열, 노드 탐색 규칙)은 구현체 안에만 존재한다([ARCHITECTURE.md](ARCHITECTURE.md) INV-4).

구현체 목록:

| 구현체 | Plan | 상태 |
|---|---|---|
| `ChatGptAccessibilityBridge` | A | `EXPERIMENTAL` / `DEVICE_TEST_REQUIRED` / `HIGH MAINTENANCE RISK` |
| `ShareIntentBridge` | B | `DEVICE_TEST_REQUIRED`, same conversation `NOT GUARANTEED` |
| (Web `?q=` fallback) | C | emergency fallback, v0.1 수동 옵션 |
| `OpenAiApiBridge` | — | 인터페이스만 예약 |
| `FutureOfficialChatGptBridge` | — | 공식 경로 등장 시 |

Bridge 선택기(`BridgeSelector`)는 Plan A → Plan B 순으로 자동 시도하고, Plan C는 설정에서 켠 경우만 시도한다. 모두 실패하면 텍스트를 클립보드 + 알림으로 보존한다.

---

## 3. Plan A — ChatGPT Accessibility Bridge (`ChatGptAccessibilityBridge`)

### Purpose

현재 열려 있는 ChatGPT 대화의 composer에 텍스트를 넣고 Send를 누른다.

### Status

```text
EXPERIMENTAL
DEVICE_TEST_REQUIRED
HIGH MAINTENANCE RISK
```

Android Accessibility API가 존재한다는 사실(`CONFIRMED`)과, **현재 공식 ChatGPT Android 앱의 composer가 실제로**

- editable 노드를 노출하는가
- `ACTION_SET_TEXT`를 지원하는가
- Send 버튼을 접근성 트리에서 찾을 수 있는가
- `ACTION_CLICK`이 안정적으로 동작하는가

는 **별개 문제**이며 모두 `DEVICE_TEST_REQUIRED`(GV-11~13)다. 실기기에서 검증되기 전에는 `SUPPORTED`라고 쓰지 않는다.

### Flow

```text
prepare(turnId)
  1. BridgeHealth 확인: Accessibility 서비스 활성? com.openai.chatgpt 설치?
     → 아니면 NotReady(recoverable=false) → SM은 Plan B
  2. 잠금 상태면 NotReady("locked", recoverable=true) → SM은 큐잉
  3. VoiceInteractionSession.startAssistantActivity(launcherIntentOf("com.openai.chatgpt"))
     - warm: 직전 화면(대화) 복귀 기대 — DEVICE_TEST_REQUIRED (GV-14)
     - cold: 새 대화/목록 가능 — NOT GUARANTEED (GV-15)
  4. TYPE_WINDOW_STATE_CHANGED (packageName == com.openai.chatgpt) 대기
  5. composer 노드 탐색 (Selector Stack) — 최대 4 s, 200 ms 폴링 (초기값)
     → 찾으면 Ready, 못 찾으면 NotReady("composer not found")

send(turnId, text)
  6. sentFlag[turnId] 검사 → 이미 true면 SendFailed("duplicate call")
  7. ACTION_SET_TEXT(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE = text)
     실패 → ACTION_FOCUS → 클립보드 → ACTION_PASTE (클립보드 사용 로그, 즉시 비움)
     그래도 실패 → InjectionFailed
  8. 검증: composer.text == text 이고 Send 노드가 isEnabled 될 때까지 대기 (최대 1.5 s 초기값)
     불일치 → 재주입 1회 → 재실패 시 InjectionFailed
  9. Send 노드 탐색 (Selector Stack)
     못 찾음 → InjectedButNotSent("send node not found")
 10. sentFlag[turnId] = true  ← 클릭 **전에** 설정
 11. ACTION_CLICK 1회
 12. 300 ms(초기값) 내 composer가 비면 → Sent(composerVerified=true)
     비지 않으면 → SendFailed("composer not cleared")  ← 재클릭 금지
```

### Selector Stack

우선순위 순으로 시도하고, 성공한 규칙을 `lastSuccessfulSelector`로 저장해 다음 turn에 먼저 시도한다.

```text
1. Accessibility semantic information
   (className, isEditable, isClickable, isEnabled, isVisibleToUser, isFocusable)
2. editable / clickable / visible state 조합
   composer: isEditable && isVisibleToUser && isFocusable, 화면 하단 40 % 안 (초기값)
   send   : composer와 같은 부모 또는 형제 subtree, isClickable
3. contentDescription
   Send 후보 문자열 집합은 설정 데이터로 외부화 (예: "Send", "보내기", "전송" — 추측값, 실기기 dump로 확정)
4. hierarchy relationship
   composer의 부모/형제 관계
5. screen-relative location heuristic
   화면 크기 대비 상대 위치. 절대 좌표 금지
6. calibrated coordinate
   온보딩 "브릿지 보정"에서 사용자가 지정한 화면 상대 좌표 → dispatchGesture. 최후 폴백
```

**금지**: 고정 absolute 좌표. 추측한 `resource-id`(Compose 앱은 `testTagsAsResourceId` 없이는 없음).

### Success criteria

- `Sent(composerVerified=true)` 반환.
- GV-13에서 warm start 10회 중 9회 이상 성공(초기 목표).

### Failure criteria

- composer 노드 미발견(GV-11 실패) → **Plan A 폐기 검토**, Plan B 기본화.
- `ACTION_SET_TEXT` 미동작 + 클립보드 paste도 미동작(GV-12 실패) → 동일.
- Send 노드는 찾았으나 클릭 후 composer가 비지 않음 → `SendFailed`, 사용자 확인.
- 연속 실패 3회(초기값) → `BridgeSelector`가 Plan B로 자동 전환하고 알림.

### Security implication

- Accessibility 권한은 원칙적으로 모든 앱 화면을 읽을 수 있다. `packageNames = ["com.openai.chatgpt"]`로 서비스 필터를 제한하고, `canRetrieveWindowContent`만 사용한다.
- 클립보드 폴백은 텍스트를 다른 앱이 읽을 수 있는 상태로 잠시 둔다. 사용 즉시 비우고 로그에 남긴다.
- 상세: [SECURITY_PRIVACY.md](SECURITY_PRIVACY.md) §6.

### Maintenance risk

- ChatGPT 앱 업데이트마다 노드 구조·contentDescription이 바뀔 수 있다. `PACKAGE_REPLACED` 감지 시 `healthCheck()` 재실행 + 온보딩 배지 갱신.
- Samsung 키보드 자동완성 툴바 등 One UI 요소가 트리를 흔들 수 있다(DEVICE_TEST_REQUIRED).

### Fallback

Plan B → Plan C(설정 시) → 클립보드 + 알림.

---

## 4. Plan B — Share Intent (`ShareIntentBridge`)

### Purpose

Accessibility 없이 텍스트를 ChatGPT 앱에 전달한다.

### Status

```text
DEVICE_TEST_REQUIRED (share target 존재 여부: GV-21)
same conversation preservation: NOT GUARANTEED
automatic send: NOT GUARANTEED
```

### Flow

```text
prepare: com.openai.chatgpt 가 ACTION_SEND text/plain 을 받는지 확인 (PackageManager)
send:
  Intent(ACTION_SEND).setType("text/plain").setPackage("com.openai.chatgpt").putExtra(EXTRA_TEXT, text)
  → VoiceInteractionSession.startAssistantActivity(intent)
  → Accessibility 서비스가 살아 있으면 Selector Stack으로 Send 클릭 시도 (Plan A 8~12단계)
  → 없으면 InjectedButNotSent("user must tap send")
```

- 새 대화가 열릴 가능성이 높다. 텍스트 앞에 "(이전 질문 이어서)" 같은 접두어는 **넣지 않는다**(사용자 텍스트 오염 금지).

### Success criteria

ChatGPT 앱이 텍스트가 채워진 composer와 함께 열린다.

### Failure criteria

Share target 없음(GV-21 실패) → Plan C 또는 클립보드.

### Security implication

Intent extra로 텍스트 전달. 클립보드 미사용.

### Maintenance risk

낮음. ChatGPT 앱이 share target을 제거하면 깨짐.

### Fallback

Plan C(설정 시) → 클립보드 + 알림.

---

## 5. Plan C — Web / 기타 (emergency fallback)

### Purpose

앱이 없거나 앱 경로가 모두 실패했을 때 최후 수단.

### Status

```text
EMERGENCY FALLBACK ONLY
v0.1: 설정에서 사용자가 켠 경우만
same conversation: NOT GUARANTEED
automatic send: NOT GUARANTEED (COMMUNITY_REPORTED: chatgpt.com/?q= 자동 제출은 외부 진입 시 차단될 수 있음)
```

### Flow

`Custom Tabs`로 `https://chatgpt.com/?q=<urlencoded>`. 브라우저 로그인 세션 필요. 길이 제한(수천 자 수준, COMMUNITY_REPORTED 431 오류) 적용.

### Success / Failure

브라우저가 열리고 텍스트가 보이면 성공. 자동 전송은 기대하지 않는다.

### Security implication

URL에 질문 텍스트가 들어간다(브라우저 히스토리 기록). 사용자가 명시적으로 켠 경우만.

### Maintenance risk

OpenAI 웹 변경에 종속. 유지보수 대상 아님.

### Fallback

클립보드 + 알림("질문을 복사했습니다").

---

## 6. Future — `FutureOfficialChatGptBridge`

OpenAI가 다음 중 하나를 제공하면 Plan A를 대체한다.

- ChatGPT 앱 deep link(composer prefill + 대화 지정)
- 문서화된 intent / Android automation API
- 3rd-party assistant integration contract

아키텍처는 이를 막지 않는다. `ChatBridge` 인터페이스만 만족하면 SM·EndpointDetector·오디오 파이프라인 변경 없이 교체된다.

---

## 7. Idempotent Send — 핵심 원칙

```text
Missing send is better than duplicate send.
```

- `turnId`별 `sentFlag`는 **클릭 전에** true로 설정한다. 클릭 후 예외가 나도 재클릭 경로가 없다.
- `SendFailed`("composer not cleared")는 실제로 전송됐을 수도 있다. 자동 재시도하면 중복 전송 위험 → **절대 자동 retry 금지**. 사용자에게 "전송 확인 필요" 알림.
- SM 레벨에서도 같은 `turnId`로 `send()`를 두 번 호출하지 않는다([ARCHITECTURE.md](ARCHITECTURE.md) INV-5).
- GV-16(중복 전송 테스트)에서 0건이 아니면 v0.1 출시 불가.

---

## 8. Onboarding — Bridge 보정

1. Accessibility 서비스 활성화 안내 (Android 13+ 사이드로드 시 "제한된 설정 허용" 선행 — [ANDROID_CONSTRAINTS.md](ANDROID_CONSTRAINTS.md) §9).
2. ChatGPT 앱을 열고 대화 화면으로 이동.
3. 앱이 노드 dump를 수행해 composer/Send 후보를 표시. 사용자가 확인.
4. 실패 시 사용자가 composer·Send 위치를 탭으로 지정 → 화면 상대 좌표 저장(Selector 6).
5. 테스트 문장("테스트")을 실제로 넣지 않고 **주입 → 검증 → 삭제**까지만 수행. Send는 누르지 않는다.

---

## 9. 확정하지 않은 것

| 항목 | 태그 | 검증 |
|---|---|---|
| composer editable 노드 노출 | DEVICE_TEST_REQUIRED | GV-11 |
| `ACTION_SET_TEXT` 동작 | DEVICE_TEST_REQUIRED | GV-12 |
| Send 노드 contentDescription 실제 값 | DEVICE_TEST_REQUIRED (추측 금지) | GV-11 dump |
| `ACTION_CLICK` 후 composer 비움 타이밍 | DEVICE_TEST_REQUIRED | GV-13 |
| warm/cold start 대화 복귀 | DEVICE_TEST_REQUIRED / NOT GUARANTEED | GV-14, GV-15 |
| share target 존재 | DEVICE_TEST_REQUIRED | GV-21 |
| Samsung 키보드 툴바의 트리 영향 | DEVICE_TEST_REQUIRED | GV-12 |
| 폴링 간격·타임아웃 값 | DEVICE_TUNABLE | |

---

## 10. Go / No-Go Rule (Spike S-3 결과 처리)

결정 근거: [DECISIONS.md](DECISIONS.md) ADR-015. Spike 절차: [SPIKE_TEST_GUIDE.md](SPIKE_TEST_GUIDE.md).

### 10.1 분기 규칙

```text
S-3 Accessibility 성공
→ Official ChatGPT App Bridge 방식 계속 진행

S-3 Accessibility 실패
→ Plan B Share Intent 테스트

Plan B가 자동 Send까지 가능
→ degraded bridge로 계속 가능

Plan B도 사용자 탭을 요구
→ 원래 제품 목표인 fully hands-free ChatGPT App Bridge는 NO-GO
```

### 10.2 "성공"의 정의 (추측 금지, 실기기 관측만)

**Plan A 성공 =** 아래 네 가지가 **모두** 실기기에서 관측될 때.

| # | 관측 항목 | 판정 근거 | GV |
|---|---|---|---|
| A1 | composer 후보가 `isEditable && isVisibleToUser`로 발견된다 | Node Inspector 출력 | GV-11 |
| A2 | `ACTION_SET_TEXT` 후 해당 노드의 `text`가 주입값과 일치한다 | Set Text 재조회 | GV-12 |
| A3 | Send 후보가 접근성 트리에서 발견되고 `isEnabled`가 된다 | Node Inspector 출력 | GV-11/13 |
| A4 | `ACTION_CLICK` **1회** 후 composer가 비워진다 | 300 ms / 1 s / 2 s 재조회 | GV-13 |

A1~A3은 되는데 A4만 안 되면 `InjectedButNotSent`이며, **자동 Send 불가**로 분류한다(= Plan A 실패). 재클릭으로 성공시키지 않는다(INV-5).

**Plan B 자동 Send 가능 =** Share Intent로 텍스트가 채워진 뒤, **사용자 탭 없이** 전송이 완료될 때. 실제로는 Accessibility가 살아 있어야 가능하므로, Plan A가 실패한 상태에서 Plan B만으로 자동 Send가 되는 경우는 흔치 않다. **이 항목은 관측으로만 판정한다.**

### 10.3 판정표

| Plan A (A1~A4) | Plan B 자동 Send | 판정 | 다음 단계 |
|---|---|---|---|
| 모두 PASS | — | **GO** | Plan A로 v0.1 진행 |
| 실패 | PASS | **GO (degraded)** | Plan B 기본화. "같은 대화 유지" 목표를 낮춘다 |
| 실패 | 사용자 탭 필요 | **NO-GO** | §10.4 |
| 미실행 / 로그 불충분 | — | `INCONCLUSIVE` | PASS로 기록하지 않는다. 재실행 |

### 10.4 NO-GO 시 행동

```text
Official ChatGPT App Bridge v0.1 = NO-GO
```

1. 이 판정을 [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md) GV-11~13, GV-21 Result와 [RISK_REGISTER.md](RISK_REGISTER.md) R-01에 기록한다.
2. **전체 v0.1을 억지로 구현하지 않는다.**
3. **임의로 OpenAI API 버전으로 전환하지 않는다.** 사용자에게 아래 선택지를 제시한다.

| 선택지 | 유지되는 것 | 포기하는 것 | 영향 문서 |
|---|---|---|---|
| **OpenAI API Bridge** | hands-free, Natural Endpoint | 공식 앱 UI, 기존 대화, 구독 UI. 새 ADR + PRODUCT_SPEC §1 수정 필요 | ADR-006/007 supersede |
| **공식 integration 대기** | 아키텍처 전부. S-1/S-2 파이프라인만 완성 | 지금 당장의 종단 UX | MVP_PLAN Phase 2 축소 |
| **반자동 degraded mode** | 공식 앱 UI, 기존 대화(Plan A 부분 성공 시) | fully hands-free (Send 탭 1회) | PRODUCT_SPEC §2 수정 |

Spike 단계에서 이 선택을 대신 내리지 않는다.
