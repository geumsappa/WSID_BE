# report — 결과 리포트와 마이페이지

훈련이 끝난 뒤의 평가 결과를 만들고, 저장하고, 다시 보여줘요.

## 명세서 매핑

| 명세서 항목 | ID | 우선순위 | 상태 | 관련 코드 |
|---|---|---|---|---|
| 리포트 생성과 즉시 표시 | SIM-010 · I4 | 상 | 엔티티만 | `TrainingReport.create` |
| 마이페이지에 저장 | RPT-001 | 상 | 엔티티만 | `TrainingReport.saveToMyReports` |
| 저장한 리포트 PDF 다운로드 | RPT-004 | 상 | 미구현 | - |
| 저장한 리포트 목록 | RPT-002 | 상 | 미구현 | `TrainingReportRepository`의 TODO |
| 리포트 상세 다시 보기 | RPT-003 | 상 | 미구현 | - |
| 훈련 원문 삭제 | 비기능 | 상 | 미구현 | - |

## 목표 API

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| RPT-01 | GET | `/api/trainings/{id}/report` | 필요 | 엔티티·repository만; 목표 경로의 서비스·API 미구현 |
| RPT-02 | POST | `/api/reports/{id}/save` | 필요 | saveToMyReports 엔티티 메서드만; 원문 삭제·API 없음 |
| RPT-03 | GET | `/api/reports` | 필요 | repository TODO; API 없음 |
| RPT-04 | GET | `/api/reports/{id}` | 필요 | 문서 계획; 소유권 조회 서비스·API 없음 |
| RPT-05 | GET | `/api/reports/{id}/pdf` | 필요 | 문서 계획; PDF 생성·API 없음 |

모두 로그인 필수예요. 엑셀의 SIM-010은 RPT-01, RPT-001~004는 각각 RPT-02~05에 대응해요. 전체 평가에는 잘한 행동·놓친 위험 신호·권장 대응을 담아요.

## 테이블

- `training_reports` → `domain/TrainingReport`
- `saved_at`이 비어 있으면 아직 '내 리포트에 저장'을 누르지 않은 리포트예요.
- 리포트에는 `user_id`가 없어요. 소유자는 `training_sessions.user_id`로 확인해요.

## 개발 순서

1. **SIM-010 생성**: 훈련이 끝날 때(`TurnOutcome.finished()`) 평가 결과로 리포트를 만들어요.
   종료 상태를 저장하는 트랜잭션을 마친 뒤, `training_messages`의 `action_code` 등 평가에 필요한 기록을 읽어 AI-05(I4)를 트랜잭션 밖에서 호출해요. 결과 리포트 저장은 별도 트랜잭션으로 처리해요.
   현재 `applyTurn`은 트랜잭션 안에서 실행되므로 그 안에서 AI 평가까지 동기 호출하지 않아요. 종료 API의 응답·재시도·중복 생성 처리는 이 경계에 맞춰 정의해요.
   AI가 실패하면 행동 기록만으로 만든 기본 리포트를 주고, 실패했다는 사실을 화면에 알려요.
2. **조회**: `GET /api/trainings/{id}/report`. 세션을 소유자 조건으로 먼저 읽은 뒤 리포트를 찾아요.
3. **RPT-001 저장**: `saveToMyReports`를 호출해요. 다시 눌러도 한 번만 저장돼요. 저장한 뒤 그 훈련의 `training_messages.content`와 재전송용 원문 사본이 있다면 함께 지워요. 삭제는 아직 미구현이에요.
4. **RPT-002 목록**: 로그인한 사용자의 `saved_at`이 있는 리포트를 제목, 훈련 유형, 완료일과 함께 최신 완료순으로 돌려줘요. 없으면 빈 목록이에요.
5. **RPT-003 상세**: 훈련 세션이 만료돼도 볼 수 있어야 해요. 그래서 세션 행은 지우지 않아요.
6. **RPT-004 PDF**: 저장된 `report_json`으로 만들어요. 대화 원문은 넣지 않아요.

## 규칙

- 다른 계정의 리포트는 **404**예요 (403이 아니에요).
- `report_json`에는 평가 결과만 넣어요. 사용자가 입력한 원문은 넣지 않아요.

## 정할 것

- `report_json`의 구조 (잘한 행동, 놓친 위험 신호, 권장 대응)
- 저장하지 않은 리포트와 대화 원문의 보관 기간
- PDF 라이브러리
