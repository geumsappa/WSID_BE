# AI-05 구현 보고서와 연동 개발 안내

작성일: 2026-11-16 · 상태: Spring 1차 클라이언트 구현 완료

명세 출처는 **AI-05 내부 API 명세 초안(2026-11-16 제공본)**이다. 구현 범위는 [AI-05 구현·테스트 계획서](ai-05-evaluation-plan.md)의 1차다. 실제 FastAPI 스키마·운영 오류 정책·LLM 평가 품질·리포트 생성/저장은 아직 구현하지 않았다.

## 1. 구현 결과와 적용 범위

`POST /internal/ai/training/evaluate`에 시나리오·평가 대상 대화·행동 코드를 보내고, 리포트에 넣을 평가 결과를 받는 Spring 클라이언트를 구현했다.

| 항목 | 결과 |
|---|---|
| 새 호출 메서드 | `AiClient.evaluateTraining(TrainingEvaluateRequest)` |
| 요청 DTO | 시나리오·대화(최대 40개)·행동 코드(최대 100개)·요청 ID. null 요소 거부, 평가 근거가 하나도 없으면 거부, 목록 방어적 복사 |
| 응답 DTO | overallFeedback·strengths·improvements·recommendations(문자열 배열)·turnFeedback(빈 배열만)·mockPageEvaluation(boolean 3개) |
| fake | 명시적 `MOCK_PAGE_OPEN` 코드만 페이지 열기로 표시하는 결정적 결과, `[AI_FAIL]` 실패 표시 |
| HTTP | 새 경로·내부 키·`X-Request-Id`·JSON 전송, JsonNode 단계의 엄격한 타입 검사 |
| 실패 | `AiUnavailableException`으로 변환. 응답 본문·cause 비보관, 자동 재시도 없음 |
| 시간 제한 | 평가 전용 `evaluationRestClient`와 `wsid.ai.evaluation-read-timeout`(기본 20초) |
| 테스트 | 신규 127개, 기존 포함 319개 통과 |
| 기존 기능 | AI-01~04 메서드·경로·시간 제한·생성자 호출 방식 유지 |

**운영 코드에서 `evaluateTraining()`을 호출하는 곳은 아직 없다.** 호출자는 이번에 추가한 테스트뿐이다. `TrainingService`의 리포트 TODO, `report` 패키지, DB 마이그레이션, 공개 RPT API는 변경하지 않았다. 따라서 "훈련이 끝나면 리포트가 생성된다"는 상태가 아니다.

## 2. 변경한 파일과 호출 구조

| 파일 | 내용 | 설계 이유 |
|---|---|---|
| [EvaluationAction](../src/main/java/com/wsid/ai/dto/EvaluationAction.java) | 행동 코드 1개. `[A-Z0-9_]{1,100}` | 출처·장면 연결은 미합의라 최소 형식만 정의. 엔티티 직렬화 금지 |
| [TrainingEvaluateRequest](../src/main/java/com/wsid/ai/dto/TrainingEvaluateRequest.java) | scenario·conversation·actions·requestId | 요청 전 입력 오류 발견, 불변 목록 보관, toString에 원문 없음 |
| [TrainingEvaluateResult](../src/main/java/com/wsid/ai/dto/TrainingEvaluateResult.java) | 평가 결과와 `MockPageEvaluation` | 닫힌 필드만 정의해 원문 필드가 들어올 자리를 만들지 않음 |
| [AiClient](../src/main/java/com/wsid/ai/AiClient.java) | `evaluateTraining` 추가 | 리포트 서비스가 HTTP 구현에 직접 의존하지 않게 함 |
| [FakeAiClient](../src/main/java/com/wsid/ai/FakeAiClient.java) | 개발용 평가 결과 | FastAPI 없이 3차 서비스·화면 개발 가능 |
| [HttpAiClient](../src/main/java/com/wsid/ai/HttpAiClient.java) | 평가 경로·전용 RestClient·4개 인자 생성자·타입 검사 | 긴 평가 지연이 훈련 턴 제한에 영향을 주지 않게 분리 |
| [AiProperties](../src/main/java/com/wsid/ai/AiProperties.java) | `evaluationReadTimeout` 추가, 5·6개 인자 생성자 유지 | 값이 없으면 readTimeout 사용. 0·음수 거부 |
| [application.yml](../src/main/resources/application.yml) | `evaluation-read-timeout: ${AI_EVALUATION_READ_TIMEOUT:20s}` | 환경 변수로 AI-05만 조정 |

AI-04의 [SimulationMessage](../src/main/java/com/wsid/ai/dto/SimulationMessage.java)와 AI-03의 [ScenarioGenerateResult.Scenario](../src/main/java/com/wsid/ai/dto/ScenarioGenerateResult.java)는 새로 만들지 않고 재사용했다. 역할(user/assistant)·1,000자 규칙, 제목 100자·설명 1,000자 규칙이 같기 때문이다. 이 두 값 타입의 규칙을 바꾸면 AI-03·04·05가 함께 영향을 받는다.

```text
(3차) 리포트 서비스 → TrainingEvaluateRequest 생성
                    → AiClient.evaluateTraining(request)
                       ├─ fake: FakeAiClient가 개발용 결과 생성
                       └─ http: HttpAiClient.evaluationRestClient
                                 → POST /internal/ai/training/evaluate
                                 → JsonNode 타입 검사
                                 → TrainingEvaluateResult 생성자 검증
                    ← 결과 DTO 또는 AiUnavailableException
```

결과 DTO를 받았다는 것은 **형식이 맞다**는 뜻일 뿐이다. 원문 복사 여부, 모의 페이지 boolean이 실제 이벤트와 맞는지, 리포트로 저장해도 되는지는 3차 검증 계층이 판단한다.

## 3. 요청 구성과 검증

```http
POST /internal/ai/training/evaluate
Content-Type: application/json
X-Internal-Api-Key: <AI_API_KEY>
X-Request-Id: <평가 요청 UUID>
```

```json
{
  "scenario": {"title": "중고거래 안전 확인 훈련", "description": "가상 거래 상황"},
  "conversation": [
    {"role": "assistant", "content": "안내를 어떻게 확인하시겠어요?"},
    {"role": "user", "content": "안내의 내용을 확인해 보겠습니다."}
  ],
  "actions": [{"actionCode": "MOCK_PAGE_OPEN"}]
}
```

| 검사 | 위반 시 |
|---|---|
| scenario·requestId null | `IllegalArgumentException` |
| conversation·actions null, 40개/100개 초과 | `IllegalArgumentException` |
| 목록 안의 null 요소 | `IllegalArgumentException` (List.copyOf의 NPE가 나가지 않도록 먼저 검사) |
| conversation·actions 모두 빈 목록 | `IllegalArgumentException("평가할 기록이 없어요.")` |
| 메시지 역할·공백·1,000자 초과 | `SimulationMessage` 생성 시 `IllegalArgumentException` |
| 행동 코드 형식 | `EvaluationAction` 생성 시 `IllegalArgumentException` |

requestId는 `@JsonIgnore`라 본문에 들어가지 않고 헤더로만 간다. 같은 논리적 평가를 다시 시도할 때는 같은 ID를 재사용한다. 중복 코드와 알 수 없는(형식상 유효한) 코드는 순서대로 그대로 보낸다. 의미 해석은 3차 서비스가 한다.

### DTO가 하지 않는 일

- 어떤 메시지를 평가 대상으로 고를지 결정하지 않는다. 40개 초과·과거 긴 대사를 **잘라내지도 않는다.** 넘으면 예외가 나고, 처리는 호출자 몫이다.
- 행동 코드가 실제 화면 이벤트인지 AI 텍스트 분석인지 구분하지 않는다.
- 원문 복사·민감정보를 검사하지 않는다.

## 4. 응답 처리와 타입 검사 순서

```json
{
  "success": true,
  "data": {
    "overallFeedback": "훈련 기록을 바탕으로 확인 방법을 돌아보세요.",
    "strengths": [], "improvements": [], "turnFeedback": [],
    "mockPageEvaluation": {"openedPage": true, "attemptedMockLogin": false, "checkedOfficialChannel": false},
    "recommendations": []
  }
}
```

1. HTTP 4xx·5xx면 `RestClientException` → 사용 불가.
2. 응답이 객체인지, `success`가 **실제 boolean true**인지, `data`가 객체인지 확인한다. HTTP 200이어도 `success:false`면 거부한다.
3. `overallFeedback`이 문자열인지, 세 배열이 배열이고 요소가 모두 문자열인지 확인한다.
4. `turnFeedback`이 배열이고 비어 있는지 확인한다.
5. `mockPageEvaluation`이 객체이고 세 필드가 **모두 존재하며 boolean**인지 확인한다.
6. `treeToValue`로 변환하면 DTO 생성자가 길이·개수·공백을 검사한다.

2~5단계를 먼저 하는 이유는 Jackson 기본 설정이 `"true"` 문자열을 boolean으로, 숫자를 String으로 자동 변환하기 때문이다. 또 `MockPageEvaluation`은 primitive boolean이라 필드가 빠지면 Jackson이 false로 채운다. JsonNode 검사가 없으면 "모의 로그인 시도 안 함"이 근거 없이 만들어진다.

| 값 | 결과 제한 |
|---|---|
| overallFeedback | 공백 불가, 1,000자 이하 |
| strengths·improvements·recommendations | 각 10개 이하, 항목당 공백 불가·500자 이하 |
| turnFeedback | 빈 배열만 (턴별 피드백 제외 정책 유지) |
| 알 수 없는 추가 필드 | 무시. 결과 DTO에 남지 않음 |

## 5. 실패·로그·개발용 fake

`RestClientException`, `JacksonException`, `IllegalArgumentException`은 모두 **cause 없이** `AiUnavailableException("AI-05 호출 또는 응답 검증에 실패했어요.")`로 바꾼다. 원인 예외에는 응답 본문(대화·평가 문구·오류 메시지)이 들어 있을 수 있기 때문이다. 요청이 null이면 HTTP를 호출하지 않고 `IllegalArgumentException`을 그대로 던진다. 호출자는 이 둘을 구분해 처리한다.

- `IllegalArgumentException`: 우리 쪽 입력 구성 문제다. 3차에서는 기본 리포트로 전환하고 원인을 "평가 범위 제한"으로 기록한다.
- `AiUnavailableException`: 원격 실패다. 종료 상태는 유지하고 기본 리포트로 전환한다.

### FakeAiClient.evaluateTraining

- 대화에 `[AI_FAIL]`이 있으면 `AiUnavailableException`을 던진다.
- actions에 `MOCK_PAGE_OPEN`이 있을 때만 `openedPage=true`다. 나머지 두 값은 항상 false다.
- 고정 문구만 돌려주고 사용자 원문을 끼워 넣지 않는다.

fake 결과는 화면·서비스 개발용이다. LLM 평가 정확도나 근거 검증을 뜻하지 않는다. fake의 `false`를 "행동하지 않았다"는 근거로 쓰면 안 된다.

## 6. 현재 직접 상호작용하는 파일

| 파일 | 상호작용 | 이후 개발 시 참고할 점 |
|---|---|---|
| [AiClient](../src/main/java/com/wsid/ai/AiClient.java) | 호출자와 구현 사이의 인터페이스 | 새 구현체·테스트 스텁도 `evaluateTraining`을 구현해야 컴파일됨 |
| [AiClientConfig](../src/main/java/com/wsid/ai/AiClientConfig.java) | mode에 따라 fake/HTTP 선택 | 변경 없음. 평가용 빈을 따로 만들지 않음 |
| [AiProperties](../src/main/java/com/wsid/ai/AiProperties.java)·[application.yml](../src/main/resources/application.yml) | 평가용 시간 제한 제공 | AI-01·02·04는 readTimeout, AI-03은 scenarioReadTimeout, AI-05는 evaluationReadTimeout. 일괄 변경 금지 |
| [TrainingEvaluateRequest](../src/main/java/com/wsid/ai/dto/TrainingEvaluateRequest.java)·[EvaluationAction](../src/main/java/com/wsid/ai/dto/EvaluationAction.java) | FastAPI 요청 스키마와 직접 대응 | scenario·conversation·actions만 본문에 전달. 40개 대화·100개 행동과 코드 형식을 FastAPI 스키마에도 맞춤 |
| [TrainingEvaluateResult](../src/main/java/com/wsid/ai/dto/TrainingEvaluateResult.java) | FastAPI 응답 data와 직접 대응 | 문자열 배열·빈 turnFeedback·모의 페이지 boolean 3개를 기대. FastAPI 응답을 바꾸면 DTO·HTTP 검사·계약 테스트를 함께 변경 |
| [HttpAiClient](../src/main/java/com/wsid/ai/HttpAiClient.java) | JSON 전송·검사·변환 | 2·3개 인자 생성자는 평가에도 기본 빌더를 씀. 평가 제한을 따로 주려면 4개 인자 생성자 사용 |
| [SimulationMessage](../src/main/java/com/wsid/ai/dto/SimulationMessage.java) | 대화 요소 | AI-04와 공유. 길이·역할 규칙 변경 시 두 API 동시 영향 |
| [ScenarioGenerateResult.Scenario](../src/main/java/com/wsid/ai/dto/ScenarioGenerateResult.java) | 시나리오 값 | AI-03·04·05 공유 |
| [AiUnavailableException](../src/main/java/com/wsid/ai/AiUnavailableException.java) | 실패 통로 | cause 없는 예외 정책 유지 |
| [DiscoveryDatabaseTest](../src/test/java/com/wsid/training/DiscoveryDatabaseTest.java) | `@MockitoBean AiClient` 사용 | 미설정 시 `evaluateTraining`은 null 반환. 3차에서 리포트 생성을 연결하면 `when(ai.evaluateTraining(any()))`를 설정해야 하며, null을 AI 장애로 오해하지 않게 함 |

## 7. 리포트 연결 시 파일별 구현 방법 (3차)

아래는 아직 구현하지 않은 부분이다. 현재 파일 상태와, 나중에 어떻게 구현해야 하는지를 적었다.

### 7.1 기존 파일

| 파일 | 현재 상태 | 나중에 구현할 내용 |
|---|---|---|
| [TrainingService](../src/main/java/com/wsid/training/service/TrainingService.java) | `applyTurn`(228행)에 리포트 TODO. `applyTurn`은 `TransactionTemplate` 안에서 실행 | **여기서 AI-05를 호출하지 않는다.** 트랜잭션이 끝난 뒤(`transaction.execute(...)`가 반환된 뒤) `outcome.finished()`이면 `TrainingReportService.createAfterFinish(sessionId, userId)`를 부른다. 평가 실패가 종료 응답을 실패시키지 않게 예외를 막는다 |
| [TrainingSession](../src/main/java/com/wsid/training/domain/TrainingSession.java) | `finish()`가 REPORT·COMPLETED·completedAt 설정. 시나리오 제목·설명은 저장하지 않음 | 평가 결과 때문에 상태를 되돌리지 않는다. 시나리오 저장(TRN-06·07)이 생기기 전에는 평가 요청의 scenario를 fraudTypeCode로 검토된 콘텐츠에서 가져와야 한다 |
| [TrainingMessage](../src/main/java/com/wsid/training/domain/TrainingMessage.java) | sender·phase·content·actionCode·actionMetaJson·turnEvaluationJson | 대화는 `phase=SIMULATION` + `sender in (USER, AI)`만 고른다. 종료 안내는 SYSTEM이며 REPORT 단계로 저장되므로 자동 제외된다. **현재 `actionCode`는 AI가 사용자 글을 분류한 값(UserAction)이지 실제 화면 이벤트가 아니다.** 이것을 그대로 `EvaluationAction`으로 보내면 안 된다 |
| [TrainingMessageRepository](../src/main/java/com/wsid/training/repository/TrainingMessageRepository.java) | `findByTrainingIdAndPhaseOrderBySequenceNoAsc` 있음 | 이 메서드로 SIMULATION 메시지를 시간순으로 읽고 서비스에서 SYSTEM을 거른다. 모의 페이지 이벤트 저장소가 생기면 이벤트 조회를 별도로 추가 |
| [TrainingSessionRepository](../src/main/java/com/wsid/training/repository/TrainingSessionRepository.java) | `findByIdAndUserId` | 리포트 서비스의 소유권 확인에 사용. 저장 직전 재확인에도 사용 |
| `TrainingService.requireCurrent` | COMPLETED면 `SESSION_FINISHED`, 만료면 `SESSION_EXPIRED` | **리포트 생성·조회에 재사용하지 않는다.** 완료된 훈련의 리포트 생성을 막아 버린다. 리포트 서비스는 소유권과 COMPLETED 여부만 확인 |
| [TrainingReport](../src/main/java/com/wsid/report/domain/TrainingReport.java) | `create(trainingId, title, reportJson, now)`, `saveToMyReports` | title은 서버 고정 문구(예: 유형별 검토 문구)로 200자 이내. reportJson에는 7.4의 래퍼만 넣는다 |
| [TrainingReportRepository](../src/main/java/com/wsid/report/repository/TrainingReportRepository.java) | `findByTrainingId`, RPT-002 TODO | 생성 전 재사용 확인과 동시 생성 후 승자 조회에 사용 |
| [V2 제약](../src/main/resources/db/migration/V2__add_constraints.sql) | `uq_training_reports_training` | 훈련당 리포트 1개를 DB가 최종 보장. 생성 상태 컬럼이 필요하면 새 마이그레이션(V3 이후)으로 추가 |
| [ErrorCode](../src/main/java/com/wsid/common/error/ErrorCode.java) | 리포트·AI 관련 코드 없음 | 조회 API를 만들 때 `REPORT_NOT_FOUND(404)`를 추가. 생성 중·실패 상태를 노출할지는 프론트와 합의 후 추가 |
| [GlobalExceptionHandler](../src/main/java/com/wsid/common/error/GlobalExceptionHandler.java) | 메시지 제약 2개만 변환, 나머지 무결성 오류는 다시 던짐 | 리포트 유니크 충돌은 여기서 처리하지 않고 서비스에서 잡아 승자 리포트를 반환. 다른 무결성 오류를 중복 오류로 감추지 않음 |
| [report/README](../src/main/java/com/wsid/report/README.md) | SIM-010 생성 순서 설명 | "action_code를 읽어 AI-05 호출" 문장은 위 주의점(텍스트 분석과 실제 이벤트 구분)에 맞게 연결 시 고친다 |

### 7.2 새로 만들 파일

| 제안 파일 | 책임 |
|---|---|
| `report/service/TrainingReportService.java` | 평가 입력 구성 → AI 호출 또는 기본 리포트 선택 → 검증 → 저장. 트랜잭션 경계 조율 |
| `report/service/EvaluationInputBuilder.java` | DB 기록을 `TrainingEvaluateRequest`로 변환. 한도 초과·기록 누락이면 "AI 호출 불가 + 사유"를 반환 |
| `report/service/ReportContentValidator.java` | 원문 복사(긴 구절 일치)·민감정보·모의 페이지 boolean과 실제 이벤트 대조. 실패 시 AI 결과 채택 거부 |
| `report/service/FallbackReportFactory.java` | 확인 가능한 기록만으로 기본 리포트 생성. 미관측 행동을 수행/미수행으로 단정하지 않음 |
| `report/dto/StoredReport.java` | report_json 래퍼(7.4) |
| `report/controller/ReportController.java`, `report/dto/ReportResponse.java` | RPT-01 조회. 소유권 404, 미생성 REPORT_NOT_FOUND |

### 7.3 생성 흐름 예시

아래는 흐름을 설명하는 **의사 코드**다. `TrainingReportService`·`EvaluationInputBuilder` 등은 아직 없는 클래스이므로 그대로 복사해 컴파일하지 않는다. 그래서 IDE가 Java로 검사하지 않도록 `text` 블록으로 적었다.

```text
// TrainingService: 종료 트랜잭션이 커밋된 뒤 (applyTurn 안이 아님)
response = transaction.execute(status -> applyTurn(...))
if response.finished:
    try:
        reportService.createAfterFinish(sessionId, userId)
    catch RuntimeException:
        // 훈련은 이미 종료·커밋됐다. 리포트 실패로 종료 응답을 실패시키지 않는다.
        // 원문 없이 sessionId만 로그에 남기고, 리포트는 "없음" 상태로 둔다 → 재생성 POST/복구 작업이 처리
        log.warn("리포트 생성 실패 sessionId={}", sessionId)
return response   // 프론트는 RPT-01이 REPORT_NOT_FOUND면 재생성 경로로 안내
```

```text
// TrainingReportService.createAfterFinish (메서드 자체에는 트랜잭션 없음)
// 반환: CREATED | EXISTING | NOT_EVALUABLE.  DB 장애 등은 예외로 올려 호출자가 위처럼 처리

1) 짧은 읽기 트랜잭션
   input = inputBuilder.build(trainingId, userId)   // 소유권·COMPLETED 확인
   if input.existingReport: return EXISTING
   if input.noTrainingRecord: return NOT_EVALUABLE   // DISCOVERY에서만 종료

2) 트랜잭션 밖에서 평가 결과 결정
   if not input.evaluable:                    // 40개·1,000자 초과, 기록 누락
       report = fallback.create(input, input.limitReason)
   else:
       try:
           result = aiClient.evaluateTraining(input.request)
           accepted = validator.accept(result, input)   // 원문 복사·이벤트 대조
           report = accepted ? StoredReport.ai(result, input.recordStatus)
                             : fallback.create(input, "VALIDATION_REJECTED")
       catch AiUnavailableException:
           report = fallback.create(input, "AI_UNAVAILABLE")
       catch IllegalArgumentException:          // 입력 구성 버그. 사용자 실패로 보내지 않음
           report = fallback.create(input, "INPUT_REJECTED")

3) 짧은 저장 트랜잭션
   try:
       transaction: 세션 상태 재확인 → findByTrainingId 재확인 → 없으면 save(report)
       return CREATED
   catch DataIntegrityViolationException e:
       if e가 uq_training_reports_training 충돌:
           return EXISTING                      // 다른 요청이 먼저 저장. 승자 결과를 사용
       throw e                                  // 다른 무결성 오류는 숨기지 않음 → 호출자가 로그·복구
```

실패별 결과는 다음과 같다.

| 상황 | 저장되는 것 | 훈련 종료 응답 |
|---|---|---|
| AI 성공 + 검증 통과 | AI 리포트(`source=AI`) | 정상 |
| AI 장애·검증 거부·입력 한도 초과 | 기본 리포트(`source=FALLBACK` + 사유) | 정상 |
| 동시 생성 충돌 | 먼저 저장된 1개 | 정상 |
| DISCOVERY에서만 종료 | 없음(평가 대상 아님) | 정상 |
| DB 장애·예상 못 한 예외 | 없음 | 정상. 로그를 남기고 재생성 경로로 복구 |

`inputBuilder.build`가 해야 할 일:

- DISCOVERY에서만 끝난 훈련(SIMULATION 메시지 없음, 이벤트 없음)은 평가 대상이 아니다. AI-05를 부르지 않고 "평가할 훈련 없음"으로 처리한다.
- 메시지 40개 초과·1,000자 초과 과거 대사가 있으면 자르지 않고 `evaluable=false, limitReason="HISTORY_LIMIT"`로 둔다.
- actions에는 **실제로 기록된 화면 이벤트만** 넣는다. 지금은 이벤트 저장소가 없으므로 빈 목록이 맞고, `recordStatus=UNOBSERVED`로 표시한다.
- requestId는 같은 훈련의 재시도에서 재사용할 수 있게 정한다(예: 생성 상태 행에 저장하거나 trainingId 기반 UUID).

### 7.4 report_json 저장 형태 제안

```json
{
  "schemaVersion": 1,
  "source": "AI",
  "recordStatus": "UNOBSERVED",
  "limitReason": null,
  "evaluation": {
    "overallFeedback": "…",
    "strengths": [], "improvements": [], "turnFeedback": [],
    "mockPageEvaluation": {"openedPage": false, "attemptedMockLogin": false, "checkedOfficialChannel": false},
    "recommendations": []
  }
}
```

- `source`: `AI` 또는 `FALLBACK`. 화면에서 "AI 평가가 아님"을 표시하는 근거다.
- `recordStatus`: `COMPLETE`면 이벤트 없음 = false로 확정할 수 있다. `UNOBSERVED`면 화면에서 모의 페이지 결과를 "기록 없음/평가 제한"으로 보여 준다.
- HTTP 봉투·JsonNode·요청 대화·모델 근거는 넣지 않는다. 검증된 `TrainingEvaluateResult`만 `evaluation`에 직렬화한다.
- 원문 삭제 후 재평가를 위해 대화를 여기에 숨겨 두지 않는다.

### 7.5 조회 API

- `GET /api/trainings/{id}/report`: `findByIdAndUserId`로 소유권을 먼저 확인(타인·없는 훈련 404) → `findByTrainingId` → 없으면 `REPORT_NOT_FOUND`.
- 조회가 LLM 생성을 다시 실행하지 않는다. 생성이 비어 있을 때(종료 직후 프로세스 중단 등) 다시 만드는 경로는 별도 POST나 복구 작업으로 정한다.
- 완료·만료된 훈련이라도 본인 리포트는 조회할 수 있어야 한다.

## 8. 테스트 파일과 이후 수정 시 참고사항

| 파일 | 개수 | 실제 검증 | 이후 변경 시 참고 |
|---|---|---|---|
| [TrainingEvaluateRequestTest](../src/test/java/com/wsid/ai/TrainingEvaluateRequestTest.java) | 13 | 대화만/행동만 허용, 둘 다 빈 경우 거부, 40·100개 경계, null 요소, 코드 형식, 순서·중복·미확정 코드 유지, 복사, toString 원문 비노출 | 한도·코드 형식·확장 필드(source 등) 추가 시 경계 수정 |
| [TrainingEvaluateResultTest](../src/test/java/com/wsid/ai/TrainingEvaluateResultTest.java) | 13 | 빈 배열·1,000/500자·10개 경계, 공백·초과 거부, 비어 있지 않은 turnFeedback 거부, 불변 목록, toString | 배열 요소를 객체로 바꾸거나 turnFeedback을 도입하면 함께 변경 |
| [FakeAiClientEvaluationTest](../src/test/java/com/wsid/ai/FakeAiClientEvaluationTest.java) | 3 | MOCK_PAGE_OPEN 결정성, 대화·미확정 코드로 이벤트를 지어내지 않음, 실패 표시와 cause 없음 | fake를 LLM 정확도 증거로 쓰지 않음 |
| [HttpAiClientEvaluationTest](../src/test/java/com/wsid/ai/HttpAiClientEvaluationTest.java) | 91 | MockRestServiceServer로 경로·헤더·본문, 추가 필드 무시, 문자열 boolean·숫자·객체 요소·필드 누락·success=false·HTML·HTTP 오류·시간 초과 거부, 재시도 시 같은 ID, 생성자별 빌더 | 오류 정책·필수 필드 변경 시 거부 사례 갱신 |
| [HttpAiClientEvaluationTransportTest](../src/test/java/com/wsid/ai/HttpAiClientEvaluationTransportTest.java) | 7 | localhost 목업 FastAPI로 실제 HTTP 왕복(본문 구조·UTF-8·헤더), 200/503/500 실패 봉투, 평가·AI-04 시간 제한 독립, 자동 재시도 없음, 설정 바인딩·기본값·0s 기동 실패, 5·6개 인자 생성자, fake 모드 무호출 | 클라이언트 구성 변경 시 평가가 어느 제한을 쓰는지 재확인 |

## 9. FastAPI 개발 디렉토리의 구현·검증 항목

**이 장은 앞으로 FastAPI를 개발할 때 적용할 계약이다.** 현재는 Spring 클라이언트만 구현했다. FastAPI 코드는 이 저장소에 없으며 아래 경로는 실제 AI 저장소 구조에 맞춰 적용할 제안이다. Python 파일을 이 Spring 저장소에 새로 만드는 지침은 아니다.

### 9.1 개발 디렉토리와 Spring 코드의 연결

```text
<FastAPI 프로젝트 루트>/
  schemas/evaluation.py
  routers/training.py
  services/training_evaluator.py
  services/evaluation_output_validator.py
  core/errors.py
  tests/test_training_evaluate.py
```

| FastAPI 제안 위치 | 직접 상호작용하는 Spring 파일 | 구현할 책임 |
|---|---|---|
| `schemas/evaluation.py` | [TrainingEvaluateRequest](../src/main/java/com/wsid/ai/dto/TrainingEvaluateRequest.java), [SimulationMessage](../src/main/java/com/wsid/ai/dto/SimulationMessage.java), [EvaluationAction](../src/main/java/com/wsid/ai/dto/EvaluationAction.java), [TrainingEvaluateResult](../src/main/java/com/wsid/ai/dto/TrainingEvaluateResult.java) | camelCase 필드·타입·배열 요소·길이·개수 검증. 요청과 응답의 형태를 9.2에 맞춤 |
| `routers/training.py` | [HttpAiClient.evaluateTraining](../src/main/java/com/wsid/ai/HttpAiClient.java) | POST `/internal/ai/training/evaluate`, 내부 키 검증, X-Request-Id UUID 수신, 성공 봉투 생성. ID를 본문에서 찾지 않음 |
| `services/training_evaluator.py` | 요청 DTO와 향후 `EvaluationInputBuilder` | 시나리오·전체 대화·행동을 평가하되 가상 설정·텍스트 의사 표현·실제 이벤트를 구분. DB 저장이나 훈련 종료는 수행하지 않음 |
| `services/evaluation_output_validator.py` | 결과 DTO와 향후 `ReportContentValidator` | 반환 전 원문 복사·민감정보·근거 없는 행동 평가·임의 기관명/연락처/링크 검사. 실패한 결과를 success=true로 내보내지 않음 |
| `core/errors.py` | [AiUnavailableException](../src/main/java/com/wsid/ai/AiUnavailableException.java), HttpAiClient의 실패 처리 | 공급자/검증 실패 봉투 반환. 요청·평가·오류 원문은 로그에 남기지 않음 |
| `tests/test_training_evaluate.py` | [HttpAiClientEvaluationTest](../src/test/java/com/wsid/ai/HttpAiClientEvaluationTest.java), [전송 테스트](../src/test/java/com/wsid/ai/HttpAiClientEvaluationTransportTest.java) | 스키마 객체뿐 아니라 실제 HTTP JSON의 타입·필드 유지·내부 인증·요청 ID·실패 봉투를 검증 |

### 9.2 현재 Spring이 기대하는 응답

| 항목 | FastAPI에서 맞출 규칙 | Spring 확인 위치 |
|---|---|---|
| `success` | 실제 boolean. 성공이면 true. LLM이 봉투를 생성하게 하지 않고 FastAPI가 처리 결과로 설정 | HttpAiClient의 JsonNode 검사 |
| `data` | 객체, 아래 평가 필드 모두 포함 | HttpAiClient의 JsonNode 검사 |
| `overallFeedback` | 공백 아닌 문자열, 1,000자 이하 | 문자열 타입 검사 후 TrainingEvaluateResult 생성자 |
| `strengths`, `improvements`, `recommendations` | 문자열 배열. 각 10개 이하·항목당 공백 불가·500자 이하. 객체 배열이나 null은 현재 미지원 | isStringArray 검사 및 결과 DTO의 checkedItems |
| `turnFeedback` | 필드 필수, 1차에서는 `[]`만 반환 | JsonNode의 배열·빈 값 검사 및 결과 DTO |
| `mockPageEvaluation` | 필드 필수, 객체 | JsonNode 검사 및 중첩 MockPageEvaluation |
| `openedPage`, `attemptedMockLogin`, `checkedOfficialChannel` | 세 필드 모두 필수, 각각 실제 boolean. false여도 생략하지 않음 | 각 필드의 isBoolean 검사 |
| 추가 필드 | Spring은 무시하므로 새 필수 기능을 추가 필드에만 담지 않음 | 닫힌 응답 DTO |

Python에서는 boolean을 `True`·`False`로 구성한다. 문자열 `"true"`·`"false"`나 숫자 0·1로 반환하지 않는다. 평가 항목이 없으면 필드를 지우거나 null로 보내지 말고 빈 배열을 넣는다.

**false 값과 빈 배열을 생략하지 않는다.** 특히 `response_model_exclude_defaults`·`response_model_exclude_unset` 설정으로 기본값이나 미설정 필드가 빠지지 않는지 확인한다. `response_model_exclude_none`은 None 값만 제거하므로 false·빈 배열 생략 옵션과 구분한다. 최종 판단은 실제 반환 JSON에 필수 필드가 존재하는지로 검사한다.

요청 쪽도 Spring과 맞춰야 한다. 본문은 scenario·conversation·actions이며 요청 ID는 헤더에만 있다. conversation은 user/assistant 역할·각 1,000자·최대 40개, actions는 코드 형식 `[A-Z0-9_]{1,100}`·최대 100개다. 두 목록이 모두 비어 있으면 현재 Spring은 요청을 만들지 않는다.

모의 페이지 boolean은 실제 근거에 맞춰 평가해야 한다. MOCK_PAGE_OPEN만으로 공식 채널 확인=true를 만들지 않는다. 기록 수집이 미연결인 세션은 미관측 상태이므로 행동하지 않았다고 단정하지 않는다. 이 구분에 필요한 출처·수집 상태 계약은 3차 연결 전에 함께 확정한다.

### 9.3 실패·시간 제한·계약 변경 시 수정 범위

실패 시 HTTP 상태는 아직 미정이다. Spring은 200 + `success:false`와 4xx·5xx를 모두 사용 불가로 처리한다. 이 둘 중 어느 쪽을 택하느냐만으로 정상 성공 응답이 거부되는 것은 아니다. 공급자 장애는 503·AI_SERVICE_UNAVAILABLE로 통일하는 것을 제안하며 내부 인증·요청 오류 상태도 합의한다.

FastAPI 처리 예산은 평가용 응답 제한(기본 20초) 안에서 반환할 수 있도록 정한다. 운영에서 더 긴 시간이 필요하면 [AiProperties](../src/main/java/com/wsid/ai/AiProperties.java)와 [application.yml](../src/main/resources/application.yml)의 `AI_EVALUATION_READ_TIMEOUT`을 조정하고 프론트·프록시 대기 제한도 맞춘다. 훈련용·시나리오 생성용 제한을 함께 늘리지 않는다.

| FastAPI 계약을 바꾸는 경우 | 함께 확인·변경할 Spring 코드 |
|---|---|
| 평가 배열을 문자열에서 객체로 변경 | TrainingEvaluateResult, HttpAiClient의 배열 요소 검사, 결과 DTO/HTTP 계약 테스트 |
| 비어 있지 않은 turnFeedback 도입 | 결과 DTO·HTTP 검사·턴별 피드백 제외 정책. 3차 공개 리포트·화면·PDF 계약도 함께 확정 |
| 모의 페이지 필드를 optional/null로 변경 | HttpAiClient의 필수 boolean 검사와 MockPageEvaluation 타입. 미관측 상태·기본값 의미를 먼저 합의 |
| 필드 이름·봉투·필수 여부 변경 | 요청/결과 DTO, HttpAiClient, 직렬화·HTTP 계약 테스트 |
| 대화 역할·길이 변경 | 공유 SimulationMessage와 AI-04/05 테스트. AI-04에도 영향이 있으므로 평가만 보고 변경하지 않음 |
| 시간 제한 변경 | AiProperties·application.yml·실제 HTTP 전송 테스트 및 다른 AI API 제한 회귀 |

### 9.4 FastAPI 개발 완료 후 확인 순서

1. 9.2 계약을 스키마·라우터·출력 검증 코드에 반영한다.
2. false boolean·빈 배열·필수 필드가 실제 HTTP JSON에 남는지 FastAPI 테스트로 확인한다.
3. 합성 입력으로 Spring에서 실제 FastAPI를 호출해 성공·실패·인증·요청 ID·시간 제한을 확인한다. 현재 localhost 목업 테스트 통과만으로 실 서버 연결이 검증된 것은 아니다.
4. 원문 복사·민감정보·허위 행동 평가·프롬프트 공격을 별도 내용 평가로 검증한다.
5. 위 계약을 확인한 뒤 7장의 종료 후 호출·근거 대조·리포트 저장을 연결한다.

## 10. 검증 결과와 실행 방법

2026-11-16 `./gradlew clean test --rerun-tasks`를 실행했다. **전체 319개, 실패 0개·건너뜀 0개**다. 신규 AI-05 테스트는 127개다.

추가로 테스트가 실제 결함을 잡는지 확인하려고 구현을 일부러 바꿔 실행한 뒤 원래대로 복구했다.

| 일부러 넣은 결함 | 잡아낸 테스트 |
|---|---|
| 평가가 전용 클라이언트 대신 기본 클라이언트 사용 | 전송 테스트의 시간 제한 2건 |
| `X-Request-Id` 헤더 누락 | 전송 테스트의 왕복 검증 |
| `success` 값 검사 제거 | HTTP 계약 테스트, 전송 테스트의 실패 봉투 검증 |

이 결과는 DTO·클라이언트·설정과 기존 코드 회귀에 대한 증거다. 목업 서버는 우리가 정한 계약대로 응답하도록 만든 것이므로, 실제 FastAPI가 같은 형식을 보내는지·LLM 평가 품질·원문 보호·리포트 저장은 검증하지 않았다. DB 코드를 바꾸지 않아 `dbTest`는 실행하지 않았다.

```bash
# 전체 기본 테스트
./gradlew test

# 신규 AI-05 테스트만 실행
./gradlew test --tests 'com.wsid.ai.*Evaluat*'
```

후속 순서: FastAPI와 배열 요소·turnFeedback·실패 상태 확정 → 실제 FastAPI와 합성 입력으로 HTTP 왕복 확인 → 시나리오 저장·모의 페이지 이벤트 기록 연결 → 7장의 리포트 서비스·저장·조회 구현 → PostgreSQL `dbTest`로 동시 생성·소유권 검증 → 합성 평가 세트로 LLM 근거·원문 보호 평가.
