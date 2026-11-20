# SOL-04 피해 사실 수정 구현 보고서

작성일 2026-11-17 · 브랜치 OHC(`d66d8b3` 위 작업, 미커밋) · 계획서: [SOL-04 구현·테스트 계획서](sol-04-facts-update-plan.md) · 앞 단계: [SOL-03 구현 보고서](sol-03-session-message.md)

`PATCH /api/solutions/sessions/{id}/facts`로 사용자가 자기 상담의 피해 사실을 직접 고쳐요. 고친 전체 사실로 대응 카드와 다음 질문을 다시 계산해 저장하고, **응답은 SOL-03과 같은 모양**(`facts`, `responseCards`, `nextQuestion`, `stateVersion`)으로 돌려줘요. AI·FastAPI는 부르지 않아요.

## 1. 확정한 결정

| 결정 | 내용 |
|---|---|
| 응답에 `nextQuestion` 포함 | 명세 개요("카드·후속질문 재계산")와 맞추고, 고친 뒤 옛 질문이 화면에 남지 않게 해요. PATCH 뒤 SOL-02를 다시 부를 필요가 없어요 |
| 카드 형식 | SOL-01~03과 같은 전체형(`code, priority, title, institutionCodes`). 새 응답 DTO 없이 `SolutionMessageResponse`를 같이 써요 |
| 응답 형식 공유 | 프론트에 2026-11-17 공유 완료. 명세 예시 정정은 남은 일 |
| 기존 YES/NO 변경 | 직접 정정은 YES↔NO, YES/NO→UNKNOWN, UNKNOWN→YES/NO 모두 가능. SOL-03의 AI 병합 보호(`mergeExtracted`)는 그대로 |
| UNKNOWN으로 고침 | "지금은 모름"이라는 답으로 보고 이 상담에서 다시 묻지 않아요(SOL-03 UNKNOWN 선택과 같음) |
| 수정 기록 | `solution_messages`에 사실별 USER 기록을 남겨요. 그래야 UNKNOWN 정정이 이후 SOL-03에서도 유지돼요 |
| 같은 값 재전송 | 확인 요청으로 받아 버전 +1. 한 PATCH에 네 항목을 보내도 +1 |
| 저장된 카드 참조 | 읽지 않아요. 카드는 고친 사실로 새로 계산해요(SOL-03과 같음) |
| 중복 JSON 키 | SOL-03·04 모두 400. 공유 파서에서 처리 |
| 마이그레이션 | 없음. V4의 `fact_key`를 그대로 써요 |

## 2. API 계약

요청:

```json
{"stateVersion": 3, "changes": {"installedApp": "YES"}}
```

- 키는 `stateVersion`, `changes` 두 개뿐이에요. `requestId`·`content` 같은 다른 키가 있으면 400이에요.
- `stateVersion`은 실제 JSON 정수 0~2,147,483,646이에요.
- `changes`는 1~4개 항목이에요. 키는 4개 사실 키 중 하나, 값은 문자열 `YES`/`NO`/`UNKNOWN`만 받아요. 하나라도 틀리면 전체를 거부해요.
- 본문은 최대 16KiB, `application/json`만 받아요(그 외 415). 후행 JSON과 중복 키는 400이에요.

응답(200, 송금 YES·앱 설치 YES로 고쳤고 두 카드가 등록된 환경):

```json
{
  "success": true,
  "data": {
    "facts": {"transferredMoney": "YES", "sharedInformation": "NO", "installedApp": "YES", "contactContinues": "UNKNOWN"},
    "responseCards": [
      {"code": "TRANSFER_RESPONSE", "priority": "URGENT", "title": "...", "institutionCodes": ["POLICE", "BANK"]},
      {"code": "MALICIOUS_APP_RESPONSE", "priority": "URGENT", "title": "...", "institutionCodes": ["POLICE"]}
    ],
    "nextQuestion": {"factKey": "contactContinues", "content": "상대방과 지금도 연락하고 있나요?", "options": ["YES", "NO", "UNKNOWN"]},
    "stateVersion": 4
  }
}
```

카드의 priority·title·기관은 규칙이 아니라 등록된 카드 콘텐츠 값이에요. 더 물을 항목이 없으면 `nextQuestion`은 `null`이고 상담은 ACTIVE로 남아요.

검사 순서는 **CSRF(403) → ID 형식(404) → 본문(400·415) → 소유권(404) → 상태(409 `SOLUTION_FINISHED`) → 버전(409 `STATE_VERSION_CONFLICT`)**이에요.

| 응답 | 상황 | 변경 |
|---|---|---|
| 200 | 본인 ACTIVE 상담, 최신 버전 | 상태·기록 저장, 버전 +1 |
| 400 `INVALID_REQUEST` | 본문 형식·키·값 오류, 중복 키 | 없음 |
| 401 `LOGIN_REQUIRED` | 로그인 principal이 있으나 비활성 회원 | 없음 |
| 403 `CSRF_TOKEN_INVALID` | 토큰 없음·다른 세션 토큰 | 없음 |
| 404 `SOLUTION_NOT_FOUND` | 잘못된 ID, 없는 상담, 남의 상담, 익명 소유자 없음, 로그인 뒤 이전 익명 상담 | 없음 |
| 409 `STATE_VERSION_CONFLICT` | 오래된 버전, 동시 수정(SOL-03 포함)에서 짐 | 없음 |
| 409 `SOLUTION_FINISHED` | 완료된 상담 | 없음 |
| 503 `RESPONSE_CONTENT_UNAVAILABLE` | 고친 사실에 필요한 검토 카드가 없음 | 없음(전체 롤백) |

SOL-04에는 요청 ID가 없어서 `DUPLICATE_REQUEST`가 없어요. 응답을 못 받고 같은 본문을 다시 보내면 409가 나와요. 이때 프론트는 SOL-02로 현재 상태를 확인하고, 자동으로 재전송하지 않아요.

## 3. 처리 흐름

```text
SolutionController.updateFacts(id 문자열)
  ├─ parseId → 404
  ├─ strictBody: 16KiB·Content-Type·후행 JSON·중복 키 검사(SOL-03과 공유)
  ├─ UpdateSolutionFactsRequest.from: 키·타입·값 엄격 검사 → 불변 EnumMap
  └─ UpdateSolutionFactsService.update(id, 요청, () -> SolutionOwnerResolver.findExisting(...))
        ├─ 소유자 없음 → 404(DB 조회 없음)
        └─ 쓰기 트랜잭션 1개(READ COMMITTED)
             · findOwned → 404 / ACTIVE 아님 → 409 / 버전 다름 → 409
             · readFacts().withChanges(changes)
             · 제외 집합 = answeredUnknownKeys(저장 이력) + 이번 UNKNOWN 정정
             · SolutionQuestion.next(사실, 제외 집합), ResponseRules.cards → 카드 없으면 503
             · compareAndSet(id, 소유자, ACTIVE, 기대 버전) → 0행이면 경쟁 패배
             · 정정 기록(사실 선언 순서) + 질문이 바뀌었을 때만 SYSTEM 질문 저장 → flush
        └─ 경쟁 패배·순번 충돌 → 트랜잭션 종료 뒤 새 읽기에서 404/409 분류
```

- AI 호출이 없어서 SOL-03처럼 조회와 저장을 나누지 않고 짧은 쓰기 트랜잭션 하나에서 끝내요.
- 같은 버전으로 SOL-04 두 건, 또는 SOL-03과 SOL-04가 경쟁하면 한 건만 반영돼요. 서로 다른 사실을 고쳤어도 자동으로 합치지 않아요.
- SOL-03 TEXT가 AI 응답을 기다리는 사이 SOL-04가 먼저 저장되면, 늦은 SOL-03은 저장 직전 버전 검사에서 409가 나고 사용자가 고친 값은 그대로예요(DB 테스트로 확인).

### 정정 기록

| 컬럼 | 값 |
|---|---|
| sender / input_kind | USER / OPTION(SOL-02에서는 `CHOICE`로 보여요) |
| fact_key / selected_code | 고친 사실 키 / YES·NO·UNKNOWN |
| client_request_id | NULL(SOL-04에는 요청 ID가 없음. 기존 UNIQUE는 NULL 여러 개를 허용) |
| safe_content | `사실 수정: ` + 서버 고정 질문 문구 + ` 선택: ` + 값. 요청 문자열은 넣지 않아요 |

SOL-02 복원에서는 SOL-03 선택 답변과 같은 `CHOICE`로 보이고 `content`의 `사실 수정: ` 접두사로만 구별돼요. 프론트가 구조적으로 구별해야 하면 공개 메시지 필드를 따로 정해야 해요.

## 4. 파일별 상호작용

### 새로 만든 파일

| 파일 | 부르는 곳 | 이 파일이 쓰는 것 |
|---|---|---|
| `solution/dto/UpdateSolutionFactsRequest` | `SolutionController.updateFacts`, 서비스 | `SolutionFact.fromKey`, `FactValue`, `ErrorCode.INVALID_REQUEST` |
| `solution/service/UpdateSolutionFactsService` | `SolutionController.updateFacts` | `SolutionSessionRepository.findOwned`·`compareAndSet`(SOL-03 CAS), `SolutionMessageRepository.answeredUnknownKeys`·`lastSequence`·`save`·`flush`, `SolutionFacts.withChanges`, `SolutionQuestion.next`, `ResponseRules`·`ResponseCardCatalog`, `SolutionSession.prepareUpdate`·`readFacts`·`readNextQuestion`, `SolutionMessage.correction`, `SolutionMessageResponse` |

### 고친 파일

| 파일 | 변경 | 영향 |
|---|---|---|
| `solution/controller/SolutionController` | `PATCH /sessions/{id}/facts` 추가. 본문 읽기 함수를 `strictBody`로 이름을 바꾸고 중복 키 검사(`STRICT_DUPLICATE_DETECTION`) 추가 | SOL-03도 중복 키가 400이 됨(회귀 테스트 추가). SOL-01은 기존 바인딩이라 영향 없음 |
| `solution/security/SolutionSecurityConfig` | facts PATCH만 `permitAll`. SOL-05는 계속 차단 | CSRF는 기존 기본값으로 회원·비회원 모두 검사. CORS는 AUTH 설정이 이미 PATCH 허용 |
| `solution/domain/SolutionFacts` | `withChanges`(여러 사실 직접 정정, 불변) | `withChoice`·`mergeExtracted`(SOL-03)는 그대로 |
| `solution/domain/SolutionMessage` | `correction` 팩토리, `CORRECTION_PREFIX` | `answeredUnknownKeys`가 OPTION+UNKNOWN+fact_key를 읽어 SOL-03 질문 진행에도 반영 |
| `solution/dto/SolutionMessageResponse` | 설명만 "SOL-03·04 공통"으로 | 필드 변경 없음 |

### 그대로 쓰는 기존 코드

| 파일 | 역할 |
|---|---|
| `SolutionOwnerResolver.findExisting`, `AnonymousOwnerIssuer.existingOwner`, `LoginService.requireActiveUser`(LSH) | 기존 소유자만 확인. 새 익명 UUID를 만들지 않음. 로그인 회원 우선, 비활성이면 401 |
| `SolutionSessionUpdatesImpl.compareAndSet`(SOL-03) | 소유자·ACTIVE·버전 조건 UPDATE, `state_version + 1`, JPA 캐시 정리 |
| `SolutionMessageRepository.answeredUnknownKeys`·`lastSequence`(SOL-03) | UNKNOWN 답변 이력, 다음 순번(max+1) |
| `SolutionQueryService`(SOL-02) | 정정 뒤 복원. 저장된 카드 참조로 현재 카드를 보여줘요 |
| `ErrorCode`(공통, LSH 정의 포함) | 새 오류 코드 없음 |

AUTH 파일(`LoginController`, `SecurityConfig`), 공통 오류 코드, 마이그레이션은 바뀌지 않았어요.

## 5. 테스트 결과

DB·Redis 테스트는 전용 컨테이너(PostgreSQL 127.0.0.1:55432, Redis 127.0.0.1:56379)에서만 실행했어요. 실행 명령은 [SOL-01 보고서](sol-01-session-start.md) 9장과 같아요.

| 실행 | 결과 | SOL-04로 추가한 테스트 |
|---|---|---|
| `./gradlew test` | **799건 통과**(756 + 43) | `UpdateSolutionFactsRequestTest` 29건: 1·4개 정정, 선언 순서, 불변 Map, toString에 값 없음, 버전 경계, 잘못된 키·값·타입·대소문자·추가 키 27종 전체 거부 · `UpdateSolutionFactsServiceTest` 11건: 알려진 값 정정과 카드 2장 재계산(전체 카드형), YES→NO 카드 제거, UNKNOWN 정정 후 다음 질문·전부 답하면 null/ACTIVE, 같은 값 +1·질문 반복 없음, 4개 정정 +1·기록 순서, 손상된 카드 참조 무시, 카드 없음 503·CAS 없음, 소유자·버전·완료 실패 무변경, CAS 0행 → 롤백 후 409, 관련 없는 DB 오류는 숨기지 않음, 도메인 불변·AI 병합 보호 유지 · `SolutionWebSecurityTest` 3건: 실제 두 보안 체인에서 비회원 200·전체 카드·no-store·SOL-02 복원 기록·같은 버전 재전송 409, CSRF→ID→본문→소유권 순서, 다른 세션 404·다른 토큰 403, **중복 키 400(SOL-04·SOL-03)** |
| `./gradlew dbTest` | **33건 통과**(25 + 8) | `SolutionFactsDatabaseTest`: 버전 +1·NULL UUID 기록·복원 일치, UNKNOWN 정정이 이후 SOL-03 선택에서도 유지, flush 실패 시 상태·기록 전체 롤백, 오래된 버전·남의 상담·완료 무변경, 서로 다른 사실 동시 정정은 1건만(자동 병합 없음), SOL-04와 SOL-03 동시 경쟁 1건만, READ COMMITTED에서 패자 UPDATE 0행, AI 대기 중 정정 → 늦은 SOL-03 409·정정값 유지, 회원 소유자 조건 |
| `./gradlew integrationTest` | **28건 통과**(27 + 1) | 실제 서버·Redis: 비회원 CSRF → 시작 → PATCH 200(no-store) → SOL-02 복원 일치·정정 기록, 운영 빈 카탈로그에서 카드 필요 정정 503·무변경, 같은 버전 재전송 409, 다른 세션 404, 토큰 없음 403, 고친 버전으로 SOL-03 선택 이어짐 |
| `./gradlew bootJar` | 성공 | |

실행 후 Redis 상담 키 0개, 두 테스트 DB의 상담 행 0개예요.

## 6. 남은 일

1. **명세 정정(명세 담당):** SOL-04 응답 예시에 `nextQuestion`과 전체 카드 필드 추가, 송금 YES + 앱 설치 YES면 카드 두 장. 오류 목록에 404·409 `SOLUTION_FINISHED`·503 추가.
2. **프론트 확인:** SOL-02 복원에서 정정 기록이 `CHOICE` + `사실 수정: ` 접두사로 보이는 표시 방식, 409 뒤 GET 후 사용자가 다시 고치는 흐름, UNKNOWN으로 고치면 다시 묻지 않는다는 안내.
3. **콘텐츠:** 운영 카드가 비어 있어 송금 YES·앱 설치 YES로 고치면 503이에요. 카드 등록 전까지 이 경로는 완료로 보지 않아요.
4. **SOL-05:** 같은 `compareAndSet` 버전 경계를 쓰고, 종료 뒤 SOL-03·04는 `SOLUTION_FINISHED`예요.
5. **"다시 물어봐 달라"는 기능이 필요하면:** 지금은 UNKNOWN 이력을 지우지 않아서 다시 묻지 않아요. 바꾸려면 SOL-03 질문 진행과 저장 모델을 함께 설계해야 해요.
