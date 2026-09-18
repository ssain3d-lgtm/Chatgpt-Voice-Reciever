# RISK_REGISTER — 위험 목록

- 문서 상태: **Authoritative** (Status는 실기기 결과로 갱신)
- 상위: [ARCHITECTURE.md](ARCHITECTURE.md) · 제약 근거: [ANDROID_CONSTRAINTS.md](ANDROID_CONSTRAINTS.md) · 검증: [GALAXY_VALIDATION.md](GALAXY_VALIDATION.md)

Severity = Likelihood × Impact. **Critical**은 실패 시 제품이 성립하지 않거나 사용자에게 되돌릴 수 없는 피해를 주는 항목이다.
Status: `OPEN` (검증 전) / `MITIGATED` / `ACCEPTED` (platform limitation으로 수용) / `CLOSED`.

---

## Critical

### R-01 ChatGPT Accessibility node unavailable

| 필드 | 내용 |
|---|---|
| Risk | 공식 ChatGPT 앱 composer가 editable 노드를 노출하지 않거나, `ACTION_SET_TEXT`가 동작하지 않거나, Send 노드를 찾을 수 없다 |
| Likelihood | Medium |
| Impact | High — Plan A 전체 무효 |
| Severity | **Critical** |
| Evidence | Compose 앱은 `resource-id`가 없을 수 있음(공식 Compose 접근성 문서). ChatGPT 앱의 실제 트리는 미확인 |
| Mitigation | Selector Stack(6단계), 온보딩 보정 화면, `healthCheck()`, `PACKAGE_REPLACED` 감지 |
| Fallback | Plan B Share Intent → Plan C → 클립보드 + 알림 |
| Validation | GV-11, GV-12, GV-13 |
| Status | `OPEN` — Spike S-3 |

### R-02 Background microphone survival (화면 OFF / Doze)

| 필드 | 내용 |
|---|---|
| Risk | 화면 OFF 또는 Doze에서 FGS 마이크가 무음을 받거나 서비스가 중단된다 |
| Likelihood | Medium |
| Impact | High — hands-free 핵심 UX 불가 |
| Severity | **Critical** |
| Evidence | Android 공식 문서의 while-in-use 예외에 VIS 명시(`CONFIRMED`). One UI 실제 적용은 미확인 |
| Mitigation | VIS `onReady()`에서 FGS 시작, `microphone` 타입 선언, 배터리 최적화 제외, 주기 점검·재시작 |
| Fallback | 없음 — 실패 시 아키텍처 재검토 |
| Validation | GV-03, GV-05, GV-06 |
| Status | `OPEN` — Spike S-1 |

### R-03 Battery drain

| 필드 | 내용 |
|---|---|
| Risk | 상시 `AudioRecord` + Wake 추론 + VAD로 배터리 소모가 수용 불가 수준 |
| Likelihood | Medium |
| Impact | High — 사용자가 앱을 끔 |
| Severity | **Critical** |
| Evidence | DSP 경로 없음(`AlwaysOnHotwordDetector` @SystemApi, `CONFIRMED`). 실제 소모량 미측정 |
| Mitigation | 16 kHz mono, Porcupine 경량 추론, Endpoint VAD는 Wake 이후에만 활성([ARCHITECTURE.md](ARCHITECTURE.md) §4.5 / ADR-013), v0.2 대기 스케줄 |
| Fallback | 대기 시간대 제한 옵션 |
| Validation | GV-06 (12h 배터리) |
| Status | `OPEN` — 목표 수치는 실측 후 설정. 확정 수치 기재 금지 |

### R-04 Wake Word false positive

| 필드 | 내용 |
|---|---|
| Risk | 일반 대화·YouTube·ChatGPT 관련 콘텐츠에서 오탐 → 의도치 않은 STT(온라인 가능)·전송 |
| Likelihood | Medium |
| Impact | High — 프라이버시 + 잘못된 질문 전송 |
| Severity | **Critical** |
| Evidence | "GPT" 단독은 기술 대화에 빈번. "헤이 지피티"로 변경(ADR-002) |
| Mitigation | 4음절 이상 키워드, sensitivity 튜닝, false wake 4 s 타임아웃, 세션 UI 즉시 표시, `취소` 명령, grace |
| Fallback | 다른 키워드로 교체 |
| Validation | GV-02 (오탐 ≤ 1/h) |
| Status | `OPEN` |

### R-05 STT microphone handoff (첫 음절 손실 / race)

| 필드 | 내용 |
|---|---|
| Risk | Wake 후 `SpeechRecognizer`가 마이크를 잡는 사이 사용자의 첫 음절이 잘리거나, 두 캡처가 충돌해 무음 |
| Likelihood | High (Mode H) / Low (Mode P) |
| Impact | Medium — 질문 앞부분 손실 |
| Severity | **Critical** (UX 핵심) |
| Evidence | Android 10+ 오디오 입력 공유 규칙(`CONFIRMED`). `EXTRA_AUDIO_SOURCE` recognizer 지원은 선택적(`CONFIRMED` 문서 문구) |
| Mitigation | Mode P(pipe + pre-roll) 우선. Mode H는 release 후 start + 피드백 후 발화 유도 |
| Fallback | Mode H |
| Validation | GV-09, GV-10 |
| Status | `OPEN` — Spike S-2 |

### R-06 Privacy Indicator UX acceptance

| 필드 | 내용 |
|---|---|
| Risk | 대기 중 녹색 마이크 인디케이터 상시 표시를 사용자가 수용하지 못함 |
| Likelihood | High (표시됨) |
| Impact | Medium — 사용 중단 |
| Severity | **Critical** (제품 제약) |
| Evidence | `AlwaysOnHotwordDetector` @SystemApi(`CONFIRMED`). 3rd-party는 앱 마이크 필요 |
| Mitigation | 온보딩 첫 화면에서 platform limitation으로 설명. v0.2 대기 스케줄 |
| Fallback | 없음 — 숨길 수 없음 |
| Validation | GV-22 |
| Status | `ACCEPTED` (platform limitation) — 사용자 수용 여부만 GV-22 |

### R-07 Samsung process killing (Sleeping / Deep sleeping apps)

| 필드 | 내용 |
|---|---|
| Risk | 음성으로만 사용하는 앱이 "미사용"으로 분류되어 FGS 제한(3일) 또는 deep sleeping(16일) |
| Likelihood | Medium |
| Impact | High — 며칠 뒤 조용히 죽음 |
| Severity | **Critical** |
| Evidence | Samsung Developers "App management"(`CONFIRMED`). VIS 바인딩이 사용으로 집계되는지 미확인 |
| Mitigation | `targetSdk ≥ 34`, FGS 타입 정확 선언, 온보딩에서 Never sleeping 등록 + 배터리 최적화 제외, VIS 주기 점검·재시작 |
| Fallback | 온보딩 문구 강화, 주기적 앱 열기 유도 알림 |
| Validation | GV-07 (72h) |
| Status | `OPEN` |

### R-08 Duplicate message send

| 필드 | 내용 |
|---|---|
| Risk | 같은 질문이 ChatGPT에 두 번 전송 |
| Likelihood | Low (설계상) |
| Impact | High — 되돌릴 수 없음, 신뢰 붕괴 |
| Severity | **Critical** |
| Evidence | Send 클릭 후 composer 비움 검증 실패 시 실제 전송 여부 불명 |
| Mitigation | `turnId`별 `sentFlag`(클릭 전 설정), 자동 retry 금지(INV-5), SM에서 `send()` 1회 호출 보장 |
| Fallback | "전송 확인 필요" 알림 (사용자 확인) |
| Validation | GV-16, GV-17 |
| Status | `OPEN` — 0건 아니면 출시 불가 |

---

## High

### R-09 False endpoint (문장 중간에 끊김)

| 필드 | 내용 |
|---|---|
| Risk | 사용자가 생각하는 사이 전송. 반쪽 질문 |
| Likelihood | High (튜닝 전) |
| Impact | Medium |
| Severity | High |
| Evidence | 한국어 연결어·조사 끝 발화에서 멈춤이 흔함 |
| Mitigation | 3층 EndpointDetector, CONNECTIVE/FILLER 긴 threshold, grace, hard cap 3000/4000/5000 비교 |
| Fallback | 보수적(긴) threshold 프리셋 |
| Validation | GV-19 |
| Status | `OPEN` |

### R-10 `startAssistantActivity`로 ChatGPT 실행 시 원하는 화면이 아님

| 필드 | 내용 |
|---|---|
| Risk | cold start 시 새 대화/목록. warm이어도 다른 화면 |
| Likelihood | Medium |
| Impact | Medium — "같은 대화" 불가 |
| Severity | High |
| Evidence | API 존재 `CONFIRMED`. ChatGPT 반응 미확인 |
| Mitigation | best-effort로 문서화. Selector Stack은 어떤 대화 화면에서도 composer를 찾도록 |
| Fallback | 새 대화에 전송(사용자에게 알림) |
| Validation | GV-14, GV-15 |
| Status | `OPEN` |

### R-11 SpeechRecognizer가 자체 endpointing으로 세션 종료

| 필드 | 내용 |
|---|---|
| Risk | 1~2 s 무음이면 recognizer가 final을 내고 끝냄. 재시작 사이 음성 손실 |
| Likelihood | High (Mode H) |
| Impact | Medium |
| Severity | High |
| Evidence | 공식 문서: continuous recognition 용도 아님(`CONFIRMED`). silence 힌트는 무시될 수 있음 |
| Mitigation | Final = Segment 취급, 즉시 재시작, segmentId, Mode P 세그먼트 세션 |
| Fallback | Mode H 재시작 방식 |
| Validation | GV-10 |
| Status | `OPEN` |

### R-12 Android 13+ Restricted Settings로 Accessibility 활성화 불가

| 필드 | 내용 |
|---|---|
| Risk | 사이드로드 APK가 접근성 설정에서 차단 |
| Likelihood | High (발생) / Low (해결 불가) |
| Impact | Medium — 온보딩 이탈 |
| Severity | High |
| Evidence | `COMMUNITY_REPORTED`. One UI 동작 미확인 |
| Mitigation | 온보딩에 "앱 정보 → 제한된 설정 허용" 단계 명시. `adb install` 경로 차이 문서화 |
| Fallback | Plan B(Accessibility 불필요) |
| Validation | GV-20 |
| Status | `OPEN` |

### R-13 Porcupine 라이선스/비용 조건

| 필드 | 내용 |
|---|---|
| Risk | 무료 플랜의 custom `.ppn` 모델·다중 키워드·상용 조건이 가정과 다름 |
| Likelihood | Unknown |
| Impact | Medium — 엔진 교체 |
| Severity | High |
| Evidence | 다중 키워드 `SUPPORTED BY SDK`(공식 문서). 비용·약관은 미확인 |
| Mitigation | `WakeWordEngine` 인터페이스로 교체 가능. 문서에 비용 확정 표현 금지 |
| Fallback | openWakeWord / sherpa-onnx KWS (한국어 품질 미확인) |
| Validation | 현행 Picovoice 약관 확인 (`MUST BE VERIFIED AGAINST CURRENT PICOVOICE TERMS`) |
| Status | `OPEN` |

---

## Medium

### R-14 잠금 상태 UX

| 필드 | 내용 |
|---|---|
| Risk | 잠금 중 ChatGPT 화면 표시 불가. `startAssistantActivity`가 잠금 해제 화면을 띄움 |
| Likelihood | High |
| Impact | Low — 큐잉으로 대응 |
| Severity | Medium |
| Mitigation | 잠금 중 큐잉 + 해제 시 전송(`ACTION_USER_PRESENT`) |
| Validation | GV-04 |
| Status | `OPEN` |

### R-15 Bluetooth/Buds 입력에서 Wake/STT 품질 저하

| 필드 | 내용 |
|---|---|
| Risk | HFP 대역폭으로 Wake 감지율 하락 |
| Likelihood | Medium |
| Impact | Low (v0.1은 기본 확인만) |
| Severity | Medium |
| Mitigation | v0.2 라우팅 정책 |
| Validation | GV-18 |
| Status | `OPEN` |

### R-16 ChatGPT 앱 업데이트로 Plan A 파손 (유지보수)

| 필드 | 내용 |
|---|---|
| Risk | 업데이트마다 selector 깨짐 |
| Likelihood | High (장기) |
| Impact | Medium |
| Severity | Medium (개인용) |
| Mitigation | `PACKAGE_REPLACED` 감지 → `healthCheck()` → 배지, Selector 저장, 보정 화면 재실행 |
| Fallback | Plan B |
| Status | `ACCEPTED` (HIGH MAINTENANCE RISK) |

### R-17 온디바이스 STT(ko-KR) 미지원 → 온라인 의존

| 필드 | 내용 |
|---|---|
| Risk | 프라이버시 기대와 어긋남, 오프라인 불가 |
| Likelihood | Medium |
| Impact | Low |
| Severity | Medium |
| Mitigation | 온보딩 명시, `EXTRA_PREFER_OFFLINE` |
| Validation | GV-08 |
| Status | `OPEN` |

### R-18 Follow-up Mode 응답 완료 감지 불가

| 필드 | 내용 |
|---|---|
| Risk | v0.2 실험 자체가 불가 |
| Likelihood | Medium |
| Impact | Low (v0.1 범위 밖) |
| Severity | Medium |
| Mitigation | v0.1 제외(ADR-009). v0.2에서 "Stop 버튼 소멸/Send 재활성" 실험만 |
| Status | `OPEN` (v0.2) |

---

## Low

### R-19 AccessKey 노출

APK 역공학으로 노출 가능. 개인 키, 저장소 미포함, 재발급 가능. `ACCEPTED`.

### R-20 클립보드 폴백 노출

짧은 시간, 즉시 비움, 로그. Samsung 클립보드 히스토리 잔존은 `DEVICE_TEST_REQUIRED`. `ACCEPTED`.

### R-21 명령어 오인(문장 일부 `취소`)

마지막 어절만 검사. 오인 사례 로그 수집. `OPEN`.

---

## 요약 매트릭스

| ID | 위험 | Severity | Status | Spike |
|---|---|---|---|---|
| R-01 | ChatGPT Accessibility node unavailable | Critical | OPEN | S-3 |
| R-02 | Background microphone survival | Critical | OPEN | S-1 |
| R-03 | Battery drain | Critical | OPEN | — |
| R-04 | Wake false positive | Critical | OPEN | S-1 |
| R-05 | STT microphone handoff | Critical | OPEN | S-2 |
| R-06 | Privacy Indicator UX acceptance | Critical | ACCEPTED | — |
| R-07 | Samsung process killing | Critical | OPEN | — |
| R-08 | Duplicate message send | Critical | OPEN | S-3 |
| R-09 | False endpoint | High | OPEN | — |
| R-10 | startAssistantActivity ChatGPT 화면 | High | OPEN | S-3 |
| R-11 | Recognizer 자체 endpointing | High | OPEN | S-2 |
| R-12 | Restricted Settings | High | OPEN | S-3 |
| R-13 | Porcupine 라이선스/비용 | High | OPEN | — |
| R-14~R-18 | Medium 항목 | Medium | OPEN/ACCEPTED | — |
| R-19~R-21 | Low 항목 | Low | ACCEPTED/OPEN | — |
