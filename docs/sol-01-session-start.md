# SOL-01 피해 대응 상담 시작 구현 보고서

작성일 2026-11-17 · 브랜치 OHC(LSH AUTH `0d21cd6`·`1557fcb` 반영 후) · 계획서: [SOL-01 구현·테스트 계획서](sol-01-session-start-plan.md)

`POST /api/solutions/sessions`로 회원·비회원 모두 피해 대응 상담을 시작할 수 있어요. 설명이 있으면 AI-06으로 사실을 추출하고, 없거나 AI가 실패하면 직접 선택 질문으로 시작해요. 설명 원문은 DB·세션·로그에 남기지 않아요.

> **2026-11-17 변경: LSH 파일 미수정 방식.** 처음에는 LSH의 `LoginController`·`SecurityConfig`를 고쳤지만, SOL-02 작업에서 두 파일을 LSH 원본(`1557fcb`)으로 되돌리고 SOL 전용 보안 설정(`solution/security/SolutionSecurityConfig`)과 비회원용 `GET /api/solutions/csrf`로 옮겼어요. 아래 내용은 바뀐 방식 기준이에요. 자세한 이유와 파일 관계는 [SOL-02 구현 보고서](sol-02-session-get.md) 2장을 봐요. ★ 항목은 여전히 팀 확인 대상이에요.

## 1. 결정 사항과 적용 상태

| 항목 | 적용한 내용 | 상태 |
|---|---|---|
| 로그인 방식 | 기존 세션 로그인 그대로(JWT·refresh 없음) | 팀 결정 그대로 |
| CSRF 방식(합의 1) | LSH의 CSRF 토큰 방식 유지. 토큰은 서버 세션 안에 있고 별도 만료·재발급 없음 | ★ 확인 필요 |
| 비회원 CSRF 발급(합의 2) | LSH `GET /api/auth/csrf`는 그대로 회원 전용. 비회원은 상담 전용 `GET /api/solutions/csrf`로 받음(같은 세션 토큰 저장소라 회원 토큰도 상담에 통과) | ★ 팀에 알림(팀 가이드는 기존 API 확장이었음) |
| SOL CSRF 검사·경로(합의 3) | `SolutionSecurityConfig`가 `/api/solutions/**`만 맡아 POST·PATCH 등을 회원·비회원 모두 검사. 공개 경로는 csrf·SOL-01·SOL-02만, 나머지는 차단. LSH `SecurityConfig`는 미수정(옛 `/api/solution/**` 규칙도 그대로) | ★ 팀에 알림 |
| 대응 카드 카탈로그(합의 4) | `ResponseCardCatalog` 인터페이스만 만들고 운영 구현은 "카드 없음". LSH `GuideCatalog`는 가져오지 않음 | 합의 대기 |
| 마이그레이션 번호(합의 5) | `V3__support_anonymous_solution_owners.sql` | ★ 확인 필요 |
| 민감정보 정책 | 감지 시 400 거부(가리고 진행하지 않음). 탐지 기준은 초안 | FastAPI 쪽과 기준 맞춤 필요 |

## 2. 요청 흐름

```text
1. 브라우저 → GET /api/solutions/csrf (비회원 허용. 회원은 /api/auth/csrf도 가능)
   서버 → Redis 세션 + SESSION 쿠키 + {headerName: "X-CSRF-TOKEN", token}
2. 브라우저 → POST /api/solutions/sessions (같은 쿠키 + X-CSRF-TOKEN 헤더 + 선택 설명)
3. 서버
   CSRF 확인(SolutionSecurityConfig) → 요청 형식 확인(StartSolutionRequest)
   → 민감정보 검사(SensitiveDescriptionGuard) → 소유자 결정(SolutionOwnerResolver)
   → 설명이 있으면 AI-06 호출(트랜잭션 밖) / 없거나 실패하면 UNKNOWN 4개
   → 카드 규칙(ResponseRules) + 다음 질문(SolutionQuestion)
   → 저장 트랜잭션: 상담 INSERT → 접수·질문 메시지 INSERT → flush
   → 201 {success, data}
```

민감정보 검사는 소유자 결정보다 먼저예요. 거부된 요청으로 익명 소유자 UUID를 발급하거나 상담 행을 만들지 않아요.

## 3. 요청·응답

| 요청 | 결과 |
|---|---|
| 본문 없음(Content-Type 없음), `application/json` 빈 본문, `{}`, `{"description": null}`, 빈/공백 문자열 | 201 선택형 시작 |
| `{"description": "..."}` (1,000자 이하, 원문 `String.length` 기준) | 201, AI-06 추출 |
| 잘못된 JSON, 객체가 아닌 본문, `description` 외 키(`userId` 등), 문자열이 아닌 `description`, 1,001자 이상 | 400 `INVALID_REQUEST` |
| JSON이 아닌 본문(`text/plain`, Content-Type 없는 본문) | 415 |
| 민감정보 감지 | 400 `SENSITIVE_DATA_DETECTED`(입력값을 응답에 넣지 않음) |
| CSRF 토큰 없음·위조·다른 세션 토큰 | 403 `CSRF_TOKEN_INVALID` |
| 로그인 principal은 있으나 비활성·삭제 회원 | 401 `LOGIN_REQUIRED`(비회원으로 바꾸지 않음) |
| 규칙이 요구하는 검토 카드가 카탈로그에 없음 | 503 `RESPONSE_CONTENT_UNAVAILABLE`(행 생성 없음) |
| Redis 장애로 익명 소유자 발급 불가 | 503 `AUTH_SERVICE_UNAVAILABLE` |

성공 응답 예시(송금 YES, 연락 NO):

```json
{
  "success": true,
  "data": {
    "solutionId": 301,
    "status": "ACTIVE",
    "stateVersion": 0,
    "facts": {"transferredMoney": "YES", "sharedInformation": "UNKNOWN", "installedApp": "UNKNOWN", "contactContinues": "NO"},
    "responseCards": [{"code": "TRANSFER_RESPONSE", "priority": "URGENT", "title": "송금 관련 우선 대응", "institutionCodes": ["POLICE", "BANK"]}],
    "nextQuestion": {"factKey": "sharedInformation", "content": "개인정보를 전달한 적이 있나요?", "options": ["YES", "NO", "UNKNOWN"]}
  }
}
```

- `facts`는 항상 4개 키(위 순서)와 YES/NO/UNKNOWN이에요.
- `responseCards`는 항상 배열이고, 카드 `version`은 응답에 넣지 않고 DB에만 저장해요.
- 모든 사실이 확정되면 `nextQuestion`은 명시적 `null`이고 상태는 ACTIVE로 남아요.
- 소유자 ID·익명 UUID·세션 ID·원문은 응답에 없어요.

현재 운영 카드가 비어 있어서, 실제 서버에서는 송금 YES나 앱 설치 YES가 나오면 503이에요. 카드가 필요 없는 조합(설명 없음, AI 실패, 해당 사실 NO/UNKNOWN)은 정상 201이에요. 긴급 안내가 빠진 201을 보내지 않으려는 의도예요.

## 4. 소유권

| 경우 | 소유자 |
|---|---|
| 로그인 principal(Long) + 활성 회원 | `user_id` |
| 인증 없음·익명 토큰 | HTTP 세션의 익명 UUID → `anonymous_owner_id` |
| 로그인 후 세션에 익명 UUID가 남아 있음 | 회원 우선. 이전 익명 상담은 자동 이관하지 않음 |

- **익명 UUID 발급(`AnonymousOwnerIssuer`):** 세션 속성 `wsid.solution.anonymousOwnerId`에 있으면 재사용해요. 없으면 Redis `SETNX`(키 `wsid:solution:anonymous-owner:{SHA-256(세션 ID)}`, TTL = `spring.session.timeout`)로 하나만 이기게 하고, 이긴 값을 세션에 저장해요. 서버가 여러 대여도 같은 세션의 동시 첫 요청은 같은 UUID를 받아요.
- **조회(`SolutionSessionRepository.findOwned`):** 회원은 `user_id`, 비회원은 `anonymous_owner_id` 조건으로만 조회해요. SOL-02~05가 이걸 쓰고, 없으면 `SOLUTION_NOT_FOUND`(404)로 남의 상담과 없는 상담을 구분하지 않아요.
- **세션 만료:** Redis 세션이 만료되거나 쿠키를 잃으면 이전 익명 상담은 복원할 수 없어요(SOL-02 명세와 같음).

## 5. DB 변경 (`V3__support_anonymous_solution_owners.sql`)

- `solution_sessions.user_id`의 NOT NULL을 해제했어요(users 외래키는 유지).
- `anonymous_owner_id UUID NULL`을 추가했어요.
- `ck_solution_sessions_single_owner`: 두 소유자 중 정확히 하나만 있어야 해요.
- `ix_solution_sessions_anonymous_status`: 익명 소유자·상태 부분 인덱스예요.

기존 회원 상담 행은 `anonymous_owner_id = NULL`이라 그대로 제약을 만족해요. JSON 형식도 새 상담부터 정했어요.

| 컬럼 | 형식 |
|---|---|
| `facts_json` | `{"transferredMoney":"YES",...}` 영문 키·YES/NO/UNKNOWN |
| `card_codes_json` | `[{"code":"TRANSFER_RESPONSE","version":1}]` 본문 복사 없이 코드·버전만 |
| `next_question_json` | `{"factKey","content","options"}` 또는 NULL |
| `fraud_type_code` | 시작 시 추정하지 않아 NULL |

메시지는 원문 없이 고정 문구만 남겨요.

| 순서 | 내용 | `client_request_id` |
|---|---|---|
| 1 | 설명 있음: USER/TEXT "피해 설명을 제출했습니다." · 설명 없음: SYSTEM/TEXT "설명 없이 직접 선택으로 상담을 시작했습니다." | 서버 추적 UUID(AI-06 `X-Request-Id`와 같음) |
| 2 | 다음 질문이 있으면 SYSTEM/TEXT 질문 문구 | NULL |

초기 상태를 모두 채운 뒤 한 번 INSERT해서 `state_version`이 0이에요. 메시지 저장이 실패하면 상담 행도 함께 롤백돼요.

## 6. 사실·질문·카드 규칙

- **질문 순서:** transferredMoney → sharedInformation → installedApp → contactContinues 중 첫 UNKNOWN이에요. 명세 예시와 같은 "개인정보를 전달한 적이 있나요?" 외 3개 문구는 초안이라 콘텐츠 담당 검토가 필요해요.
- **카드 규칙:** 명세 예시 두 개만 넣었어요. 송금 YES → `TRANSFER_RESPONSE`, 앱 설치 YES → `MALICIOUS_APP_RESPONSE`. 다른 조합은 콘텐츠 담당이 검토한 매트릭스로 추가해요. 우선순위는 카드 콘텐츠의 `priority`로만 정하고 URGENT를 먼저 보여줘요.
- **AI 결과:** `AiUnavailableException`(실패·타임아웃·계약 위반)만 직접 선택으로 넘겨요. 요청 구성 오류 같은 다른 예외는 숨기지 않아요. AI-07은 호출하지 않아요.

## 7. 민감정보 검사(초안)

| 거부 | 허용 |
|---|---|
| 주민등록번호(6-7자리) | 쉼표 금액(1,000,000원), 50만원, 300000원 |
| 카드번호(4자리×4, 공백·하이픈) | 날짜(2026-11-17, 2026.11.17)·시각 |
| 하이픈 숫자 9자리 이상(계좌·전화번호), 붙은 숫자 10자리 이상 | "인증번호를 알려줬어요"처럼 숫자 없는 문장 |
| 인증번호·인증코드·비밀번호·OTP·보안카드 뒤 4~8자리 숫자 | |

정규식이라 모든 개인정보를 찾지는 못해요. FastAPI의 `app/guard/sensitive.py`와 같은 기준으로 맞춰 확정해요.

## 8. 바뀐 파일

| 파일 | 역할 |
|---|---|
| `solution/domain/FactValue`·`SolutionFact`·`SolutionFacts` | 4개 사실과 값. 질문 순서·문구, 외부 문자열 엄격 변환 |
| `solution/domain/SolutionQuestion` | 첫 UNKNOWN 질문, 모두 확정이면 없음 |
| `solution/domain/ResponseCard`·`CardPriority`·`ResponseCardCatalog`·`ResponseRules` | 카드 형식(LSH Card + version), 조회 경계, 사실→카드 규칙·정렬·누락 감지 |
| `solution/domain/SolutionOwner` | 회원 ID 또는 익명 UUID 중 하나 |
| `solution/domain/SolutionSession` | `anonymous_owner_id`, nullable `user_id`, 초기 상태를 한 번에 만드는 `start` |
| `solution/repository/SolutionSessionRepository` | 익명 소유자 조회, 소유자 종류별 `findOwned` |
| `solution/security/SolutionOwnerResolver`·`AnonymousOwnerIssuer` | 소유자 결정, Redis SETNX 익명 UUID 발급 |
| `solution/content/UnavailableResponseCardCatalog` | 운영 기본 카탈로그(카드 없음). 합의 4 뒤 교체 |
| `solution/dto/StartSolutionRequest`·`StartSolutionResponse` | 엄격한 요청 검사, 공개 응답 |
| `solution/service/SensitiveDescriptionGuard`·`StartSolutionService` | 민감정보 거부, 시작 흐름·트랜잭션 |
| `solution/controller/SolutionController` | `POST /api/solutions/sessions` |
| `common/error/ErrorCode` | `CSRF_TOKEN_INVALID`(403), `SENSITIVE_DATA_DETECTED`(400), `SOLUTION_NOT_FOUND`(404), `RESPONSE_CONTENT_UNAVAILABLE`(503) |
| `solution/security/SolutionSecurityConfig` ★ | `/api/solutions/**` 전용 보안 체인: SOL CSRF 검사, 공개 경로, CSRF 거부 JSON 응답(처음엔 `common/config/SecurityConfig`를 고쳤다가 이 파일로 옮김) |
| `solution/controller/SolutionController`의 `GET /csrf` ★ | 비회원 CSRF 발급(처음엔 `auth/controller/LoginController`를 고쳤다가 되돌림) |
| `db/migration/V3__support_anonymous_solution_owners.sql` ★ | 비회원 소유자 컬럼·제약·인덱스 |

CSRF 거부 JSON 403(`CSRF_TOKEN_INVALID`)은 `/api/solutions/**`에만 적용돼요. LSH의 로그아웃·훈련 요청은 원래대로 빈 403이에요.

## 9. 테스트 결과

| 실행 | 결과 | 새로 추가한 테스트 |
|---|---|---|
| `./gradlew test` | 784건 통과(SOL-01 시점. SOL-02 반영 후 수치는 SOL-02 보고서)(기존 713 + 71) | `SolutionDomainTest`, `StartSolutionRequestTest`(요청·민감정보), `StartSolutionServiceTest`, `SolutionOwnerResolverTest`, `SolutionWebSecurityTest`(실제 SecurityConfig·CSRF 필터·MVC 바인딩) |
| `./gradlew dbTest` | 11건 통과(기존 7 + 4) | `SolutionDatabaseTest`: V3 적용, 버전 0, JSONB 형식, 원문 미저장, 소유자 분리, CHECK·FK, 메시지 실패 롤백, AI 호출 시 트랜잭션 없음 |
| `./gradlew integrationTest` | 16건 통과(기존 14 + 2) | `SolutionIntegrationTest`: 실제 서버·Redis 세션으로 비회원 GET csrf → POST 201, 토큰 누락·다른 세션 토큰 403, 같은 세션 같은 소유자·다른 세션 다른 소유자, 민감정보 400, 카드 없는 조합 503, 로그인 후 회원 소유·익명 상담 미이관 |
| `./gradlew bootJar` | 성공 | |

DB·Redis 테스트는 전용 컨테이너(`wsid-ohc-test-postgres` 127.0.0.1:55432, `wsid-ohc-test-redis` 127.0.0.1:56379)에서만 실행했어요. 실행 후 테스트 상담 행과 Redis 키가 남지 않은 것도 확인했어요.

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/wsid_auth_test \
SPRING_DATASOURCE_USERNAME=wsid SPRING_DATASOURCE_PASSWORD=wsid \
WSID_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/wsid_ai01_test \
SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT=56379 \
SESSION_NAMESPACE=wsid:ohc:test:session AUTH_REDIS_NAMESPACE=wsid:ohc:test:verification \
AUTH_MAIL_ENABLED=false ./gradlew dbTest integrationTest --no-daemon
```

`WSID_TEST_DB_URL`을 빼면 DB 테스트가 `localhost:5432`로 접속해요. 이 PC에서는 그 포트를 다른 프로젝트가 쓰므로 반드시 지정해요.

## 10. 남은 일

1. **팀 확인:** ★ 항목(CSRF 방식 유지, 상담 전용 CSRF 발급·보안 설정을 SOL에 둔 것, V3 번호). 나중에 하나로 합치기로 하면 `SolutionSecurityConfig`의 규칙을 `SecurityConfig`로 옮기고 `/api/solutions/csrf`를 지우면 돼요.
2. **카드 카탈로그(합의 4):** LSH `GuideCatalog`의 카드·기관을 가져와 `ResponseCardCatalog`를 구현하고 `version`을 추가해요. 그 뒤 `UnavailableResponseCardCatalog`를 지워요. 실제 카드는 콘텐츠 담당 검토 후 등록해요.
3. **콘텐츠 검토:** 질문 문구 3개, 사실 조합별 카드 매트릭스.
4. **민감정보 기준:** FastAPI 쪽 검사와 같은 기준으로 확정하고 오탐·미탐 사례를 테스트에 추가해요.
5. **명세 정정:** SOL-01의 "최초 호출 시 세션·쿠키 발급"은 GET csrf → POST 흐름으로 바뀌었어요. 오류 코드 4개도 명세에 추가해야 해요.
6. **알려진 한계:**
   - 응답이 유실되어 다시 POST하면 상담이 하나 더 생겨요. 생성 멱등성이 필요하면 Idempotency-Key 계약을 따로 정해요.
   - AI 대기 시간은 공통 20초 제한을 써요.
   - FastAPI에는 AI-06이 아직 없어서, 실제 서버에서 `AI_MODE=http`이면 AI-06 호출이 실패하고 직접 선택으로 시작해요.
7. **후속 API:** SOL-02~05는 `findOwned`와 `SOLUTION_NOT_FOUND`를 쓰고, 각 경로를 `SolutionSecurityConfig`에 공개할 때 소유권 검사를 함께 넣어요.
