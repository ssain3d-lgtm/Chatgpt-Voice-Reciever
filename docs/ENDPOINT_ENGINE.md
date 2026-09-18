# ENDPOINT_ENGINE — Natural Endpoint 설계

- 문서 상태: **Authoritative** (파라미터 값은 전부 초기 실험값)
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) §9.4 · 결정: [DECISIONS.md](DECISIONS.md) ADR-005 · 검증: [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md) GV-19

**이 문서의 모든 숫자는 `DEFAULT_INITIAL_VALUE` / `DEVICE_TUNABLE` / `NOT A PRODUCT CONSTANT`다.** 특히 hard cap 3000 ms는 확정값이 아니다. 실기기에서 3000 / 4000 / 5000 ms를 비교한 뒤 갱신한다.

---

## 1. 문제 정의

사용자가 "전송"이라고 말하지 않아도, 앱이 **말이 끝났다**고 판단해야 한다.

두 종류의 실패가 있고, 이 제품에서는 **false endpoint가 더 나쁘다**.

| 실패 | 정의 | 사용자 경험 |
|---|---|---|
| **False endpoint** (너무 빨리 끊음) | 사용자가 생각하느라 멈춘 사이 전송 | 반쪽 질문이 ChatGPT에 감. 되돌릴 수 없음 |
| **Late endpoint** (너무 오래 기다림) | 말이 끝났는데 hard cap까지 대기 | 답답함. `보내`로 복구 가능 |

사용자는 문장 중간에서 자주 생각한다("FastH3하고… 음… 기존 방식하고"). 그래서 어미·연결어 분석 없이 고정 silence 타이머만 쓰면 false endpoint가 잦다.

---

## 2. 입력과 출력

`EndpointDetector`는 **pure Kotlin**이다. 오디오 프레임을 받지 않는다.

```text
입력
  VadEvent        : SpeechOnset, SpeechEnd, SilenceTick(silenceMs)   ← VadEngine
  TranscriptEvent : Partial(text, tsMs), Segment(text, tsMs)         ← SpeechEngine
  Settings        : EndpointThresholds

출력
  EndpointEvent.Possible(turnId, reason, class)   → SM: LISTENING → POSSIBLE_ENDPOINT
  EndpointEvent.Confirmed(turnId)                 → SM: POSSIBLE_ENDPOINT → ENDPOINT_GRACE
  EndpointEvent.Resumed(turnId)                   → SM: → LISTENING
  EndpointEvent.HardCap(turnId)                   → SM: → ENDPOINT_GRACE
```

`SpeechEvent.Final`(recognizer가 자체 endpointing으로 세션을 끝낸 결과)은 **`Segment`로 취급**한다. 전송 신호가 아니다([ARCHITECTURE.md](ARCHITECTURE.md) INV-6).

---

## 3. 3층 구조

```text
Layer 0  Acoustic     VAD trailing silence (ms), speech resumed
Layer 1  Linguistic   마지막 어절의 어미 형태 → EndingClass
Layer 2  Stability    partial transcript가 마지막으로 바뀐 뒤 경과 시간
```

각 층은 "기다림 시간(threshold)"에 기여하고, 판정은 다음과 같다.

```text
threshold = base(EndingClass)                      // Layer 1
          + stabilityPenalty(stabilityMs)          // Layer 2
          × userSpeedFactor                        // v0.2 adaptive, v0.1은 1.0

if silenceMs >= min(threshold, hardCap):  → Possible
```

### 3.1 Layer 0 — VAD

- 엔진: Silero VAD (ONNX) 또는 WebRTC VAD. `VadEngine` 인터페이스 뒤.
- **활성 시점: Wake 이후에만.** IDLE에서는 돌리지 않는다([ARCHITECTURE.md](ARCHITECTURE.md) §4.5, [DECISIONS.md](DECISIONS.md) ADR-013).
- 프레임: 30 ms (초기값). 출력: `SpeechOnset`, `SpeechEnd`, `SilenceTick(silenceMs)`(100 ms 간격).
- 왜 `SpeechRecognizer`의 `onRmsChanged`를 쓰지 않는가: RMS만 주고 무음 길이를 주지 않는다. 자체 VAD가 있어야 무음 ms를 정확히 재고, recognizer가 세션을 먼저 끊어도 우리 판단을 유지하며, Windows에서 같은 로직을 재사용한다.

### 3.2 Layer 1 — Korean EndingClassifier

```kotlin
enum class EndingClass {
    FINAL_IMPERATIVE,   // 확인해줘, 정리해봐, 알려주세요
    FINAL_QUESTION,     // 뭐야?, 되나, 가능한가요
    FINAL_DECLARATIVE,  // 궁금해, 알고 싶어, 그렇다
    CONNECTIVE,         // FastH3하고, 기존 방식이, 그런데
    FILLER,             // 음, 어, 저기
    UNKNOWN             // FastH3, H3, 고유명사
}

interface EndingClassifier {
    fun classify(lastEojeol: String, fullTranscript: String): EndingClass
}
```

**원칙**: 단순 단어 목록으로 판단하지 않는다. **마지막 어절**을 오른쪽에서 왼쪽으로 어미 패턴 매칭한다.

규칙은 코드에 하드코딩하지 않고 외부 데이터로 둔다(초기 형식 제안: `endpoint/ko/endings.yaml`). 아래는 예시이며 데이터 파일이 authoritative다.

| 클래스 | 어절 끝 패턴 (예시) | 예 |
|---|---|---|
| FINAL_IMPERATIVE | `줘`, `주세요`, `해`, `해라`, `봐`, `봐줘`, `보세요` | 확인해줘, 정리해봐 |
| FINAL_QUESTION | `?`, `까`, `나`, `니`, `가요`, `나요`, `습니까`, `는데?` | 되나, 맞아?, 가능한가 |
| FINAL_DECLARATIVE | `다`, `야`, `요`, `어`, `네`, `지`, `거든`, `싶어` | 궁금해, 알고 싶어 |
| CONNECTIVE (어미) | `고`, `는데`, `ㄴ데`, `서`, `면`, `면서`, `니까`, `지만` | 확인하고, 그런데 |
| CONNECTIVE (조사로 끝) | `하고`, `이랑`, `랑`, `과`, `와`, `에서`, `에`, `을`, `를`, `이`, `가`, `은`, `는`, `도` | FastH3하고, 기존 방식이 |
| CONNECTIVE (접속 어절) | 어절 전체가 `그리고`, `그런데`, `근데`, `그러니까`, `그래서`, `그럼`, `일단`, `그` | 그리고… |
| FILLER | 어절 전체가 `음`, `어`, `아`, `저기`, `잠깐`, `그게`, `뭐지`, `뭐더라` | 음… |
| UNKNOWN | 그 외 (영문 토큰, 숫자, 고유명사) | FastH3 |

**양면성 처리 (리뷰 §C-4 반영)**

- `하고`: "…하고 싶어"는 FINAL 앞에 오지만, 그 경우 마지막 어절은 "싶어"다. **어절 끝 기준**으로만 CONNECTIVE.
- `그`: 단독 어절일 때만 CONNECTIVE/FILLER. "그 방법"의 "그"는 다음 어절이 오면 partial이 바뀌므로 Layer 2가 처리.
- 영문 토큰으로 끝남(FastH3, H3): UNKNOWN → 중간값 대기. 한국어 문장에서 영문 명사로 끝나는 경우는 대부분 미완결.
- 물음표: STT가 `?`를 붙이는지는 recognizer 의존(DEVICE_TEST_REQUIRED). 없어도 어미로 판단할 수 있어야 한다.

### 3.3 Layer 2 — Transcript Stability

- `stabilityMs` = 마지막으로 partial 텍스트가 **바뀐** 시점부터 경과 시간.
- `stabilityMs < 300 ms`이면 recognizer가 아직 쓰는 중 → threshold에 `+300 ms`(초기값).
- partial이 바뀌면 `POSSIBLE_ENDPOINT` → `LISTENING` 복귀.

---

## 4. 초기값 (전부 DEVICE_TUNABLE)

| 파라미터 | 초기값 | 태그 | 비고 |
|---|---|---|---|
| `threshold.FINAL_IMPERATIVE` | 600 ms | DEFAULT_INITIAL_VALUE | |
| `threshold.FINAL_QUESTION` | 600 ms | DEFAULT_INITIAL_VALUE | |
| `threshold.FINAL_DECLARATIVE` | 800 ms | DEFAULT_INITIAL_VALUE | |
| `threshold.UNKNOWN` | 1100 ms | DEFAULT_INITIAL_VALUE | |
| `threshold.CONNECTIVE` | 1800 ms | DEFAULT_INITIAL_VALUE | |
| `threshold.FILLER` | 2200 ms | DEFAULT_INITIAL_VALUE | |
| `hardCap` | **3000 ms** | **DEFAULT_INITIAL_VALUE — NOT A PRODUCT CONSTANT** | 실기기에서 3000 / 4000 / 5000 비교 (GV-19). false endpoint 비율이 높으면 올린다 |
| `graceMs` | 500 ms | DEFAULT_INITIAL_VALUE | §5 |
| `stabilityPenaltyMs` | 300 ms | DEFAULT_INITIAL_VALUE | |
| `noSpeechTimeoutMs` (Wake 후 무발화) | 4000 ms | DEFAULT_INITIAL_VALUE | false wake 복구 |
| `totalTurnTimeoutMs` | 30000 ms | DEFAULT_INITIAL_VALUE | 발화 전체 상한 |
| `preRollMs` | 300 ms | DEFAULT_INITIAL_VALUE | 오디오 파이프 (ARCHITECTURE §4.2) |
| `userSpeedFactor` | 1.0 (고정) | v0.1 | v0.2 adaptive |

모든 값은 `core.settings`의 `EndpointThresholds`에서 읽는다. 코드 상수로 두지 않는다([ARCHITECTURE.md](ARCHITECTURE.md) INV-8).

---

## 5. Grace Window

Endpoint 후보가 확인되어도 **바로 dispatch하지 않는다**.

```text
Possible endpoint (silence >= threshold)
→ Confirmed
→ ENDPOINT_GRACE (graceMs = 500 ms 초기값)
   ├── grace 중 VAD SpeechOnset 또는 partial 변경 → Resumed → LISTENING (텍스트 이어 붙임, 비용 0)
   └── grace 경과 → SUBMITTING
```

grace 값도 `DEFAULT_INITIAL_VALUE`다. 사용자가 "…확인해줘" 뒤에 "아, 그리고"를 붙이는 빈도를 실측해 조정한다.

---

## 6. Endpoint Score (v0.1은 threshold 방식, score는 예약)

v0.1은 위의 "클래스별 threshold vs silence" 비교로 충분하다. v0.2 이후 다음 피처를 가중합한 score로 확장할 수 있도록 `EndpointDetector` 내부에서 판정 함수를 분리한다.

```text
features (예약):
  silenceMs, endingClass, stabilityMs, transcriptLength,
  trailingIntonation (OPEN_QUESTION, v0.3+ — pitch 추출 비용)
```

---

## 7. CommandParser와의 관계

`CommandParser`는 EndpointDetector와 별개의 pure Kotlin 컴포넌트다. transcript의 **마지막 어절**만 검사한다.

| 명령 | 조건 | 동작 |
|---|---|---|
| `보내` / `전송` / `송부` (+ `줘`, `해줘` 변형) | 마지막 어절 | 즉시 `HardCap`과 동일하게 ENDPOINT_GRACE(grace 생략 가능, 초기값: grace 200 ms). 해당 어절은 전송 텍스트에서 제거 |
| `취소` | 마지막 어절 + 그 앞이 짧은 무음 | LISTENING → IDLE |
| `다시` | 마지막 어절 | transcript 폐기 → ARMED |

"예약 취소하는 방법 알려줘"의 "취소"는 마지막 어절이 아니므로 명령이 아니다. "취소"가 마지막 어절인데 문장 일부일 가능성(예: "이거 취소")은 v0.1에서 감수한다. 오인 사례는 로그로 수집한다.

---

## 8. 오류 복구

### 8.1 False endpoint (너무 빨리 끊음)

- dispatch 전: grace 창에서 복구(§5).
- dispatch 후: 되돌릴 수 없다. 이어지는 발화는 **다음 turn**(Wake Word 필요, v0.1). 그래서 threshold를 보수적으로(길게) 시작한다.

### 8.2 Late endpoint (너무 오래 기다림)

- `hardCap`이 상한. `보내` 명령이 즉시 복구.
- `totalTurnTimeoutMs` 도달 시 강제 endpoint.

### 8.3 STT 무응답

- `ERROR_SPEECH_TIMEOUT` / `ERROR_NO_MATCH` → 세그먼트 재시작 1회 → 재실패 시 "다시 말씀해 주세요" → IDLE.
- VAD가 speech를 감지하는데 partial이 3초(초기값) 이상 안 오면 recognizer 재시작.

### 8.4 Recognizer가 먼저 세션을 끊음

- `Final`을 `Segment`로 저장하고 즉시 `startListening()` 재호출(Mode H) 또는 pipe 유지(Mode P).
- 재시작 사이 공백(Mode H)에 들어온 음성은 손실될 수 있다 → RISK R-05.

---

## 9. 테스트 데이터셋

실기기 이전에 `core` 단위 테스트로 검증한다. 데이터셋은 저장소에 `core/src/test/resources/endpoint/ko/` (구현 시)로 둔다.

| 세트 | 내용 | 수량 (초기) |
|---|---|---|
| D1 완결 문장 | 명령형/의문형/서술형 각 10개 | 30 |
| D2 중간 정지 | 연결어·조사·접속 어절에서 1.5~3 s 멈춤 후 이어 말함 | 20 |
| D3 filler | "음…", "어…" 포함 | 10 |
| D4 영문 토큰 끝 | "FastH3", "H3 API" 등으로 끝나는 미완결 | 10 |
| D5 명령어 | 마지막 어절 `보내`/`취소`/`다시`, 문장 중간 `취소` | 10 |
| D6 실기기 녹음 | GV-19에서 수집한 실제 partial 타임라인 | 누적 |

각 항목은 `(partial 타임라인, VAD 타임라인, 기대 endpoint 시점 범위)`로 기술한다. 실기기 STT partial 로그를 그대로 재생(replay)할 수 있는 형식으로 만든다.

---

## 10. 평가 지표

| 지표 | 정의 | v0.1 목표 (초기) |
|---|---|---|
| False endpoint rate | 기대 시점보다 먼저 Confirmed 된 비율 (grace 복구 제외) | ≤ 10 % |
| Late endpoint rate | hardCap에 의해 끝난 비율 | ≤ 20 % |
| Median endpoint latency | 실제 발화 끝 → Confirmed | ≤ 1200 ms (FINAL 클래스) |
| Grace recovery rate | Confirmed 후 grace에서 복귀한 비율 | 기록만 (튜닝 입력) |
| Command misfire | 문장 일부를 명령으로 오인 | 0 (D5) |

hardCap 비교(GV-19)는 3000 / 4000 / 5000 ms 각각에 대해 위 지표를 D2 세트로 측정해 기록한다.

---

## 11. 확정하지 않은 것

| 항목 | 태그 |
|---|---|
| hardCap 최종값 | DEVICE_TUNABLE (3000/4000/5000 비교 후) |
| grace 최종값 | DEVICE_TUNABLE |
| VAD 엔진 선택 (Silero vs WebRTC) | OPEN_QUESTION — CPU/정확도 실측 |
| recognizer가 partial에 `?`를 붙이는지 | DEVICE_TEST_REQUIRED |
| 문말 억양 피처 | OPEN_QUESTION (v0.3+) |
| 사용자 속도 적응 | v0.2 |
