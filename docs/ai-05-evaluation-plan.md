# AI-05 훈련 평가 구현·테스트 계획서

작성일: 2026-11-16 · 상태: 구현 전 계획

명세 출처: **AI-05 내부 API 명세 초안(2026-11-16 제공본)** 및 팀 API 전체 명세의 AI-05 절(같은 날짜 기준, 저장소 미포함)이다. 리포트 공개 응답 예시도 같은 명세의 RPT-01과 대조했다. 신규 필드·배열 요소·오류 정책은 실제 FastAPI 계약과 대조해 확정한다. 이 문서는 계획만 작성하며 Java 코드·DB·훈련 종료 동작을 변경하지 않는다.

## 1. 목표와 단계별 범위

`POST /internal/ai/training/evaluate`는 훈련 시나리오·대화·행동 기록을 받아 **리포트에 넣을 평가 결과만** 반환하는 내부 API다. Spring은 종료 상태와 평가 근거를 확보하고, 결과를 검증한 뒤 `training_reports.report_json`에 저장한다. FastAPI가 DB를 저장하거나 종료 여부를 결정하지 않는다.

| 단계 | 구현 범위 | 완료 기준 |
|---|---|---|
| 1차: Spring 클라이언트 | 요청·응답 DTO, `AiClient.evaluateTraining`, fake/HTTP, 타입 검사·시간 제한·계약 테스트 | 합의한 메시지 형식으로 호출·결과 반환. 운영 호출자와 DB 저장은 아직 없음 |
| 2차: FastAPI | 근거 기반 평가, 출력 스키마·원문 복사 방지·가드레일, 내부 인증·요청 추적 | 평가 가능한 범위만 구조화해 반환 |
| 3차: 리포트 연결 | 종료 후 호출, 근거 검증·기본 리포트·저장·조회·동시 생성·재시도 | 훈련 종료를 유지하며 사용자 소유 리포트를 생성·복원 |
| 후속: 보관·PDF | 저장 처리·원문 삭제·내 목록·PDF | 저장된 평가만으로 다시 조회·출력 가능 |

AI-03·04처럼 1차는 호출 클라이언트까지 구현한다. 리포트 생성 서비스·공개 RPT API·FastAPI 구현을 완료했다고 표시하지 않는다.

## 2. 현재 코드와 선행 과제

| 확인한 코드·상태 | 구현에 미치는 영향 |
|---|---|
| [TrainingReport](../src/main/java/com/wsid/report/domain/TrainingReport.java)에 title·reportJson·savedAt이 있음 | 저장 공간은 존재. 현재 create는 JSON 내용·개인정보를 검증하지 않음 |
| [TrainingReportRepository](../src/main/java/com/wsid/report/repository/TrainingReportRepository.java)에 findByTrainingId가 있음 | 훈련당 기존 리포트 조회 가능. 생성·소유권 서비스는 없음 |
| [V2 제약](../src/main/resources/db/migration/V2__add_constraints.sql)의 uq_training_reports_training | 훈련 1건당 리포트 1개. 동시 생성 충돌을 서비스에서 처리해야 함 |
| [TrainingService.applyTurn](../src/main/java/com/wsid/training/service/TrainingService.java)에 리포트 TODO가 있음 | 현재 저장 트랜잭션 내부이므로 여기서 바로 AI 평가를 호출하면 안 됨 |
| [TrainingSession](../src/main/java/com/wsid/training/domain/TrainingSession.java)은 종료 시 REPORT·COMPLETED로 변경 | 평가 실패 때문에 종료 트랜잭션을 롤백하거나 ACTIVE로 되돌리지 않음 |
| [TrainingMessage](../src/main/java/com/wsid/training/domain/TrainingMessage.java)에 inputKind·actionCode·메타데이터 공간이 있음 | 실제 OPTION·ACTION 생성/저장·메타데이터 조회는 후속 작업 |
| AI-04는 새 클라이언트만 구현되고 서비스는 옛 호출 유지 | 복수 행동·실제 화면 이벤트가 모두 저장된다고 가정하면 안 됨 |
| 시나리오 생성·저장, 모의 페이지·선택지 API가 미연결 | 현재 DB만으로 완전한 평가 근거를 확보할 수 없음 |
| [개발 정책](../README.md)에 턴별 피드백 제외가 명시됨 | 새 turnFeedback 필드와 정책 충돌을 별도 결정해야 함 |

### turnFeedback 범위

최신 AI-05 초안의 필드를 계획에 포함하되, 기존의 턴별 피드백 제외 정책을 조용히 바꾸지 않는다. **초기 계약은 turnFeedback 필드를 유지하면서 빈 배열만 지원하는 방안**을 제안한다. null·누락·비어 있지 않은 배열은 1차 미지원 응답으로 처리한다. FastAPI와 이 제한을 먼저 맞춰 정상 응답이 전부 거부되는 일을 방지한다.

턴별 피드백을 실제로 제공하기로 결정하면 요소의 메시지 식별자·평가 구조·원문 인용 금지·공개 응답·화면/PDF 범위를 함께 정하고 DTO와 정책 문서를 확장한다. `turn_evaluation_json`에 턴별 평가를 임의로 저장하는 작업은 이번 1차에 포함하지 않는다.

## 3. 요청 계약과 평가 근거

```http
POST /internal/ai/training/evaluate
Content-Type: application/json
X-Internal-Api-Key: <환경 설정값>
X-Request-Id: <현재 평가 요청의 UUID>
```

```json
{
  "scenario": {
    "title": "중고거래 안전 확인 훈련",
    "description": "가상 거래 상황"
  },
  "conversation": [
    {"role": "assistant", "content": "안내를 어떻게 확인하시겠어요?"},
    {"role": "user", "content": "안내의 내용을 확인해 보겠습니다."}
  ],
  "actions": [
    {"actionCode": "MOCK_PAGE_OPEN"}
  ]
}
```

시나리오의 빈 객체와 배열 요소의 상세 형식은 초안에서 미정이다. 1차에서는 시나리오 title·description과 conversation의 role·content, actions의 actionCode를 최소 계약으로 제안한다. 선택지의 구체적인 항목·장면·행동 출처 연결이 필요하면 확장 스키마를 FastAPI와 합의한다. 기존 엔티티를 그대로 직렬화해 숨겨진 필드를 추가하지 않는다.

| 값 | 1차 검증 제안 |
|---|---|
| scenario | AI-03의 Scenario 값 타입 재사용. 제목·설명 필수, 100자·1,000자 이하 |
| conversation | null 불가, 빈 배열 허용. user/assistant만, 각 내용은 공백 불가·1,000자 이하 |
| 대화 개수 | 최대 40개 제안. 최근 일부가 아닌 평가 대상 전체 훈련 대화 |
| actions | null 불가, 빈 배열 허용. 최대 100개 제안 |
| actionCode | 각 객체의 필수 문자열. `[A-Z0-9_]{1,100}` 형식 |
| 평가 근거 | conversation과 actions가 모두 비었으면 입력 오류로 거부하는 방안 |
| requestId | Java DTO에 UUID 필수. JSON 본문에서 제외하고 헤더로만 전송 |

수치는 확정 전 제안값이다. 문자는 Java String.length 기준으로 검사하고 목록은 방어적으로 복사한다. null 요소·잘못된 역할·타입·길이는 DTO에서 입력 예외로 거부한다. 학습용 설명이나 가상 사례 자체를 사용자가 수행한 행동으로 보내지 않는다.

### 대화 전체와 과거 데이터

- AI-04의 최근 20개 제한을 그대로 재사용하지 않는다. AI-05는 종료 시점까지의 SIMULATION 사용자·AI 대화를 시간순으로 평가한다.
- 현재 종료 안내는 SYSTEM 메시지이므로 평가할 역할극에서 제외한다. 상황 파악 대화도 필요한 요약 사실과 구분한다.
- 최대 턴 정책과 40개 한도의 관계를 검증하고, 기존 데이터·향후 턴 상한 변경으로 한도를 넘는 경우를 처리한다.
- 옛 AI 대사의 1,000자 초과나 손상된 기록을 임의로 자르거나 일부만 보내 전체 평가라고 표시하지 않는다.
- **3차 입력 구성에서 길이·개수 위반이나 주요 기록 누락을 발견하면 AI 호출을 생략하고, 확인 가능한 행동만 사용하는 기본 리포트로 전환한다.** 평가 범위가 제한됐다는 상태를 저장·표시한다. DTO 예외를 사용자 종료 요청의 실패로 흘려보내지 않는다.
- 단순 대화 없음과 평가할 훈련이 없음은 다르다. 화면 이벤트만 있다면 이벤트 범위에서 평가할 수 있지만, DISCOVERY에서만 종료한 경우에는 평가할 훈련이 없음을 안내하고 AI-05를 호출하지 않는다.

### 행동 출처와 선택지

`MOCK_PAGE_OPEN`은 이번 AI-05 초안에 제시된 코드다. 모의 로그인 시도·페이지 닫기·공식 채널 확인·선택지 응답 코드 목록은 TRN-10 및 관련 입력 계약과 함께 확정한다. AI-04의 OFFICIAL_CHANNEL_CHECK 분석을 실제 공식 채널 버튼 클릭으로 바꾸지 않는다.

3차 서비스는 DB의 실제 이벤트와 AI의 텍스트 행동 분석을 구분한다. 1차 actionCode만 있는 최소 요청으로 출처·장면·선택지 항목을 모두 구분할 수 있다고 보지 않는다. 실제 선택지·복수 행동 평가 연결 전에 source·관련 메시지/장면·optionCode 등 필요한 필드를 별도 DTO 계약으로 확정한다. 프론트가 보낸 행동 문자열만으로 검증된 이벤트를 만들지 않는다.

알 수 없는 형식상 유효 코드는 1차에서 보존하되 의미를 추측하지 않는다. 3차에서는 평가에 사용할 수 있는 코드·출처를 선별하고 미확정 기록이 있으면 평가 범위를 표시한다. 배열 개수 초과도 조용히 잘라 평가 근거를 잃지 않도록 기본 리포트 전환 조건에 포함한다.

## 4. 성공 응답과 모의 페이지 사실 검증

```json
{
  "success": true,
  "data": {
    "overallFeedback": "훈련 기록을 바탕으로 확인 방법을 돌아보세요.",
    "strengths": [],
    "improvements": [],
    "turnFeedback": [],
    "mockPageEvaluation": {
      "openedPage": true,
      "attemptedMockLogin": false,
      "checkedOfficialChannel": false
    },
    "recommendations": []
  }
}
```

위 예시는 실제 이벤트 기록이 완전하고 페이지 열기만 기록된 합성 사례다. 초안의 checkedOfficialChannel=true는 예시 요청의 MOCK_PAGE_OPEN만으로는 근거가 없어 여기서는 false로 맞췄다. 기록이 불완전한 경우의 false는 “행동하지 않았다”는 뜻으로 표시해서는 안 된다.

| 필드 | 1차 검증 제안 |
|---|---|
| success | 실제 JSON boolean true 필수 |
| data | 객체 필수 |
| overallFeedback | 문자열, 공백 불가, 최대 1,000자 |
| strengths·improvements·recommendations | 필수 배열. 초기에는 문자열 배열 제안, 각 최대 10개·항목당 500자 |
| turnFeedback | 필수 빈 배열. 요소 스키마·제공 정책 확정 후 확장 |
| mockPageEvaluation | 필수 객체 |
| openedPage·attemptedMockLogin·checkedOfficialChannel | 필드 모두 필수, 실제 JSON boolean |

AI-05 자체는 빈 배열만 예시로 제시하지만 API 전체 명세의 RPT-01은 strengths·improvements·recommendations에 문자열 요소를 사용한다. 1차는 이 공개 응답과 일치하는 문자열 배열을 제안한다. FastAPI가 객체 배열을 사용한다면 내부·공개 계약의 관계를 먼저 합의하고 DTO·테스트를 함께 바꾼다. 평가 결과의 null 배열을 빈 배열로 조용히 바꾸지 않는다.

`HttpAiClient.evaluateTraining()`은 **JsonNode로 먼저 응답을 읽어 boolean·객체·배열·요소 문자열 타입을 검사한 뒤 treeToValue로 변환**한다. 문자열 true/false와 숫자의 자동 변환을 허용하지 않는다. 필수 배열·boolean 필드가 생략되지 않도록 FastAPI 직렬화 설정도 확인한다.

### 모의 페이지 평가는 LLM의 사실 판정이 아니다

1차 클라이언트는 boolean 타입을 검사할 뿐 실제 이벤트의 완전성까지 보장하지 않는다. 2차 FastAPI는 실제 이벤트에 해당하는 값만 만들고, 3차 Spring은 DB 이벤트에서 계산한 사실과 결과를 대조한다. 불일치 결과는 리포트로 저장하지 않고 기록 기반 기본 리포트로 전환한다.

기록 출처·수집 상태가 완전한 경우에만 이벤트 없음으로 false를 확정한다. 아직 모의 페이지·이벤트 API가 연결되지 않은 기존 세션은 미관측 상태다. report_json의 평가 메타데이터에 기록 상태를 보관하고, 화면에서도 “기록 없음/평가 제한”으로 구분하는 방안을 제안한다. 세 boolean만으로 미관측까지 표현할 수 있다고 가정하지 않는다.

## 5. 원문 복사 방지와 실패 처리

평가 입력에는 대화가 필요하지만 결과 리포트에는 전체 원문·인식 문자·현재 입력·LLM 프롬프트를 복사하지 않는다.

- 닫힌 응답 DTO에 overallFeedback·평가 배열·모의 페이지 평가만 정의한다. conversation·messages·rawInput·evidenceText 같은 원문 필드는 만들지 않는다.
- HTTP 봉투나 원본 JsonNode를 report_json으로 저장하지 않는다. 사용하지 않는 추가 응답 필드는 버리고 검증된 결과 DTO만 직렬화한다.
- **DTO의 필드 제한만으로 자유 텍스트 안의 원문 복사를 막을 수는 없다.** FastAPI 출력 검증과 3차 ReportContentValidator에서 전체 대화·긴 원문 구절·민감정보 복사를 검사한다. 탐지 시 결과를 채택하지 않고 기본 리포트로 전환한다.
- 정확히 일치하는 전체 원문·긴 구절 탐지와 의미·개인정보 검사를 구분한다. 짧고 일반적인 “확인한다” 같은 문구까지 무조건 차단하지 않도록 기준과 평가 세트를 합의한다.
- 기관명·공식 연락처·링크를 LLM이 임의로 생성해 권고에 넣지 않는다. 필요한 공식 정보는 검토된 콘텐츠에서 연결한다.
- DTO의 toString과 예외에는 평가 입력·출력 원문을 남기지 않는다. 응답 생성 실패의 cause도 본문을 포함할 수 있어 보관하지 않는다.

| 상황 | 클라이언트 결과 | 3차 호출자 처리 |
|---|---|---|
| 로컬 요청 값 오류 | IllegalArgumentException | 입력 구성 오류와 과거 기록 제한을 구분해 처리 |
| 정상 형식 | 평가 결과 DTO | 근거·내용 검증 후 저장 |
| success=false·HTTP 오류·JSON/타입 위반·시간 초과 | AiUnavailableException | 종료 상태를 유지하고 기록 기반 기본 리포트 생성 |
| 원문 복사·모의 페이지 사실 불일치 | 후속 검증 계층에서 채택 거부 | 기본 리포트와 제한 상태 표시 |
| DB 장애 | 저장 실패 | 성공 리포트가 생성됐다고 표시하지 않고 복구 가능한 생성 상태로 안내 |

생성 장애는 503·AI_SERVICE_UNAVAILABLE 실패 봉투를 제안하며 최종 형식은 AI 담당자와 확정한다. 사용자에게 모델 오류 본문을 그대로 전달하지 않는다. 자동 재시도는 1차에 넣지 않는다.

## 6. 1차 Spring 구현 파일과 순서

| 파일 | 작업 |
|---|---|
| ai/dto/TrainingEvaluateRequest.java | scenario·conversation·actions와 헤더용 요청 ID. training/report 엔티티 import 금지 |
| ai/dto/EvaluationMessage.java | 평가 대상 대화의 role·content. 전체 평가의 개수·길이 규칙 분리 |
| ai/dto/EvaluationAction.java | 초기 actionCode 형식. 출처·선택지 연결은 합의된 스키마로 확장 |
| ai/dto/TrainingEvaluateResult.java | 전체 피드백·배열·모의 페이지 평가. 초기 turnFeedback 빈 배열 계약 |
| ai/AiClient.java | evaluateTraining 추가 |
| ai/FakeAiClient.java | 결정적 개발용 결과·실패 흐름. 모델 평가 정확도와 구분 |
| ai/HttpAiClient.java | 새 경로·헤더·JsonNode 검사·오류 변환 |
| ai/AiProperties.java·AiClientConfig.java·application.yml | 평가용 응답 제한·별도 전송 구성 |
| src/test/java/com/wsid/ai/ | 요청/결과·fake·HTTP·설정·실제 전송 테스트 |

1. 배열 요소·turnFeedback 초기 범위·길이·근거 최소 조건을 FastAPI와 확정한다.
2. DTO와 인터페이스를 추가하고, 같은 훈련의 상태 객체 대신 필요한 값만 전달한다. 목록은 방어적으로 복사한다.
3. fake는 평가 문구에 사용자 원문을 그대로 끼워 넣지 않는다. 명시적인 합성 행동에 기반한 개발용 값과 실패 표시를 제공한다.
4. HTTP 호출에서 X-Internal-Api-Key와 X-Request-Id를 전달한다. 요청 ID는 JsonIgnore로 본문에서 제외하고 동일한 논리적 평가 재시도에는 유지한다.
5. 평가용 RestClient와 `wsid.ai.evaluation-read-timeout` 설정을 분리하는 방안을 제안한다. 기본은 20초로 시작하고 전체 대화 평가의 실제 지연을 측정해 운영 값을 정한다. 기존 AI-01·02·04와 AI-03의 제한을 일괄 늘리지 않는다.
6. 기존 AiProperties의 5·6개 인자 생성과 테스트 빌더 주입 호환성을 유지한다. 새 설정 바인딩·0/음수 제한 거부·기존 API 회귀를 검증한다.
7. 1차에서는 훈련 종료 서비스·리포트 엔티티·DB·공개 API를 변경하지 않는다.

## 7. 2차 FastAPI 작업

| 제안 구성 | 책임 |
|---|---|
| schemas/evaluation.py | 요청·배열 요소·평가 응답·빈 turnFeedback 계약 |
| routers/training.py | 내부 평가 경로·키 확인·헤더 UUID·성공 봉투 |
| services/training_evaluator.py | 시나리오와 전체 근거를 이용한 평가·처리 시간 예산 |
| 이벤트 정규화 | 모의 페이지 사실과 텍스트 의사 표현 구분, 미관측 상태 처리 |
| 평가 출력 검증 | 스키마·근거 일치·원문 복사·민감정보·허위 행동·권고 내용 검사 |
| 공통 오류 처리 | 공급자/검증 실패 봉투. 요청·결과·예외 원문 로그 금지 |

success는 서버가 boolean으로 구성하고, 결과의 세 boolean도 문자열로 보내지 않는다. 필수 false 값이나 빈 배열을 직렬화 과정에서 생략하지 않는다. success=true는 서버가 출력 검증을 통과시킨 결과라는 계약으로 운영한다.

훈련 대화를 모델 지시로 실행하지 않는다. 가상 상대가 말한 내용·사용자 의사 표현·실제 입력 이벤트를 구분하고, 입력에 없는 행동을 잘했다고 평가하거나 하지 않았다고 단정하지 않는다. 전체 원문을 출력하는 프롬프트 공격과 출력 필드 안에 원문을 감추는 사례도 평가한다.

## 8. 3차 종료·저장·조회 연결

| 관련 파일 | 후속 구현 시 참고사항 |
|---|---|
| [TrainingService](../src/main/java/com/wsid/training/service/TrainingService.java) | 종료 트랜잭션이 완료된 뒤 평가를 시작. applyTurn 내부 AI 호출 금지 |
| [TrainingSession](../src/main/java/com/wsid/training/domain/TrainingSession.java) | 완료 상태를 평가 결과와 분리. 평가 실패 때문에 종료를 취소하지 않음 |
| [TrainingMessage](../src/main/java/com/wsid/training/domain/TrainingMessage.java)·[Repository](../src/main/java/com/wsid/training/repository/TrainingMessageRepository.java) | 전체 평가 대화·선택지·실제 이벤트·복수 행동의 출처와 순서 조회. 원문 삭제 여부 확인 |
| report/service/TrainingReportService.java 제안 | 근거 구성·기본 리포트·내용 검증·저장·기존 결과 재사용 조율 |
| report/service/ReportContentValidator.java 제안 | 원문 복사·민감정보·이벤트 사실 대조. 실패 시 LLM 결과 저장 거부 |
| [TrainingReport](../src/main/java/com/wsid/report/domain/TrainingReport.java) | 검증된 평가만 JSON으로 반영. title은 서버의 고정/검토 문구로 생성하고 200자 제한 준수 |
| [TrainingReportRepository](../src/main/java/com/wsid/report/repository/TrainingReportRepository.java) | 기존 리포트 조회·중복 생성 처리. trainingId 유니크 활용 |
| report/controller 및 응답 DTO 제안 | 소유권 확인, 평가 출처·제한/실패 상태 표현, 조회와 생성 책임 분리 |
| [GlobalExceptionHandler](../src/main/java/com/wsid/common/error/GlobalExceptionHandler.java) | 리포트 생성 충돌은 서비스에서 식별. 모든 무결성 오류를 중복 메시지 오류로 감추지 않음 |
| RPT 저장·PDF 구현 | 저장된 report_json만 사용. 원문 삭제 후에도 조회/PDF 동작 |

### 트랜잭션과 생성 수명

```text
종료 트랜잭션: 마지막 입력·행동·종료 안내·REPORT/COMPLETED 저장
  → 트랜잭션 종료
평가 입력 읽기: 완료 상태·소유권·기존 리포트·기록 상태 확인
  → 트랜잭션 밖 AI-05 호출 또는 기본 리포트 선택
내용·이벤트 사실 검증
  → 짧은 저장 트랜잭션에서 완료 상태·기존 결과·원문 삭제/기록 변경 여부 재확인
  → 검증된 평가·출처·범위 메타데이터 저장
```

초기 연결은 종료 커밋 후 동기 평가를 제안한다. 이때 응답 시간이 길어지는 점과 기존 종료 응답에 생성 상태를 어떻게 표현할지 확정한다. 비동기로 바꾸려면 내구성 있는 작업 상태·재시도·복구 설계를 별도로 추가하며, 메모리 이벤트만으로 생성이 보장된다고 보지 않는다.

프로세스가 종료 커밋 직후 중단되면 리포트가 없을 수 있다. 별도 생성/재시도 POST 또는 복구 작업을 계약으로 정한다. GET 조회가 매번 LLM 생성을 재실행하지 않게 하고, 첫 생성·생성 중·실패·기본 리포트·완료 상태를 프론트가 구분하도록 한다. 현재 상태 필드는 없으므로 저장 위치·스키마 변경은 이 단계에서 확정한다.

API 전체 명세의 RPT-01은 미생성 조회에 REPORT_NOT_FOUND를 정의한다. 기본 조회 계약을 유지하고, 생성 중·실패 상태를 노출하는 방식이나 별도 생성 POST는 새로 합의한다. ErrorCode·공개 응답 DTO·프론트 분기를 함께 수정하며 명세에 없는 상태 필드를 몰래 추가하지 않는다.

일반 훈련 메시지용 load/requireActive 검사를 재사용해 완료 세션의 리포트 생성을 거부하지 않는다. 리포트 전용 서비스에서 소유자와 완료 상태를 확인한다. 이미 생성된 리포트의 조회는 ACTIVE·무활동 만료 여부가 아니라 소유권으로 보호한다.

### 동시 생성과 기본 리포트

- 생성 전 기존 리포트를 조회하고 이미 있으면 반환한다. 조회만으로 동시 생성이 방지되는 것은 아니다.
- AI 호출 뒤 짧은 저장 트랜잭션에서 기존 결과를 재확인하고 trainingId 유니크 제약으로 최종 1개를 보장한다. 필요하면 부모 세션 행 잠금을 이 짧은 구간에만 사용한다.
- 유니크 충돌로 롤백되었다면 트랜잭션 밖에서 승자의 리포트를 조회한다. 다른 DB 오류를 동일하게 처리하지 않는다.
- 요청 ID는 추적용이다. 같은 훈련의 동시 LLM 호출 비용까지 막으려면 생성 작업/잠금 설계가 추가로 필요하다.
- AI 장애·불완전 근거·검증 실패 시 확인 가능한 기록으로 기본 리포트를 구성하고 AI 평가가 아님을 표시한다. 미기록 행동을 수행/미수행으로 단정하지 않는다.
- 기본 리포트도 저장되면 훈련당 1개 제약의 대상이다. 이후 AI 재평가로 덮어쓸지 여부는 별도 계약으로 정하고, 사용자가 저장한 리포트를 조용히 교체하지 않는다.

### 저장 내용·소유권·삭제

report_json에는 평가 DTO와 출처·기록 범위·스키마 버전 같은 메타데이터만 넣는 방안을 제안한다. 전체 요청·대화 원문·모델 분석 근거를 함께 보관하지 않는다. DTO 구조와 저장용 래퍼는 구분해 정의한다.

리포트 자체에는 user_id가 없으므로 training_sessions.user_id로 소유권을 확인한다. 다른 사용자의 리포트는 404로 응답한다. 마이페이지 저장은 saved_at을 한 번만 설정하고, 원문 삭제는 검증된 리포트 저장과 안전하게 연결한다. 원문이 삭제된 뒤 재평가에 필요한 대화를 복원하려고 report_json에 숨겨 저장하지 않는다. 기존 평가·PDF를 사용하거나 재평가 불가를 안내한다.

## 9. 테스트 계획

### 1차: DB 없는 단위·HTTP·설정 테스트

| 테스트 파일 제안 | 확인할 사례 |
|---|---|
| TrainingEvaluateRequestTest | 최소 입력·대화만/행동만·양쪽 빈 값 거부·시나리오·역할·null 요소·길이/개수 경계·방어적 복사·요청 ID·원문 비노출 |
| TrainingEvaluateResultTest | 필수 문자열/배열·배열 항목·빈 배열·최대 길이·초과·빈 turnFeedback 제한·모의 페이지 객체/boolean·불변 목록 |
| FakeAiClientEvaluationTest | 결정성·개발용 실제 이벤트 fixture·원문 재출력 금지·실패 흐름·빈 근거 입력 처리 |
| HttpAiClientEvaluationTest | 정확한 경로·메서드·내부 키·UUID 헤더·JSON 본문에서 ID 제외·정상 data 추출·추가 필드 미사용 |
| 타입·오류 계약 | 문자열 success/boolean, 숫자 피드백, 비배열/비문자열 요소, 필수 필드 누락, null·HTML·잘못된 JSON·HTTP 오류·시간 초과 거부 |
| 평가 전송 설정 | 별도 응답 제한 바인딩·기본값·0/음수 거부·실제 localhost HTTP 지연·기존 AI-03/04 제한 유지 |
| 회귀 | 기존 AI-01·02·03·04 및 옛 훈련 호출·기본 테스트 유지 |

MockRestServiceServer로 계약을 확인하고 localhost HTTP 서버로 실제 전송·요청 헤더·시간 제한을 별도 검증한다. 잘못된 요청의 IllegalArgumentException과 원격 실패의 AiUnavailableException을 구분해 검사한다. 오류의 cause·message·toString에서 합성 원문이 노출되지 않는지 확인한다.

### 2차: FastAPI·내용 평가

| 범위 | 기대 결과 |
|---|---|
| 내부 키·요청 ID | 인증 실패 거부, 같은 요청 ID로 추적, ID를 본문에서 찾지 않음 |
| 직렬화 | false boolean·빈 배열 필드 유지, 필드 타입과 초기 turnFeedback 범위 일치 |
| 원문 복사 공격 | 전체 대화·고유한 긴 원문 표식이 평가 문자열에 복사되면 실패/채택 거부. 짧은 일반 표현과 구분 |
| 허위 행동 평가 | MOCK_PAGE_OPEN만 있는 완전 기록에서 공식 확인=true를 생성하지 않음 |
| 기록 미관측 | 로그 수집 미연결과 행동 미수행을 구분, 과도한 단정 평가 방지 |
| 근거·가상 설정 | 상대의 사기성 대사나 가상 설정을 사용자의 실제 행동으로 평가하지 않음 |
| 민감정보·권고 | 연락처·계좌·유도 링크·원문·구체적 사기 수법이 리포트에 나오지 않음 |
| 공급자 실패 | 종료 상태와 무관하게 명시적 실패 봉투, 원문 로그 없음 |

### 3차: 서비스·PostgreSQL·공개 API

| 범위 | 기대 결과 |
|---|---|
| 종료 경계 | 종료 커밋을 마친 뒤 AI 호출. 호출 중 트랜잭션 없음. AI 실패해도 훈련 종료 유지 |
| 평가 대상 | DISCOVERY에서만 종료한 훈련은 미평가 안내. 대화·화면 기록이 있는 훈련은 해당 범위만 평가 |
| 전체 대화 | 20개 초과 이력도 누락 없이 전달. 40개/1,000자 초과 과거 데이터는 임의 절삭 대신 제한된 기본 리포트 |
| 실제 행동 | 선택지·텍스트 분석·화면 이벤트의 출처와 순서 유지. 미확정 코드·미관측 기록 처리 |
| 이벤트 사실 대조 | 응답 boolean이 DB 이벤트와 다르면 AI 결과 저장 거부·기본 리포트 |
| 원문 비보관 | report_json의 모든 문자열과 추가 필드에서 긴 합성 원문 표식·전체 대화가 저장되지 않음 |
| 생성 중복 | 연속 재요청·동시 생성에도 리포트 1개. 승자 결과 반환. 무관한 DB 오류는 숨기지 않음 |
| 실패·복구 | 종료 직후 프로세스 중단·AI 장애·저장 실패를 구분하고 재시도 경로 제공. 기본 리포트 출처/제한 표시 |
| 소유권 | 타인·없는 훈련/리포트는 404, 미로그인 거부. 완료·만료 뒤에도 본인 저장 리포트 조회 가능 |
| 보관·삭제 | save 반복 시 saved_at 유지, 원문 삭제 후에도 결과 조회·PDF 가능, 재전송 사본·메타데이터의 원문 잔존 방지 |
| Mockito 전환 | 리포트 서비스를 테스트하는 경우 when(ai.evaluateTraining(any())) 응답 설정. 미설정 null을 AI 장애 테스트와 혼동하지 않음 |

기본 검증은 `./gradlew test`, DB 단계는 별도 PostgreSQL 테스트 DB에서 `./gradlew dbTest`로 실행한다. 실 공급자·실 사용자 원문을 단위 테스트에 사용하지 않는다. 실제 LLM 내용 평가는 합성 평가 세트와 검토자의 확인을 별도 증거로 남긴다.

## 10. 결정 항목과 완료 기준

| 결정 | 권장안/확정할 내용 |
|---|---|
| 배열 요소·수치 | 문자열 배열, 각 10개·500자, 전체 피드백 1,000자 제안값 확정 |
| turnFeedback | 초기 필드 유지·빈 배열만 지원. 재도입 시 기존 정책과 화면·PDF·요소 스키마 함께 변경 |
| 대화 범위 | 평가 대상 전체 SIMULATION 대화. 40개·1,000자 제한과 과거 데이터 기본 리포트 정책 확정 |
| 행동·선택지 | 실제 이벤트 코드·출처·선택지/장면 연결·복수 행동 스키마를 TRN-10·AI-04와 정렬 |
| 모의 페이지 사실 | 완전 기록과 미관측 구분, Spring의 DB 대조와 저장 메타데이터 정의 |
| 원문 복사 방지 | 필드 제한 외에 긴 원문·전체 대화·민감정보 검증과 일반 문구 오탐 기준 확정 |
| 시간 제한 | 평가용 별도 클라이언트·설정 기본 20초 제안, 운영 지연과 프론트/프록시 제한 정렬 |
| 생성 수명 | 종료 후 동기 호출 제안. 상태 저장·복구/재시도 POST 또는 작업 방식·실패 응답 합의 |
| 저장 스키마 | 평가 결과+출처·범위·버전, title은 서버 생성. 필요 시 새 마이그레이션 |
| 기본 리포트 | 내용·표시·재평가 허용 여부와 저장된 결과 교체 정책 확정 |

**1차 완료:** DTO·fake/HTTP·설정·계약 테스트가 통과하며 운영 서비스는 미연결임을 문서에 표시한다.

**전체 완료:** 실제 FastAPI·LLM의 근거/원문 보호 검증, 종료 후 생성·DB 저장·조회·중복/복구·원문 삭제 이후 이용까지 확인한다. 이후 저장·PDF 개발은 저장된 평가만 사용한다.

이번 작업은 계획 문서 작성이다. AI-05 구현·테스트 실행·DB 변경은 하지 않았다.
