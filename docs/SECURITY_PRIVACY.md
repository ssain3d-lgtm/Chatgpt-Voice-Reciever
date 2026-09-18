# SECURITY_PRIVACY — 보안·프라이버시 설계

- 문서 상태: **Authoritative**
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) · 제품 기대: [PRODUCT_SPEC.md](PRODUCT_SPEC.md) §6 · 위험: [RISK_REGISTER.md](RISK_REGISTER.md)

이 앱은 **상시 마이크 + Accessibility 권한 + 어시스턴트 역할**을 모두 가진다. 개인용이지만, 권한 조합이 강하므로 설계 시점에 경계를 고정한다.

---

## 1. 원칙

```text
P-1  Idle audio never leaves the device.          (Wake 대기 중 PCM은 로컬 엔진에만)
P-2  Audio is never persisted.                    (Ring buffer는 메모리, STT 전달 후 폐기)
P-3  Transcripts are not logged by default.       (Debug 빌드 + 사용자 opt-in 시만 원문)
P-4  Accessibility reads only the target app.     (packageNames = com.openai.chatgpt)
P-5  Secrets are not in the repository.           (AccessKey는 local.properties → BuildConfig)
P-6  Fallbacks that widen exposure are logged.    (클립보드 사용 등)
P-7  User is told what the platform shows.        (privacy indicator는 숨길 수 없고 숨기지 않는다)
```

---

## 2. Wake Audio — 로컬 처리

- `AudioCaptureService`의 PCM은 `WakeWordEngine`(Porcupine, 온디바이스 추론)과 `VadEngine`(온디바이스)에만 전달된다.
- IDLE 상태에서 네트워크 소켓을 여는 코드는 오디오 경로에 존재하지 않는다. (Porcupine AccessKey 검증은 SDK 초기화 시 온라인 확인이 필요할 수 있다 — Picovoice 공식 문서 기준으로 구현 시 확인. `OPEN_QUESTION`: 오프라인 유예 기간)
- Wake Word 감지 결과(키워드 인덱스, 시각)만 이벤트로 나간다. 오디오는 나가지 않는다.

## 3. No Idle Cloud Streaming

- STT(`SpeechRecognizer`)는 **Wake Word 이후** `SessionStateMachine`이 `SpeechEngine.start(turnId)`를 호출한 뒤에만 오디오를 받는다.
- Android `SpeechRecognizer`는 기기 설정에 따라 **온라인 인식**일 수 있다. 온디바이스(`createOnDeviceSpeechRecognizer`)는 `checkRecognitionSupport("ko-KR")` 결과가 있을 때만 사용한다(DEVICE_TEST_REQUIRED, GV-08). 온보딩에서 "질문 음성은 Google/Samsung 인식 서비스로 전달될 수 있다"를 명시한다.
- `SpeechEngine.stop(turnId)` 이후 pipe/마이크는 즉시 닫는다.

## 4. Audio Retention

- Ring buffer: 메모리 전용, 용량 2초(초기값). 파일 기록 없음.
- Pre-roll(300 ms 초기값)은 STT pipe에 넘긴 뒤 버퍼 포인터가 지나가면 자연 소멸.
- Debug 빌드에서도 오디오 파일 저장 기능은 **만들지 않는다**. 실기기 디버깅은 이벤트 타임라인 로그로 한다.

## 5. Transcript / Debug Logging

| 빌드 | Transcript 원문 | Endpoint 이벤트 | Bridge 이벤트 |
|---|---|---|---|
| release | 기록 안 함 (길이·EndingClass·타임스탬프만) | 기록 | 기록 (노드 텍스트 제외) |
| debug + opt-in OFF | 위와 동일 | 기록 | 기록 |
| debug + opt-in ON | 원문 기록 (메모리 ring buffer) | 기록 | 기록 (composer 검증 텍스트 포함) |

- `DebugLog`: 메모리 ring buffer, 최근 200 이벤트(초기값). 파일 export는 사용자가 뷰어에서 명시적으로 눌렀을 때만, 앱 전용 저장소에.
- 로그에 남기지 않는 것: 오디오, AccessKey, ChatGPT 화면의 답변 텍스트.
- `StaleEvent`(turnId 불일치) 폐기도 로그에 남긴다(race 디버깅용).

## 6. Accessibility Privilege

- 서비스 설정: `packageNames = ["com.openai.chatgpt"]`, `canRetrieveWindowContent = true`, `notificationTimeout = 100`(초기값), `flagRetrieveInteractiveWindows`는 필요 시만.
- **다른 앱 화면은 읽지 않는다.** 이벤트 필터가 패키지로 제한되며, Bridge 코드는 `rootInActiveWindow`의 패키지명을 확인한 뒤에만 탐색한다.
- 읽는 것: composer 노드의 text(우리가 넣은 텍스트 검증용), Send 노드의 상태. **ChatGPT 답변 본문은 읽지 않는다**(v0.1). v0.2 Follow-up 실험에서 "Stop 버튼 소멸/Send 재활성"만 읽을 수 있다.
- Android 13+ Restricted Settings: 사이드로드 앱의 접근성 활성화가 차단되는 것은 **의도된 보안 장치**다. 우회하지 않는다. 온보딩에서 정규 절차("앱 정보 → 제한된 설정 허용")를 안내한다(COMMUNITY_REPORTED / DEVICE_TEST_REQUIRED).
- 사용자가 접근성 서비스를 끄면 Plan A는 즉시 `BridgeUnavailable`. 앱은 켜달라고 요청만 하고 자동으로 켜는 시도는 하지 않는다(불가능하기도 하다).

## 7. Clipboard Fallback

- `ACTION_SET_TEXT` 실패 시 클립보드 → `ACTION_PASTE`. 이 경로는:
  - 텍스트가 잠시 시스템 클립보드에 존재한다(다른 앱·클립보드 히스토리가 읽을 수 있음).
  - 사용 즉시 `ClipboardManager.clearPrimaryClip()` 호출.
  - `DebugLog`에 `ClipboardFallbackUsed(turnId)` 기록.
  - Samsung 클립보드 히스토리(키보드)에 남을 수 있음 — `DEVICE_TEST_REQUIRED`. 남는다면 온보딩에서 안내.
- 모든 Bridge 실패 시 "질문 보존"도 클립보드를 사용한다. 이때는 사용자가 붙여넣기할 것이므로 비우지 않는다. 알림에 명시.

## 8. Privacy Indicator

- 3rd-party 앱은 `AlwaysOnHotwordDetector`(DSP 경로)를 쓸 수 없으므로(`@SystemApi`, Android 12+), 로컬 Wake Word 대기 중에도 앱이 마이크를 실제로 열고 있다. **녹색 마이크 인디케이터가 대기 중 계속 표시될 수 있다.**
- 이것은 **platform limitation이며 bug가 아니다**. 숨기려는 시도를 하지 않는다.
- 온보딩 첫 화면에서 설명하고, 설정에 "대기 스케줄"(야간 OFF, 통화 중 OFF — v0.2)을 두어 사용자가 노출 시간을 줄일 수 있게 한다.

## 9. AccessKey Handling (Porcupine)

- `local.properties`의 `PICOVOICE_ACCESS_KEY` → Gradle에서 `BuildConfig.PICOVOICE_ACCESS_KEY`. `local.properties`는 `.gitignore`.
- 개인용 사이드로드 APK에서 **완전한 은닉은 불가능**하다(APK 역공학). 이는 수용하되, 저장소에 커밋되지 않는 것만 보장한다.
- AccessKey·custom `.ppn` 모델의 사용 조건, 무료 플랜 제한, 모델 만료 여부는 **현행 Picovoice 약관 확인 필요**(`MUST BE VERIFIED AGAINST CURRENT PICOVOICE TERMS`). 문서에 확정 표현을 쓰지 않는다.

## 10. Threat Model (개인용 기준)

| 위협 | 가능성 | 대응 |
|---|---|---|
| 대기 중 오디오가 외부로 유출 | 설계상 경로 없음 | P-1, 코드 리뷰 시 오디오 경로에 네트워크 의존 금지 |
| Wake 오탐으로 주변 대화가 STT(온라인)로 전송 | 중 | 오탐률 목표(≤1/h), 세션 UI로 즉시 가시화, `취소` 명령, false wake 4 s 타임아웃 |
| 오탐 + endpoint로 의도치 않은 질문이 ChatGPT에 전송 | 중 | 위와 동일 + grace + "처리 중" 표시. 전송 후 되돌릴 수 없음을 PRODUCT_SPEC에 명시 |
| Accessibility 권한 남용(다른 앱 읽기) | 설계상 제한 | P-4, packageNames 필터 |
| 클립보드 노출 | 낮음(짧은 시간) | §7 |
| AccessKey 유출 | 중(APK 역공학) | 개인 키, 저장소 미포함. 유출 시 Picovoice 콘솔에서 재발급 |
| 잠금 상태에서 타인이 Wake Word로 질문 전송 | 중 | 잠금 중에는 큐잉만, 해제 후 전송(ANDROID_CONSTRAINTS §6). 설정으로 "잠금 중 Wake 비활성" 옵션 |
| 로그 export에 민감 정보 | 낮음 | release는 원문 미기록. export는 명시적 조작 |
| 악의적 앱이 우리 VIS/세션을 흉내 | 낮음(개인 기기) | 범위 밖 |

## 11. 권한 목록 (v0.1 예정)

| 권한 | 용도 | 비고 |
|---|---|---|
| `RECORD_AUDIO` | 마이크 | 런타임 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | FGS | Android 14+ |
| `BIND_VOICE_INTERACTION` (서비스 측) | VIS | |
| `BIND_ACCESSIBILITY_SERVICE` (서비스 측) | Plan A | 사용자 활성화 |
| `POST_NOTIFICATIONS` | FGS 알림, 실패 알림 | Android 13+ |
| `INTERNET` | Porcupine AccessKey 검증, STT(시스템 서비스 경유이므로 앱 권한 불필요할 수 있음) | 최소화. `OPEN_QUESTION`: AccessKey 검증만 필요한지 |
| `RECEIVE_BOOT_COMPLETED` | 재부팅 후 FGS 복구 | VIS `onReady`로 충분하면 제외 |
| `SYSTEM_ALERT_WINDOW` | **사용 안 함** | v0.1 제외 |
| `QUERY_ALL_PACKAGES` | 사용 안 함 | `<queries>`로 `com.openai.chatgpt`만 선언 |
