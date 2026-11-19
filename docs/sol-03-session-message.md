# SOL-03 피해 대응 상담 메시지 구현 보고서와 연동 안내

> 사기 유형 반영(2026-11-19): AI-06 응답의 `fraudType`(MESSENGER_PHISHING·SMISHING·LOAN_FRAUD·INSTITUTION_IMPERSONATION·INVESTMENT_FRAUD·SECONDHAND_TRADE)을 `solution_sessions.fraud_type_code`에 저장해요. SOL-01은 시작 때, SOL-03 텍스트·사진은 값이 비어 있을 때만 채워요(처음 값 유지). 모르는 코드는 버려요. assist PLAN·ANSWER 요청에 `fraudType`으로 함께 보내요(없으면 null). 프론트 API 형식은 바뀌지 않아요. 사진 상담 안내(`SolutionImageAdviceService`)에는 아직 보내지 않아요.

작성일: 2026-11-17 · 기준 브랜치: OHC · [구현 계획서](sol-03-session-message-plan.md)

`POST /api/solutions/sessions/{id}/messages`를 구현했다. 회원·비회원 모두 본인 상담에 TEXT 또는 CHOICE로 답할 수 있다. TEXT는 기존 AI-06 클라이언트를 호출하고, CHOICE는 AI 없이 반영한다. 사실·현재 검토 카드·다음 질문·안전한 메시지·버전은 PostgreSQL 트랜잭션 한 번으로 저장한다.

**Spring 상담 기능 연결까지 구현했다. 실제 FastAPI/LLM과 운영 카드 콘텐츠는 이번 작업에서 구현하거나 승인하지 않았다.** 운영 카탈로그는 여전히 비어 있어 송금 YES·앱 설치 YES처럼 카드가 필요한 조합은 503으로 실패하며 상태는 바뀌지 않는다. 테스트용 카드는 운영 콘텐츠가 아니다. SOL-04 사실 수정·SOL-05 종료 API도 아직 없다.

## 1. 계획서 검토 사항 8개 반영 결과

| 검토 사항 | 구현·문서 반영 |
|---|---|
| 성공 HTTP 상태 누락 | 200 OK를 명시하고 실제 서버·MVC 테스트에서 확인 |
| 짧은 UUID 허용 위험 | 8-4-4-4-12 정규식 검사 후 UUID 변환. `1-1-1-1-1` 거부 테스트 |
| 쓰기 격리 수준 | 읽기·쓰기를 READ COMMITTED로 명시. 실제 동시성 테스트에서 격리 수준과 CAS 패자의 0행을 확인 |
| knownFacts에 UNKNOWN 포함 | `SolutionFacts.knownValues()`로 YES/NO만 전달. 실제 HTTP 본문 검사 |
| TEXT 정정이 적용되지 않는 UX | 알려진 YES/NO는 유지. 충돌 개수·requestId만 로그에 기록. 프론트 상시 정정 안내와 SOL-04 화면 연결은 후속 작업으로 분리 |
| 동일 질문 반복 저장 | 다음 질문이 이전 질문과 다를 때만 SYSTEM 메시지 추가. 변화 없는 TEXT는 USER 접수 문구만 저장 |
| ID/본문 오류 순서 | CSRF → ID → 본문 → 소유권. ID 검사 후 요청 스트림을 읽어 깨진 JSON도 순서를 유지 |
| 질문 문구 차이 | 현재 SolutionFact 고정 문구 유지. 명세의 앱 설치 질문과 문구가 다르며 콘텐츠 담당의 통일·명세 정정 필요 |

## 2. 공개 API와 프론트 호출

비회원은 기존 `GET /api/solutions/csrf`에서 SESSION 쿠키와 `{headerName, token}`을 받고 같은 쿠키와 `X-CSRF-TOKEN`으로 요청한다. 회원은 기존 `/api/auth/csrf` 토큰도 사용할 수 있다. `/api/auth/csrf`의 회원 전용 정책과 AUTH 공통 보안 설정은 바꾸지 않았다.

CHOICE 요청 예시(초기 버전 0, 현재 질문 transferredMoney):

```json
{
  "requestId": "b2ef722e-950b-48e3-bd1c-950870aa6f33",
  "stateVersion": 0,
  "inputKind": "CHOICE",
  "factKey": "transferredMoney",
  "selectedCode": "NO"
}
```

200 OK 예시:

```json
{
  "success": true,
  "data": {
    "facts": {
      "transferredMoney": "NO",
      "sharedInformation": "UNKNOWN",
      "installedApp": "UNKNOWN",
      "contactContinues": "UNKNOWN"
    },
    "responseCards": [],
    "nextQuestion": {
      "factKey": "sharedInformation",
      "content": "개인정보를 전달한 적이 있나요?",
      "options": ["YES", "NO", "UNKNOWN"]
    },
    "stateVersion": 1
  }
}
```

TEXT 요청 예시:

```json
{
  "requestId": "be89911c-1f45-4a57-8a80-4e9e849d9a77",
  "stateVersion": 1,
  "inputKind": "TEXT",
  "content": "송금 후 연락이 끊겼어요."
}
```

TEXT에서는 content만, CHOICE에서는 factKey·selectedCode만 받는다. 교차 필드가 null로 들어와도 거부한다. 모르는 키·잘못된 실제 JSON 타입·필수 값 누락은 400이다. content는 공백만이면 안 되고 원래 Java 문자열 길이 1,000자 이하다. 정수 버전은 0~2,147,483,646을 받는다(다음 +1이 int 범위를 넘지 않도록 최댓값 제외). UUID는 정확한 길이·하이픈 형식이며 대소문자 표기는 같은 UUID로 처리한다.

컨트롤러는 최대 16KiB + 1바이트까지만 읽고 초과 입력을 400으로 거부한다. 빈 본문은 400, 비JSON Content-Type은 415다. JSON 뒤에 다른 값이 붙은 본문도 거부한다. CSRF가 유효할 때 잘못된 ID + 깨진 JSON은 404, 올바른 ID + 깨진 JSON은 400이다. CSRF가 없으면 ID/본문과 관계없이 먼저 403이다.

성공에는 기존 `ApiResponse`, 오류에는 기존 `{code,message}`의 `ErrorResponse`를 쓴다. 오류 봉투 전체를 success:false 구조로 바꾸지 않았다. `nextQuestion`은 없을 때 null, responseCards는 없을 때 빈 배열이다. Spring Security의 no-store 캐시 헤더를 유지한다.

## 3. 요청이 실제로 처리되는 순서

1. `SolutionSecurityConfig`가 회원·비회원 모두 CSRF를 검사한다. messages POST만 새로 허용하며 미구현 facts/complete 경로는 열지 않는다.
2. `SolutionController`가 ID·제한된 JSON 본문·DTO를 검사한다.
3. `SolutionOwnerResolver.findExisting`이 기존 회원/익명 소유자만 찾는다. 이어 `findOwned`로 본인 상담을 확인한다. 새 익명 UUID를 발급하는 SOL-01용 resolve를 사용하지 않는다.
4. 짧은 읽기에서 중복 UUID → ACTIVE 여부 → 버전 → 현재 질문을 검사하고 불변 스냅샷을 만든다.
5. TEXT는 `SensitiveDescriptionGuard` 통과 후 트랜잭션 밖에서 AI-06을 호출한다. knownFacts에는 YES/NO만 담고 requestId는 헤더로 보낸다. CHOICE는 호출하지 않는다.
6. 소유자를 다시 확인한다. 쓰기 트랜잭션에서 상담 소유권·중복·상태·버전·현재 질문을 다시 확인한다. 도중에 상태가 바뀌었다면 AI 결과를 최신 상태에 임의로 재적용하지 않는다.
7. 사실 병합, 검토 카드 선택, 다음 질문 계산을 수행한다. 필요한 카드가 없으면 변경하지 않고 실패한다.
8. 조건부 UPDATE, 마지막 순번 조회, USER 기록과 필요 시 SYSTEM 질문 저장을 한 트랜잭션에서 수행한다. 실제 DB 버전을 읽어 200 응답을 만든다.

로그인 회원이 있으면 회원이 우선이다. 로그인 전 익명 상담을 남은 UUID로 수정하거나 자동 이관하지 않는다. 비활성 회원은 AUTH 오류로 끝나며 비회원으로 우회하지 않는다. 새 CSRF 세션만 발급받아도 기존 상담 소유권이 생기지 않는다.

## 4. 사실 병합·질문·원문 보호

### 사실과 카드

CHOICE는 현재 질문과 일치하는 사실 하나만 바꾼다. 공개 CHOICE는 내부 `InputKind.OPTION`으로 저장한다. TEXT는 기존 UNKNOWN에 대해서만 AI가 제안한 YES/NO를 채운다. 기존 YES/NO는 AI가 UNKNOWN이나 반대 값을 보내도 그대로 유지한다. 현재 출처별 메타데이터가 없으므로 모든 알려진 값을 동일하게 보호한다.

사용자가 TEXT로 "사실 송금은 안 했어요"라고 적어도 기존 YES를 수정하지 않는다. 응답 필드에는 충돌 표시를 추가하지 않았다. 프론트는 TEXT 입력 주변에 사실 정정 방법을 상시 안내하고, SOL-04가 준비되면 수정 화면으로 연결해야 한다. 현재는 서버 기능만 구현했으며 프론트 화면을 고치지 않았다. 충돌이 있었을 때만 다른 안내가 필요하면 응답 계약을 추가로 합의해야 한다. 로그는 conflictCount·requestId만 남기며 원문·사실 키·제안 값을 기록하지 않는다.

카드는 `ResponseRules.cards`가 현재 facts로 다시 계산한다. 검토된 현재 카드가 반환되면 코드+버전 참조만 세션에 저장한다. SOL-02 복원도 코드로 현재 검토 카드를 찾으므로 연락처 등 콘텐츠 변경을 반영한다. LLM이 기관·카드·전화번호·링크를 만들게 하지 않는다. 원 명세의 송금 YES + 빈 카드 예시는 현재 규칙과 다르므로 팀 명세 정정 대상이다.

### UNKNOWN과 질문

UNKNOWN을 고른 CHOICE는 사실을 UNKNOWN으로 두고 해당 사실의 질문을 마친 것으로 기록한다. 이력의 fact_key·selected_code=UNKNOWN을 읽어 이후 질문에서 제외한다. 순서는 transferredMoney → sharedInformation → installedApp → contactContinues다. TEXT 추출 결과가 UNKNOWN인 것은 명시적 UNKNOWN 선택이 아니므로 건너뛰지 않는다.

모든 항목이 알려졌거나 UNKNOWN으로 답을 마쳤으면 nextQuestion=null이며 상태는 ACTIVE다. 자동 NO나 자동 완료로 바꾸지 않는다. 같은 질문이 그대로 유지된 TEXT에서는 USER만 추가하고 SYSTEM 질문을 다시 저장하지 않는다. 나중에 SOL-04가 UNKNOWN 정정을 지원할 때 과거 건너뛰기 이력을 계속 적용할지 별도 결정해야 한다.

### DB와 로그에 남기는 것

- TEXT는 고정 접수 문구 `피해 설명을 제출했습니다.`만 safe_content에 저장한다. guard를 통과한 일반 문장도 원문 저장하지 않는다.
- CHOICE는 서버의 고정 질문 문구 + 선택 코드 표현과 구조화된 fact_key·selected_code를 저장한다.
- USER 한 행만 client_request_id를 가지며 후속 SYSTEM 질문은 null이다. reference_codes_json은 factKey 저장 공간으로 사용하지 않는다.
- facts_json은 네 사실, card_codes_json은 코드·버전, next_question_json은 고정 질문 또는 NULL이다. 설명·프롬프트·AI 원시 응답은 저장하지 않는다.
- guard는 일부 민감정보 패턴의 거부 검사다. 모든 개인정보를 탐지하는 도구가 아니며, 그래서 통과 원문도 저장하지 않는다. FastAPI·프록시·APM의 원문 로깅 정책은 해당 운영 환경에서도 확인해야 한다.

## 5. 중복·동시성·트랜잭션

### 같은 UUID는 409 후 복원

반영된 requestId를 다시 보내면 409 DUPLICATE_REQUEST다. 오래된 stateVersion보다 중복 검사를 먼저 한다. 같은 UUID에 다른 내용을 보내도 새 입력으로 반영하지 않는다. 원래 성공 응답의 200 재생은 구현하지 않았다. 프론트는 SOL-02로 최신 상태를 조회한다.

AI 장애·카드 누락 등 실패는 UUID를 저장하지 않으므로 같은 UUID로 재시도할 수 있다. 다른 입력이 먼저 성공했다면 버전 충돌이다. 성공 전 같은 UUID의 동시 TEXT는 AI를 각각 호출할 수 있지만 DB에는 한 번만 반영된다. HTTP 자동 재시도나 AI 호출 중복 제거 저장소는 추가하지 않았다.

### 상태 UPDATE와 메시지는 하나로 커밋

서비스 send 진입점은 `@Transactional(NOT_SUPPORTED)`다. 호출자가 트랜잭션을 열었더라도 잠시 중단하며, AI 구간에서는 트랜잭션을 유지하지 않는다. 내부 읽기/쓰기는 별도 TransactionTemplate의 READ COMMITTED다. SOL-02 조회의 REPEATABLE_READ를 가져오지 않았다.

custom repository는 같은 DataSource의 JdbcTemplate로 ID·소유자·ACTIVE·기대 버전 조건의 UPDATE를 실행한다. 상태 JSON·updated_at·state_version+1을 한 번에 바꾼다. 같은 내용의 TEXT도 성공한 새 메시지이므로 정확히 한 번 증가한다. 관리 엔티티를 직접 변경하지 않고 UPDATE 뒤 EntityManager를 clear하여 오래된 캐시를 버린다. JPA `@Version` dirty checking UPDATE를 이 경로에 중복 적용하지 않는다.

UPDATE 성공 후 행 잠금이 유지되는 동안 max(sequence_no)+1로 메시지를 저장한다. count를 쓰지 않아 삭제·결번이 있어도 순번이 겹치지 않는다. USER INSERT/flush 실패 시 JDBC UPDATE도 같은 트랜잭션에서 롤백된다. 실제 DB 테스트로 확인했다.

두 요청이 같은 버전으로 경쟁하면 READ COMMITTED에서 승자는 1행, 패자는 0행이다. 실패 트랜잭션 종료 후 새 조회에서 UUID가 이미 반영됐으면 DUPLICATE_REQUEST, 다른 버전 경쟁이면 STATE_VERSION_CONFLICT로 구분한다. 상담 삭제/소유자 불일치는 404, 완료는 SOLUTION_FINISHED다. 잠금 예외와 알려진 두 메시지 유일 제약도 이 경계에서 처리하며, 관련 없는 무결성 오류를 전부 409로 숨기지 않는다.

Redis 세션 확인과 PostgreSQL 커밋은 하나의 분산 트랜잭션이 아니다. 저장 전 소유자를 재확인하지만 커밋과 완전히 동시에 일어난 로그아웃·만료까지 원자적으로 묶는다고 보장하지 않는다.

## 6. 오류 처리

| 상태·코드 | 처리 |
|---|---|
| 400 INVALID_REQUEST | 타입·필수/추가 필드·현재 질문 불일치. 변경 없음 |
| 400 SENSITIVE_DATA_DETECTED | AI 전송·저장 없이 거부 |
| 401 기존 AUTH 오류 | 비활성 등 인증 오류. 익명으로 우회하지 않음 |
| 403 CSRF_TOKEN_INVALID | 회원·비회원 토큰 누락/불일치 |
| 404 SOLUTION_NOT_FOUND | ID 형식 오류·없는 상담·다른 소유자·익명 소유자 없음 |
| 409 DUPLICATE_REQUEST | 이미 반영된 UUID. 복원 후 확인 |
| 409 STATE_VERSION_CONFLICT | 오래된 버전·동시 갱신. 복원 후 사용자 재입력 |
| 409 SOLUTION_FINISHED | COMPLETED 상담 변경 거부 |
| 503 AI_SERVICE_UNAVAILABLE | AI 장애·계약 위반·시간 초과. 이번 입력 미저장 |
| 503 RESPONSE_CONTENT_UNAVAILABLE | 필요한 검토 카드 누락. 전체 미저장 |

새 오류 코드 3개는 ErrorCode에 추가했다. 기존 훈련의 SESSION_VERSION_CONFLICT·SESSION_FINISHED와 전역 처리기는 변경하지 않았다. SOL-01은 AI 실패 시 UNKNOWN으로 시작하지만 SOL-03은 기존 상담을 그대로 두고 503을 반환한다. 완료 후 갱신 거부는 이번 구현에서 채택한 정책이며 SOL-05 구현과 맞춰야 한다.

## 7. 파일별 상호작용과 이후 개발 시 참고

경로 접두사는 `src/main/java/com/wsid/`다.

| 파일 | 역할·상호작용 | 이후 주의할 점 |
|---|---|---|
| `solution/controller/SolutionController.java` | ID/JSON 검사 → 새 메시지 서비스 → ApiResponse | 시작·복원과 구분하여 POST 성공 200 유지. 자동 본문 바인딩으로 바꾸면 ID 오류 우선순위가 달라질 수 있음 |
| `solution/dto/SendSolutionMessageRequest.java` | strict 타입·조건부 필드·UUID 검사 | 공개 CHOICE와 내부 OPTION 구분, toString 원문 보호. 입력 키 추가 시 FastAPI/프론트 계약도 확인 |
| `solution/dto/SolutionMessageResponse.java` | 네 응답 필드, 기존 카드·질문 DTO 재사용 | 충돌 메타데이터·원래 원문을 임의로 추가하지 않음 |
| `solution/service/SendSolutionMessageService.java` | 소유권/중복/버전 조회, AI 호출, 짧은 쓰기·오류 변환 | AI 중 트랜잭션 없음. 장애 때 UNKNOWN 초기화 금지. 실패한 트랜잭션 안에서 재조회 금지 |
| `solution/domain/SolutionFacts.java` | knownValues·withChoice·mergeExtracted | SOL-04 직접 정정과 AI 병합은 다른 정책. 직접 수정 구현에 UNKNOWN만 채우는 함수를 재사용하지 않음 |
| `solution/domain/SolutionQuestion.java` | 이전 함수 유지 + UNKNOWN 선택 제외 집합 지원 | SOL-01 초기 질문은 기존 함수로 유지. SOL-04에서 건너뛰기 이력 정책 추가 합의 |
| `solution/domain/SolutionMessage.java` | choice 팩토리, factKey·selectedCode·UUID | factKey는 내부용. SOL-02의 공개 5개 필드는 그대로이고 OPTION→CHOICE 복원 유지 |
| `solution/domain/SolutionSession.java` | prepareUpdate가 저장용 불변 JSON 생성 | 엔티티 변경 + CAS를 같은 트랜잭션에서 섞으면 이중 버전 증가/덮어쓰기 위험 |
| `solution/repository/SolutionSessionUpdates.java`, `SolutionSessionUpdatesImpl.java` | 소유자/상태/버전 CAS와 JPA 캐시 정리 | SOL-04·05도 동일한 버전 경쟁 규칙 사용. 별도 DB 커넥션을 열거나 독립 커밋하지 않음 |
| `solution/repository/SolutionMessageRepository.java` | 최대 순번·UNKNOWN 선택 키 조회, 기존 UUID 검사 | 메시지 추가 전 세션 행 갱신 잠금 규칙 준수. 다른 경로에서 직접 순번 배정하지 않음 |
| `solution/security/SolutionSecurityConfig.java` | SOL-03 POST만 공개, CSRF 유지 | 후속 경로를 와일드카드로 전부 열지 않음. 기존 LSH AUTH 체인과 분리 유지 |
| `solution/security/SolutionOwnerResolver.java`(미변경) | 기존 소유자·활성 회원 확인 | 호출자는 회원/익명 ID를 요청 본문에서 받지 않음. 신규 생성용 resolve와 혼동 금지 |
| `solution/service/SensitiveDescriptionGuard.java`(미변경) | TEXT 외부 전송 전 민감정보 거부 | 개인정보 완전 탐지 보장 아님. 필터 통과 원문도 저장하면 안 됨 |
| `solution/domain/ResponseRules.java`, `ResponseCardCatalog.java`(미변경) | 사실로 카드 선택·검토된 현재 콘텐츠 확인 | 빈 운영 카탈로그 연결 작업 필요. 긴급 카드를 빼고 성공 처리하지 않음 |
| `ai/AiClient.java`, `HttpAiClient.java`, `ai/dto/SolutionExtractRequest.java`(미변경) | AI-06 실제 HTTP·헤더·엄격한 응답 검증 | DTO 허용 UNKNOWN과 SOL-03 knownFacts의 YES/NO 전달 정책을 구별. 제한은 공통 20초이며 실제 지연 측정 후 별도 조정 |
| `common/error/ErrorCode.java` | 신규 SOL 오류 코드 | 프론트 409/503 분기 반영. 기존 training 코드 이름 변경 금지 |

LSH의 AUTH 컨트롤러·공통 SecurityConfig·LoginService, 기존 AI 클라이언트·설정, Gradle 의존성은 바꾸지 않았다. SOL-02 조회 서비스와 공개 메시지 형식도 변경하지 않았다.

### DB 마이그레이션

[V4__add_solution_message_fact_key.sql](../src/main/resources/db/migration/V4__add_solution_message_fact_key.sql)을 새로 추가했다. 기존 V1~V3는 수정하지 않았다. fact_key는 nullable VARCHAR(50)이며 값이 있으면 4개 허용 키 중 하나여야 한다. 과거 메시지는 NULL로 유지하며 factKey를 추정해 채우지 않는다. 과거 키 없는 OPTION/UNKNOWN 이력은 건너뛰기 대상으로 식별할 수 없다.

로컬 현재 브랜치에서는 V4 번호가 비어 있었지만 다른 작업 브랜치의 미병합 마이그레이션까지 확인한 것은 아니다. **공유 브랜치 머지 전 DB/AUTH 담당자와 V4 번호 충돌을 확인해야 한다.** 이번 테스트 DB에는 V4를 적용했다. 이미 적용·공유한 마이그레이션을 임의로 다시 쓰지 않는다.

기존 `SolutionDatabaseTest`가 "최신 마이그레이션은 3"이라고 고정하던 검사는 자기 범위인 V3 적용 여부만 확인하도록 바꿨다. V4 이후에도 SOL-01 제약 검증이 불필요하게 깨지지 않게 했다.

## 8. FastAPI·프론트·콘텐츠 후속 작업

다음 FastAPI 경로는 실제 파일을 확인한 위치가 아닌 역할별 제안이다. 이 저장소에는 Python 소스가 없다.

| 작업 위치 | 맞춰야 할 내용 |
|---|---|
| FastAPI `routers/solutions.py` | 기존 AI-06 `/internal/ai/solutions/extract`를 제공. 공개 SOL-03 경로는 Spring 소유 |
| FastAPI `schemas/solution_extract.py` | description·knownFacts 입력, 정확한 네 사실·uncertainFields 출력. success 실제 boolean, 값은 실제 문자열. UNKNOWN 키와 uncertainFields 일치 |
| FastAPI `services/solution_extractor.py`, `prompts/` | knownFacts는 YES/NO인 확인 사실만 온다. 빈/부분 Map 허용. 설명에 없는 항목을 추측하지 않음. 현재 알려진 값과 반대인 문장의 처리 정책도 평가 |
| FastAPI 미들웨어·APM | X-Request-Id 추적, 요청·프롬프트·AI 응답 원문 로깅 금지. 오류 cause에 원문 포함하지 않기 |
| FastAPI 계약/모델 테스트 | 부정문·정정·정보 부재·모순, 실제 JSON 타입·503/실패 봉투·타임아웃 평가. Spring fake는 모델 정확도 증거가 아님 |
| 프론트 상담 화면 | 쿠키+CSRF, 새 입력별 UUID, 같은 제출 재시도는 같은 UUID, 409 후 SOL-02 복원. 기존 TEXT를 최신 버전에 자동 재적용하지 않기 |
| 프론트 정정 UI·SOL-04 | 알려진 값은 TEXT로 바뀌지 않음을 안내. 수정 API 준비 후 화면 연결. UNKNOWN 건너뛰기 재질문 정책 합의 |
| 콘텐츠·카드 담당 | 실제 승인 카드 카탈로그 연결, 기관 참조/우선순위/버전 검토. 명세의 카드 예시와 고정 질문 문구 정정 |
| SOL-04·05 담당 | 같은 소유권·CSRF·ACTIVE·버전 조건, 원문 미저장, CAS와 메시지 순번 규칙 준수. 종료 후 변경 거부 정책 유지/변경 시 계약·테스트 함께 수정 |

## 9. 검증 결과

검증 명령은 `./gradlew test dbTest integrationTest bootJar`다. Java 21, 프로젝트 전용 PostgreSQL 17(127.0.0.1:55432)·Redis 7(127.0.0.1:56379)에서 실행했다. dbTest는 wsid_ai01_test, integrationTest는 wsid_auth_test를 사용했다. HTTP 통합 테스트는 무작위 포트의 실제 Spring 서버와 localhost FastAPI 대역을 사용한다. 다른 프로젝트의 기본 5432/6379에는 연결하지 않았다.

최종 실행에서 **단위 859건 + DB 25건 + 통합 23건, 총 907건**이 모두 통과했다. 실패·오류·건너뜀은 0건이다.

| 범위 | 결과·확인한 내용 |
|---|---|
| `test` | 859건 통과. 요청·병합·질문·서비스·로그·회원/비회원 MVC·기존 전체 회귀 |
| `dbTest` | 25건 통과. SOL-03 12건: CAS·실제 버전·JPA 캐시·행 잠금/0행 경쟁·롤백·결번·V4 제약·AI 장애 재시도·외부 트랜잭션 중단 등 |
| `integrationTest` | 23건 통과. 실제 Redis·서버의 선택/복원·소유권·CSRF·만료·중복·버전·AUTH 회귀. AI HTTP 대역 5건 포함 |
| `bootJar` | 빌드 성공 |

HTTP 대역에서는 정상 헤더/본문·YES/NO knownFacts·CHOICE AI 미호출을 확인했다. 503·깨진 JSON·문자열 success·300ms 제한을 넘는 지연에 대해 실제 HttpAiClient가 실패하고 상담/메시지가 그대로인 것, 같은 UUID로 정상 재시도가 가능한 것을 확인했다. 대역의 요청 건수·UUID 헤더와 DB 결과를 함께 검사하여 경로가 실제 실행됐음을 검증한다.

문서의 JSON 예시·로컬 링크와 `git diff --check`도 통과했다. IntelliJ Java 검사는 프로젝트 content root 설정 때문에 실행되지 않았으며, 이를 Java 검사 통과로 계산하지 않았다. Java 검증은 Gradle 컴파일·테스트·패키징 결과다.

Postman CLI 1.75.0의 `postman status`는 이 환경에서 지원되지 않아 기존 JUnit·localhost HTTP 서버 방식으로 검증했다. 외부 공개 mock이나 실제 FastAPI 서버를 조회·배포하지 않았다. 실제 LLM의 사실 추출 정확도, 운영 FastAPI 로그·APM 설정, 승인 콘텐츠 품질·문구 합의, 프론트 실제 화면은 이번 검증 대상이 아니다.

## 10. 변경 범위와 운영 전 남은 일

계획서·구현 보고서·docs 목차·API 상태·solution/ai README를 함께 갱신했다. 구현/테스트 코드는 `solution/`과 공통 ErrorCode, 새 V4에 한정한다. 커밋·푸시는 하지 않았다.

운영 전에는 승인 카드 카탈로그 연결, 실제 FastAPI 계약/추출 평가, 프론트의 409/503/정정 안내, SOL-04·05 정책 연결, 마이그레이션 번호 조정 여부를 확인해야 한다. 현재 동작하는 Spring API·로컬 HTTP 대역 검증과 아직 없는 외부 구현을 구별해 관리한다.
