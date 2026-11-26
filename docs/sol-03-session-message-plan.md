# SOL-03 피해 대응 상담 메시지 구현·테스트 계획서

> **구현 완료(2026-11-17).** 실제 변경·테스트 결과와 연관 파일의 후속 작업은 [SOL-03 구현 보고서](sol-03-session-message.md)를 참고한다. 검토 지적 8개를 반영했으며, 중복 409·알려진 값 보호·UNKNOWN 질문 진행·완료 후 변경 거부는 이번 Spring 구현에서 채택했다. 프론트 UX·FastAPI·콘텐츠 합의는 보고서의 남은 작업으로 구분한다.

작성일: 2026-11-17 · 상태: **구현 완료, 구현 전 계획과 결정 근거 보존** · 코드 기준: OHC `23644e7`(SOL-01·02 구현 포함)

명세 출처는 팀 공유 API 명세의 SOL-03 절과 AI-06 절이다. 현재 코드는 [SOL-01 구현 보고서](sol-01-session-start.md), [SOL-02 구현 보고서](sol-02-session-get.md), [AI-06 구현 보고서](ai-06-solution-extract.md)와 함께 확인했다. 아래의 **제안 정책**은 명세에 없는 세부 동작이며 구현 전 프론트·AUTH·DB·FastAPI 담당자와 확정한다. 최초 계획 작성에서는 코드·DB를 변경하지 않았고, 이후 사용자 요청으로 아래 계획을 구현했다. 실제 코드와 검증 범위는 구현 보고서를 기준으로 확인한다.

## 1. 목표와 범위

`POST /api/solutions/sessions/{id}/messages`로 본인 상담에 입력을 추가하고 사실·검토 카드·다음 질문을 다시 계산한다. TEXT는 Spring이 기존 AI-06 클라이언트로 FastAPI를 호출한다. CHOICE는 LLM 없이 Spring에서 처리한다. 두 경로 모두 최종 저장과 카드 선택은 Spring 책임이다.

```text
브라우저(SESSION 쿠키 + X-CSRF-TOKEN)
  → SolutionController: 경로 ID·JSON 타입·조건부 필드 검사
  → SolutionOwnerResolver.findExisting + repository.findOwned: 소유권 확인
  → 짧은 조회: 중복 requestId·버전·상태·현재 질문 확인
  → TEXT만: 민감정보 검사 → AI-06 호출(저장 트랜잭션 밖)
  → 짧은 저장 트랜잭션: 재검증 → 사실 병합 → 카드·질문 계산
     → 조건부 상태 갱신 + USER/SYSTEM 메시지 저장
  → 성공 봉투: facts / responseCards / nextQuestion / stateVersion
```

이번 구현 범위는 요청·응답 DTO, 입력 적용 도메인, 메시지 서비스, DB 변경, 전용 보안 경로, 단위·DB·통합 테스트다. SOL-04 사실 수정·SOL-05 종료 API, AI-07 설명 생성, FastAPI/LLM 자체 구현은 별도 작업이다. SOL-04가 재사용할 사실 갱신 경계는 준비하되 해당 HTTP 경로를 먼저 열지 않는다.

## 2. 실제 코드 상태와 재사용 경계

| 파일·디렉토리 | 현재 상태 | SOL-03에서 할 일 |
|---|---|---|
| `solution/controller/SolutionController.java` | 시작·복원·비회원 CSRF 발급 구현. ID 문자열 검사 후 같은 404 반환 | messages POST 추가. 기존 시작·복원 계약 유지 |
| `solution/security/SolutionOwnerResolver.java` | `findExisting`이 새 익명 UUID·HTTP 세션을 만들지 않고 기존 소유자 확인 | 생성용 `resolve` 대신 `findExisting` 사용 |
| `solution/security/SolutionSecurityConfig.java` | SOL 전용 체인. 변경 요청은 회원·비회원 모두 CSRF 검사. messages는 차단 중 | 정확한 POST 경로만 공개. AUTH 공통 체인 수정 불필요 |
| `solution/repository/SolutionSessionRepository.java` | `findOwned`가 회원·익명 소유권 조건으로 조회 | 조회 재사용, 상태·소유자·버전 조건부 UPDATE 추가 |
| `solution/domain/SolutionSession.java` | 사실·카드·질문 읽기, `@Version` 존재. 갱신 메서드 없음 | 불변 갱신 후보 생성과 저장 형식 검증 경계 추가 |
| `solution/domain/SolutionFacts.java`, `SolutionQuestion.java` | 4개 사실 검증, 첫 UNKNOWN 질문. 병합·건너뛰기 없음 | 알려진 값 보호, 선택 적용, UNKNOWN 답변 후 질문 진행 구현 |
| `solution/domain/SolutionMessage.java` | `selectedCode` 필드는 있으나 설정 수단 없음. `factKey` 컬럼 없음 | 선택 메시지 팩토리·factKey·요청 ID 조회 수단 추가 |
| `solution/repository/SolutionMessageRepository.java` | UUID 존재 검사·전체 이력·건수 조회 | 마지막 순번, UNKNOWN 선택 이력 조회 추가. count로 다음 순번 계산하지 않음 |
| `solution/service/SensitiveDescriptionGuard.java` | 민감정보 패턴 발견 시 입력 거부. 완전한 개인정보 탐지는 보장하지 않음 | TEXT 전송 전에 재사용. 원문 마스킹 저장기로 취급하지 않음 |
| `solution/domain/ResponseRules.java`, `ResponseCardCatalog.java` | 송금 YES·앱 설치 YES 카드 규칙, 검토 카드 없으면 실패 | 동일 규칙·현재 검토 카드 사용. 임의 카드·기관·연락처 생성 금지 |
| `solution/content/UnavailableResponseCardCatalog.java` | 운영 구현은 모든 카드 조회가 비어 있음 | 실제 콘텐츠 연결 전 카드 필요 조합은 계속 503. 테스트 카탈로그를 운영 승인으로 간주하지 않음 |
| `ai/AiClient.java`, `ai/HttpAiClient.java`, `ai/dto/SolutionExtractRequest.java` | AI-06 요청·응답·HTTP 타입 검사 구현. 공통 응답 제한 20초 | 기존 `extractSolutionFacts` 호출. ai 패키지가 solution 도메인을 import하지 않음 |
| `common/error/ErrorCode.java`, `GlobalExceptionHandler.java` | `DUPLICATE_REQUEST`는 존재. `STATE_VERSION_CONFLICT`·`AI_SERVICE_UNAVAILABLE`는 없음. 전역 낙관적 잠금은 훈련 오류로 변환 | SOL 전용 오류 추가·변환. 기존 훈련 오류 코드를 일괄 변경하지 않음 |

현재 비회원은 **GET `/api/solutions/csrf` → 같은 SESSION 쿠키·반환 헤더로 POST** 순서다. `/api/auth/csrf`는 회원 전용으로 유지돼 있다. 과거 SOL-01 계획의 AUTH 확장안보다 실제 SOL 전용 구현을 우선한다.

## 3. 요청 계약과 검증

본문은 필수 JSON 객체다. SOL-01의 설명 생략 시작 규칙을 적용하지 않는다. `JsonNode`에서 실제 타입을 검사하고 검증된 DTO로 변환한다. 숫자 문자열·boolean 문자열을 자동 변환하지 않는다.

| 필드 | 규칙 |
|---|---|
| `requestId` | 필수 문자열. `[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}` 정규식으로 검사한 뒤 UUID로 변환. `UUID.fromString`만으로 짧은 UUID를 허용하지 않음. 소문자·대문자는 동일 UUID로 취급 |
| `stateVersion` | 필수 실제 JSON 정수. 0 이상 2,147,483,646 이하. 성공 시 +1을 위해 int 최댓값은 요청에서 거부. 문자열·소수·boolean·null 거부 |
| `inputKind` | 필수 문자열 `TEXT` 또는 `CHOICE`만 허용. DB 공통 enum의 `ACTION`·`OPTION`은 공개 입력으로 거부 |
| `content` | TEXT에서만 필수. 공백만이면 거부, 원래 문자열 길이 1,000자 이하. CHOICE에서는 필드 존재 자체를 거부 |
| `factKey` | CHOICE에서만 필수. 4개 허용 사실 키이며 저장된 현재 질문의 factKey와 일치해야 함. TEXT에서는 거부 |
| `selectedCode` | CHOICE에서만 필수. 현재 질문 options에 있는 YES/NO/UNKNOWN 문자열. TEXT에서는 거부 |

허용하지 않은 키, 다른 입력 종류의 키, 명시적 null은 400 `INVALID_REQUEST`다. TEXT 길이 기준은 AI-06과 동일하게 Java `String.length()`를 사용한다. DTO의 `toString`에는 원문 대신 입력 종류·길이·requestId만 넣는다. JSON 문법 오류·본문 없음·배열 본문은 400, 비JSON 본문은 415로 검증한다.

CHOICE 예시(저장된 현재 질문이 sharedInformation이고 버전이 2인 경우):

```json
{
  "requestId": "b2ef722e-950b-48e3-bd1c-950870aa6f33",
  "stateVersion": 2,
  "inputKind": "CHOICE",
  "selectedCode": "NO",
  "factKey": "sharedInformation"
}
```

TEXT 예시:

```json
{
  "requestId": "be89911c-1f45-4a57-8a80-4e9e849d9a77",
  "stateVersion": 2,
  "inputKind": "TEXT",
  "content": "송금 후 연락이 끊겼어요."
}
```

검사 순서는 **CSRF → ID 형식(404) → 본문 형식(400 또는 415) → 소유권(404)**로 고정한다. messages 컨트롤러는 자동 `@RequestBody` 변환에 앞서 ID를 검사하기 위해 요청 스트림을 직접 읽으며, JSON 문법 오류도 ID 검사 뒤 처리한다. 본문은 최대 16KiB만 읽어 크기를 제한한다.

CSRF는 컨트롤러보다 먼저 검사된다. 따라서 토큰이 없으면 잘못된 ID나 본문이라도 먼저 403이 될 수 있다. 소유권에 따른 동일 404 정책은 CSRF와 요청 구문 검사를 통과한 요청에 적용한다.

## 4. 소유권·입력 반영·질문 정책

### 4.1 소유권과 상태

- ID 파싱은 SOL-02 방식을 재사용한다. 없는 상담·남의 상담·기존 익명 소유자 없음·잘못된 ID 형식은 404 `SOLUTION_NOT_FOUND`다.
- `findExisting`과 `findOwned`를 사용하고 POST를 이유로 새 익명 ID를 만들지 않는다. CSRF 토큰 발급으로 HTTP 세션이 생겨도 상담 소유권이 자동으로 생기지는 않는다.
- 로그인 회원 principal이 있으면 활성 회원으로만 처리한다. 남아 있는 익명 UUID로 우회하거나 로그인 전 상담을 자동 이관하지 않는다. 비활성 회원은 기존 AUTH 오류를 유지한다.
- 제안: ACTIVE만 변경 가능. COMPLETED는 새 SOL 전용 `SOLUTION_FINISHED`(409)로 거부한다. SOL-05 명세가 완료 후 변경 정책을 미정으로 두므로 함께 확정한다.
- 성공한 새 입력은 실제 사실 변화 여부와 무관하게 한 번 반영된 메시지로 취급하고 버전을 정확히 1 올린다. 실패·중복·충돌은 버전과 메시지를 바꾸지 않는다.

### 4.2 CHOICE: AI 미호출

현재 질문이 없으면 400이다. factKey가 현재 질문과 다르거나 options에 없는 선택이면 400이다. 요청 버전이 오래됐으면 질문 검사보다 먼저 409 `STATE_VERSION_CONFLICT`를 반환한다. 사용자 화면이 오래된 경우와 잘못된 선택 요청을 구별한다.

해당 사실 하나만 선택한 값으로 갱신하고 나머지는 유지한다. 공개 CHOICE를 DB `InputKind.OPTION`으로 매핑한다. 다른 사실을 수정하려면 이후 SOL-04를 사용한다.

### 4.3 TEXT: AI-06 추출과 보수적 병합

민감정보 검사 후 `SolutionExtractRequest(content, currentFacts.knownValues(), requestId)`를 만든다. `knownValues()`는 YES/NO인 항목만 포함한다. UNKNOWN은 확인된 사실이 아니므로 제외한다. FastAPI도 이 의미로 처리해야 한다. 과거 대화 원문이나 전체 메시지 배열을 AI-06에 보내지 않는다. UUID는 기존 HTTP 클라이언트가 `X-Request-Id`로 보낸다.

제안: **기존 YES/NO는 출처와 무관하게 유지하고, 기존 UNKNOWN만 AI의 YES/NO로 채운다.** AI의 UNKNOWN은 기존 값을 지우지 않는다. AI가 이미 알려진 값과 반대 값을 제안해도 덮어쓰지 않으며, 정정은 SOL-04 경로에서 사용자가 직접 수행한다. 현재 DB에는 사실별 출처가 없으므로 "CHOICE 확정값만 구별해 보호한다"고 구현했다고 간주하지 않는다. 출처별 정책이 필요해지면 별도 저장 모델을 추가한다.

충돌 제안은 원문·키·값 없이 충돌 개수와 requestId만 로그로 남긴다. 응답 필드는 늘리지 않으므로 프론트는 TEXT 입력 근처에 "기존 사실을 정정하려면 사실 수정 기능을 이용해 주세요"라는 상시 안내를 제공하는 안으로 정리한다. SOL-04가 아직 없으므로 실제 수정 화면 링크는 그 API와 화면이 준비된 후 연결한다. 정정 시도를 감지한 경우만 따로 안내하려면 추후 별도 응답 계약 합의가 필요하다.

AI-06은 네 사실을 모두 반환하므로 결과 전체로 `facts_json`을 교체하면 기존 확정값을 잃을 수 있다. 병합 함수는 solution 도메인에 둔다. 모델이 내용을 정확히 읽는지·추측하지 않는지는 Spring DTO 검증과 별개로 FastAPI 평가가 필요하다.

### 4.4 UNKNOWN 답변과 질문 진행

현재 `SolutionQuestion.next(facts)`만 쓰면 UNKNOWN을 골랐을 때 같은 질문이 반복된다. 제안은 다음과 같다.

1. CHOICE UNKNOWN은 그 사실을 UNKNOWN으로 유지하되 **이번 상담에서 답변을 마친 항목**으로 취급한다.
2. `solution_messages`에 해당 USER 선택의 factKey·selectedCode를 저장한다. UNKNOWN 선택 이력을 집합으로 읽어 다음 질문 후보에서 제외한다.
3. 질문 순서는 기존 transferredMoney → sharedInformation → installedApp → contactContinues를 유지한다. 아직 UNKNOWN이며 UNKNOWN으로 답변을 마치지 않은 첫 항목을 고른다.
4. TEXT 결과가 전부 UNKNOWN이면 답변 완료로 추정하지 않는다. 성공 메시지·버전은 저장하고 현재 질문을 유지한다. TEXT가 사실을 채웠으면 그 사실은 질문 대상에서 제외한다.
5. 더 물을 항목이 없으면 `nextQuestion: null`, 상태는 ACTIVE다. UNKNOWN이 남아 있어도 자동 NO·자동 완료로 바꾸지 않는다. 재확인·정정은 이후 SOL-04에서 처리한다.

SOL-04 구현 시 UNKNOWN으로 직접 정정한 항목을 다시 질문할지, 과거 UNKNOWN 선택을 계속 제외할지 함께 정해야 한다. 이번에는 선택 이력 삭제·수정을 임의로 구현하지 않는다.

## 5. 중복 요청·버전·트랜잭션 설계

### 5.1 requestId 정책: 중복 반영 방지, 원래 성공 응답 재생은 하지 않음

제안: 같은 상담에 이미 저장된 requestId는 **409 `DUPLICATE_REQUEST`**다. 프론트는 SOL-02로 최신 상태를 다시 조회한다. 같은 UUID에 다른 본문을 보내도 같은 중복 오류다. 원문·요청 해시·과거 응답 스냅샷을 추가 저장하지 않는다. requestId는 새 사용자 제출마다 생성하고, 응답 유실로 동일 제출을 재시도할 때만 재사용한다.

소유권 확인 후 중복 검사를 버전 검사보다 먼저 수행한다. 이미 성공한 요청의 옛 버전 때문에 중복을 버전 충돌로 잘못 분류하지 않는다. 미반영 AI 실패·카드 실패 요청의 UUID는 저장하지 않아 같은 UUID로 재시도할 수 있다. 그사이 다른 요청이 성공했다면 버전 충돌을 받고 복원한다.

현재 UNIQUE `(solution_id, client_request_id)`를 최종 보장으로 사용한다. USER 메시지 한 행에만 UUID를 넣고 후속 SYSTEM에는 null을 넣는다. 같은 UUID의 동시 TEXT 두 건은 저장 전 둘 다 AI를 호출할 수 있다. 이번 계획은 AI 호출 1회 보장이 아니라 **DB 반영 최대 1회** 보장이다. 자동 HTTP 재시도는 추가하지 않는다.

### 5.2 조회 → 외부 호출 → 저장 재검증

서비스 진입점은 `@Transactional(propagation = NOT_SUPPORTED)`로 호출자의 트랜잭션을 중단하고, 내부 `TransactionTemplate`으로 짧은 조회·쓰기를 나눈다. AI 호출 중에는 트랜잭션이 없다. 쓰기 트랜잭션의 격리 수준은 **READ COMMITTED로 명시**한다. SOL-02의 REPEATABLE_READ 설정을 가져오지 않는다. 이 격리 수준에서 일반적인 동일 행 CAS 경쟁의 패자는 재평가된 조건에 따라 UPDATE 0행을 받는다. 다른 잠금 실패도 별도로 처리한다.

1. 기존 소유자를 확인한 뒤 짧은 읽기 트랜잭션에서 상담·사실·현재 질문을 불변 스냅샷으로 읽는다. 중복·상태·버전을 검사한다. 엔티티를 네트워크 호출 구간까지 관리 상태로 유지하지 않는다.
2. TEXT라면 민감정보 검사와 AI-06 호출을 트랜잭션 밖에서 수행한다. CHOICE는 AI를 호출하지 않는다.
3. 저장 전 활성 회원 여부·현재 요청의 소유자를 다시 확인한다. 짧은 쓰기 트랜잭션에서 소유권·중복·상태·버전·현재 질문을 다시 검사한다. 첫 조회 후 변경된 상태에 AI 결과를 자동 재적용하지 않는다.
4. 병합 사실, 현재 검토 카드, 질문, 안전한 메시지를 만든다. 필요한 카드가 없으면 상태 갱신 전에 실패한다.
5. 아래 조건부 UPDATE로 상태를 한 번 갱신하고 USER/SYSTEM 메시지를 같은 트랜잭션에 저장한다. 중간 실패 시 전부 롤백한다.

Redis 세션 소유권 확인과 PostgreSQL 커밋은 분산 트랜잭션이 아니다. 저장 전 재검증으로 범위를 줄이되, 커밋 순간과 완전히 동시에 발생한 로그아웃·세션 만료까지 원자적으로 묶는다고 보장하지 않는다.

### 5.3 버전 증가와 순번 경쟁

제안은 **명시적 조건부 UPDATE(CAS)**다. 저장 시 ID·소유자·ACTIVE·기대 버전 조건으로 facts_json·card_codes_json·next_question_json·updated_at과 `state_version = state_version + 1`을 한 SQL에서 변경한다. 갱신 행 수가 1일 때만 메시지를 저장한다. 실제 값이 같은 TEXT 입력도 버전이 한 번 오른다.

`SolutionSession`의 `@Version` 자동 dirty checking UPDATE와 이 SQL을 같은 작업에 섞지 않는다. 관리 엔티티를 변경하지 않고 별도 불변 갱신 값을 넘기며, bulk UPDATE 뒤 영속성 컨텍스트를 정리하고 응답은 갱신 후 DB 버전으로 구성한다. 자동 flush로 이전 엔티티가 덮어쓰지 않는지 DB 테스트로 확인한다. SQL의 JSONB·소유자 분기 구현은 custom repository에 둔다.

동일 버전의 서로 다른 요청이 경쟁하면 하나만 성공한다. UPDATE 0행 또는 잠금 충돌 시 실패 트랜잭션을 종료하고 새 읽기에서 소유권·중복 여부를 확인한다. 없거나 남의 상담이면 404, UUID가 반영됐으면 DUPLICATE_REQUEST, 완료 상태면 SOLUTION_FINISHED, 그 외 버전 충돌이면 STATE_VERSION_CONFLICT로 구별한다. 같은 요청을 자동 재실행하지 않는다.

성공한 상태 UPDATE가 해당 상담 행 잠금을 커밋까지 유지한 상태에서 마지막 메시지 `max(sequence_no)`를 읽고 순번을 배정한다. count는 삭제·결번을 고려하지 못하므로 쓰지 않는다. 기존 순번 UNIQUE도 유지한다. 이후 SOL-04·05도 같은 상담 상태 갱신 경계를 사용해야 한다.

`uq_solution_messages_request`·`uq_solution_messages_sequence` 위반은 저장 트랜잭션 **바깥**에서 이름을 구분해 처리한다. 롤백된 트랜잭션 안에서 재조회하지 않는다. 다른 무결성 오류를 전부 409로 숨기지 않는다. 기존 전역 훈련용 오류 매핑으로 흘려보내지 않도록 SOL 서비스 경계에서 변환한다.

## 6. 저장·응답·오류 계약

### 6.1 DB 저장과 개인정보

- TEXT USER의 safe_content는 SOL-01과 같은 고정 접수 문구를 사용한다. 민감정보 탐지를 통과했더라도 원문을 그대로 저장하지 않는다.
- CHOICE USER는 서버 고정 질문 문구와 YES/NO/UNKNOWN으로 만든 안전한 표현, DB OPTION, selected_code, fact_key를 저장한다. 요청에서 받은 자유 문장을 합치지 않는다.
- **다음 질문이 있고 이전 질문과 달라졌을 때만** SYSTEM 고정 질문 메시지 한 건을 추가한다. TEXT 결과가 전부 UNKNOWN이라 현재 질문이 유지되면 SYSTEM 질문을 반복 저장하지 않는다. 질문이 없으면 불필요한 원문·AI 요약 메시지를 만들지 않는다.
- facts는 정확한 4개 키, 카드 참조는 현재 채택한 `[{code, version}]`, 다음 질문은 기존 구조 또는 NULL로 저장한다. 전체 카드 본문·AI 원시 응답을 메시지에 복사하지 않는다.
- `reference_codes_json`은 선택 factKey 저장 공간으로 전용하지 않는다. 카드 참조 메시지 용도가 확정되기 전에는 기존처럼 null을 유지한다.
- 본문·민감정보·AI 예외 cause는 로그에 남기지 않는다. 필요한 로그는 내부 상담 ID·requestId·오류 코드·처리 시간 정도다. HTTP body 로깅·APM·FastAPI 로그에도 원문이 남지 않도록 별도 확인한다.

새 마이그레이션은 번호 합의 후 `V{합의번호}__add_solution_message_fact_key.sql`로 추가한다. 현재 V3는 익명 소유자 지원에 이미 사용 중이다. V1~V3를 수정하지 않는다. `fact_key VARCHAR(50) NULL`을 추가하고 값이 있으면 허용 4개 키인지 검사하는 CHECK를 둔다. 기존 행은 NULL로 유지하고 과거 선택 factKey를 추정해 채우지 않는다. 과거 OPTION/UNKNOWN 행에 키가 없다면 건너뛰기 이력에 포함할 수 없다는 한계를 명시한다.

### 6.2 성공 응답

성공 HTTP 상태는 **200 OK**다(SOL-01의 201을 재사용하지 않음). 새 `SolutionMessageResponse`는 facts·responseCards·nextQuestion·stateVersion만 반환한다. 카드·질문 항목은 기존 `StartSolutionResponse.Card/Question`을 재사용한다. 배열은 생략하지 않고 질문이 없으면 명시적 null을 보낸다. 소유자·원문·요청 ID·엔티티·내부 카드 버전은 공개하지 않는다.

다음 예시는 **TRANSFER_RESPONSE 검토 카드가 등록된 테스트/운영 환경**에서 sharedInformation에 NO를 답한 경우다. 원 명세의 `responseCards: []`와 달리 현재 ResponseRules는 transferredMoney=YES이면 이 카드를 요구하므로 빈 카드로 성공했다고 예시를 만들지 않는다. 질문 문구도 현재 SolutionFact의 고정 문구를 사용한다.

```json
{
  "success": true,
  "data": {
    "facts": {
      "transferredMoney": "YES",
      "sharedInformation": "NO",
      "installedApp": "UNKNOWN",
      "contactContinues": "NO"
    },
    "responseCards": [
      {
        "code": "TRANSFER_RESPONSE",
        "priority": "URGENT",
        "title": "송금 관련 우선 대응",
        "institutionCodes": ["POLICE", "BANK"]
      }
    ],
    "nextQuestion": {
      "factKey": "installedApp",
      "content": "상대방이 알려준 앱을 설치한 적이 있나요?",
      "options": ["YES", "NO", "UNKNOWN"]
    },
    "stateVersion": 3
  }
}
```

institutionCodes는 검토 카드의 기관 참조 코드다. SOL-03에서 은행 연동 API를 추가하거나 LLM이 기관을 선택한다는 뜻이 아니다. 현재 빈 운영 카탈로그로 위 예시를 호출하면 `RESPONSE_CONTENT_UNAVAILABLE`이며 변경은 롤백돼야 한다.

### 6.3 오류와 프론트 처리

| HTTP·코드 | 조건 | 상태 변경·프론트 처리 |
|---|---|---|
| 400 INVALID_REQUEST | DTO·현재 질문·선택지 불일치 | 변경 없음. 입력 확인 |
| 400 SENSITIVE_DATA_DETECTED | TEXT 민감정보 발견 | AI 전송·변경 없음. 해당 정보를 빼고 다시 입력 |
| 401 기존 AUTH 코드 | 비활성·삭제·유효하지 않은 회원 인증 | 비회원으로 우회하지 않음 |
| 403 CSRF_TOKEN_INVALID | 토큰 없음·불일치 | 변경 없음. CSRF 재발급·쿠키 확인 |
| 404 SOLUTION_NOT_FOUND | 잘못된 ID·상담 없음·다른 소유자·익명 소유자 없음 | 상담 존재 여부 구분하지 않음 |
| 409 STATE_VERSION_CONFLICT(신규) | 오래된 버전·동시 갱신 | 변경 없음. SOL-02 복원 후 재입력 |
| 409 DUPLICATE_REQUEST(기존) | 해당 상담에 이미 반영된 UUID | 추가 변경 없음. SOL-02로 반영 여부 확인 |
| 409 SOLUTION_FINISHED(신규 제안) | 완료 상담 변경 | 변경 없음. 종료 화면으로 안내 |
| 503 AI_SERVICE_UNAVAILABLE(신규) | AI-06 시간 초과·HTTP 오류·계약 위반 | 이번 입력 미저장. 기존 사실·질문 유지. 재시도 또는 현재 질문 CHOICE |
| 503 RESPONSE_CONTENT_UNAVAILABLE | 필요한 검토 카드 누락 | 사실·메시지·버전 모두 미변경. 콘텐츠 복구 필요 |

SOL-01은 AI 실패에도 UNKNOWN으로 상담을 생성하지만, **SOL-03은 기존 상담을 초기화하지 않고 503을 반환**하는 제안이다. DTO 구성 실수·손상된 DB JSON 등 프로그램/데이터 오류를 AI 장애로 숨기지 않는다. 실제 서버 오류는 500으로 처리하고 입력 원문을 오류 응답에 넣지 않는다.

성공은 `ApiResponse.ok`, 오류는 기존 공통 `ErrorResponse`의 `{code,message}`를 유지한다. 오류까지 success:false 봉투로 바꾸는 것은 별도 공통 계약 작업이다. Spring Security가 붙이는 `Cache-Control`의 no-store를 성공·오류에서 검증한다. 프론트는 409 후 자동으로 이전 입력을 최신 버전에 덧씌우지 않는다.

## 7. 파일별 개발 순서와 이후 참고사항

경로의 공통 접두사는 `src/main/java/com/wsid/`다. 신규 이름은 구현 시 확정한다.

| 순서 | 파일 | 작업·상호작용·후속 개발 시 주의점 |
|---|---|---|
| 1 | `solution/dto/SendSolutionMessageRequest.java`(신규), `SolutionMessageResponse.java`(신규) | JsonNode 엄격 검사, TEXT/CHOICE 분리, 원문 없는 toString. 프론트는 공개 CHOICE만 사용 |
| 2 | `solution/domain/SolutionFacts.java`, `SolutionQuestion.java` | 불변 선택·AI 병합 함수와 질문 제외 집합 입력 추가. SOL-01의 기존 `next(facts)` 호출은 그대로 유지 |
| 3 | `solution/domain/SolutionSession.java`, `SolutionMessage.java` | 세션 갱신 후보 직렬화·선택 팩토리·factKey 추가. 기존 시작 팩토리와 SOL-02 read* 호환 유지 |
| 4 | `src/main/resources/db/migration/V{합의번호}__add_solution_message_fact_key.sql` | 기존 데이터 유지·허용 키 CHECK. DB 담당과 마이그레이션 번호 예약 |
| 5 | `solution/repository/SolutionSessionRepository.java` + custom repository, `SolutionMessageRepository.java` | 소유권·ACTIVE·버전 CAS, 중복 조회·최대 순번·UNKNOWN 선택 이력. SOL-04·05도 같은 버전 경계 준수 |
| 6 | `solution/service/SendSolutionMessageService.java`(신규) | 조회/AI/저장 분리, 소유권 재검증, 실패 롤백·오류 매핑. StartSolutionService를 복사해 AI 실패 초기화까지 가져오지 않음 |
| 7 | `common/error/ErrorCode.java` | 신규 SOL 오류 추가. 전역 훈련용 SESSION_VERSION_CONFLICT·SESSION_FINISHED는 유지. GlobalExceptionHandler는 필요한 경우만 제한적으로 수정 |
| 8 | `solution/controller/SolutionController.java`, `solution/security/SolutionSecurityConfig.java` | messages POST 연결·정확한 경로 공개. 기존 ID 검사·findExisting·CSRF·CORS 공유. AUTH 담당 파일 수정 불필요 |
| 9 | `src/test/java/com/wsid/solution/` | 아래 테스트 추가·확장. SOL-03을 무조건 차단한다고 검사하던 기존 보안 테스트 기대값도 수정 |
| 10 | `docs/sol-03-session-message.md`(구현 후 신규), `docs/api.md`, `solution/README.md`, `docs/README.md` | 실제 변경 파일·결정 정책·검증 결과·FastAPI와 콘텐츠 미연결 범위를 보고. 계획서를 구현 보고서로 덮어쓰지 않음 |

SOL-02 공개 메시지는 현재 5개 필드(sequenceNo/sender/inputKind/content/selectedCode)다. factKey DB 추가 때문에 공개 응답을 임의로 늘리지 않는다. 기존 OPTION→CHOICE 변환을 재사용하며 선택 기록이 복원되는지 확인한다. 다음 질문 재계산에는 원문 재분석 대신 저장 factKey를 사용한다.

FastAPI 저장소는 이 프로젝트에 없다. 아래는 실제 경로가 아닌 **역할별 디렉토리 제안**이다.

| FastAPI 역할·디렉토리 제안 | SOL-03과 맞출 내용 |
|---|---|
| `routers/solutions.py` | 기존 POST `/internal/ai/solutions/extract` 재사용. SOL-03 공개 API를 FastAPI에 중복 구현하지 않음 |
| `schemas/solutions.py` | description·knownFacts 요청, 네 사실·uncertainFields 응답. success는 실제 boolean, 사실 값은 실제 문자열 |
| `services/solution_extract.py`, `prompts/` | knownFacts를 고려하되 언급 없는 사실 추정 금지. UNKNOWN을 NO로 바꾸지 않음. 알려진 값 보호는 Spring도 수행 |
| 미들웨어·로그 설정 | X-Request-Id 추적, description·LLM 프롬프트/응답 원문 로그 금지, 처리 시간·실패 코드만 기록 |
| 계약·모델 평가 테스트 | 실제 JSON 타입·불확실성·민감정보 처리·타임아웃 검증. 정상 문장/부정문/모순 문장 평가. Spring fake 성공을 실제 LLM 정확도 증거로 삼지 않음 |

## 8. 구현·검증 계획

| 범위 | 주요 확인 사항 |
|---|---|
| 요청 DTO 단위 테스트 | 정상 TEXT/CHOICE, 표준 UUID·대소문자·짧은 `1-1-1-1-1` 거부, 정수 경계·문자열 버전 거부, 잘못된 키·null·교차 필드·빈 본문, content 1,000/1,001자, toString 원문 없음 |
| 도메인 단위 테스트 | CHOICE 한 사실만 변경, TEXT는 UNKNOWN만 보완, 기존 YES/NO·사용자 선택 보호, AI UNKNOWN/반대값, 질문 순서·UNKNOWN 건너뛰기·전부 답변 후 null, 기존 SOL-01 질문 유지·같은 질문 SYSTEM 중복 없음 |
| 서비스 단위 테스트 | 소유권/중복/버전 사전 거부 시 AI 미호출, CHOICE AI 미호출, TEXT knownFacts·UUID 전달, AI 호출 중 트랜잭션 비활성, 저장 전 재검증, AI·카드 실패 시 무저장 |
| 개인정보 테스트 | 민감정보는 AI 미전송, 일반 TEXT도 원문 미저장, 고정 safe_content·로그·예외 원문 없음. 실제 이름 등 guard 탐지 범위 밖 문자열도 DB에는 원문 미저장 |
| PostgreSQL dbTest | 새 fact_key·CHECK·기존 행 호환, CAS의 정확한 버전 +1, 내용이 같은 TEXT도 +1, 첫 순번·결번 뒤 max+1, UUID/순번 유일, 중간 저장 실패 전체 롤백, DB 재조회 후 JSON·버전 일치 |
| 동시성 dbTest | 서로 다른 두 UUID/같은 버전은 성공 1+충돌 1, 같은 UUID 동시 요청은 반영 1+중복 1, 승자만 USER/SYSTEM 저장, 최신값 덮어쓰기 없음·쓰기 READ COMMITTED 확인. 별도 트랜잭션·동기화 장치로 실제 경쟁 재현 |
| 웹·보안 테스트 | 회원·비회원 정상, 다른 세션·쿠키 분실·로그인 후 과거 익명 상담 404, 비활성 회원 AUTH 오류, 토큰 없음/다른 세션 토큰 403, 경로별 허용 범위·no-store·본문·응답 타입·잘못된 ID+깨진 JSON은 404, 올바른 ID+깨진 JSON은 400 |
| 실제 Redis·PostgreSQL integrationTest | 비회원 CSRF→시작→메시지→SOL-02 복원, 선택 저장·버전·고정 문구, 세션 만료 후 차단, 기존 AUTH/시작/복원 회귀 |
| AI 장애 전송 검증 | 기존 AI-06 localhost HTTP 테스트와 연계. 서비스 통합에서는 지연·5xx·깨진 JSON을 반환하는 로컬 FastAPI 대역으로 503/상태 무변경을 확인. 별도 테스트 설정으로 지연 시간을 줄임 |
| 실제 FastAPI 연결(별도) | 실제 서버의 경로·헤더·계약·LLM 추출 품질 확인. FastAPI 미구현이면 미검증으로 보고 |

DB 테스트는 mock repository 통과만으로 끝내지 않는다. 특히 CAS·JPA 영속성 컨텍스트·JSONB·유일 제약·동시성은 실제 PostgreSQL에서 검증한다. 테스트 AI는 엄격한 기존 fake 문장 또는 Mockito 설정으로 결과를 명시한다. AI-06 fake는 임의 설명을 자연어 분석하지 않으며, `[AI_FAIL]` 실패 표식을 사용할 수 있다.

실행은 `./gradlew test`, 프로젝트 전용 PostgreSQL 설정의 `./gradlew dbTest`, 전용 PostgreSQL·Redis 설정의 `./gradlew integrationTest`, `./gradlew bootJar` 순서로 한다. 다른 프로젝트의 기본 5432/6379 인스턴스에 마이그레이션·세션을 쓰지 않는다. 외부 저장소가 없으면 실행하지 못한 항목을 구분해 보고한다. 최초 계획 작성 당시에는 테스트를 실행하지 않았다. 구현 후 수행한 Gradle·DB·실제 HTTP 검증은 구현 보고서에 기록한다.

## 9. 구현 전 확정할 정책

1. **재시도:** 제안한 DUPLICATE_REQUEST 409 + SOL-02 복원을 프론트와 합의한다. 같은 성공 응답을 200으로 재생해야 한다면 응답 스냅샷·요청 동일성 검사·보관 정책을 포함해 설계를 바꿔야 한다.
2. **사실과 질문:** 기존 YES/NO를 TEXT로 바꾸지 않고 SOL-04로 정정하는 정책, UNKNOWN 선택 후 다음 항목 진행, 재확인 화면 흐름과 TEXT 정정 시도의 SOL-04 안내 방식을 확정한다. 충돌 로그에는 개수만 남긴다.
3. **실패와 종료:** SOL-03 AI 실패 503/무저장, 완료 후 변경 409 SOLUTION_FINISHED, 신규 공개 오류 코드를 확정한다. SOL-01의 AI 실패 폴백과 차이를 프론트에 문서화한다.
4. **카드:** 명세의 송금 YES + 빈 카드 예시를 현재 카드 규칙과 맞춘다. SOL-01·02와 같은 전체 카드 DTO 사용, 검토 카드 등록 주체·현재 빈 카탈로그 교체를 콘텐츠 담당과 합의한다. 질문 문구도 명세의 "상대 안내로 앱을 설치했나요?"와 코드의 "상대방이 알려준 앱을 설치한 적이 있나요?" 중 코드 문구를 이번 구현에서 유지하되, 명세 정정/문구 통일은 콘텐츠 담당과 확정한다. 운영 카드 승인 전 송금/앱 설치 YES 성공 경로를 완료로 표시하지 않는다.
5. **DB:** fact_key 추가와 과거 NULL 처리, CAS 구현·다른 쓰기 API와 버전 규칙 공유, 마이그레이션 번호를 확정한다. 이후 SOL-04·05의 독립 구현이 이 규칙을 우회하지 않게 한다.
6. **FastAPI·시간 제한:** 기존 AI-06 계약과 원문 로그 정책을 확인한다. SOL-03 사용자 요청 안에서 공통 20초 응답 제한이 적절한지 실제 지연 측정 후 결정하고, 필요 시 AI-06 전용 클라이언트 설정으로 별도 조정한다.

## 10. 완료 기준

- TEXT/CHOICE가 회원·비회원 본인 상담에만 적용되고 CSRF가 모두 검사된다.
- 성공한 새 입력마다 버전이 정확히 1 증가하며, 중복·충돌·외부 장애는 부분 저장을 남기지 않는다.
- 원문이 DB·세션·Redis·로그·에러 응답에 저장되지 않는다. 브라우저 복원은 고정 safe_content와 선택 코드만 사용한다.
- 알려진 사실 유지·카드 재판정·UNKNOWN 답변 후 질문 진행이 합의된 정책대로 동작한다.
- 단위·DB·실제 Redis 통합·패키징 검증 결과를 각각 기록하고 SOL-01·02·AUTH 회귀를 확인한다.
- 구현 보고서에 실제 상호작용 파일·향후 SOL-04/05 참고점·카드/FastAPI 미완료 범위를 명시한다. 실제 FastAPI/LLM을 검증하지 않았다면 연결 완료로 쓰지 않는다.
