# SOL-03 자유 질문·추가 질문 구현 보고서

작성일: 2026-11-18 · 상태: **Spring 구현·테스트 완료, 운영 승인 근거·공유 명세·프론트 연결 전**

관련 문서: [수정 계획서](solution-assist-question-plan.md) · [LangGraph 연결 계획(내부 계약 3.1장)](solution-langgraph-integration-plan.md) · [assist 시간 제한 보고서](solution-assist-spring-timeout-report.md)

## 1. 요약

기존 `POST /api/solutions/sessions/{id}/messages`에 `purpose`를 추가해 자유 질문(QUESTION)과 AI 추가 질문 답변(CLARIFICATION_ANSWER)을 처리한다. purpose를 생략하면 기존 사실 입력(FACT_UPDATE)과 똑같이 동작한다. 새 URL과 보안 규칙은 없다.

- AI 호출은 트랜잭션 밖에서 하고, 결과는 저장 직전에 소유권·상태·중복·버전·대기 질문을 다시 확인한 뒤 CAS 한 번으로 사실·카드·질문·대기 상태를 함께 저장한다.
- 질문 원문과 AI 답변 원문은 DB·로그에 저장하지 않는다. 대기 상태는 질문 ID·사실 키·의도 코드·물은 키·남은 횟수만 저장한다.
- AI 실패·시간 초과·응답 검증 실패면 사용자의 선택도 저장하지 않고 503을 반환한다.

## 2. 공개 API 변경

### 요청

| purpose | inputKind | 필드 | 처리 |
| --- | --- | --- | --- |
| 생략 또는 FACT_UPDATE | TEXT / CHOICE | 기존과 같음 | 기존 AI-06 사실 추출·고정 질문 선택 |
| QUESTION | TEXT | requestId, stateVersion, content | 새 질문 PLAN(필요 시 ANSWER) |
| CLARIFICATION_ANSWER | CHOICE | requestId, stateVersion, questionId, selectedCode | 대기 질문의 사실에 선택 반영 후 PLAN 재실행 |

- purpose는 문자열 코드만 받는다. null·빈 값·소문자·알 수 없는 값, 표에 없는 조합은 400이다.
- `questionId`는 CLARIFICATION_ANSWER에서만 받는다. 이때 `factKey`는 보내지 않는다(서버가 대기 질문에서 정함).
- QUESTION content는 기존 TEXT 규칙(길이·민감정보 검사)을 그대로 적용한다.

### 응답

기존 `facts`, `responseCards`, `nextQuestion`, `stateVersion`에 `assistant`를 추가했다. FACT_UPDATE와 SOL-04 응답에서는 `assistant: null`이다.

```json
"assistant": {
  "status": "NEED_USER_INPUT",
  "answer": null,
  "referenceCodes": [],
  "question": {"questionId": "uuid", "factKey": "installedApp",
               "content": "상대방이 알려준 앱을 설치했나요?", "options": ["YES","NO","UNKNOWN"]},
  "reasonCode": null
}
```

| status | answer | question | referenceCodes | reasonCode |
| --- | --- | --- | --- | --- |
| NEED_USER_INPUT | null | 서버 발급 ID + Spring 고정 질문 문구 | [] | null |
| ANSWERED | 검증한 AI 답변 | null | 인용 코드 1개 이상 | null |
| UNABLE_TO_ANSWER | 서버 고정 안내 문구 | null | [] | OUT_OF_SCOPE / NO_EVIDENCE / LIMIT_REACHED |

- 추가 질문 문구는 AI가 준 문장이 아니라 `SolutionFact`의 고정 질문이다.
- 추가 질문 대기 중에는 `nextQuestion`이 null이다. 대기가 끝나면 최신 사실과 UNKNOWN 이력으로 기존 고정 질문을 다시 계산한다.
- SOL-02 복원 응답에도 `assistant`가 추가됐다. 대기 질문만 복원되고, 과거 자유 질문·AI 답변은 원문을 저장하지 않으므로 복원되지 않는다. 완료된 상담은 `assistant: null`이다.

### 오류

| 상황 | 응답 |
| --- | --- |
| purpose·조합·questionId 형식 오류 | 400 INVALID_REQUEST |
| 대기 질문이 없거나 questionId가 다르거나, 대기 사실이 이미 UNKNOWN이 아님 | 400 INVALID_REQUEST |
| 대기 질문이 있는데 새 QUESTION | 409 ASSIST_QUESTION_PENDING(신규) |
| 중복 requestId / 완료 상담 / 버전 불일치 | 기존 409 DUPLICATE_REQUEST / SOLUTION_FINISHED / STATE_VERSION_CONFLICT |
| AI 실패·응답 검증 실패·전체 기한 초과 | 503 AI_SERVICE_UNAVAILABLE(상태 변경 없음) |
| 사실에 필요한 검토 카드 없음 | 기존 503 RESPONSE_CONTENT_UNAVAILABLE |

## 3. 처리 흐름

```text
소유권·중복·ACTIVE·버전·대기 질문 확인(읽기 트랜잭션)
 → QUESTION이면 민감정보 검사
 → CLARIFICATION_ANSWER면 메모리에서 사실 반영·카드 계산(누락 시 503)
 → 트랜잭션 밖 PLAN
     ├ NEED_USER_INPUT → 서버 questionId 발급, 물은 키 추가, 남은 횟수 1 차감
     ├ UNABLE_TO_ANSWER → 고정 안내
     └ EVIDENCE_READY → 승인 카탈로그에서 code·version 일치 근거만 선택
                         ├ 0개 → NO_EVIDENCE 안내(ANSWER 호출 안 함)
                         └ 있음 → ANSWER → 인용·금지 출력 검증
 → 쓰기 트랜잭션(READ COMMITTED): 재확인 → CAS(사실·카드·질문·대기 상태) → 메시지 1건 저장
```

- 첫 PLAN은 `askedFactKeys=[]`, `remainingClarifications=3`이다. 질문을 발급할 때만 차감하고 답할 때는 차감하지 않는다.
- 후속 PLAN·ANSWER에는 원문 대신 `question=null`과 저장된 `questionIntent`를 보낸다.
- 전체 처리 기한은 `assist-total-timeout`(기본 40초)이다. AI 호출은 남은 시간만 기다리고, 저장이 기한을 넘기면 롤백한다.

## 4. 저장과 다른 SOL 연동

`V5__add_solution_assist_state.sql`:

- `solution_sessions.pending_assist_json`(JSONB): `questionId`, `fact`, `questionIntent`, `askedFactKeys`, `remainingClarifications` 다섯 키만 저장한다. 읽을 때 키·타입·횟수 관계를 검사하고 깨져 있으면 오류로 처리한다.
- `solution_messages.purpose`, `solution_messages.assist_question_id`: 요청 이력. 자유 질문은 고정 문구 "질문을 접수했습니다."로, 추가 질문 답변은 기존 선택 메시지 형식으로 저장한다. 실제 인용 코드는 `reference_codes_json`에 남긴다.

| 경로 | 동작 |
| --- | --- |
| SOL-02 | 대기 질문 복원, 대기 중 nextQuestion=null |
| SOL-03 FACT_UPDATE | 기존 처리 유지. 저장 시 대기 질문 해제 |
| SOL-04 | 사실 정정 저장 시 대기 질문 해제(assistant=null) |
| SOL-05 | 종료 시 대기 질문 제거 |

기존 `compareAndSet`이 `pending_assist_json = NULL`을 함께 저장하므로 SOL-03 FACT_UPDATE와 SOL-04 코드는 바꾸지 않았다.

## 5. 설정

```yaml
wsid.ai:
  assist-read-timeout: ${AI_ASSIST_READ_TIMEOUT:20s}    # PLAN·ANSWER 각각
  assist-total-timeout: ${AI_ASSIST_TOTAL_TIMEOUT:40s}  # 요청 전체
  assist-mode: ${AI_ASSIST_MODE:shared}                 # shared(AI_MODE 따름) / fake / http
  assist-base-url: ${AI_ASSIST_BASE_URL:${AI_BASE_URL:http://localhost:8000}}
  assist-api-key: ${AI_ASSIST_API_KEY:${AI_API_KEY:local-dev-key}}
wsid.content.approved-references: classpath:content/approved-references.json
```

`AI_MODE=fake`, `AI_ASSIST_MODE=http`로 assist만 실제 Python에 연결할 수 있다. 승인 근거 파일은 `reviewed=true`·https 출처·확인일이 있는 항목만 받으며 형식이 틀리면 기동에 실패한다.

## 6. 변경 파일

| 영역 | 파일 |
| --- | --- |
| 요청·응답 | `SendSolutionMessageRequest`(purpose·questionId), `SolutionMessageResponse`·`SolutionStateResponse`(assistant), `SolutionAssistant`(신규) |
| 서비스 | `SolutionAssistService`(신규), `SendSolutionMessageService`(purpose 분기), `SolutionQueryService`(복원) |
| 도메인·저장 | `PendingAssist`(신규), `SolutionSession`, `SolutionMessage`, `SolutionSessionUpdates(Impl)`, `V5__add_solution_assist_state.sql` |
| AI 연결 | `SolutionAssistGateway`(신규), `AiProperties`, `HttpAiClient`(assist 전용 read timeout), `application.yml` |
| 근거 | `ApprovedReferenceCatalog`(신규), `content/approved-references.json`(빈 배열) |
| 오류 | `ErrorCode.ASSIST_QUESTION_PENDING` |
| 빌드 | `build.gradle`의 `liveAiTest`(기본 test에서 제외) |

## 7. 테스트 결과

WSID 전용 PostgreSQL(55432)·Redis(56379)에서 실행했다.

| 구분 | 결과 | assist 관련 |
| --- | --- | --- |
| 단위 `test` | 958건 통과 | 입력 12, 서비스 9, 승인 카탈로그 2, AI 클라이언트·DTO 110 |
| DB `dbTest` | 50건 통과 | 대기 상태 저장·복원·종료 해제, 선택 시 사실·카드·횟수 동시 저장, AI 실패 시 무변경, FACT_UPDATE·SOL-04 해제, 같은 버전 동시 요청 중 1건만 반영 |
| 통합 `integrationTest` | 33건 통과 | 브라우저(쿠키·CSRF) → Spring → 실제 HttpAiClient → localhost Python 대역 → DB 흐름, CSRF 없으면 403·AI 미호출, 다른 사용자 404, 재전송 409, 원문 미저장, Python 503·시간 초과 시 상태 무변경 |
| `bootJar` | 성공 | |

**실제 Python(WSID_AI OHC `136272c`, 가짜 모델·가짜 임베딩) 연결 결과:** 처음에는 `liveAiTest`가 실패했다. Spring의 JDK HttpClient가 기본값 HTTP/2로 `Upgrade: h2c` 헤더를 붙여 보내고, uvicorn이 이를 처리하지 못해 422를 돌려줬다. `HttpAiClient`·`TrainingAiGateway`를 HTTP/1.1로 고정하고 전송 테스트에 회귀 검사를 추가한 뒤 PLAN(추가 질문) → 후속 PLAN(근거 후보) → ANSWER(인용 답변)이 통과했다. 기존 테스트의 JDK 내장 서버 대역은 업그레이드 헤더를 문제없이 처리해 이 문제를 드러내지 못했다.

## 8. 남은 제한과 결정 사항

- **운영 승인 근거가 0개다.** `approved-references.json`이 빈 배열이므로 실제 운영에서는 EVIDENCE_READY가 와도 항상 NO_EVIDENCE 안내가 나간다. 검토된 근거를 넣어야 답변이 나온다.
- **운영 대응 카드 카탈로그도 비어 있다.** 송금·앱 설치가 YES가 되는 선택은 기존과 같이 503이 된다.
- **공유 명세(`WSID-API-Spec.md`) 미반영.** purpose·questionId·assistant·ASSIST_QUESTION_PENDING을 팀 명세에 추가해야 한다.
- **프론트 미연결.** Spring 배포 전에 purpose를 보내면 기존 서버는 400이다.
- **원문 미보관.** 추가 질문 뒤에는 의도 코드와 사실만으로 답한다. 원문을 유지할지는 팀 결정 사항이다.
- **사기 유형 미전달.** fraudType은 assist 요청에 없다.
- **LIMIT_REACHED:** 현재 Python은 반환하지 않는다. 공개 응답 타입에는 남아 있다.
- **중복 requestId는 응답을 재생하지 않고 409로 거부한다.**
- **실제 Gemini 품질과 응답 시간은 측정하지 않았다.** 측정 후 read·전체 기한을 조정한다.
- **AI-06·07 응답 형식(해결).** OHC의 `/extract`·`/explain`은 `{"success":true,"data":…}` 없이 결과만 반환한다. Spring `HttpAiClient`가 이 두 API에 한해 두 형식을 모두 받도록 바꿨다(`success` 키가 있으면 기존처럼 엄격 검사, 없으면 결과 필드가 최상위에 있어야 함, 내용 검사는 동일). assist·시뮬레이션·평가는 그대로 봉투를 요구한다. 이후 `AI_MODE=http` 하나로 상담 시작(설명), FACT_UPDATE TEXT, assist가 실제 FastAPI와 모두 연결됐다.
- **Python `/extract`·`/explain`에 내부 키 검사가 없다(Python 수정 필요).** 키 없이 호출해도 200이다. `/assist`는 401을 반환한다.
- **근거 파일 필드 이름 차이.** Python `references.jsonl`은 `source_url`·`verified_at`, Spring 승인 카탈로그는 `source`·`checkedAt`을 요구한다. 승인 근거를 옮길 때 변환해야 한다.
