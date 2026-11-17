# SOL-02 피해 대응 상담 복원 구현 보고서

작성일 2026-11-17 · 브랜치 OHC(`e8f1300` 위 작업) · 계획서: [SOL-02 구현·테스트 계획서](sol-02-session-get-plan.md) · 앞 단계: [SOL-01 구현 보고서](sol-01-session-start.md)

`GET /api/solutions/sessions/{id}`로 상담의 현재 상태·사실·대응 카드·다음 질문·기록 메시지를 다시 불러와요. 새로고침·뒤로 가기·앱 복귀·409 충돌 뒤처럼 화면 상태를 잃었을 때, 프론트가 주소의 상담 ID로 호출해요. 같은 작업에서 **LSH AUTH 파일을 원래대로 되돌리고** 상담 보안 설정을 SOL 전용 파일로 옮겼어요(2장).

## 1. 확정한 결정

| 결정 | 내용 |
|---|---|
| 로그인 뒤 이전 비회원 상담(결정 3) | **A: 회원 상담만 조회.** 로그인 전 익명 상담은 지워지지 않지만 404이고, 새 상담을 처음부터 다시 시작해요. 프론트는 상담 중 로그인 전에 "로그인하면 지금 상담은 이어 볼 수 없어요" 안내 |
| 카드 복원 기준(결정 2) | **A: 저장된 카드 코드로 현재 검토 카드를 보여줌.** DB의 카드 version은 기록용이고 화면에 쓰지 않아요. 카드가 카탈로그에서 없어졌으면 빼지 않고 503 |
| LSH 코드 | **수정하지 않음.** `/api/solutions/**` 보안은 `SolutionSecurityConfig`가 따로 맡아요 |
| 메시지 항목 형식(결정 1) | 추천안 `sequenceNo·sender·inputKind·content·selectedCode`로 구현. 프론트 확인 대기 |
| 잘못된 id 형식 | 400이 아니라 같은 404 |

## 2. LSH 파일 원복과 SOL 전용 보안 설정

### 바뀐 점

| 파일 | 이전(SOL-01 `e8f1300`) | 지금 |
|---|---|---|
| `auth/controller/LoginController` | `GET /api/auth/csrf`에서 로그인 확인 제거 | **LSH 원본으로 복원**(회원 전용) |
| `common/config/SecurityConfig` | SOL CSRF 조건·SOL 공개 경로·CSRF JSON 403 추가, 옛 `/api/solution/**` 규칙 제거 | **LSH 원본으로 복원**(`git diff 1557fcb` 기준 변경 없음) |
| `solution/security/SolutionSecurityConfig` | 없음 | 새 보안 체인. `/api/solutions/**`만 맡음 |
| `solution/controller/SolutionController` | SOL-01 POST | `GET /api/solutions/csrf`(비회원 CSRF 발급)·SOL-02 GET 추가 |

### 두 보안 체인이 같이 동작하는 방식

```text
요청 ─┬─ /api/solutions/** ──▶ solutionSecurityFilterChain (SolutionSecurityConfig, 먼저 검사)
      │                         · CSRF: 변경 메서드는 회원·비회원 모두 검사, GET은 검사 안 함
      │                         · 공개: GET /csrf, POST /sessions, GET /sessions/{id} / 나머지 차단
      │                         · CSRF 거부 → 403 {"code":"CSRF_TOKEN_INVALID"}
      └─ 그 밖의 모든 경로 ───▶ securityFilterChain (LSH SecurityConfig, 그대로)
```

| 공유하는 것 | 어디에 있나 | 왜 같아야 하나 |
|---|---|---|
| `HttpSessionSecurityContextRepository` 빈 | LSH `SecurityConfig` | 로그인 정보(Long principal)를 같은 세션에서 읽어야 회원 상담을 구분해요 |
| `UrlBasedCorsConfigurationSource` 빈 | LSH `SecurityConfig` | 프론트 origin·허용 헤더(`X-CSRF-TOKEN`)가 같아야 해요 |
| CSRF 토큰 저장소 | 두 체인 모두 Spring 기본 `HttpSessionCsrfTokenRepository` | 회원이 `/api/auth/csrf`로 받은 토큰도 상담 요청에 통과해요(테스트로 확인) |
| Redis 세션·SESSION 쿠키 | `application.yml`(LSH) | 비회원 익명 소유자 UUID가 이 세션에 저장돼요 |

새 체인은 `@Order(HIGHEST_PRECEDENCE + 10)`라서 LSH 체인(경로 조건 없음)보다 먼저 검사돼요. LSH 쪽에 남은 옛 `/api/solution/**`(단수) 공개 규칙은 LSH 소유라 손대지 않았어요. 연결된 컨트롤러가 없어 동작에는 영향이 없어요.

**단점:** CSRF 발급 API가 `/api/auth/csrf`(회원)와 `/api/solutions/csrf`(회원·비회원) 두 개가 되고, 보안 설정도 둘로 나뉘어요. 나중에 하나로 합치기로 하면 `SolutionSecurityConfig`의 규칙을 `SecurityConfig`로 옮기고 `/api/solutions/csrf`를 지우면 돼요.

## 3. API 계약

### `GET /api/solutions/csrf`

비회원도 호출할 수 있어요. 처음 호출하면 Redis 세션과 `SESSION` 쿠키가 생겨요. 응답 형식은 LSH `/api/auth/csrf`와 같아요.

```json
{"success": true, "data": {"headerName": "X-CSRF-TOKEN", "token": "..."}}
```

### `GET /api/solutions/sessions/{id}`

```json
{
  "success": true,
  "data": {
    "solutionId": 301,
    "status": "ACTIVE",
    "stateVersion": 0,
    "facts": {"transferredMoney": "YES", "sharedInformation": "UNKNOWN", "installedApp": "UNKNOWN", "contactContinues": "NO"},
    "responseCards": [{"code": "TRANSFER_RESPONSE", "priority": "URGENT", "title": "송금 관련 우선 대응", "institutionCodes": ["POLICE", "BANK"]}],
    "nextQuestion": {"factKey": "sharedInformation", "content": "개인정보를 전달한 적이 있나요?", "options": ["YES", "NO", "UNKNOWN"]},
    "messages": [
      {"sequenceNo": 1, "sender": "USER", "inputKind": "TEXT", "content": "피해 설명을 제출했습니다.", "selectedCode": null},
      {"sequenceNo": 2, "sender": "SYSTEM", "inputKind": "TEXT", "content": "개인정보를 전달한 적이 있나요?", "selectedCode": null}
    ]
  }
}
```

- 앞 6개 필드는 SOL-01 응답과 같은 값이에요(통합 테스트로 비교). 프론트는 두 응답을 같은 화면 상태로 쓸 수 있어요.
- `messages[].inputKind`는 DB의 `OPTION`을 공개 값 `CHOICE`로 바꿔요. 상담에서 쓰지 않는 `ACTION`이 저장돼 있으면 서버 오류로 처리해요.
- `content`는 저장된 `safe_content` 그대로예요. SOL-01은 원문 대신 고정 문구만 저장하므로 원본 피해 설명은 복원되지 않아요.
- 소유자 ID·익명 UUID·`client_request_id`·카드 version은 응답에 없어요.
- `Cache-Control: no-cache, no-store, max-age=0`은 Spring Security 기본 헤더로 붙어요(테스트로 확인).

| 상황 | 응답 |
|---|---|
| 소유자 본인(회원 로그인 ID 또는 같은 비회원 세션) | 200 |
| 없는 상담, 남의 상담, 쿠키 없음·만료, 로그인 뒤 이전 익명 상담, id가 `abc`·`0`·`-1`·`01`·범위 초과 | 404 `SOLUTION_NOT_FOUND`(모두 같은 본문) |
| 로그인 principal은 있으나 비활성·삭제 회원 | 401 `LOGIN_REQUIRED` |
| 저장된 카드가 카탈로그에서 없어짐 | 503 `RESPONSE_CONTENT_UNAVAILABLE` |
| 저장된 JSON이 형식에 맞지 않음 | 500(상담 ID·컬럼 이름만 로그, 값은 남기지 않음) |

CSRF 토큰은 필요 없어요(GET). 조회는 새 HTTP 세션이나 익명 UUID를 만들지 않아서, 쿠키 없이 호출하면 `Set-Cookie` 없이 404예요.

## 4. 처리 흐름

```text
SolutionController.get(id 문자열)
  ├─ id 검증: [1-9][0-9]{0,18} + Long 범위, 아니면 404
  └─ SolutionQueryService.get(id, () -> SolutionOwnerResolver.findExisting(...))
        ├─ 소유자 확인(트랜잭션 밖)
        │    · 로그인 Long principal → LoginService.requireActiveUser → 회원(세션에 익명 UUID가 있어도 무시)
        │    · 비로그인 → request.getSession(false) → AnonymousOwnerIssuer.existingOwner(세션 속성만 읽음)
        │    · 없으면 DB 조회 없이 404
        └─ 읽기 전용 + REPEATABLE_READ 트랜잭션
             · SolutionSessionRepository.findOwned(id, owner) → 없으면 404
             · SolutionMessageRepository.findBySolutionIdOrderBySequenceNoAsc(id)
             · SolutionSession.readFacts / readCardReferences / readNextQuestion (엄격 변환)
             · ResponseCardCatalog.findReviewed(code)로 현재 카드 → 현재 priority로 URGENT 먼저 정렬
        → SolutionStateResponse
```

- **REPEATABLE_READ를 쓰는 이유:** PostgreSQL 기본값(READ COMMITTED)에서는 쿼리마다 그 순간의 데이터를 봐요. 상담 행을 읽은 뒤 다른 요청이 메시지를 추가하고 커밋하면 "버전 0인데 메시지 3개"처럼 섞여요. DB 테스트로 재현했어요. 격리 수준을 기본값으로 바꾸면 테스트가 실패하고(기대 2개, 실제 3개), REPEATABLE_READ에서는 통과해요.
- 상태·버전을 바꾸지 않고(DB 테스트에서 행 전체를 비교) AI를 호출하지 않아요.

## 5. 파일별 상호작용

### 새로 만든 파일

| 파일 | 부르는 곳 | 이 파일이 쓰는 것 |
|---|---|---|
| `solution/security/SolutionSecurityConfig` | Spring Security(필터 체인 빈) | LSH `SecurityConfig`의 `HttpSessionSecurityContextRepository`·`UrlBasedCorsConfigurationSource` 빈, `common/error/ErrorCode`·`ErrorResponse` |
| `solution/domain/CardReference` | `SolutionSession.readCardReferences`, `SolutionQueryService` | 없음(값 검증만) |
| `solution/dto/SolutionStateResponse` | `SolutionQueryService`, `SolutionController` | `StartSolutionResponse.Card`·`Question`(SOL-01과 같은 형식), `SolutionMessage`, `common/domain/InputKind`·`MessageSender` |
| `solution/service/SolutionQueryService` | `SolutionController.get` | `SolutionSessionRepository.findOwned`, `SolutionMessageRepository.findBySolutionIdOrderBySequenceNoAsc`(기존), `ResponseCardCatalog`, `PlatformTransactionManager`, `ErrorCode` |

### 고친 파일

| 파일 | 추가·변경 | 영향 받는 곳 |
|---|---|---|
| `solution/controller/SolutionController` | `GET /csrf`, `GET /sessions/{id}`, id 검증 | 프론트. `SolutionSecurityConfig`의 공개 경로와 맞물려요 |
| `solution/security/SolutionOwnerResolver` | `findExisting`(조회 전용) | `SolutionQueryService`. 기존 `resolve`(SOL-01 발급용)는 그대로 |
| `solution/security/AnonymousOwnerIssuer` | `existingOwner`(세션 속성만 읽음, Redis 미사용) | `SolutionOwnerResolver.findExisting` |
| `solution/domain/SolutionSession` | `readFacts`·`readCardReferences`·`readNextQuestion` | `SolutionQueryService`. SOL-03~05도 현재 상태를 읽을 때 재사용 |
| `auth/controller/LoginController`, `common/config/SecurityConfig` | LSH 원본으로 복원 | LSH 통합 테스트·로그아웃·훈련 CSRF가 원래 동작으로 돌아감 |

### 그대로 쓰는 기존 코드

| 파일 | SOL-02에서의 역할 |
|---|---|
| `auth/service/LoginService.requireActiveUser`(LSH) | 회원이 활성 상태인지 확인. 비활성이면 401 |
| `auth/LoginCurrentUser.requireUserId`(LSH) | Long principal 꺼내기 |
| `solution/repository/SolutionSessionRepository.findOwned`(SOL-01) | 소유자 조건 조회. 없는 상담과 남의 상담을 구분하지 않아요 |
| `solution/domain/SolutionFacts`·`SolutionQuestion`·`ResponseCard`·`CardPriority`(SOL-01) | 저장값 검증과 응답 변환 |
| `solution/content/UnavailableResponseCardCatalog`(SOL-01) | 운영 기본 카탈로그(카드 없음). 지금 운영에서 카드가 있는 상담은 SOL-01이 만들 수 없어서 SOL-02의 503도 생기지 않아요 |
| `common/error/GlobalExceptionHandler`·`ErrorCode.SOLUTION_NOT_FOUND`(SOL-01에서 추가) | 404·401·503 JSON 응답 |
| `common/api/ApiResponse` | `{success, data}` 봉투 |

DB 스키마는 바뀌지 않았어요(V3 그대로).

## 6. 테스트 결과

| 실행 | 결과 | SOL-02로 추가·변경한 테스트 |
|---|---|---|
| `./gradlew test` | **799건 통과**(SOL-01 784 + 15) | `SolutionDomainTest`: 저장 JSON 읽기·손상값 거부(값이 오류 메시지에 안 나옴) · `SolutionOwnerResolverTest`: 조회가 세션·UUID·Redis를 만들지 않음, 위조 속성 무시, 로그인 후 회원만, 비활성 401 · `SolutionQueryServiceTest`(새): 읽기 전용·REPEATABLE_READ 확인, 소유자 없음 DB 미조회 404, 남의 상담 404, 현재 카드·현재 우선순위 정렬, 카드 없음 503, OPTION→CHOICE, ACTION 오류 · `SolutionWebSecurityTest`: 두 체인으로 재구성, `/api/auth/csrf` 비회원 401 유지, 회원은 `/api/auth/csrf` 토큰으로 상담 통과, SOL-01 응답과 SOL-02 응답 일치, 다른 세션 404, 세션 없는 조회는 세션 미생성, 잘못된 id 7종 404, 로그인 뒤 이전 익명 상담 404, 미구현 SOL-03·PUT 차단 |
| `./gradlew dbTest` | **13건 통과**(SOL-01 11 + 2) | `SolutionDatabaseTest`: 회원·익명 상담 복원과 서로 조회 불가, 조회 후 행 불변 · 중간 커밋이 있어도 같은 시점 버전·메시지(기본 격리 수준으로 바꾸면 실패하는 것도 확인) |
| `./gradlew integrationTest` | **17건 통과**(SOL-01 16 + 1) | `SolutionIntegrationTest`: 실제 서버·Redis로 `/api/auth/csrf` 비회원 401 유지, 비회원 `/api/solutions/csrf` → 생성 → 같은 쿠키 복원 200(SOL-01 응답과 일치, `no-store`), 다른 쿠키·쿠키 없음 404(새 쿠키 없음), `abc` 404, Redis 세션 삭제 뒤 404, 로그인 뒤 회원 상담 200·이전 익명 상담 404. LSH `RegistrationIntegrationTest` 13건도 수정 없이 통과 |

DB·Redis 테스트는 전용 컨테이너에서만 실행했어요. 실행 명령은 [SOL-01 보고서](sol-01-session-start.md) 9장과 같아요(`WSID_TEST_DB_URL` 필수).

## 7. 남은 일

1. **팀에 알릴 것:** LSH 파일을 되돌리고 상담 보안을 `SolutionSecurityConfig`·`/api/solutions/csrf`로 분리했어요. 팀 가이드("기존 CSRF API 확장")와 다르니 공유하고, 합칠지 정해요. V3 번호 확인도 여전히 필요해요.
2. **프론트 확인:** 메시지 항목 5개 필드, 404를 받으면 "상담을 다시 시작해 주세요" 안내, 상담 중 로그인 전 안내 문구, 비회원은 `/api/solutions/csrf` 사용.
3. **명세 정정:** SOL-02 메시지 항목 형식, 잘못된 id도 404, 503 추가. 명세 예시의 `transferredMoney: YES` + `responseCards: []`는 SOL-01 규칙(송금 YES → 카드)과 맞지 않아 고쳐야 해요.
4. **SOL-03~05:** `findExisting`·`findOwned`·`read*` 메서드를 그대로 쓰고, 각 경로를 `SolutionSecurityConfig`에 소유권 검사와 함께 하나씩 공개해요. SOL-03은 선택 응답을 `InputKind.OPTION` + `selected_code`로 저장하면 SOL-02가 `CHOICE`로 보여줘요.
5. **목록 없음:** 홈의 "진행 중 상담 이어 하기"는 프론트가 마지막 상담 ID를 `localStorage`에 저장해 SOL-02를 부르는 방식으로 해요(404면 저장값 삭제). 회원이 다른 기기에서 이어 하려면 별도 API를 명세에 추가해야 해요.
