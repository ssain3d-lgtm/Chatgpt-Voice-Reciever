# ANDROID_CONSTRAINTS — 플랫폼 제약과 근거

- 문서 상태: **Authoritative** (근거 기준일 2026-09-18)
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) · 위험: [RISK_REGISTER.md](RISK_REGISTER.md) · 검증: [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md)

이 문서는 "Android/Samsung이 무엇을 허용하는가"를 항목별로 태그와 근거와 함께 기록한다.
태그 의미는 [ARCHITECTURE.md](ARCHITECTURE.md) §0. 근거 우선순위: Android Developers > AOSP(source.android.com) > Samsung Developers > 라이브러리 공식 문서 > 커뮤니티 보고.

**규칙**: 커뮤니티 보고만 있는 항목은 `CONFIRMED`로 쓰지 않는다. API가 존재한다는 사실과 그 API로 원하는 UX가 나온다는 것은 별개로 태그한다.

---

## 1. VoiceInteractionService (VIS)

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| 3rd-party 앱이 VIS를 제공할 수 있음 | `CONFIRMED` | `android.service.voice.VoiceInteractionService` 공개 API. 매니페스트에 `BIND_VOICE_INTERACTION` 권한 + `<meta-data android:name="android.voice_interaction">` 리소스 필요 |
| `RecognitionService` 컴포넌트 선언 필수 | `CONFIRMED` (AOSP Voice Interaction guide) | VIS 메타데이터의 `recognitionService` 속성이 유효한 컴포넌트를 가리켜야 어시스턴트 목록에 표시된다. v0.1은 등록용 stub `GptRecognitionService`를 둔다 |
| 선택된 VIS는 시스템이 상시 바인딩 | `CONFIRMED` | `VoiceInteractionManagerService`가 현재 어시스턴트 VIS를 바인딩·유지. `onReady()`가 진입점 |
| `onReady()`에서 FGS 시작 | `CONFIRMED` (API) / 실제 생존은 `DEVICE_TEST_REQUIRED` | §4 참조 |
| 사용자가 Bixby → 이 앱으로 변경 필요 | `CONFIRMED` | Galaxy 기본 어시스턴트는 Bixby. 설정 → 앱 → 기본 앱 → 디지털 어시스턴트 앱 |

## 2. VoiceInteractionSession

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| 세션 창(오버레이 권한 없이 모든 앱 위 표시) | `CONFIRMED` | `VoiceInteractionSession.onCreateContentView()`. `SYSTEM_ALERT_WINDOW` 불필요 |
| `startAssistantActivity(Intent)` API 존재 | `CONFIRMED` | API 23+. 세션이 활성 상태일 때 어시스턴트 자격으로 액티비티 시작. BAL(백그라운드 액티비티 시작 제한)의 어시스턴트 경로 |
| 이 API로 `com.openai.chatgpt` 런처 intent 실행 시 원하는 UX가 나오는가 | `DEVICE_TEST_REQUIRED` | API 존재와 ChatGPT 앱의 반응은 별개 문제. GV-14/15 |
| Warm start 시 직전 대화 화면 복귀 | `DEVICE_TEST_REQUIRED` | ChatGPT 앱이 recents에 살아 있을 때의 동작. 보장 없음 |
| Cold start 시 직전 대화 복귀 | `NOT GUARANTEED` | 새 대화 화면 또는 목록일 가능성. "같은 대화 유지"는 best-effort |
| 잠금 화면 위 세션 표시 | `DEVICE_TEST_REQUIRED` | VIS 메타데이터 `supportsLaunchVoiceAssistFromKeyguard`. 세션 UI 표시 가능성은 있으나 One UI 동작 검증 필요. GV-04 |
| 잠금 상태에서 `startAssistantActivity` | `DEVICE_TEST_REQUIRED` | 잠금 해제 화면이 뜰 것으로 예상. 정책: 잠금 중이면 큐잉 후 해제 시 전송 |
| `showSession()` 호출 주체 | `CONFIRMED` | VIS에서 `showSession(args, flags)`. 세션 UI는 이 경로로만 표시 |

## 3. ROLE_ASSISTANT

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` | `CONFIRMED` | API 29+. 사용자에게 역할 부여 요청 다이얼로그 |
| Samsung 설정 UI에서 어시스턴트 변경 | `CONFIRMED` (Samsung 기본 동작) / 화면 경로는 One UI 버전별 `DEVICE_TEST_REQUIRED` | |
| 역할 상실 감지 | `OPEN_QUESTION` | 사용자가 다시 Bixby로 바꾸면 VIS `onShutdown()`. 알림으로 재등록 안내 예정 |

## 4. Background Microphone / Foreground Service

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| FGS `foregroundServiceType="microphone"` (Android 14+ 필수 선언) | `CONFIRMED` | Android 14(targetSdk 34)부터 FGS 타입 선언 필수. `FOREGROUND_SERVICE_MICROPHONE` 권한 |
| 백그라운드에서 시작된 FGS의 마이크 접근 예외 | `CONFIRMED` | Android Developers "Restrictions on starting a foreground service from the background" while-in-use 예외 목록에 **"The service starts by an app which provides the VoiceInteractionService"** 명시 |
| 위 예외가 Galaxy One UI에서 실제로 적용되는가 | `DEVICE_TEST_REQUIRED` | 화면 OFF 상태에서 마이크 무음 여부 확인. 실패 로그 패턴: `Foreground service started from background can not have location/camera/microphone access`. GV-03 |
| VIS 프로세스에서 `AudioRecord` 상시 캡처 | `SUPPORTED WITH LIMITATIONS` | 가능하나 배터리·privacy indicator 상시. §9, §10 |
| `AudioManager.AudioRecordingCallback`로 타 앱 녹음 감지 | `CONFIRMED` | API 29+. 통화·타 앱 캡처 시 Wake 일시정지에 사용 |
| Android 10+ 오디오 입력 공유 규칙 | `CONFIRMED` | 일반적으로 마지막에 시작한 앱이 우선, 어시스턴트/접근성/통화 예외 있음. 두 앱 동시 캡처 시 한쪽은 무음 가능 → Mode H는 반드시 release 후 `startListening()` |

## 5. Android 14 / 15 / 16

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| Android 14: FGS 타입 강제, `microphone` 타입은 백그라운드 시작 제한 있음 | `CONFIRMED` | VIS 예외로 우회. 예외 미적용 시 제품 불가 |
| Android 15: `SYSTEM_ALERT_WINDOW` 기반 BAL 예외는 오버레이가 실제로 보일 때만 | `CONFIRMED` | 우리는 오버레이 미사용이므로 무관. 세션 UI 채택 근거 중 하나 |
| Android 15: FGS 6시간 제한 | `CONFIRMED` (dataSync/mediaProcessing 타입) / `microphone` 타입은 해당 없음 | 타입 선언 정확성이 중요 |
| Android 16 세부 변경 | `OPEN_QUESTION` | One UI 8 기준 실기기 확인 시 갱신 |
| `targetSdk ≥ 34` | 결정 | Samsung 공식 입장(One UI 6.0+, Android 14 타겟 FGS 정책 준수 시 보장)과 정합. §12 |

## 6. Screen Off / Keyguard / Doze

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| 화면 OFF(잠금 해제 상태)에서 FGS 마이크 유지 | `DEVICE_TEST_REQUIRED` | GV-03 |
| Doze 진입 후 CPU 스로틀이 Wake 추론에 미치는 영향 | `DEVICE_TEST_REQUIRED` | FGS는 Doze whitelist 대상이 아님. 배터리 최적화 제외로 완화. GV-05 |
| 잠금 상태에서 세션 UI | `DEVICE_TEST_REQUIRED` | §2 |
| 잠금 상태에서 ChatGPT 화면 표시 | `NOT GUARANTEED` | 잠금 해제 없이는 불가로 가정. UX: "듣기+큐잉"까지만 |
| `ACTION_USER_PRESENT`로 해제 감지 후 dispatch | `CONFIRMED` (broadcast) / 타이밍은 `DEVICE_TEST_REQUIRED` | |

## 7. Privacy Indicator / AlwaysOnHotwordDetector

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| `AlwaysOnHotwordDetector`는 3rd-party 사용 불가 | `CONFIRMED` | AOSP Android 12 release notes: "Starting in Android 12, the AlwaysOnHotwordDetector class including its factory method `VoiceInteractionService.createAlwaysOnHotwordDetector()` is a system API (@SystemApi) instead of a public API." "intended for use by Assistant apps with system-level privileges" |
| DSP 저전력 Wake Word 경로 | `NOT AVAILABLE` (3rd-party) | 따라서 앱 프로세스 `AudioRecord` 상시 캡처가 유일한 로컬 Wake 경로 |
| 마이크 사용 중 녹색 인디케이터 상시 표시 | `CONFIRMED` (Android 12+ privacy indicator 동작) / One UI 표시 방식은 `DEVICE_TEST_REQUIRED` | **Platform limitation. Bug 아님.** PRODUCT_SPEC §7, RISK R-06 |
| 인디케이터 없이 Wake Word 감지 | `NOT AVAILABLE` | 시스템 앱 권한(`CAPTURE_AUDIO_HOTWORD`) 필요 |

## 8. SpeechRecognizer

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| `SpeechRecognizer` 공개 API, main thread 사용 | `CONFIRMED` | 공식 문서 |
| "연속 인식용이 아니다" | `CONFIRMED` | 공식 문서: 구현체가 원격 서버로 스트리밍할 수 있어 continuous recognition 용도가 아님. → Wake 이후 구간만 사용 |
| `createOnDeviceSpeechRecognizer()` | `CONFIRMED` (API 31) | |
| `isOnDeviceRecognitionAvailable()` | `CONFIRMED` (API 31) | |
| `checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)` | `CONFIRMED` (API 33) | `ko-KR` 지원 여부 확인용. GV-08 |
| Galaxy에서 `ko-KR` 온디바이스 인식 지원 | `DEVICE_TEST_REQUIRED` | 기본 인식 서비스(Google/Samsung)에 따라 다름. `settings get secure voice_recognition_service` |
| `RecognizerIntent.EXTRA_AUDIO_SOURCE` (+ `_CHANNEL_COUNT`, `_ENCODING`, `_SAMPLING_RATE`) | `CONFIRMED` (API 33) | 공식 설명: "If this extra is not set **or the recognizer does not support this feature**, the recognizer will open the mic". 호출자가 fd를 닫아야 함 |
| Google/Samsung RecognitionService가 `EXTRA_AUDIO_SOURCE`를 실제 지원하는가 | `DEVICE_TEST_REQUIRED` | 공식 문서가 미지원 가능성을 명시. GV-09 |
| `RecognizerIntent.EXTRA_SEGMENTED_SESSION` | `CONFIRMED` (API 33) | `EXTRA_AUDIO_SOURCE`와 함께 쓰면 "The recognition session will end when and only when the audio is closed" |
| `onSegmentResults` 실제 동작 | `DEVICE_TEST_REQUIRED` | GV-10 |
| `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` 등 silence 힌트 | `SUPPORTED WITH LIMITATIONS` | 힌트이며 구현체가 무시할 수 있음. 우리 endpoint 판단의 근거로 삼지 않음 |
| `EXTRA_PREFER_OFFLINE` | `CONFIRMED` (API 23) | 온디바이스 우선 힌트. 강제 아님 |
| Recognizer가 자체 endpointing으로 세션 종료 | `CONFIRMED` (동작 특성) | 우리 EndpointDetector와 독립. Final = segment 결과로 취급하고 재시작 |

## 9. Accessibility

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| `AccessibilityService`로 타 앱 노드 읽기·`ACTION_SET_TEXT`·`ACTION_CLICK` | `CONFIRMED` (API) | 공개 API. `canRetrieveWindowContent`, `packageNames` 필터 |
| Compose 기반 앱은 `resource-id`가 없을 수 있음 | `CONFIRMED` (Compose 접근성 문서) | `testTagsAsResourceId` 미설정 시 view id 탐색 불가. Selector Stack 필요 |
| **ChatGPT 앱 composer가 editable 노드를 노출하는가** | `DEVICE_TEST_REQUIRED` | GV-11 |
| **`ACTION_SET_TEXT`가 ChatGPT composer에 동작하는가** | `DEVICE_TEST_REQUIRED` | GV-12 |
| **Send 버튼을 접근성 트리에서 찾을 수 있는가** | `DEVICE_TEST_REQUIRED` | GV-13 |
| **`ACTION_CLICK`으로 Send가 안정적으로 동작하는가** | `DEVICE_TEST_REQUIRED` | GV-13 |
| Android 13+ Restricted Settings (사이드로드 APK의 접근성 활성화 차단) | `COMMUNITY_REPORTED` / `DEVICE_TEST_REQUIRED` | 다수 개발자 보고: 앱 정보 → "제한된 설정 허용" 후 활성화 가능. `adb install`은 예외라는 보고 있음. One UI 동작 GV-20 |
| Accessibility 권한의 보안 함의 | `CONFIRMED` | 모든 화면 읽기 가능 권한. `packageNames`로 `com.openai.chatgpt`만 제한. [SECURITY_PRIVACY.md](SECURITY_PRIVACY.md) |
| `dispatchGesture` 좌표 클릭 | `CONFIRMED` (API 24) / 사용은 최후 폴백 | 절대 좌표 금지. 사용자 보정 상대 좌표만 |

## 10. Samsung One UI

Samsung 동작을 Android 일반 동작으로 단정하지 않는다. 아래는 Samsung 고유 항목이다.

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| Sleeping apps (약 3일 미사용) | `CONFIRMED` (Samsung Developers "App management") | "features such as Job, Alarm, and Foreground-service are restricted" |
| Deep sleeping apps (16일 미사용, 정책 변동 가능) | `CONFIRMED` (동일) | 사용자가 열 때만 동작 |
| Never sleeping apps | `CONFIRMED` (동일) | Settings → Device care → Battery → Background usage limits |
| 음성으로만 쓰는 앱이 "미사용"으로 분류되는가 | `DEVICE_TEST_REQUIRED` | VIS 바인딩이 "사용"으로 집계되는지 불명. GV-07 |
| 배터리 최적화 제외 ↔ Never sleeping 상호작용 | `OPEN_QUESTION` / `COMMUNITY_REPORTED` | "최적화 제외 요청 시 never-sleeping 목록에서 빠진다"는 포럼 보고. 검증 필요 |
| One UI 6.0+ / Android 14 타겟 FGS 보장 | `CONFIRMED` (Samsung 공식 입장, dontkillmyapp 인용) | "foreground services of apps targeting Android 14 will be guaranteed to work as intended so long as they are developed according to Android's new foreground service API policy" |
| Adaptive battery 영향 | `DEVICE_TEST_REQUIRED` | 온보딩 권고 항목에 포함 |
| VIS 자체 생존 | `DEVICE_TEST_REQUIRED` | 시스템 바인딩이므로 생존 예상. FGS만 별도 감시·재시작 |
| Samsung 키보드 자동완성 툴바가 접근성 트리에 미치는 영향 | `DEVICE_TEST_REQUIRED` | `ACTION_SET_TEXT`는 IME 없이 동작하나 노드 변동 가능 |

## 11. Bluetooth (v0.1은 기본 동작 확인만)

| 항목 | 태그 | 내용 / 근거 |
|---|---|---|
| HFP/SCO 입력(8–16 kHz) 또는 LE Audio | `SUPPORTED WITH LIMITATIONS` | 대역폭 제한으로 Wake 정확도 저하 가능 |
| `AudioManager.setCommunicationDevice` | `CONFIRMED` (API 31) | v0.2 라우팅 정책 |
| Galaxy Buds 입력에서 Wake/STT 품질 | `DEVICE_TEST_REQUIRED` | GV-18 |

## 12. 이 문서의 결정 사항 요약

- `targetSdk ≥ 34`, FGS `microphone` 타입 정확히 선언.
- Wake Word는 앱 프로세스 `AudioRecord` 상시 캡처. DSP 경로 없음. 인디케이터 상시 표시 수용.
- STT는 `EXTRA_AUDIO_SOURCE` pipe(Mode P) 우선 시도, 미지원 시 handoff(Mode H).
- Accessibility Bridge는 `EXPERIMENTAL`. 실기기 검증 전 `SUPPORTED` 표기 금지.
- Samsung 정책은 온보딩 안내 + 72시간 생존 테스트로 확인.

## 13. 근거 목록

- Android Developers — Restrictions on starting a foreground service from the background (while-in-use 예외: VoiceInteractionService)
- Android Developers — `RecognizerIntent` (`EXTRA_AUDIO_SOURCE`, `EXTRA_SEGMENTED_SESSION`: API 33 diff 확인)
- Android Developers — `SpeechRecognizer` (`createOnDeviceSpeechRecognizer` API 31, `checkRecognitionSupport` API 33)
- Android Developers — `VoiceInteractionSession.startAssistantActivity`
- Android Developers — Accessibility in Jetpack Compose
- source.android.com — Android 12 release notes (AlwaysOnHotwordDetector @SystemApi)
- source.android.com — Voice Interaction: App development (RecognitionService 필수)
- developer.samsung.com — App management (sleeping / deep sleeping / never sleeping)
- dontkillmyapp.com/samsung — Samsung 2024 공식 입장 인용 (2차 출처)
- Android 13 Restricted Settings — Android Police / Esper 보도 (`COMMUNITY_REPORTED`)
