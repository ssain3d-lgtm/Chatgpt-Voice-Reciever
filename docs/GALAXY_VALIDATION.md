# GALAXY_VALIDATION — Samsung Galaxy 실기기 검증 계획

- 문서 상태: **Authoritative** (Result 열은 실기기 결과로 채움)
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) §11 · [MVP_PLAN.md](MVP_PLAN.md) Phase 1/3 · [RISK_REGISTER.md](RISK_REGISTER.md)

이 문서의 각 항목은 **실기기에서 실행하기 전까지 결과가 없다**. Result가 비어 있는 항목을 근거로 다른 문서에 `CONFIRMED`를 쓰지 않는다.

---

## 0. 공통

| 항목 | 내용 |
|---|---|
| 대상 기기 | Samsung Galaxy, One UI 7/8, Android 15/16 (모델명·빌드 번호를 Result에 기록) |
| 설치 | 사이드로드 APK (`adb install` 경로와 파일 관리자 설치 경로를 구분해 기록 — Restricted Settings 차이) |
| 공통 로그 | `adb logcat -s GptVoice:V ActivityManager:I AudioFlinger:W` 를 각 테스트별 파일로 저장 |
| 상태 로그 | `SessionStateMachine` 전이 로그(turnId 포함)를 `DebugLog` export |
| 기록 형식 | 각 테스트의 Result에 날짜, 기기, 앱 버전, ChatGPT 앱 버전, 결과, 첨부 로그 파일명 |
| Spike 매핑 | S-1: GV-01/02/03 · S-2: GV-08/09/10 · S-3: GV-11/12/13/21 |

---

## 1. Assistant / Wake

### GV-01 Assistant registration

| 필드 | 내용 |
|---|---|
| Purpose | VIS가 어시스턴트 목록에 표시되고 선택·바인딩되는지 |
| Setup | APK 설치. 매니페스트에 VIS + SessionService + `RecognitionService` stub 포함 |
| Action | 설정 → 앱 → 기본 앱 → 디지털 어시스턴트 앱 → 이 앱 선택 |
| Expected | 목록에 표시. 선택 후 `adb shell settings get secure assistant` 및 `voice_interaction_service`에 패키지 출력. `onReady()` 로그 |
| Failure | 목록 미표시(RecognitionService 누락 의심) / 선택 직후 해제 |
| Logs | `dumpsys voiceinteraction`, 위 settings 출력 |
| Result | _(미실행)_ |

### GV-02 Wake Word, screen ON

| 필드 | 내용 |
|---|---|
| Purpose | 감지율과 오탐률 |
| Setup | FGS 시작, 조용한 방, 화면 ON |
| Action | "헤이 지피티" 10회 (1 m 거리). 이후 일반 대화 30분(ChatGPT/GPT 언급 포함) + YouTube 기술 영상 30분 |
| Expected | 감지 ≥ 9/10. 오탐 ≤ 1/hour |
| Failure | 감지 < 7/10 또는 오탐 > 2/hour → sensitivity 조정, 키워드 재검토 |
| Logs | 감지 시각, keywordIndex, sensitivity 값 |
| Result | _(미실행)_ |

### GV-03 Wake Word, screen OFF (잠금 해제 상태)

| 필드 | 내용 |
|---|---|
| Purpose | 화면 OFF에서 FGS 마이크 유지 (R-02) |
| Setup | 잠금 해제 후 화면만 OFF. 배터리 최적화 제외 |
| Action | 1분, 5분, 30분 후 "헤이 지피티, FastH3 확인해줘" |
| Expected | 화면 켜짐 + 세션 UI + STT 동작 + (Plan A 시) ChatGPT 전송 |
| Failure | 마이크 무음. 로그에 `Foreground service started from background can not have … microphone access` |
| Logs | 위 문자열 grep, `dumpsys appops <pkg>` |
| Result | _(미실행)_ |

### GV-04 Lock screen

| 필드 | 내용 |
|---|---|
| Purpose | 잠금 상태에서 세션 UI 표시·큐잉 정책 |
| Setup | 화면 잠금(PIN) |
| Action | "헤이 지피티, 테스트 질문" |
| Expected | 세션 UI가 잠금 위에 표시(또는 표시 불가 기록). 텍스트 큐잉 + "잠금 해제 후 전송" 알림. 해제 시 전송 |
| Failure | Wake 미반응 / `startAssistantActivity`가 예상 외 동작 |
| Logs | 상태 전이, `ACTION_USER_PRESENT` 수신 시각 |
| Result | _(미실행)_ |

### GV-05 Doze

| 필드 | 내용 |
|---|---|
| Purpose | Doze 진입 후 Wake 반응 |
| Setup | 충전 분리, 화면 OFF, `adb shell dumpsys deviceidle force-idle` (또는 자연 진입 1시간) |
| Action | "헤이 지피티" |
| Expected | 반응. 반응 지연 시간 기록 |
| Failure | 무반응 → 배터리 최적화 제외 여부 재확인 후 재시험 |
| Logs | `dumpsys deviceidle`, 상태 전이 |
| Result | _(미실행)_ |

### GV-06 12h battery

| 필드 | 내용 |
|---|---|
| Purpose | 대기 소모량 (R-03). **목표 수치는 이 결과로 설정** |
| Setup | Never sleeping 등록, 배터리 최적화 제외, 100 % 충전 후 분리, `dumpsys batterystats --reset` |
| Action | 12시간 방치. 3시간마다 Wake 1회 |
| Expected | 4/4 반응. 소모량 기록 (초기 관찰 기준선; 확정 목표 없음) |
| Failure | 반응 실패 또는 소모량이 사용자 기준 수용 불가 |
| Logs | `dumpsys batterystats`, Battery Historian |
| Result | _(미실행)_ |

### GV-07 72h survival (One UI sleeping 정책)

| 필드 | 내용 |
|---|---|
| Purpose | 음성으로만 사용 시 sleeping 편입 여부 (R-07) |
| Setup | 앱 UI를 열지 않음. Never sleeping 등록 상태와 미등록 상태 두 번 |
| Action | 72시간 동안 하루 3회 Wake |
| Expected | Never sleeping 등록 시 전 회 반응. 미등록 시 결과 기록 |
| Failure | sleeping 목록 편입, FGS 사망 → 온보딩 문구 강화 |
| Logs | 설정 → 배터리 → 백그라운드 사용 제한 스크린샷, `dumpsys activity services <pkg>` |
| Result | _(미실행)_ |

---

## 2. STT

### GV-08 Recognition service / on-device STT

| 필드 | 내용 |
|---|---|
| Purpose | 기본 인식 서비스 확인, `ko-KR` 온디바이스 지원 |
| Action | `adb shell settings get secure voice_recognition_service`. 앱에서 `isOnDeviceRecognitionAvailable()`, `checkRecognitionSupport(ko-KR)` 호출 |
| Expected | 서비스 컴포넌트명, `RecognitionSupport` 결과(installed / pending / online 언어 목록) |
| Failure | 온디바이스 미지원 → 온라인 인식으로 확정, 온보딩 문구 반영 |
| Logs | 두 API 결과 원문 |
| Result | _(미실행)_ |

### GV-09 EXTRA_AUDIO_SOURCE (Mode P)

| 필드 | 내용 |
|---|---|
| Purpose | recognizer가 우리 pipe를 받는지 (R-05) |
| Setup | `AudioRecord` → `ParcelFileDescriptor` pipe. `EXTRA_AUDIO_SOURCE` + `_CHANNEL_COUNT=1` + `_ENCODING=PCM_16BIT` + `_SAMPLING_RATE=16000` |
| Action | `startListening` 후 pre-roll 포함 발화 |
| Expected | partial/final 수신. 첫 음절 포함 여부 확인 |
| Failure | `ERROR_CLIENT`, 즉시 종료, 또는 recognizer가 자체 마이크를 열어 우리 AudioRecord와 충돌(무음) → Mode H 고정 |
| Logs | RecognitionListener 콜백 타임라인, `dumpsys audio` (동시 캡처 여부) |
| Result | _(미실행)_ |

### GV-10 Segmented recognition

| 필드 | 내용 |
|---|---|
| Purpose | `EXTRA_SEGMENTED_SESSION`으로 한 세션에서 여러 segment 수신 (R-11) |
| Setup | GV-09 통과 시 pipe 모드. 실패 시 Mode H + 재시작 방식 |
| Action | 20초 발화 중 2 s 휴지 3회 |
| Expected | `onSegmentResults` ≥ 3회, 또는 (Mode H) 재시작 사이 손실 구간 길이 측정 |
| Failure | 세그먼트 미지원 → 재시작 방식 확정 |
| Logs | 콜백 타임라인, 각 segment 텍스트 |
| Result | _(미실행)_ |

---

## 3. ChatGPT Bridge

### GV-11 Accessibility node dump

| 필드 | 내용 |
|---|---|
| Purpose | composer / Send 노드 존재·속성 (R-01) |
| Setup | ChatGPT 앱 대화 화면. 접근성 서비스 활성 |
| Action | `adb shell uiautomator dump` + 앱 내 `rootInActiveWindow` 순회 dump |
| Expected | editable 노드 1개(composer)와 clickable 노드(Send) 발견. `className`, `contentDescription`, `hintText`, `bounds`, `isEnabled` 기록 |
| Failure | editable 노드 없음 → Plan A 폐기 검토 |
| Logs | dump XML, 앱 dump JSON |
| Result | _(미실행)_ — **Send contentDescription 실제 값은 여기서 확정. 추측 금지** |

### GV-12 ACTION_SET_TEXT

| 필드 | 내용 |
|---|---|
| Purpose | composer에 텍스트 주입·검증 |
| Setup | GV-11 통과 |
| Action | `ACTION_SET_TEXT("테스트 문장")` → composer.text 재조회. 실패 시 `ACTION_FOCUS` + 클립보드 + `ACTION_PASTE`. Samsung 키보드 표시/비표시 각각 |
| Expected | text 일치. Send `isEnabled` 전이 |
| Failure | 미반영 → paste 경로. paste도 실패 → Plan B |
| Logs | 각 단계 타임스탬프, 검증 결과 |
| Result | _(미실행)_ |

### GV-13 Send click

| 필드 | 내용 |
|---|---|
| Purpose | `ACTION_CLICK` 후 전송·composer 비움 |
| Setup | GV-12 통과. 테스트용 대화 |
| Action | `ACTION_CLICK` 1회 → 300 ms / 1 s / 2 s 시점에 composer.text 조회 |
| Expected | 전송됨, composer 빔. 비워지는 시점 기록 (→ 검증 타임아웃 초기값 조정) |
| Failure | 클릭 무반응 → hierarchy/heuristic selector 시도 → 보정 좌표 |
| Logs | 타임스탬프, ChatGPT 화면 스크린샷 |
| Result | _(미실행)_ |

### GV-14 Warm ChatGPT start

| 필드 | 내용 |
|---|---|
| Purpose | `startAssistantActivity`(런처 intent) warm start 시 화면 (R-10) |
| Setup | ChatGPT 특정 대화 열어둔 채 홈으로 |
| Action | 세션에서 `startAssistantActivity` |
| Expected | 같은 대화 화면 복귀 (기대). 실제 결과 기록 |
| Failure | 새 대화/목록 → "same conversation" best-effort 문구 유지, 사용자 안내 |
| Logs | `dumpsys activity activities | grep openai` |
| Result | _(미실행)_ |

### GV-15 Cold ChatGPT start

| 필드 | 내용 |
|---|---|
| Purpose | 강제종료 후 실행 시 화면 |
| Setup | `adb shell am force-stop com.openai.chatgpt` |
| Action | 세션에서 `startAssistantActivity` |
| Expected | 결과 기록 (새 대화 예상). composer 탐색 성공 여부 |
| Failure | composer 미발견 → 대기 시간 조정 |
| Logs | 위와 동일 + 로딩 시간 |
| Result | _(미실행)_ |

### GV-16 Duplicate send

| 필드 | 내용 |
|---|---|
| Purpose | 중복 전송 0건 (R-08) |
| Setup | Plan A 동작 상태 |
| Action | 20 turn 연속. 그중 5회는 Send 클릭 직후 네트워크 차단(비행기 모드)으로 composer 비움 지연 유도 |
| Expected | ChatGPT 대화에 같은 질문 2회 **0건**. 지연 케이스는 `SendFailed` + 알림 |
| Failure | 1건이라도 중복 → 출시 불가, 원인 분석 |
| Logs | turnId별 sentFlag, ChatBridgeResult, ChatGPT 대화 스크린샷 |
| Result | _(미실행)_ |

### GV-17 Cancel race

| 필드 | 내용 |
|---|---|
| Purpose | 상태 머신 race 규칙 (ARCHITECTURE §6.3) |
| Action | (a) Wake 직후 Wake 재발화 (b) SUBMITTING 중 Wake (c) "취소" 직후 발화 (d) grace 중 발화 |
| Expected | (a) 무시 (b) pendingWake → IDLE 후 새 turn (c) 폐기, 새 turn 아님 (d) LISTENING 복귀·텍스트 이어붙임. 중복 전송 0 |
| Failure | 상태 로그가 규칙과 불일치 |
| Logs | 상태 전이 로그(turnId) |
| Result | _(미실행)_ |

### GV-21 Share target (Plan B)

| 필드 | 내용 |
|---|---|
| Purpose | ChatGPT 앱이 `ACTION_SEND text/plain`을 받는지 |
| Action | `adb shell pm query-activities -a android.intent.action.SEND -t text/plain | grep -i openai`. 실제 share intent 실행 |
| Expected | 액티비티 ≥ 1. composer에 텍스트 채워진 화면. 새 대화 여부 기록 |
| Failure | 없음 → Plan C/클립보드만 |
| Logs | 명령 출력, 스크린샷 |
| Result | _(미실행)_ |

---

## 4. Endpoint

### GV-19 Endpoint hard cap 비교 (3000 / 4000 / 5000 ms)

| 필드 | 내용 |
|---|---|
| Purpose | hard cap·threshold 초기값 검증 (R-09) |
| Setup | ENDPOINT_ENGINE §9 D1~D5 문장 세트를 실제로 발화. hard cap을 3000, 4000, 5000 ms로 각각 |
| Action | 각 설정에서 D2(중간 정지) 20문장 + D1 30문장 |
| Expected | false endpoint rate, late endpoint rate, median latency 기록. 세 설정 비교표 |
| Failure | 모든 설정에서 false endpoint > 10 % → threshold 재설계 |
| Logs | partial 타임라인 + VAD 타임라인 export (D6 데이터셋으로 저장) |
| Result | _(미실행)_ |

---

## 5. Onboarding / 기타

### GV-18 Bluetooth / Galaxy Buds

| 필드 | 내용 |
|---|---|
| Purpose | BT 입력에서 Wake/STT 기본 동작 (v0.1은 확인만) |
| Setup | Galaxy Buds 연결 (가능하면 차량 HFP도) |
| Action | Wake + 질문 각 10회 |
| Expected | Wake ≥ 7/10 (초기 목표), STT 정상. 어느 마이크가 사용되는지 기록 |
| Logs | `dumpsys audio | grep -i "input\|sco\|le audio"` |
| Result | _(미실행)_ |

### GV-20 Accessibility Restricted Settings (Android 13+)

| 필드 | 내용 |
|---|---|
| Purpose | 사이드로드 후 접근성 활성화 절차 (R-12) |
| Setup | (a) 파일 관리자로 설치 (b) `adb install`로 설치 |
| Action | 접근성 설정에서 서비스 켜기 시도 |
| Expected | (a) "제한된 설정" 안내 → 앱 정보에서 허용 후 활성화 가능 (b) 차이 기록 |
| Failure | 허용 메뉴 없음 → 온보딩 문구 재작성 |
| Logs | 각 단계 스크린샷 |
| Result | _(미실행)_ |

### GV-22 Privacy indicator UX

| 필드 | 내용 |
|---|---|
| Purpose | 대기 중 인디케이터 표시 방식과 사용자 수용 (R-06) |
| Action | IDLE 상태 1시간 사용 |
| Expected | 인디케이터 표시 여부·형태 기록. 사용자(본인) 수용 여부 기록 |
| Logs | 스크린샷 |
| Result | _(미실행)_ |

---

## 6. 결과 반영 규칙

- 각 GV 결과는 [ANDROID_CONSTRAINTS.md](ANDROID_CONSTRAINTS.md)의 해당 태그를 `DEVICE_TEST_REQUIRED` → `CONFIRMED (device: …)` / `NOT SUPPORTED (device: …)` 로 갱신한다.
- [RISK_REGISTER.md](RISK_REGISTER.md) Status를 갱신한다.
- 초기값이 바뀌면 [ENDPOINT_ENGINE.md](ENDPOINT_ENGINE.md) §4 표에 "실측 후 값 (기기, 날짜)" 열을 추가한다.
- 결정이 뒤집히면 [DECISIONS.md](DECISIONS.md)에 새 ADR을 추가한다(기존 ADR은 Superseded로).
