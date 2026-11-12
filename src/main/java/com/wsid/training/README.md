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
| 사용자 답변에 맞춘 대화 | SIM-007 · I3 | 상 | 구현 | `TrainingService.submitTurn` |
| 대응 행동 판정 | SIM-008 · I3 | 상 | 분류 저장까지 구현 | `UserAction`, `training_messages.action_code` |
| 핵심 장면 전개 | SIM-011 · I3 | 상 | 구현 (선택지 표시는 미구현) | `TrainingSession.applyTurn` |
| 가드레일 적용 | SIM-006, 007 | 상 | 구현: 미통과 시 검토된 문구로 대체 | `TurnInput.replyUsable` |
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

## API

| 메서드·경로 | 용도 |
|---|---|
| `POST /api/training/sessions` | 훈련 시작. 첫 질문을 돌려줘요 |
| `GET /api/training/sessions/active` | 홈의 이어하기. 없으면 204 |
| `GET /api/training/sessions/{id}` | 상태와 지난 대화 |
| `POST /api/training/sessions/{id}/turns` | 답변 전송 |

경로와 필드 이름은 API 명세 초안과 맞춰 확정해요.

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
- **후속 질문(SIM-001, 002)을 만들 때**: `AiClient`에 메서드를 추가하고, `finishDiscovery`를 "사실 추출 → 5회 미만이고 사실이 부족하면 다음 질문, 아니면 `beginSimulation`"으로 바꿔요. 횟수 판단은 `TrainingSession`에 메서드로 둬요.
- **직접 종료(SIM-009)**: `TrainingSession.finishByUser(now)`를 만들고 `POST /{id}/finish`를 추가해요.

## 주의

- 사용자 입력은 로그에 남기지 않아요.
- `simulation_turn_count`는 AI 메시지 수예요. 시나리오 첫 대사가 1이에요.
