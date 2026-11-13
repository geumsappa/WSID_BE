# training — 상황 대화와 가상 훈련

사용자가 '사기 상황 연습하기'를 누른 뒤부터 훈련이 끝날 때까지의 흐름을 맡아요.
**다른 기능을 만들 때 본보기로 삼는 패키지**예요. 계층 나누는 법, AI 호출 순서, 소유권 확인, 테스트가 모두 들어 있어요.

## 명세서 매핑

| 명세서 항목 | ID | 우선순위 | 상태 | 관련 코드 |
|---|---|---|---|---|
| 진행 중인 훈련 확인 (홈 이어하기) | - | 상 | 구현 | `TrainingService.findActive` |
| 훈련 세션 생성 | SIM-001 | 상 | 구현 | `TrainingService.start`, `TrainingSession.start` |
| 대화와 화면 상태 복구 | - | 상 | 구현 (선택지 복구는 미구현) | `TrainingService.getState` |
| AI가 먼저 질문 | SIM-001 | 상 | 구현 (문구는 코드 상수) | `ReviewedTexts.DISCOVERY_FIRST_QUESTION` |
| 답변에 맞춘 후속 질문 | SIM-001 · I1 | 상 | 임시: 첫 답변 뒤 바로 훈련 시작 | `TrainingService.finishDiscovery` |
| 이미지 첨부와 OCR | SIM-001 · I1 | 중 | 미구현 | - |
| 일상 정보에서 위험 요소 추출 | SIM-002 · I1 | 상 | 미구현 | `context_facts` 컬럼 |
| 훈련할 판단 지점 선정 | SIM-004 · I1 | 상 | 미구현 | - |
| 개인화 시나리오 생성 | SIM-005 · I2 | 상 | 임시: 기본 유형 고정 | `scenario_json` 컬럼 |
| 사용자 답변에 맞춘 대화 | SIM-007 · I3 | 상 | fake/HTTP 어댑터 연결, 맥락 전달 미완료 | `TrainingService.submitTurn` |
| 대응 행동 판정 | SIM-008 · I3 | 상 | 분류 저장까지 구현 | `UserAction`, `training_messages.action_code` |
| 핵심 장면 전개 | SIM-011 · I3 | 상 | 구현 (선택지 표시는 미구현) | `TrainingSession.applyTurn` |
| 가드레일 적용 | SIM-006, 007 | 상 | AI 검사 결과 플래그에 따른 대체 구현; 실제 출력 검사 연동 미검증 | `TurnInput.replyUsable` |
| 모의 페이지와 채팅 링크 | SIM-014 | 중 | 미구현 | `mock_page_template_code` 컬럼 |
| 모의 페이지 행동 기록 | SIM-014 · SIM-010 | 중 | 미구현 | `InputKind.ACTION`, `action_meta_json` |
| 직접 종료와 자동 종료 | SIM-009 | 상 | 자동 종료만 구현 | `TrainingSession.applyTurn` |
| 훈련 세션 만료 | 비기능 | 중 | 만료 판정 구현, 상태 정리는 미구현 | `TrainingSession.isExpired` |
| 중복 처리와 상태 충돌 방지 | 비기능 | 상 | 구현 (중복이면 409) | `client_request_id`, `state_version` |
| 훈련 입력의 민감정보 처리 | 비기능 · I1, I3 | 상 | 미구현 | `TrainingService.submitTurn`의 TODO |
| 훈련 원문 보관과 삭제 | 비기능 | 상 | 보관만 구현 | `TrainingMessage.content` |
| AI 호출 실패 시 대체 흐름 | 비기능 | 상 | 구현 | `TrainingService.askAi` |

## 테이블

- `training_sessions`: 훈련 한 건의 현재 상태 → `domain/TrainingSession`
- `training_messages`: 대화 한 줄 → `domain/TrainingMessage`

## 목표 API와 현재 구현

모든 TRN API는 로그인한 사용자만 이용해요. `/api/trainings/{id}`의 `{id}`는 훈련 한 건의 ID예요. 로그인 세션 번호가 아니며 훈련마다 API를 새로 만드는 것도 아니에요.

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| TRN-01 | POST | `/api/trainings` | 필요 | 기존 POST /api/training/sessions에서 동작; 경로 불일치 |
| TRN-02 | GET | `/api/trainings/active` | 필요 | 기존 GET /api/training/sessions/active에서 동작; 경로 불일치 |
| TRN-03 | GET | `/api/trainings/{id}` | 필요 | 기존 GET /api/training/sessions/{id}에서 동작; 선택지 복원 없음 |
| TRN-04 | POST | `/api/trainings/{id}/discovery/messages` | 필요 | 기존 /turns가 처리; 첫 답변 뒤 즉시 훈련으로 전이, 후속 질문·사실 추출 없음 |
| TRN-05 | POST | `/api/trainings/{id}/discovery/images` | 필요 | 미구현; 파일 제한 검증·임시 보관·삭제 구현 필요 |
| TRN-06 | POST | `/api/trainings/{id}/scenario` | 필요 | 별도 API 없음; 기본 유형 고정 |
| TRN-07 | POST | `/api/trainings/{id}/start` | 필요 | 별도 API 없음; finishDiscovery가 beginSimulation 직접 호출 |
| TRN-08 | POST | `/api/trainings/{id}/messages` | 필요 | 기존 /api/training/sessions/{id}/turns에서 동작; 경로·AI 맥락 불일치 |
| TRN-09 | GET | `/api/trainings/{id}/mock-page` | 필요 | 템플릿 코드 컬럼만; 응답 DTO·조회 없음 |
| TRN-10 | POST | `/api/trainings/{id}/actions` | 필요 | InputKind.ACTION·JSON 컬럼만; API·동작 없음 |
| TRN-11 | POST | `/api/trainings/{id}/finish` | 필요 | 자동 종료만 있음; 직접 종료·리포트 생성·API 없음 |

현재 실행 경로는 `POST /api/training/sessions`, `GET /api/training/sessions/active`, `GET /api/training/sessions/{id}`, `POST /api/training/sessions/{id}/turns`예요. 목표 API로의 경로·역할 전환은 아직 구현하지 않았어요.

## 목표 상황 파악 흐름

1. TRN-01에서 훈련을 만들고 준비된 첫 질문을 반환해요.
2. TRN-04에서 사용자 답변을 받아 AI-01로 사실 추출과 다음 질문을 제안받아요. 확인된 사실을 기존 맥락에 합치고 DB에 저장해요. 사실과 가상 설정은 구분해요.
3. 추가 질문이 필요하면 같은 TRN-04에 다음 답변을 보내요. 추가 질문 상한 5회는 초안이고, 중단 조건은 domain 규칙으로 정해요. 한 번의 답변만으로 상황 파악을 강제 종료하지 않아요.
4. 상황 파악이 끝나면 TRN-06/AI-03으로 누적된 사실과 판단 지점에 맞는 시나리오를 생성·저장해요.
5. TRN-07에서 가상 훈련을 시작하고 TRN-08/AI-04로 상대 역할과 대화해요. 프론트가 생성·시작 API를 이어서 호출할 수 있어요.

`/discovery/messages`는 사용자 상황을 알아보기 위한 질문·답변, `/messages`는 생성한 시나리오 속 상대 역할과의 대화예요. 현재 `/turns` 하나에서 두 단계를 처리하는 것은 임시 구현이에요.

이전 사실·시나리오·필요한 최근 대화는 다음 AI 요청에 전달해야 해요. 현재 `contextFacts`, `scenarioJson`은 컬럼만 있고 갱신 로직은 없으며 `TrainingTurnRequest`의 맥락 필드도 TODO예요.

## 파일

| 파일 | 역할 |
|---|---|
| `domain/TrainingSession` | 세션 엔티티 + 턴·핵심 장면·종료·만료 규칙 |
| `domain/TrainingRules` | 12턴, 15턴, 24시간 같은 설정값 |
| `domain/TurnInput`, `TurnOutcome` | 규칙의 입력과 결과 |
| `domain/FlowState` | `flow_state_json`에 저장하는 진행 상태 |
| `domain/TrainingMessage` | 메시지 엔티티 |
| `service/TrainingService` | 읽기 → AI 호출 → 저장, 소유권 확인, 대체 문구 선택 |
| `service/ReviewedTexts` | 검토된 문구 (content YAML로 옮길 예정) |
| `controller/TrainingController` | 엔드포인트 |

## 한 턴이 처리되는 순서

1. `CurrentUser`로 사용자 확인, 같은 `requestId`가 이미 있으면 409
2. 세션을 소유자 조건으로 읽고 `stateVersion` 확인
3. **트랜잭션 밖에서** AI 호출. 실패하면 빈 제안으로 대체
4. 트랜잭션 안에서 세션을 다시 읽어 버전 재확인 → `session.applyTurn(...)` → 메시지 2개와 세션 저장

## 이 패키지에서 기능을 추가하는 방법

- **턴 규칙을 바꿀 때**: `TrainingSessionTest`에 테스트를 먼저 추가하고 `TrainingSession.applyTurn`을 고쳐요. service는 건드리지 않아요.
- **진행 상태 항목을 늘릴 때**(예: 대기 중인 선택지): `FlowState`에 필드를 추가해요. 테이블은 그대로예요.
- **후속 질문(SIM-001, 002)을 만들 때**: TRN-04에서 AI-01 호출 → 사실 누적 → 필요하면 다음 질문으로 이어가요. 추가 질문 횟수와 준비 완료 판단은 domain에 둬요. 시나리오 생성(TRN-06)·시작(TRN-07)을 분리하고, 상황 답변에서 `beginSimulation`으로 바로 전환하는 임시 코드를 대체해요.
- **직접 종료(SIM-009)**: `TrainingSession.finishByUser(now)`를 만들고 `POST /api/trainings/{id}/finish`를 추가해요. 상황 파악 단계에서 종료하면 평가할 훈련이 없음을 안내하고, 가상 훈련을 마쳤을 때만 리포트 생성 흐름으로 연결해요.

## 주의

- 사용자 입력은 로그에 남기지 않아요.
- `simulation_turn_count`는 AI 메시지 수예요. 시나리오 첫 대사가 1이에요.

## 추가 구현 조건

- OCR(TRN-05/AI-02): PNG·JPEG, 최대 3장, 장당 5MB, 원본 최대 10분 보관. 읽을 수 없으면 텍스트 입력을 안내해요.
- 모의 페이지(TRN-09/10): 금융·공공 민원·채용 템플릿에 가상 기관명·로고를 사용해요. 링크는 현재 훈련 안에서 열고, 모의 로그인은 준비된 가상 정보만 사용해요. 화면 행동과 대화에서 말한 행동을 구분해 기록해요.
- 훈련 입력의 민감정보 감지·재입력 안내, 원문 삭제, 실제 계정 인증은 미구현이에요. 훈련 무활동 24시간은 로그인 세션 만료 시간과 별개예요.
