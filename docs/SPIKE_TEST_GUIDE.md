# SPIKE_TEST_GUIDE — Galaxy Technical Spike 실기기 절차

- 문서 상태: **작업 지시서** (결과는 [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md)에 기록)
- 상위: [MVP_PLAN.md](MVP_PLAN.md) §1 Phase 1 · 판정: [CHATGPT_BRIDGE.md](CHATGPT_BRIDGE.md) §10 / [DECISIONS.md](DECISIONS.md) ADR-015

이 문서는 Phase 1 Spike APK를 Galaxy에서 돌리는 순서다. **결과는 여기에 쓰지 않는다.** GV 항목의 Result에 쓴다.

---

## 0. 가장 중요한 규칙

```text
실행하지 않은 항목은 PASS가 아니다.
로그가 불충분하면 INCONCLUSIVE다.
PASS / FAIL / INCONCLUSIVE 셋만 쓴다.
```

이 Spike의 성공은 "코드가 많다"가 아니라 **"세 가설을 빠르게 반증할 수 있는 APK가 있다"**이다.

---

## 1. 이번 Spike가 검증하는 가설

| Spike | 가설 | 틀렸을 때의 의미 | 위험 |
|---|---|---|---|
| S-1 | VIS가 시작한 FGS는 **화면 OFF에서도 무음이 아닌** 마이크 입력을 계속 받는다 | hands-free 제품 자체가 성립하지 않는다. 폴백 없음 | R-02 |
| S-2 | Galaxy recognizer가 `EXTRA_AUDIO_SOURCE`(Mode P)로 우리 오디오를 받는다. 아니면 Mode H가 실사용 가능하다 | Mode P 실패는 NO-GO가 아니다. Mode H가 쓸 만하면 PASS | R-05 |
| S-3 | 공식 ChatGPT 앱이 editable composer와 클릭 가능한 Send 노드를 노출한다 | Plan A 폐기. Plan B도 자동 Send 불가면 **제품 목표 NO-GO** | R-01 |

---

## 2. 이번 Spike에서 구현한 것 / 하지 않은 것

**구현함**

```text
core/   TurnId · TurnGate(INV-3) · SendGuard(INV-5) · ChatBridge 타입
        WakeWordEngine / SpeechEngine 인터페이스 · DebugLog ring buffer · SpikeConfig
app/    VIS + SessionService + Session UI + RecognitionService stub
        AudioCaptureService(FGS microphone, 단일 AudioRecord) + RingBuffer + pre-roll
        PorcupineWakeWordEngine + DisabledWakeWordEngine
        Mode P / Mode H speech probe (콜백 타임라인 기록)
        ChatGptAccessibilityService + Node Inspector + SET_TEXT / Send probe
        Share Intent probe · warm/cold launch probe
        debug dashboard · 권한/역할 상태 · 로그 뷰어 · export
```

**구현하지 않음 (의도적)**

```text
SessionStateMachine 전체      EndpointDetector / EndingClassifier / grace / adaptive
CommandParser(보내/취소/다시)  VadEngine 구현        Follow-up mode
TTS   Windows   Whisper   OpenAI API   제품 Settings/온보딩   production UI
```

S-2의 `onResults`는 화면에 표시만 하고 **전송에 연결하지 않는다**(INV-6).

---

## 3. 준비

### 3-1. 빌드

```bash
./gradlew :core:test        # pure Kotlin. SDK 없이도 실행됨
./gradlew assembleDebug     # Android SDK 필요
./gradlew lint              # 리포트: app/build/reports/lint-results-debug.html
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Android SDK가 없으면 `:app`은 빌드에서 제외되고 그 사실이 콘솔에 출력된다(`settings.gradle.kts`). `local.properties`의 `sdk.dir` 또는 `ANDROID_HOME`을 설정한다.

### 3-2. Porcupine (선택)

`app/src/main/assets/wakeword/README.md` 참조. 없어도 앱은 정상 동작하며 **GV-03은 그대로 측정 가능**하다. GV-02만 불가.

### 3-3. 설치 경로를 기록한다

GV-20(Restricted Settings)은 설치 경로에 따라 결과가 다르다. 둘 다 시도하고 각각 기록한다.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk    # 경로 A
# 경로 B: APK를 기기로 복사해 파일 관리자로 설치
```

---

## 4. 절차

### A. 설치 후 첫 화면

앱을 연다 → **Galaxy Technical Spike** 대시보드.

기록: 기기 모델, One UI/Android 버전(상단에 표시), ChatGPT 앱 버전.

### B. 권한

`Grant microphone + notifications` → 허용.

기록: Microphone ✓ / Notification ✓

### C. Assistant Role — **GV-01**

1. `Set as digital assistant (role request)` 시도. 다이얼로그가 없으면
2. `Open assistant settings` → 디지털 어시스턴트 앱 → **AURA Spike** 선택 (Bixby 대체).

확인:

```bash
adb shell settings get secure assistant
adb shell settings get secure voice_interaction_service
adb shell dumpsys voiceinteraction | head -40
```

대시보드에서 `Assistant role ✓` **그리고** `VIS bound by system ✓` 둘 다 볼 것.

- 목록에 앱이 없으면 → `RecognitionService` stub 선언 문제. GV-01 FAIL.
- `VIS bound`가 ✗면 `onReady()`가 안 온 것. 이후 S-1은 의미가 없다.

> **중요**: `Start capture manually` 버튼으로 시작한 캡처는 GV-03의 근거가 **아니다**. while-in-use 마이크 예외는 "VIS가 시작한 FGS"에 적용되므로, 반드시 `onReady()` 경로로 시작된 상태에서 측정한다.

### D. S-1 — Assistant / Wake

#### D-1. 화면 ON — GV-02 (Porcupine 있을 때만)

S-1 화면에서 `wake status = Running` 확인 → "헤이 지피티" 10회.

기록: 감지 횟수 / 10, sensitivity, 오탐(일반 대화 30분 + 기술 영상 30분).

Porcupine이 없으면 **GV-02는 `NOT TESTED`**. 추정치를 쓰지 않는다.

#### D-2. 화면 OFF — **GV-03 (S-1의 핵심)**

1. 대시보드에서 `Battery optimization` 예외 처리.
2. Samsung Device care → 배터리 → 백그라운드 사용 제한 → **Never sleeping apps**에 추가 (앱에서 조회 불가 — 직접 확인).
3. 잠금 해제 상태에서 **화면만 OFF**.
4. 1분 / 5분 / 30분 후 폰 근처에서 말한다.
5. 화면을 켜고 **Logs → S-1 only**에서 `AudioLevel/Telemetry` 라인을 읽는다.

판정:

```text
PASS  peak/rms가 목소리에 따라 변하고 zeroFrames가 늘지 않음
      liveness = LIVE
FAIL  consecZero가 계속 증가, liveness = SILENT
      → logcat에서 다음을 찾을 것:
        adb logcat | grep -i "can not have"
        "Foreground service started from background can not have
         location/camera/microphone access"
```

이 구분이 이 Spike 전체에서 가장 중요하다. **서비스가 살아 있는 것과 오디오가 들어오는 것은 다른 문제다.** 앱은 zero-frame과 quiet-frame을 따로 센다.

#### D-3. Doze — GV-05

```bash
adb shell dumpsys deviceidle force-idle
```

이후 반응 여부와 지연을 기록. (Porcupine 없으면 AudioLevel 로그로 대체 관찰)

#### D-4. 잠금 화면 — GV-04

화면 잠금 상태에서 `Test wake feedback` 또는 Wake → 세션 UI가 잠금 위에 보이는지 기록.

### E. S-2 — Audio → STT

#### E-1. Provider — GV-08

S-2 화면 상단에서 provider / on-device 가용성 확인, `Check recognition support (ko-KR)` 실행.

```bash
adb shell settings get secure voice_recognition_service
```

**provider 컴포넌트명을 반드시 기록한다.** Mode P 지원 여부는 Android가 아니라 이 provider의 성질이므로, provider 없는 결과는 재현 불가능하다.

> ⚠️ **자기 stub 오인 함정.** AURA는 어시스턴트 목록에 뜨기 위해 `GptRecognitionService` stub을 선언하며, 이 stub은 호출되면 설계상 즉시 `ERROR_CLIENT`를 반환한다. AURA를 기본 어시스턴트로 지정한 뒤 `voice_recognition_service`가 이 stub으로 바뀌면, S-2는 Galaxy STT가 아니라 **우리 자신의 실패**를 측정하게 되고 "Galaxy가 `EXTRA_AUDIO_SOURCE`를 지원하지 않는다"는 **완전히 잘못된 결론**이 나온다.
>
> 앱이 이를 감지해 provider가 우리 패키지면 화면에 빨간 경고를 띄우고 외부 recognizer를 자동으로 pin한다(`createSpeechRecognizer(Context, ComponentName)`). 그래도 실행 전에 **`Recognizer:` 버튼의 값이 우리 패키지가 아닌지 눈으로 확인**하고, 그 값을 GV-08/09/10 결과에 함께 적는다. pin되지 않은 상태에서 나온 실패는 `INCONCLUSIVE`다.

#### E-2. Mode P — **GV-09**

1. S-1에서 캡처가 돌고 있어야 한다.
2. `Start Mode P (pipe)` → 즉시 한국어로 말한다.
3. Verdict 줄과 Timeline을 읽는다.

```text
PASS          partial이 오고 첫 음절이 살아 있음
FAIL          ERROR_CLIENT 또는 즉시 종료
INCONCLUSIVE  4초 안에 콜백이 하나도 없음
              → recognizer가 자기 마이크를 연 것인지 확인:
                adb shell dumpsys audio | grep -iE "record|client"
```

Mode P 실패는 **NO-GO가 아니다**(MVP_PLAN §1). Mode H로 넘어간다.

#### E-3. 세그먼트 — GV-10

Mode P가 동작하면 20초 발화 중 2초 휴지 3회. `onSegmentResults`가 3회 이상 오는지 기록.

#### E-4. Mode H — GV-09 대체 경로

`Start Mode H (handoff)` → **비프음 뒤에** 말한다.

기록: 비프까지 지연, 첫 음절 손실 체감, partial/final 도착 시각, 종료 후 캡처 복귀 여부(S-1 화면의 `liveness`가 LIVE로 돌아오는지).

#### E-5. pre-roll

`preRollMs`를 200 / 300 / 500으로 바꿔가며 Mode P에서 첫 음절 보존을 비교. **제품 상수가 아니다**(INV-8).

### F. S-3 — ChatGPT Bridge

> 이 절은 실제 대화에 메시지를 보낼 수 있다. 테스트용 대화를 하나 열어두고 진행한다.

#### F-0. Accessibility 활성화 — GV-20

`Open accessibility settings` → **AURA Spike — ChatGPT bridge** 켜기.

차단되면: `Open app info` → ⋮ → **제한된 설정 허용** → 다시 시도.
이것은 의도된 보안 장치다. **우회하지 않는다.** 설치 경로 A/B의 차이를 기록한다.

#### F-1. Node Inspector — **GV-11**

1. ChatGPT를 열고 대화 화면으로 이동 → 앱으로 복귀.
2. `Inspect ChatGPT accessibility tree`.

기록(각 후보 노드):

```text
className · text 여부/길이 · hintText · contentDescription
isEditable · isClickable · isEnabled · isFocusable · isVisibleToUser
bounds · supported actions
```

**Send 후보의 contentDescription 실제 값을 반드시 적는다.** 설계 문서가 "추측 금지"라고 못박은 항목이다.

```text
editable 노드 없음  → A1 FAIL → Plan A는 설계대로 동작 불가
```

> 앱은 editable/clickable 노드만 수집한다. ChatGPT 답변 본문은 수집되지 않는다(SECURITY_PRIVACY §6).

#### F-2. ACTION_SET_TEXT — **GV-12**

`Inject Test Text` (기본값 `AURA accessibility bridge test`). **전송되지 않는다.**

기록: `performAction` 반환값, composer 재조회 텍스트, 일치 여부, 소요 시간.
Samsung 키보드 표시/비표시 각각 1회씩.

#### F-3. Send — **GV-13**

`Inject + Send Test` → 확인 다이얼로그 → `Send once`.
기본 텍스트: `AURA bridge diagnostic test. Reply with OK.`

기록: 클릭 반환값, `t+300ms / 1s / 2s` 각 시점의 composer 비움 여부, 최종 결과 타입.

```text
Sent(composerVerified=true)  → A4 PASS
SendFailed                   → 앱은 재클릭하지 않는다.
                               ChatGPT를 직접 열어 실제로 갔는지 확인하고
                               사실대로 기록한다.
```

**절대 "다시 눌러서 되게 만들지" 않는다.** 중복 전송 0건은 조정 불가 기준이다(S8).

#### F-4. Warm / Cold — GV-14 / GV-15

```text
Warm : ChatGPT에서 특정 대화를 연 뒤 홈으로 → 앱에서 `Warm launch`
Cold : adb shell am force-stop com.openai.chatgpt → 앱에서 `Cold launch`
```

각각 기록 (택1, 추측 금지):

```text
same conversation? / conversation list? / new chat? / other?
```

로그에서 `path=startAssistantActivity`인지 확인한다. `path=startActivity(foreground fallback)`이면 어시스턴트 경로가 아니므로 **GV-14/15로 기록하지 않는다.**

#### F-5. Share Intent — **GV-21**

`Query share target` → 존재 여부와 액티비티 목록 기록.

```bash
adb shell pm query-activities -a android.intent.action.SEND -t text/plain | grep -i openai
```

`Share test text to ChatGPT` 후 기록:

```text
target exists?  ChatGPT opens?  text prefilled?
new conversation? same conversation?
auto send?      user tap required?
```

마지막 두 줄이 **ADR-015의 분기를 결정한다.**

### G. 로그 수집

`Logs → Copy diagnostics` 또는 `Export diagnostics`.

```bash
adb logcat -s GptVoice:V ActivityManager:I AudioFlinger:W > gv-<항목>.log
```

Transcript 원문 로깅은 기본 OFF다. 켜면 export에 발화 원문이 들어가므로, 공유 전에 확인한다.

---

## 5. Go / No-Go

### S-1

```text
PASS  Assistant 등록 가능
      + screen ON wake 가능
      + screen OFF wake 가능 (또는 최소한 screen OFF에서 오디오가 무음이 아님)
      + AudioCaptureService 생존
FAIL  → 핵심 wake architecture 재검토. 이 기반에는 폴백이 없다(ADR-001).
```

Porcupine 자산이 없어 wake를 못 돌린 경우: 오디오 liveness만 PASS로 기록하고 wake 항목은 `NOT TESTED`.

### S-2

```text
PASS  Mode P 또는 Mode H 중 하나로 실사용 가능한 STT 시작이 가능하고
      첫 음절 손실이 허용 수준
```

**Mode P 실패 자체는 NO-GO가 아니다.** Mode H가 usable하면 PASS.

### S-3

```text
PASS  composer 탐색 + text injection + Send action 이 모두
      실제 ChatGPT Android 앱에서 안정적으로 동작
      또는 Plan B가 hands-free automatic send까지 실제로 제공

NO-GO Plan A 실패 + Plan B도 사용자의 Send tap이 필요
      → Official ChatGPT App Bridge v0.1 = NO-GO
```

판정표와 NO-GO 이후 절차: [CHATGPT_BRIDGE.md](CHATGPT_BRIDGE.md) §10.3–10.4.

NO-GO면 **전체 v0.1 구현을 계속하지 않는다.** 임의로 OpenAI API로 전환하지도 않는다. 세 선택지(API Bridge / 공식 integration 대기 / 반자동 degraded)를 사용자에게 제시하고 결정을 받는다.

---

## 6. 결과 기록

| 이 가이드 | 기록 위치 |
|---|---|
| C | GV-01 |
| D-1 | GV-02 |
| D-2 | GV-03 · R-02 |
| D-3 | GV-05 |
| D-4 | GV-04 |
| E-1 | GV-08 |
| E-2 / E-4 | GV-09 · R-05 |
| E-3 | GV-10 |
| F-0 | GV-20 · R-12 |
| F-1 | GV-11 · R-01 |
| F-2 | GV-12 |
| F-3 | GV-13 · R-08 |
| F-4 | GV-14 · GV-15 · R-10 |
| F-5 | GV-21 |

반영 규칙은 [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md) §6. 실기기 결과가 없으면 `NOT TESTED`로 남기고, **꾸며내지 않는다.**
