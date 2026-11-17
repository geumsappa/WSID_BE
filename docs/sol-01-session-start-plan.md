# SOL-01 피해 대응 상담 시작 구현·테스트 계획서

작성일: 2026-11-17 · 갱신: 2026-11-17(LSH AUTH 반영) · 상태: 구현 전 계획

기준은 SOL-01 초안과 팀 공유 `WSID-API-Spec.md`의 공통 규칙·AUTH-04/06·SOL-01~05·AI-06 절, [AI-06 구현 보고서](ai-06-solution-extract.md), [AUTH 인프라 문서](auth-infrastructure.md), 실제 상담·인증·보안·DB·콘텐츠 코드다. 이 문서는 계획과 목차만 작성한다. **로그인·Redis 세션·SESSION 쿠키·CSRF 발급은 LSH AUTH 구현(OHC에 반영한 `0d21cd6`·`1557fcb`)을 공통 기반으로 재사용하고 AUTH를 새로 만들지 않는다.** SOL은 그 위에 비회원 처리만 추가한다. LSH 담당과 먼저 합의할 것은 “기존 CSRF 발급 API의 익명 허용”, “비회원 SOL 변경 요청의 CSRF 보호”, “대응 카드 카탈로그의 소유와 형식” 세 가지다. 아래 소유권 저장·입력/오류·카드/질문 정책은 구현 전 확인할 초기 제안이다.

## 1. 목표와 역할

`POST /api/solutions/sessions`는 회원·비회원의 새 상담을 만든다. 설명이 있으면 AI-06으로 사실을 추출하고, Spring이 확인 항목·검토된 대응 카드·첫 질문을 결정해 저장한 뒤 201을 반환한다. 설명이 없으면 AI 호출 없이 선택형 질문으로 시작한다.

```text
브라우저 → Spring SOL-01
           ├─ 서버 측 소유자 확인·입력/민감정보 검사
           ├─ 설명 있음: AiClient.extractSolutionFacts → FastAPI AI-06 → LLM
           ├─ 설명 없음 / AI 사용 불가: UNKNOWN 사실 + 선택형 질문
           └─ Spring 규칙 → 승인 카드·질문 → DB 저장 → 201 응답
```

Spring이 LLM 공급자를 직접 호출하지 않는다. 기존 AI-06 클라이언트를 연결하고, FastAPI가 실제 LLM 호출을 구현한다. 기본 fake로도 Spring·DB·API 개발과 검증을 진행할 수 있다. AI-07 설명 생성은 이번 요청에서 호출하지 않는다.

| 작업 범위 | 현재 상태·완료 조건 |
|---|---|
| Spring AI-06 호출 통로 | fake/HTTP DTO·클라이언트 구현됨. 상담 서비스 호출자는 아직 없음 |
| 상담 시작 도메인·서비스·API | 새 구현 필요. 초기 사실·카드·질문·상태를 일관되게 저장 |
| 비회원 소유권·DB | 새 저장 구조·마이그레이션·서버 세션 연결 필요 |
| Redis 세션·SESSION 쿠키 | LSH AUTH에 구현됨(`spring-boot-starter-session-data-redis`, namespace `wsid:session`, SESSION/HttpOnly/Secure/SameSite=Lax/Path=/). SOL은 같은 설정을 공유 |
| 민감정보·CSRF·쿠키 | 새 처리 필요. 로그인 불필요와 변경 요청 보호를 함께 충족 |
| 대응 카드 | OHC에는 카드 로더가 없음. LSH `6c2836b`(OHC 미반영)에 `GuideCatalog`·카드 형식·GUIDE-03 조회 API가 있으나 운영 카드는 비어 있고 version·적용 조건이 없음. 카탈로그 소유·형식 합의 후 테스트 자료와 운영 승인 자료를 구분해 준비 |
| 실제 FastAPI·LLM | 별도 AI 프로젝트에서 구현/연결·추정 방지·로그 정책 검증 필요 |
| SOL-02~05 | 공개 API 전체 구현은 별도 작업. 소유권·사실·질문·버전 계약은 이번에 일관되게 준비 |

## 2. 현재 코드와 선행 과제

| 파일 | 확인한 상태 | 필요한 처리 |
|---|---|---|
| [SolutionSession.java](../src/main/java/com/wsid/solution/domain/SolutionSession.java) | 회원 userId 필수, factsJson={}, 카드=[], 질문/갱신 규칙 없음 | 회원/익명 소유자·초기 사실/카드/질문을 받는 생성 규칙 |
| [SolutionMessage.java](../src/main/java/com/wsid/solution/domain/SolutionMessage.java) | safeContent 공간은 있지만 마스킹·선택/참조 코드 설정 없음 | 원문 없는 접수 기록·규칙형 질문·카드 참조 기록 생성 |
| [SolutionSessionRepository.java](../src/main/java/com/wsid/solution/repository/SolutionSessionRepository.java) | findByIdAndUserId만 존재 | 익명 소유권 조건 조회 추가·공통 소유권 정책 준비 |
| [SolutionMessageRepository.java](../src/main/java/com/wsid/solution/repository/SolutionMessageRepository.java) | 요청 ID 중복·순서 조회 있음 | 초기 메시지 순서 및 상담 생성 재시도와의 차이 정리 |
| [V1](../src/main/resources/db/migration/V1__init_schema.sql)·[V2](../src/main/resources/db/migration/V2__add_constraints.sql) | user_id NOT NULL·회원 FK, 메시지 상담별 요청/순서 유일성 | 기존 파일을 고치지 않고 다음 미사용 버전의 마이그레이션 추가 |
| [CurrentUser.java](../src/main/java/com/wsid/auth/CurrentUser.java), [LoginCurrentUser.java](../src/main/java/com/wsid/auth/LoginCurrentUser.java), [LoginController.java](../src/main/java/com/wsid/auth/controller/LoginController.java) | 로그인 시 principal에 Long 회원 ID를 저장하고 세션 ID 교체. `CurrentUser.id()`는 활성 회원만 반환하고 아니면 LOGIN_REQUIRED. local 공용 개발 계정(LocalDevCurrentUser)은 LSH에서 제거됨 | 회원 식별은 `LoginCurrentUser.requireUserId`·활성 회원 검증을 재사용. 회원 전용 `CurrentUser.id()`만으로 비회원을 처리하지 않고 SOL 전용 SolutionOwnerResolver로 회원/비회원을 구분 |
| [SecurityConfig.java](../src/main/java/com/wsid/common/config/SecurityConfig.java) | CSRF 활성. 단 검사는 **로그인 회원(principal이 Long)**의 로그아웃·`/api/training/**`·`/api/trainings/**` 변경 요청에만 적용. 공개 규칙은 이전 경로 `/api/solution/**`. CORS는 `wsid.cors.allowed-origins`, 허용 헤더 X-CSRF-TOKEN | `/api/solutions/**`로 경로 통일. 상담 생성·수정은 회원·비회원 모두 CSRF 검사하도록 보호 조건 확장. permitAll만 추가해서는 해결되지 않음 |
| [build.gradle](../build.gradle)·[application.yml](../src/main/resources/application.yml) | Redis 세션·메일 의존성, `spring.data.redis`·`spring.session`·SESSION 쿠키 설정, `integrationTest`(PostgreSQL+Redis) 작업 있음 | 기존 설정을 그대로 공유. 익명 UUID와 CSRF 토큰도 같은 Redis HTTP 세션에 저장 |
| [LoginController](../src/main/java/com/wsid/auth/controller/LoginController.java)의 `GET /api/auth/csrf` | `{headerName, token}`을 반환하지만 `requireUserId`로 **로그인 필수** | 비회원도 토큰을 받도록 확장(AUTH 합의). `/api/auth/session`·`/api/users/me` 등 다른 엔드포인트의 로그인 조건은 유지 |
| [ErrorCode.java](../src/main/java/com/wsid/common/error/ErrorCode.java)·[InputKind.java](../src/main/java/com/wsid/common/domain/InputKind.java) | SENSITIVE_DATA_DETECTED·SOLUTION_NOT_FOUND 없음. SOL 명세의 CHOICE/STATE_VERSION_CONFLICT와 내부 OPTION/SESSION_VERSION_CONFLICT도 다름 | SOL 오류 코드 추가·공개/내부 입력 및 충돌 코드 매핑 준비. 기존 훈련 계약 유지 |
| [SolutionExtractRequest.java](../src/main/java/com/wsid/ai/dto/SolutionExtractRequest.java) | 설명 1,000자·허용 사실·UUID 검사, 마스킹 없음 | 호출자가 민감정보 검사 후 필요한 설명만 전달 |
| [response-cards README](../src/main/resources/content/response-cards/README.md)·LSH `6c2836b`의 `content/GuideCatalog.java`·`content/guides.yml` | OHC에는 형식 초안 README만 있음. LSH에는 `guides.yml` 한 파일에서 유형·카드·기관을 읽는 로더가 있고 reviewed=true만 공개, 카드의 기관 코드 존재 검사, `GET /api/guides/response-cards?codes=` 제공. 카드 필드는 code·title·priority·actions·requiredMaterials·institutionCodes·sourceReferences·reviewedAt. 운영 `responseCards: []`·`institutions: []`, 테스트 `guide-fixture.yml`의 TEST_CARD만 존재 | 새 로더를 따로 만들지 않고 LSH 카탈로그를 기준으로 합의(합의 3). SOL에 필요한 version·적용 조건(규칙 매트릭스)을 추가할 위치를 정함 |

GUIDE-01/02의 유형 카탈로그를 대응 카드 카탈로그로 대신 쓰지 않는다. 유형 설명이 있다고 초기 대응 카드나 승인된 연락처가 준비된 것은 아니다. LSH `6c2836b`는 OHC의 GUIDE-01(`FraudTypeCatalog`, `/api/guides/fraud-types`)과 같은 경로를 다른 구조(`GuideCatalog`)로 구현해 그대로 합치면 충돌하므로, 카드 부분을 가져오는 방식도 합의 3에서 함께 정한다.

## 3. 요청·응답 계약

```json
{
  "description": "모르는 사람에게 돈을 보냈고 연락이 끊겼어요."
}
```

| 항목 | 초기 제안 |
|---|---|
| description | 선택 문자열. 원문 기준 최대 1,000자(Java String.length). 길이부터 검사 |
| 설명 미제공 | 본문 없음·{}·null description·빈/공백 문자열은 선택형 시작으로 통일 |
| 잘못된 JSON/타입 | 숫자·boolean·객체/배열 description과 description 외 요청 키는 400 INVALID_REQUEST |
| 서버가 결정할 값 | userId·익명 소유자 ID·facts·카드·상태·버전을 클라이언트에서 받지 않음 |
| 추적 UUID | 서버가 생성해 AI-06 requestId 및 X-Request-Id에 사용. 본문에 새 필수 필드를 추가하지 않음 |

문자열 DTO 바인딩의 숫자→문자열 자동 변환에 기대지 않는다. Spring Boot의 기본 바인딩은 모르는 JSON 키를 무시할 수 있으므로 DTO에 필드가 없다는 이유만으로 추가 키 거부가 보장되지는 않는다. JsonNode 사전 검사에서 허용 키 집합과 실제 타입을 확인하거나 그와 동등한 엄격한 역직렬화를 적용한다. 비어 있는 요청은 DTO 기본값으로 만들고, 잘못된 JSON을 설명 미제공으로 바꾸지 않는다. DTO toString·검증 오류에 description을 포함하지 않는다.

본문 없는 시작은 `@RequestBody(required = false)`로 명시한다. 불필요한 consumes 제약 때문에 Content-Type 없는 빈 요청이 415로 끝나지 않게 구성하고 이 조합을 MVC 테스트로 확인한다. 실제 JSON 본문이 있으면 application/json을 요구하는 방안을 제안하며, 비어 있지 않은 text/plain·Content-Type 미지정 본문은 415로 구분한다. 본문 없음·빈 본문·{}·잘못된 JSON·잘못된 미디어 타입을 같은 경우로 처리하지 않는다.

설명이 있고 AI-06이 명세 예시의 사실을 반환했을 때 성공 형태는 다음과 같다.

```json
{
  "success": true,
  "data": {
    "solutionId": 301,
    "status": "ACTIVE",
    "stateVersion": 0,
    "facts": {
      "transferredMoney": "YES",
      "sharedInformation": "UNKNOWN",
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
      "factKey": "sharedInformation",
      "content": "개인정보를 전달한 적이 있나요?",
      "options": ["YES", "NO", "UNKNOWN"]
    }
  }
}
```

HTTP 상태는 201이다. `ApiResponse`를 명시적으로 사용하고 엔티티·소유자 ID·원문·AI 원시 응답·카드 관리 메타데이터는 노출하지 않는다. facts는 항상 4개 키와 YES/NO/UNKNOWN 문자열을 포함한다. responseCards는 항상 배열, 질문할 UNKNOWN이 없으면 nextQuestion은 명시적 null을 제안한다. 사실이 모두 알려졌다는 이유로 상담을 자동 완료하지 않는다.

새 엔티티의 초기 상태를 처음 저장하기 전에 모두 설정한다. 먼저 빈 세션을 저장하고 나중에 사실을 UPDATE하면 @Version이 1로 올라갈 수 있다. 초기 INSERT 후 DB 버전을 읽어 응답하고, DTO에 0을 임의로 고정하지 않는다. 초기 생성 경로에서 version=0이 유지되는지 DB 테스트로 확인한다.

## 4. 회원·비회원 소유권과 DB 변경

### 서버 측 소유자 결정

`SolutionOwner`에 회원 ID 또는 서버가 만든 익명 UUID 중 하나만 갖도록 한다. `SolutionOwnerResolver`는 인증된 서버 principal과 HttpSession을 사용한다. AnonymousAuthenticationToken도 isAuthenticated=true일 수 있으므로 이 값 하나로 회원 여부를 판단하지 않는다. 클라이언트 body/header의 사용자·소유자 ID는 사용하지 않는다.

- 회원: 실제 로그인 세션의 검증된 회원 ID로 연결한다. 인증되지 않은 principal 이름을 회원 ID로 간주하지 않는다.
- 비회원: HttpSession에 서버가 임의 생성한 UUID를 저장하고 같은 세션에서는 재사용한다. 응답에는 UUID나 실제 세션 ID를 넣지 않는다.
- 로그인 직후에는 **검증된 회원 principal을 우선**한다. 세션 ID를 바꿔도 기존 세션 속성이 남을 수 있으므로 익명 UUID 속성의 존재로 비회원이라고 판단하지 않는다. 회원 principal과 익명 UUID가 함께 있으면 새 상담은 회원 ID로만 만들고 anonymous_owner_id는 null로 둔다. 남은 UUID로 새 익명 상담을 만들거나 기존 익명 상담을 회원 소유로 자동 이관하지 않는다.
- 같은 HttpSession의 동시 첫 요청은 같은 익명 UUID를 얻도록 원자적으로 초기화한다. 단일 JVM 동기화만으로 다중 서버에서 보장된다고 표시하지 않는다.
- local 프로필에도 공용 개발 계정이 더 이상 없다(LSH에서 LocalDevCurrentUser 제거). 비회원을 임의 회원에 연결하지 않고, 테스트는 회원 principal과 서로 다른 익명 세션을 명시적으로 구성한다.

회원 경로는 LSH AUTH의 로그인 principal(Long 회원 ID)과 `LoginService.requireActiveUser` 활성 회원 검증을 그대로 쓴다. 요청 body·header의 userId는 신뢰하지 않는다. SolutionOwnerResolver는 “인증된 Long principal이 있음 → 활성 회원 검증 → 회원 소유”, “인증 정보가 없음(익명) → 세션 익명 UUID”만 구분한다. LOGIN_REQUIRED 등 인증 오류를 모두 잡아 비회원으로 처리하지 않는다. 특히 principal은 있는데 회원이 비활성·삭제된 경우는 비회원으로 바꾸지 않고 오류로 끝낸다.

### 마이그레이션 제안

AUTH·DB 담당자와 번호를 예약한 뒤 `V{합의번호}__support_anonymous_solution_owners.sql` 같은 새 마이그레이션을 추가한다. 기존 V1/V2는 수정하지 않는다. [DB README](../src/main/resources/db/README.md)에는 이미 V3__add_email_verifications.sql이 AUTH 작업 예시로 등장하므로 SOL 계획서가 V3를 선점하지 않는다. 현재 폴더에 V1/V2만 있어도 다른 작업 브랜치의 미병합 파일을 확인하고, 머지 직전 최신 공유 브랜치에서 번호 중복을 다시 검사한다. 이미 공유/적용한 마이그레이션의 번호·이름·내용을 바꿔 충돌을 해결하지 않는다.

- solution_sessions.user_id의 NOT NULL 해제, 기존 users 외래키 유지
- anonymous_owner_id UUID nullable 추가
- user_id와 anonymous_owner_id 중 정확히 하나만 존재하도록 CHECK 제약
- 익명 소유자·상태 조회용 인덱스 추가. 기존 회원 인덱스 유지

기존 회원 상담은 user_id를 유지하고 anonymous_owner_id=null로 두면 새 제약을 만족한다. 가짜 users 행·회원 ID 0을 만들지 않는다. 엔티티 생성자는 두 소유자가 모두 있거나 모두 없는 상태를 거부하고 getter의 null 가능성도 반영한다.

repository는 findByIdAndUserId와 익명 조건 조회를 구분하고 SOL-02~05에서도 소유자 종류에 맞는 조건을 사용하도록 준비한다. 새 익명 세션을 만들었다고 이전 상담의 소유권을 재발급하지 않는다. 쿠키 분실·만료 시 기존 익명 상담 복원 불가는 SOL-02 명세와 맞춘다.

### Redis 세션 공유와 남은 범위

명세의 Spring Session Redis 저장은 LSH AUTH에 이미 구현돼 있다. SOL은 새 저장소나 쿠키 설정을 만들지 않고 기존 `spring.session.data.redis.namespace`(`wsid:session`)·`spring.session.timeout`(기본 30m)·SESSION 쿠키 설정을 공유한다.

애플리케이션 코드는 HttpSession을 사용하고 실제 저장은 기존 Redis 세션이다. 익명 UUID·인증/CSRF 메타데이터만 세션에 보관하고 description 원문은 Redis에도 저장하지 않는다. Redis 장애를 메모리 세션으로 조용히 대체하지 않는다. 다중 서버에서는 JVM 내부 동기화만으로 익명 UUID 초기화 경쟁을 해결할 수 없으므로 Redis 세션에서의 일관된 최초 소유자 발급 방식을 검증한다.

익명 상담에 맞는 세션 만료 시간(현재 공통 30m)·개인정보 보관/정리 기간은 후속 확정 항목이다. 쿠키가 남아 있어도 Redis 세션이 만료/삭제됐으면 이전 소유권을 복원할 수 없다. 로그인 후 새 상담은 회원 소유로 만들고, 기존 익명 상담은 자동 이관하지 않는 기준으로 시작한다. 이관이 필요하면 별도 소유권 증명·이관 요구로 다룬다.

## 5. CSRF·쿠키·첫 요청 흐름

로그인 없이 호출할 수 있어도 쿠키 기반 변경 요청에는 CSRF 보호가 필요하다. LSH AUTH는 CSRF를 활성화했지만 검사 조건이 “인증된 Long principal”일 때만 참이라 비회원 요청은 검사 대상에서 빠진다. 이 상태로 `/api/solutions/**`에 permitAll만 추가하면 비회원 상담 생성이 CSRF 없이 통과한다.

`GET /api/auth/csrf`는 LSH의 [LoginController](../src/main/java/com/wsid/auth/controller/LoginController.java)에 구현돼 있고 `{headerName, token}`(헤더 X-CSRF-TOKEN)과 `Cache-Control: no-store`를 반환한다. 공유 명세 AUTH-06의 “현재 백엔드 구현” 표기는 LSH 기준으로는 맞다. 다만 지금은 `requireUserId`로 로그인 회원에게만 발급한다.

**상담 전용 CSRF 발급 경로는 만들지 않고 이 기존 API를 비회원까지 확장한다(AUTH 합의 1).** 익명 요청에서도 토큰을 반환하고, 그 과정에서 Redis HTTP 세션과 SESSION 쿠키가 생성된다. `/api/auth/session`·`/api/users/me`·`/api/auth/logout`의 로그인 조건은 그대로 유지한다.

**CSRF 검사 조건에 상담 변경 요청을 추가한다(AUTH 합의 2).** 기존 회원 조건(로그아웃·훈련)은 그대로 두고, `/api/solutions/**`의 상태 변경 메서드(POST·PATCH 등)는 principal과 무관하게 항상 검사한다. 별도 체인을 만들지 않고 기존 SecurityConfig의 matcher를 확장해 AUTH·SOL이 같은 CsrfTokenRepository(세션 저장)·헤더 이름을 공유한다. 공개 규칙도 이전 `/api/solution/**` 대신 `/api/solutions/**`로 통일하고, SOL-01 POST부터 열며 SOL-02~05는 각 API 구현 시 소유권 검사와 함께 등록한다. CORS의 허용 메서드·헤더(현재 GET·POST·PATCH, Content-Type·X-CSRF-TOKEN)가 SOL 요청을 포함하는지 함께 확인한다.

### 최초 비회원 호출 순서와 명세 조정

SOL-01 문장은 “비회원 최초 호출 시 세션·쿠키를 발급”한다고 한다. 이번 흐름에서는 **Redis HTTP 세션과 SESSION 쿠키가 GET /api/auth/csrf에서 먼저 생기고, 상담 소유자 UUID와 상담 DB 행은 POST에서 생성**된다. 프론트 호출 순서는 아래로 맞춘다.

```text
1. 브라우저 → GET /api/auth/csrf (credentials 포함, 비회원 허용으로 확장)
   서버 → Redis HTTP 세션 + HttpOnly SESSION 쿠키 + CSRF 토큰/헤더 이름
2. 브라우저 → POST /api/solutions/sessions
   세션 쿠키 + 받은 CSRF 헤더 + description 또는 빈 요청
3. 서버 → CSRF 확인 → 입력 검사 → 상담 소유자 결정 → 상담 DB 생성
```

세션 쿠키와 CSRF 토큰을 서로 다른 값으로 취급한다. 세션 ID·토큰·쿠키를 로그에 남기지 않는다. 토큰이 없거나 잘못됐으면 403으로 거부하고 AI·상담 저장을 수행하지 않는다. 보안 오류의 JSON 형식은 AccessDeniedHandler 등 필터 계층에서 별도 처리해야 하며 GlobalExceptionHandler만으로 해결됐다고 표시하지 않는다.

첫 POST만 임의로 CSRF 예외 처리하거나 토큰 없이 통과시키지 않는다. 명세의 세션·쿠키 발급 시점 문장은 이 GET→POST 흐름에 맞춰 정정할 사항으로 기록한다.

세션 쿠키는 LSH AUTH가 이미 명세 기본값 **SESSION; HttpOnly; Secure(HTTPS 운영); SameSite=Lax; Path=/**로 설정했고, local 프로필만 HTTP 개발용으로 Secure를 끈다. SOL은 이 설정을 바꾸지 않는다. CORS 허용 origin은 `wsid.cors.allowed-origins`(local 기본 http://localhost:8443)를 공유한다. 다른 사이트 구성을 위해 SameSite=None 등이 필요하면 명세 기본값 변경으로 별도 합의한다. 로그인 시 세션 ID 교체는 AUTH 동작을 그대로 따른다.

## 6. 민감정보·원문 보호와 저장 방식

`SensitiveDescriptionGuard`를 호출 서비스 앞단에 새로 제안한다. 정의한 주민번호·계좌/카드·인증정보·전화번호 등의 탐지 정책에 걸리면 **400 SENSITIVE_DATA_DETECTED**, 고정 안내만 반환한다. 그 요청의 설명은 AI에 보내거나 상담/메시지 행에 저장하지 않는다. 부트스트랩으로 이미 생긴 HttpSession과 새 상담 행 생성을 구분한다.

초기안은 자동으로 일부를 지운 뒤 분석하기보다 민감정보가 포함된 설명을 거부하고, 민감정보를 빼서 다시 입력하거나 설명 없이 선택으로 시작하게 하는 방식이다. 탐지 범위·허용할 일반 금액/날짜·고정 오류 메시지·오탐/미탐 기준을 먼저 합의한다. 단순 정규식이 모든 개인정보를 완벽히 제거한다고 표시하지 않는다. LLM에 원문을 먼저 보내 민감정보 여부를 판단하게 하지 않는다.

검사를 통과한 설명도 메모리에서 필요한 AI 요청에만 사용하고 DB·파일·캐시·세션 속성·로그에 남기지 않는다. HTTP access/APM/body logging, DTO/Bean Validation 오류, FastAPI/LLM SDK·프록시의 본문 수집도 확인한다. 추적에는 UUID·성공/실패 분류·처리 시간·개수만 사용한다.

solution_messages.safeContent는 필드 이름만으로 안전한 값이 되지 않는다. 초기안은 사용자 설명을 가린 복사본도 저장하지 않고 다음 최소 기록을 제안한다.

- 첫 접수 메시지: 설명이 있으면 USER/TEXT + 고정 문구 “피해 설명을 제출했습니다.”, 설명이 없으면 SYSTEM/TEXT + 선택형 시작 고정 문구
- 후속 초기 질문: nextQuestion이 있으면 SYSTEM/TEXT + 검토된 질문 문구. 원문/LLM 인용 없음
- sequence_no는 1부터 순차 부여. client_request_id는 첫 접수 메시지 한 건에만 서버 추적 UUID를 넣고 후속 SYSTEM 메시지에는 null을 사용
- 카드 참조에는 카드 코드·버전을 저장하도록 형식을 정의. 원문·모델 추출 근거는 포함하지 않음

여러 메시지에 같은 UUID를 넣으면 V2의 상담별 요청 ID 유일 제약에 걸린다. 같은 초기 요청을 기록하는 행들에 UUID를 반복 저장하지 않는다. 이 접수 기록은 원래 피해 설명을 복원하는 기능이 아니다. SOL-02의 “원본 피해 상담 메시지 복원 안 함”과 맞춘다.

## 7. AI-06·사실·질문·카드 처리

### AI 호출과 사실 기본값

기본 facts는 transferredMoney·sharedInformation·installedApp·contactContinues 전부 UNKNOWN이다. description이 없으면 AI를 호출하지 않는다. 설명이 있으면 민감정보 검사 후 `SolutionExtractRequest(description, Map.of(), requestId)`를 만들어 `AiClient.extractSolutionFacts`를 호출한다.

기존 클라이언트가 응답 타입·4개 사실·미확정 집합을 검사한다. solution 도메인에서도 허용 키/값으로 변환하고 근거 없는 UNKNOWN을 NO로 바꾸지 않는다. LLM은 구조화 제안을 반환하며 문장 의미의 정확도·추정 방지는 실제 FastAPI/모델 평가가 별도로 필요하다.

| 상황 | 초기 처리 |
|---|---|
| 설명 없음 | UNKNOWN 4개·AI 미호출·첫 선택 질문 |
| AI 정상 | 반환 사실을 도메인에 적용·Spring 규칙 실행 |
| AiUnavailableException·타임아웃·계약 위반 | UNKNOWN 4개 유지·직접 선택 질문으로 시작·201 생성 |
| 요청/내부 DTO 구성 오류 | 정상 AI 실패로 숨기지 않고 입력/구현 오류로 처리 |
| 민감정보 감지 | 400·AI 미호출·상담 행 생성 없음 |
| DB 저장 실패 | 전체 저장 롤백. 성공 응답을 보내지 않음 |

서버의 AI 결과 분류는 원문 없는 추적 메타데이터로 기록한다. 공개 응답에 analysisStatus 같은 미합의 필드를 추가하지 않는다. AI 실패가 카드 콘텐츠·DB 오류까지 덮는 광범위한 catch로 이어지지 않게 한다.

### 질문 정책

첫 미확정 항목의 순서는 transferredMoney → sharedInformation → installedApp → contactContinues를 제안한다. 질문과 YES/NO/UNKNOWN 선택지는 서버의 검토된 고정 문구다. 예시처럼 송금 YES·연락 NO이면 sharedInformation을 질문한다. 전부 확정이면 nextQuestion=null이고 상태는 ACTIVE를 유지한다.

next_question_json에는 factKey·content·options를 저장한다. 이후 SOL-03 CHOICE는 factKey/selectedCode와 현재 질문을 대조한다. 현재 명세에 없는 질문 ID를 필수로 추가하지 않는다. 사용자 선택·SOL-04 직접 수정은 이후 AI 추출 제안으로 조용히 덮어쓰지 않도록 도메인 정책을 준비한다.

명세의 공개 inputKind는 CHOICE지만 현재 [InputKind](../src/main/java/com/wsid/common/domain/InputKind.java)는 OPTION을 사용한다. SOL-03 요청 DTO/컨트롤러에서 CHOICE→InputKind.OPTION을 명시적으로 매핑하고 DB에 미지원 enum 문자열 CHOICE를 그대로 쓰지 않는다. TEXT도 허용된 공개 값만 변환한다. 기존 training에서 쓰는 공통 enum을 조용히 이름 변경하지 않는다.

충돌 코드도 명세는 STATE_VERSION_CONFLICT, 기존 [ErrorCode](../src/main/java/com/wsid/common/error/ErrorCode.java)·[GlobalExceptionHandler](../src/main/java/com/wsid/common/error/GlobalExceptionHandler.java)는 SESSION_VERSION_CONFLICT다. SOL-03/04의 요청 버전 비교·낙관적 잠금 오류를 구현할 때 SOL 전용 오류 변환 경계와 STATE_VERSION_CONFLICT(409)를 준비한다. 기존 훈련의 코드·전역 핸들러를 일괄 변경하지 않는다. SOL-01은 요청 버전 비교를 받지 않으므로 이 차이는 후속 상담 변경 API를 위한 선행 계약으로 구분한다.

### 대응 카드와 긴급 우선

ResponseRules는 확인한 사실에 맞는 카드 코드를 정하고 승인 카탈로그에서 제목·기관 코드·긴급 여부를 조회한다. SOL-01 초안의 송금 YES → TRANSFER_RESPONSE/URGENT, SOL-04 초안의 앱 설치 YES → MALICIOUS_APP_RESPONSE/URGENT를 출발점으로 삼되, 나머지 사실 조합·미확정 안내·우선순위는 콘텐츠 담당자가 검토한 매트릭스로 확정한다. 새 금융 대응 방법이나 연락처를 구현자가 임의로 만들지 않는다.

카드 형식은 LSH `GuideCatalog`의 Card(code·title·priority·actions·requiredMaterials·institutionCodes·sourceReferences·reviewedAt)를 출발점으로 삼는다. SOL-01 응답의 code·priority·title·institutionCodes는 이 필드에서 바로 매핑된다. 기존 response-cards README의 id/urgent/when/do_now/contacts/prepare/version 초안과는 이름·구성이 다르므로 한 형식으로 정리한다. LSH 형식에는 SOL에 필요한 두 가지가 없다.

- **version:** 상담 DB에 카드 코드+버전을 저장하는 계획(8장)과 맞지 않는다. 지금은 reviewedAt만 있다. 카드 내용이 바뀌어도 이전 상담이 어떤 내용을 봤는지 재현하려면 version 필드를 추가하거나, 코드만 저장하고 버전 재현을 포기하는 것 중 하나를 정한다.
- **적용 조건:** 어떤 사실 조합에 어떤 카드를 보여줄지 정하는 규칙이 카드에 없다. ResponseRules의 사실→카드 코드 매트릭스를 카드 YAML의 조건 필드로 둘지, solution 도메인 코드에 둘지 정한다. 어느 쪽이든 콘텐츠 담당 검토 대상이다.

SOL-01 예시는 카드에 code·priority·title·institutionCodes가 있지만 SOL-04 예시는 code·priority만 있다. 초기안은 카드 도메인은 공유하고 공개 DTO는 API별로 구분해 SOL-01 필드를 명시적으로 매핑하는 것이다. 공통 공개 DTO를 사용하려면 SOL-04의 필수/추가 필드 계약도 프론트와 함께 확정한다. 예시가 다르다는 이유만으로 SOL-01 제목을 생략하거나 SOL-04 필드를 임의 확장하지 않는다.

기관 코드 POLICE/BANK 등은 검토된 콘텐츠 값이다. AI가 기관명·전화·URL을 생성하거나 선택하지 않는다. 같은 카드 중복을 제거하고 URGENT를 먼저, 같은 우선순위는 고정 순서로 제공한다. 규칙이 참조하는 카드가 없거나 미검토면 운영 활성화 전에 발견하고, 긴급 카드를 조용히 누락한 201을 보내지 않는다.

개발 테스트는 별도 test-content 승인 합성 자료로 가능하지만 운영 초안은 실제 승인 전 false를 유지한다. 승인 콘텐츠가 없는데 테스트 자료를 운영 카드로 배포하거나 빈 카드 결과를 정상 대응 완료로 포장하지 않는다. 필요한 카드·규칙·로더는 같은 릴리스로 준비한다.

긴급 카드 우선 정렬과 “AI 결과보다 먼저 응답”은 다르다. 이 동기 API는 최초 설명의 사실을 알기 위해 AI-06을 기다릴 수 있다. AI-07/RAG 추가 대기는 하지 않으며, 즉시 안내가 반드시 필요하면 화면의 검토된 공통 안내·직접 선택 경로 또는 별도 비동기 계약을 정해야 한다. 공통 AI 응답 제한은 현재 20초이고 SOL 응답 예산에 적합한지 측정한다. 전용 제한이 필요하면 다른 AI API에 영향을 주지 않는 클라이언트·설정·전송 테스트를 추가한다.

## 8. 트랜잭션·초기 버전·재시도

서비스 조율 메서드 전체에 @Transactional을 붙이지 않는다. 검증·소유자 해석·AI 호출·카드/질문 계산은 트랜잭션 밖에서 수행하고, 짧은 저장 트랜잭션에서 완성된 초기 세션과 원문 없는 메시지를 함께 저장한다. 기존 [TrainingService](../src/main/java/com/wsid/training/service/TrainingService.java)의 TransactionTemplate 분리 방식을 참고한다.

```text
CSRF·입력·민감정보 확인
  → 소유자 확정·추적 UUID
  → AI 호출 또는 UNKNOWN 시작 (DB 트랜잭션 없음)
  → 승인 카드·질문·초기 사실 결정
  → 서버 세션/소유자 유효성 확인
  → 저장 트랜잭션: 초기 세션 INSERT → 접수/질문 메시지 INSERT → flush
  → DB의 ID·버전으로 응답 DTO 구성 → 201
```

AI 호출 중 세션이 만료/폐기됐을 때 잘못된 소유자로 저장하지 않도록 확인 범위를 정한다. 저장 실패 시 세션·메시지를 함께 롤백한다. facts_json은 새 상담부터 canonical 키·문자열 값으로 쓰고 fraud_type_code는 이번 API에서 임의 추정하지 않아 null을 허용한다. 카드 저장은 코드·버전 참조를 담는 JSON 형태를 정하고 실제 카드 본문을 복사하지 않는다.

현재 SolutionSession의 facts_json 주석은 한글 키/값 예시이고 card_codes_json 주석은 코드 문자열 배열 예시다. 구현 시 실제 영문 canonical 키·YES/NO/UNKNOWN 및 합의한 카드 코드+버전 구조에 맞춰 주석·직렬화·테스트를 함께 갱신한다. 기존 DB에 옛 형식이 저장돼 있는지 확인하고, 필요하면 읽기 호환 또는 별도 이관을 준비한다. 주석을 바꾼 것만으로 기존 데이터가 바뀌었다고 보거나 이전 카드 버전을 임의로 1로 채우지 않는다.

공개 요청에는 클라이언트 requestId가 없다. 현재 계획은 **각 POST를 새 상담으로 생성**하는 초기안이다. 메시지의 unique(solution_id, client_request_id)는 새 상담 자체의 중복 생성을 막지 못한다. 응답 유실 후 브라우저가 다시 POST하면 중복 상담이 생길 수 있으며 서버 추적 UUID도 멱등 키가 아니다. 자동 재시도하지 않고, 생성 단위 멱등성이 필요하면 별도 Idempotency-Key/요청 계약·소유자별 유일 저장 구조를 구현 전에 확정한다.

## 9. 파일별 구현 순서와 상호작용

아래 신규 파일명은 제안이다.

| 순서 | 위치 | 개발 내용 |
|---|---|---|
| 1 | `solution/security/SolutionOwnerResolver`·기존 `auth/`(재사용) | LSH AUTH의 Long principal·활성 회원 검증 재사용, 비회원만 세션 익명 UUID·동시 최초 발급. AUTH 코드는 새로 만들지 않음. Redis 통합 실행 환경(현재 compose에 Redis 없음) 확인 |
| 2 | `db/migration/합의한번호`·`solution/domain/SolutionOwner`·기존 엔티티/repository | AUTH/DB와 번호 예약 후 nullable 회원 ID·익명 UUID·CHECK·소유권 조회·JSON 구조/주석 갱신 |
| 3 | `auth/controller/LoginController`·`common/config/SecurityConfig`(기존 파일 확장) | AUTH 합의 후 `GET /api/auth/csrf` 익명 발급, `/api/solutions/**` 변경 요청의 회원·비회원 CSRF 검사, 공개 경로 `/api/solution/**`→`/api/solutions/**` 통일, 보안 오류 형식 |
| 4 | `solution/dto/StartSolutionRequest.java`·`solution/service/SensitiveDescriptionGuard.java`·`common/error/ErrorCode.java` | 선택 설명·엄격한 키/타입·미디어 타입·길이·민감정보 거부. SENSITIVE_DATA_DETECTED(400)와 공통 소유권용 SOLUTION_NOT_FOUND(404) 추가. 후속 STATE_VERSION_CONFLICT(409)는 SOL 경계에서 매핑 |
| 5 | `solution/domain/SolutionFacts.java`·`ResponseRules.java`·`SolutionQuestions.java` | 4개 사실·카드 규칙·첫 질문·미확정/직접 선택 정책 |
| 6 | 합의 3에 따라 LSH `GuideCatalog`(`content/`) 반영 또는 카드·기관 부분만 이식, `content/guides.yml`(또는 합의한 카드 파일)·테스트 fixture | 새 로더를 중복으로 만들지 않음. version·적용 조건 추가, 규칙이 참조하는 카드 코드의 존재·승인 검사, 테스트 리소스 격리. OHC GUIDE-01과의 충돌 정리 |
| 7 | `solution/service/StartSolutionService.java`·저장 전용 서비스 | AI-06 연결·트랜잭션 밖 호출·실패 대체·초기 상태 한 번 저장 |
| 8 | `solution/dto/` 응답·`solution/controller/SolutionController.java` | 카드/질문 DTO·201 ApiResponse·소유자/원문 미노출 |
| 9 | `src/test/java/com/wsid/solution/`·인증/보안·DB 테스트 | 아래 단위/통합/원문 보호 검증 |
| 10 | `docs/sol-01-session-start.md`·solution/auth/content/common README | 실제 변경·연관 파일·DB/쿠키/AI/FastAPI 연결·실행 결과 보고 |

SENSITIVE_DATA_DETECTED와 SOLUTION_NOT_FOUND는 현재 enum에 없다. 기존 ApiException/ErrorResponse 처리 방식을 이용할 수 있지만 추가해야 할 오류 코드를 이미 존재하는 것으로 취급하지 않는다. SOLUTION_NOT_FOUND는 후속 소유권 조회 경계에서 타인 상담과 없는 상담을 구분하지 않는 404로 사용할 계약이다.

기존 [AiClient](../src/main/java/com/wsid/ai/AiClient.java)·[FakeAiClient](../src/main/java/com/wsid/ai/FakeAiClient.java)·[HttpAiClient](../src/main/java/com/wsid/ai/HttpAiClient.java)는 호출 경계로 재사용한다. ai에서 solution 도메인을 import하지 않는다. fake는 정해진 문장만 인식하므로 명세의 자유 문장이 모두 인식된다고 가정하지 않고, 서비스 테스트에는 명시적인 응답 스텁도 사용한다.

FastAPI 저장소에서는 AI-06의 요청 description/knownFacts·필수 4개 사실·uncertainFields·실제 boolean 봉투·내부 키·X-Request-Id를 맞춘다. 설명 미제공은 FastAPI로 빈 문장을 보내지 않고 Spring에서 생략한다. LLM의 사실 추정·개인정보·출력/공급자 로그 검증은 실제 모델 단계에서 수행한다.

## 10. 테스트 계획

| 구분 | 필수 사례 |
|---|---|
| 요청/민감정보 단위 | 본문 없음/{}·null·빈/공백·1,000/1,001자, 숫자/boolean/배열·알 수 없는 소유자 키 거부, 민감정보 양성/음성·일반 금액/날짜 오탐, toString/오류에 원문 없음 |
| 소유권 단위/웹 | 실제 회원 principal·AnonymousAuthenticationToken 구분, 비회원 UUID 재사용·브라우저별 분리·동시 초기화, 로그인/세션 ID 교체 후 익명 UUID가 남아도 회원 소유로만 생성·기존 익명 상담 자동 이관 없음, 개발 계정 강제 귀속 없음·클라이언트 ID 무시/거부 |
| 도메인 | UNKNOWN 4개·허용 사실/값·선택 질문 순서·전부 확정 시 null, 카드 매트릭스·중복 제거·긴급 우선·미검토/누락 카드 감지 |
| 카드 로더 | 실제 타입·source/확인일/버전/기관 코드·reviewed·중복·규칙 참조 검증, main/test 경로 격리·JAR 스트림 |
| 서비스 | 설명 없으면 AI 미호출, 정상 매핑·AiUnavailable fallback 201, 민감정보면 AI/저장 미호출, AI 호출 시 트랜잭션 비활성, AI-07 미호출, 초기 상태/응답과 저장 일치 |
| MVC | 정확한 POST·201 봉투·4개 사실·SOL-01 카드 전체 필드·질문 null/선택지, Content-Type 없는 빈 본문은 선택형 시작, JSON 본문/잘못된 JSON/미디어 타입 415 구분, 400 INVALID_REQUEST/SENSITIVE_DATA_DETECTED·공통 404 매핑, 원문/소유자 정보 미노출 |
| 실제 보안 필터 | 비회원 GET /api/auth/csrf→같은 SESSION 쿠키+X-CSRF-TOKEN POST 201, 토큰 누락/위조/다른 세션 토큰 403·AI/저장 미호출, 회원도 동일하게 검사, `/api/auth/session`·`/api/users/me`의 비회원 401 유지, 기존 로그아웃·훈련 CSRF 회귀, CORS preflight에 SOL 요청 허용 |
| Redis 세션 통합 | 기존 `integrationTest`(PostgreSQL+Redis)에 추가: 익명 UUID/CSRF 저장·세션 만료 후 소유권 상실·로그인 후 회원 소유로 생성·기존 익명 상담 미이관·다른 세션의 상담 접근 차단·동시 최초 발급. 원문 미저장 |
| PostgreSQL/Flyway | 기존 회원 행 보존·새 CHECK/FK, 회원/익명 INSERT·둘 다/둘 다 없음 거부, 초기 version=0, 사실/질문/카드 참조 JSONB, 메시지 순서/요청 ID 유일성, 원문 미저장·실패 롤백 |
| 소유권 DB/후속 조회 경계 | 회원 A/B·익명 A/B·위조 ID·만료/새 세션으로 다른 상담 조회 불가. SOL-02~05 공개 구현 시 같은 검증 재사용 |
| 후속 SOL-03/04 계약 | CHOICE→OPTION 변환·미지원 입력 거부, STATE_VERSION_CONFLICT(409)와 training의 SESSION_VERSION_CONFLICT 분리, 카드 공통 도메인/공개 DTO 형식·기존 주석/JSON 호환 |
| 실제 AI HTTP 연결 | localhost 모의 FastAPI로 경로·내부 키·UUID·본문·실패/타임아웃 대체, 실제 FastAPI 연결은 별도 계약 검증 |
| 실행/JAR·수동 smoke | 기본 쿠키/CSRF·설명 유무·민감정보 거부·fallback·DB 상태/버전·카드 승인 상태, 검토된 운영 카드와 합성 자료 구분 |

DB 변경이 있으므로 일반 `./gradlew test`만으로 완료 처리하지 않는다. Java 21에서 일반 회귀 및 별도 테스트 DB를 이용한 `./gradlew dbTest`·Flyway 업그레이드/빈 DB 생성 검증을 수행한다. 운영 DB에 테스트 데이터를 넣지 않는다. 기존 AI-01/훈련 DB 테스트에도 새 CHECK·보안 체인이 영향을 주지 않는지 확인한다.

Redis 세션 검증도 단위 테스트와 구분해 별도의 통합 실행 범위를 마련한다. 독립 테스트 Redis/namespace와 수명으로 검증하고 다른 서비스의 세션을 삭제하지 않는다. 일반 단위 테스트에 외부 Redis를 무조건 요구하지 않되, 테스트용 세션 대역의 성공을 실제 Redis 연동 완료로 표시하지 않는다. 마이그레이션은 머지 대상 브랜치의 버전 중복까지 확인한다.

현재 [docker-compose.yml](../docker-compose.yml)에는 PostgreSQL만 있고, [AUTH 인프라 문서](auth-infrastructure.md)는 별도 `wsid-redis` 컨테이너를 쓰도록 안내한다. 통합 테스트는 이 프로젝트 전용 PostgreSQL·Redis에서만 실행하고, 같은 포트를 쓰는 다른 프로젝트의 컨테이너에 연결하지 않는다. 사용한 실행 환경과 재현 명령을 구현 보고서에 남긴다.

표의 후속 SOL-03/04 공개 API 검증은 해당 API 구현 시 수행할 범위다. SOL-01에서는 필요한 도메인/변환 경계를 준비하되 미구현 후속 HTTP 경로를 이번에 추가하지 않는다.

기존 DB 테스트는 Mockito AiClient의 suggestDiscovery/suggestTrainingTurn만 설정한다. 새 상담 테스트는 extractSolutionFacts를 명시적으로 설정해 기본 null 반환이 정상 AI 결과처럼 처리되지 않게 한다. 테스트에만 승인 합성 카드를 사용하고 운영 자료를 검토 완료로 바꾸지 않는다.

### LSH AUTH 반영 후 기반 검증 결과 (2026-11-17)

OHC에 반영된 AUTH 커밋 0d21cd6·CORS 커밋 1557fcb를 기준으로 별도 환경에서 DB·Redis 통합 검증을 수행했다. 코드나 기존 개발 인프라 설정을 바꾸지 않고 WSID 전용 컨테이너를 생성했으며 tickertaka 프로젝트의 5432·6379에는 연결하지 않았다.

| 실행 범위 | 결과 | 확인 범위 |
|---|---|---|
| 일반 test | 기존 결과 713건 통과·실패/건너뜀 0 확인 | 외부 DB/Redis가 필요 없는 회귀 결과. 이번 기반 검증에서 다시 실행한 것은 아님 |
| dbTest | 새로 실행, 7건 통과·실패/건너뜀 0 | 기존 DiscoveryDatabaseTest·PostgreSQL/Flyway·훈련 회귀 |
| integrationTest | 새로 실행, 14건 통과·실패/건너뜀 0 | RegistrationIntegrationTest 13건·RedisSessionIntegrationTest 1건. AUTH·CORS·로그인/세션·기존 CSRF 및 Redis 저장/복원 |

검증 환경은 Docker 프로젝트 `wsid-ohc-tests`로 분리했다. 검증 시점에 이 작업 PC의 아래 loopback 포트를 사용했고 저장소의 기본 compose 파일은 수정하지 않았다. 재실행 전에 해당 전용 서비스가 준비됐는지 확인한다.

| 서비스 | 컨테이너·접속 정보 |
|---|---|
| PostgreSQL | wsid-ohc-test-postgres, 127.0.0.1:55432, 테스트 전용 사용자/비밀번호 wsid/wsid |
| AUTH 통합 DB | wsid_auth_test |
| 훈련 DB 테스트 DB | wsid_ai01_test |
| Redis | wsid-ohc-test-redis, 127.0.0.1:56379. 테스트 소스가 고유 namespace 사용 |

테스트 로그의 JDBC URL도 두 전용 DB 주소와 일치했다. 같은 환경이 준비된 Java 21 터미널에서는 다음처럼 접속 위치를 명시한다. 외부 환경의 기본 포트나 개인 .env에 의존해 테스트하지 않는다.

```text
env \
  SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/wsid_auth_test \
  SPRING_DATASOURCE_USERNAME=wsid SPRING_DATASOURCE_PASSWORD=wsid \
  WSID_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wsid_ai01_test \
  SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=56379 \
  SPRING_DATA_REDIS_PASSWORD= \
  SESSION_NAMESPACE=wsid:ohc:test:session \
  AUTH_REDIS_NAMESPACE=wsid:ohc:test:verification AUTH_MAIL_ENABLED=false \
  ./gradlew dbTest integrationTest --no-daemon
```

AUTH 통합 테스트의 메일은 mock으로 처리했다. 이 통과 결과는 현재 반영된 AUTH·기존 훈련의 기반 검증이며 **SOL-01 상담 생성·익명 소유권·비회원 CSRF 확장·민감정보·카드 선택·새 DB 제약의 구현/검증 결과가 아니다.** 11장의 AUTH 합의 1·2와 SOL 개발 항목은 여전히 남아 있다.

## 11. 구현 전 확정 항목과 완료 기준

먼저 확정할 항목은 다음과 같다.

- **AUTH 합의 1:** 기존 `GET /api/auth/csrf`의 비회원 발급 허용(다른 로그인 필수 API 조건 유지)
- nullable user_id/익명 소유자 CHECK·소유권 조회·기존 JSON 호환 및 AUTH/DB와 충돌 없는 마이그레이션 번호 예약
- **AUTH 합의 2:** `/api/solutions/**` 변경 요청을 회원·비회원 모두 CSRF 검사, 공개 경로를 `/api/solutions/**`로 통일. 익명 UUID 동시 발급·익명 세션 만료 기간·명세의 쿠키 발급 시점 문장 정정, 403 오류 형식
- 설명 미제공·실제 타입·민감정보 탐지 범위·400 정책·원문 없는 메시지 저장 방식
- **LSH 합의 3:** 대응 카드 카탈로그 소유와 형식. LSH `GuideCatalog`로 GUIDE 전체를 통일할지, OHC GUIDE-01을 유지하고 카드·기관만 가져올지. 카드 version과 적용 조건(사실 조합 규칙) 추가 위치, 질문 순서, SOL-01/SOL-04 카드 DTO 공유 여부. 실제 운영 카드·기관 내용은 콘텐츠 담당 검토 후 등록
- SENSITIVE_DATA_DETECTED/SOLUTION_NOT_FOUND 추가, CHOICE/OPTION·STATE_VERSION_CONFLICT/SESSION_VERSION_CONFLICT의 공개/내부 매핑 범위
- AI 실패의 201 선택형 전환·실제 SOL 응답 시간 예산·긴급 안내 UX
- 상담 생성 멱등성 필요 여부·응답 유실 재시도·데이터 보관/정리 정책

완료 기준은 LSH AUTH를 재사용한 회원 식별과 비회원 익명 소유권, 비회원 CSRF 발급·검사와 민감정보 처리, AI/선택형 시작, 승인 카드·질문 결정, 초기 버전·원문 없는 원자적 저장, 회원·비회원 상담 생성·잘못된 토큰 403·다른 세션 상담 접근 차단을 포함한 일반/DB/Redis/보안 회귀와 실행 검증까지다. AI 클라이언트를 부르는 코드만 추가하고 SOL-01 전체 완료라고 표시하지 않는다. FastAPI/LLM·운영 카드 승인이 미연결이면 그 범위를 구현 보고서에 명시한다. AI JSON 계약 불일치는 Spring↔FastAPI 사이의 별도 과제로 다룬다.

SOL-01 자체는 계획 단계다. 계획서 검토 이후 LSH 기반 코드의 dbTest·integrationTest는 위 전용 환경에서 실행했지만, SOL-01 코드·마이그레이션·보안 확장이나 실제 LLM 연결을 새로 구현·검증하지는 않았다.
