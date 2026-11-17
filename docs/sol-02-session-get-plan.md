# SOL-02 피해 대응 상담 복원 구현·테스트 계획서

> **구현 완료(2026-11-17).** 실제 구현은 [SOL-02 구현 보고서](sol-02-session-get.md)를 봐요. 계획과 달라진 점: LSH `SecurityConfig`를 고치지 않고 `solution/security/SolutionSecurityConfig`에 공개 규칙을 두었고(비회원 CSRF는 `GET /api/solutions/csrf`), 조회 트랜잭션은 같은 시점 데이터를 위해 REPEATABLE_READ를 쓰며, `Cache-Control`은 Spring Security 기본 헤더로 붙어요.

작성일 2026-11-17 · 기준 브랜치 OHC `e8f1300`(SOL-01 구현 포함) · 기준 문서: 팀 공유 `WSID-API-Spec.md`의 SOL-02 절, [SOL-01 구현 보고서](sol-01-session-start.md)

`GET /api/solutions/sessions/{id}`로 상담의 현재 상태·사실·카드·다음 질문·기록 메시지를 다시 보여준다. 회원은 로그인 ID, 비회원은 SOL-01에서 세션에 발급한 익명 소유자 UUID로만 조회할 수 있다. 명세는 "설계 초안: 개발 전 팀에서 DTO·정책 확정"이므로, 아래 **결정 필요** 항목은 추천안을 적고 팀 확인을 받는다.

## 1. 현재 상태

| 항목 | 현재(OHC `e8f1300`) | SOL-02에서 할 일 |
|---|---|---|
| 소유권 조회 | `SolutionSessionRepository.findOwned(id, owner)`(회원은 `user_id`, 비회원은 `anonymous_owner_id` 조건) | 그대로 사용 |
| 소유자 결정 | `SolutionOwnerResolver.resolve`는 비회원에게 익명 UUID가 **없으면 새로 발급**(SOL-01 생성용) | 조회 전용 경로 추가. 조회는 새 UUID·새 세션을 만들지 않음 |
| 저장 형식 | `facts_json`(영문 키·YES/NO/UNKNOWN), `card_codes_json`(`[{code, version}]`), `next_question_json`(`{factKey, content, options}` 또는 NULL) | 엄격하게 읽어 응답으로 변환 |
| 메시지 | `solution_messages`: sender·input_kind·safe_content(고정 문구만)·selected_code·sequence_no | 공개 메시지 DTO 정의(결정 필요 1) |
| 카드 | `ResponseCardCatalog.findReviewed(code)`(운영 구현은 카드 없음) | 저장된 카드 코드로 다시 조회(결정 필요 2) |
| 보안 | `POST /api/solutions/sessions`만 공개, 나머지 `/api/solutions/**`는 인증 필요 | `GET /api/solutions/sessions/{id}` 공개(CSRF 불필요한 GET) |
| 오류 코드 | `SOLUTION_NOT_FOUND`(404)는 SOL-01에서 미리 추가 | 사용 |

## 2. 응답 계약

```json
{
  "success": true,
  "data": {
    "solutionId": 301,
    "status": "ACTIVE",
    "stateVersion": 2,
    "facts": {"transferredMoney": "YES", "sharedInformation": "NO", "installedApp": "UNKNOWN", "contactContinues": "NO"},
    "responseCards": [{"code": "TRANSFER_RESPONSE", "priority": "URGENT", "title": "송금 관련 우선 대응", "institutionCodes": ["POLICE", "BANK"]}],
    "nextQuestion": {"factKey": "installedApp", "content": "상대방이 알려준 앱을 설치한 적이 있나요?", "options": ["YES", "NO", "UNKNOWN"]},
    "messages": [
      {"sequenceNo": 1, "sender": "USER", "inputKind": "TEXT", "content": "피해 설명을 제출했습니다.", "selectedCode": null},
      {"sequenceNo": 2, "sender": "SYSTEM", "inputKind": "TEXT", "content": "개인정보를 전달한 적이 있나요?", "selectedCode": null}
    ]
  }
}
```

- 200 OK. `solutionId`·`status`·`stateVersion`·`facts`·`responseCards`·`nextQuestion`은 SOL-01 응답과 같은 이름·형식이다. 프론트는 SOL-01 응답과 SOL-02 응답을 같은 화면 상태로 쓸 수 있다.
- `facts`는 항상 4개 키, `responseCards`는 항상 배열, `nextQuestion`은 없으면 명시적 `null`이다.
- `stateVersion`은 DB 값 그대로다. SOL-03/04 요청에서 이 값을 낙관적 잠금 기준으로 보낸다.
- 소유자 ID·익명 UUID·세션 ID·`client_request_id`·카드 version·`reference_codes_json`은 넣지 않는다.
- 응답에 `Cache-Control: no-store`를 붙인다(브라우저·프록시에 상담 내용을 남기지 않음).

### 결정 필요 1 · 메시지 항목 형식

명세 예시는 `"messages": []`뿐이라 항목 형식이 없다. 추천안은 위 예시의 5개 필드다.

| 필드 | 값 | 이유 |
|---|---|---|
| `sequenceNo` | 1부터 | 정렬·중복 표시 방지. DB 유일 제약 있음 |
| `sender` | `USER`/`SYSTEM`/`AI` | 말풍선 위치 |
| `inputKind` | `TEXT`/`CHOICE` | DB의 `OPTION`은 공개 값 `CHOICE`로 변환(SOL-03 공개 값과 맞춤). `ACTION`은 상담에서 쓰지 않으므로 나오면 서버 오류로 본다 |
| `content` | `safe_content` 그대로 | SOL-01은 원문 대신 고정 문구만 저장한다. 원본 피해 설명은 복원하지 않는다(명세) |
| `selectedCode` | 선택 응답의 YES/NO/UNKNOWN 등, 없으면 null | SOL-03 CHOICE 기록 표시 |

`createdAt`은 화면 요구가 없으면 넣지 않는다(필요하면 ISO-8601 UTC로 추가). 메시지 수는 상담 한 건에 수십 개 수준이라 페이지네이션 없이 전부 반환하고, SOL-03에서 상한을 정하면 함께 맞춘다.

### 결정 2 · 카드 복원 기준 (확정: 방안 A, 2026-11-17)

DB에는 카드 코드와 **당시 version**만 있고 카탈로그는 **현재 검토 카드**만 돌려준다.

| 방안 | 내용 | 평가 |
|---|---|---|
| A (확정) | 저장된 코드로 현재 검토 카드를 조회해 보여준다. 버전이 달라도 최신 안내를 쓴다 | 연락처·절차가 바뀌었을 때 최신 안내가 더 안전하다. 카탈로그에 버전 이력이 필요 없다 |
| B | 저장된 version의 카드를 그대로 보여준다 | 당시 화면 재현은 정확하지만 버전별 카드 보관 구조가 필요하다(합의 4 범위 확대) |
| C | 저장값 대신 현재 사실로 `ResponseRules`를 다시 돌린다 | 규칙이 바뀌면 같은 상담의 카드가 바뀐다. 조회가 상태를 재판정하게 되어 SOL-03/04 책임과 섞인다 |

방안 A로 확정했다. 조회는 `ResponseCardCatalog.findReviewed(code)`(현재 검토 카드)만 쓰고, DB의 카드 version은 화면 표시에 쓰지 않는다(어떤 버전을 보여줬는지 남기는 기록용). 따라서 LSH `GuideCatalog`에 버전 이력 구조를 추가할 필요가 없다. 카드 카탈로그를 연결할 때(합의 4) 카드에 version 필드가 없으면 기록용 version을 어떻게 채울지만 정한다.

이때도 저장된 카드가 카탈로그에 없으면(삭제·미검토 전환) **조용히 빼지 않고** SOL-01과 같이 503 `RESPONSE_CONTENT_UNAVAILABLE`로 응답한다. 긴급 카드가 빠진 200을 보내지 않는 원칙을 유지한다. 정렬은 SOL-01과 같이 URGENT 먼저, 같은 우선순위는 저장 순서다.

## 3. 소유권과 비회원 세션

### 조회 전용 소유자 확인

`SolutionOwnerResolver`에 `findExisting(authentication, request)`(가칭)를 추가한다. 반환은 `Optional<SolutionOwner>`이다.

| 요청 상태 | 결과 |
|---|---|
| 로그인 principal(Long) + 활성 회원 | 회원 소유자 → `findOwned`. 남의 상담·익명 상담(로그인 전 자기 상담 포함)이면 404 |
| 로그인 principal + 비활성·삭제 회원 | 401 `LOGIN_REQUIRED`(SOL-01과 같음. 비회원으로 바꾸지 않음) |
| 비로그인 + 세션에 익명 UUID 있음 | 익명 소유자 → `findOwned` |
| 비로그인 + 세션 없음·익명 UUID 없음 | 바로 404. **새 HTTP 세션·새 UUID를 만들지 않음**(`request.getSession(false)`) |

- `AnonymousOwnerIssuer`에는 세션 속성만 읽는 메서드를 추가하고, Redis SETNX 키는 조회에서 쓰지 않는다(발급은 SOL-01만).
- 세션 속성 값이 UUID 형식이 아니면 위조·손상으로 보고 404로 끝낸다(속성을 덮어쓰지 않음).
- 상담 ID만 알아도 조회할 수 없다. 없는 상담·남의 상담·소유자 정보 없음은 모두 같은 404 본문(`SOLUTION_NOT_FOUND`)이다. 응답 시간 차이로 존재 여부가 드러나지 않게, 소유자가 없을 때도 DB를 조회하지 않는 경로가 다른 경로보다 눈에 띄게 빠르다는 점은 수용 가능한 수준으로 기록만 한다.

### 결정 3 · 로그인 뒤 이전 익명 상담 (확정: 방안 A, 2026-11-17)

SOL-01은 "로그인 후 새 상담은 회원 소유, 기존 익명 상담 자동 이관 없음"으로 구현했다. 같은 브라우저에서 비회원으로 만든 상담을 로그인 뒤 다시 열면 어떻게 할지 정해야 한다.

| 방안 | 내용 |
|---|---|
| A (확정) | 로그인 상태에서는 회원 소유 상담만 조회한다. 이전 익명 상담은 404. SOL-01의 "회원 우선·이관 없음"과 일관되고 규칙이 하나다 |
| B | 회원 소유가 아니면 세션에 남은 익명 UUID로 한 번 더 확인한다. 같은 브라우저에서는 이어 볼 수 있지만, 한 요청에 소유자 두 종류가 섞이고 SOL-03~05 변경 권한도 같은 규칙을 따라야 한다 |

방안 A로 확정했다. 로그인 뒤 이전 익명 상담은 지워지지 않지만 열 수 없어서(404), 사용자는 새 상담을 처음부터 다시 시작한다. 새 상담은 회원 소유라 다른 기기에서도 이어 볼 수 있다.

- 프론트: 진행 중인 비회원 상담이 있을 때 로그인 버튼을 누르면 "로그인하면 지금 상담은 이어 볼 수 없어요. 계속할까요?"를 먼저 보여준다.
- 다시 입력하게 하는 불편이 크다고 판단되면 로그인 시점에 익명 상담을 회원 소유로 옮기는 이관 기능(방안 C)을 LSH 담당과 별도로 추가한다. SOL-02 조회 코드는 거의 바뀌지 않는다.

### 비회원 복원 범위(명세)

- 쿠키 분실·Redis 세션 만료(현재 공통 30분 비활동) 뒤에는 이전 상담을 복원할 수 없다. 이 경우도 404이고, 프론트는 404를 받으면 "상담을 다시 시작해 주세요"로 안내한다.
- 서버는 404에 "만료" 같은 구분 정보를 넣지 않는다(존재 여부 노출 방지).

## 4. 경로·입력 검증

- `{id}`가 1 이상 `Long` 범위의 10진수가 아니면(문자, 0, 음수, 범위 초과) **404 `SOLUTION_NOT_FOUND`**로 응답한다. 명세 오류 코드에 404만 있고, 형식 오류를 400으로 구분하면 존재 여부와 무관한 정보가 늘기만 한다. 컨트롤러에서 문자열로 받아 직접 변환하고, 전역 `MethodArgumentTypeMismatchException` 처리는 바꾸지 않는다.
- 쿼리 파라미터·본문은 받지 않는다(무시).

## 5. 보안 설정

- `SecurityConfig`: `GET /api/solutions/sessions/{id}`를 `permitAll`에 추가한다. 다른 메서드·하위 경로는 계속 막는다.
- GET이므로 CSRF 검사 대상이 아니다. 기존 `/api/solutions/**` CSRF 조건(변경 메서드만)을 그대로 둔다.
- CORS 허용 메서드에 GET이 이미 있다. 쿠키 포함 요청(credentials)으로 호출한다.
- **LSH 확인:** `SecurityConfig` 변경이 한 줄 더 생긴다. SOL-01 확인 요청과 묶어서 전달한다.

## 6. 조회 처리와 저장 데이터 해석

```text
경로 id 검증 → 조회 전용 소유자 확인(없으면 404)
  → 읽기 전용 트랜잭션: findOwned → 메시지 sequence 순 조회
  → JSON 컬럼 엄격 변환 → 카드 재조회(없으면 503) → 응답 DTO
```

- `SolutionQueryService`(가칭)는 `@Transactional(readOnly = true)`로 상담과 메시지를 같은 스냅샷에서 읽는다. 상태·버전을 바꾸지 않고 AI를 호출하지 않는다.
- 변환은 `SolutionSession`/도메인 쪽에 읽기 메서드로 둔다.
  - `facts_json` → `SolutionFacts.fromStrings`(4개 키·허용 값만)
  - `card_codes_json` → `[{code, version}]` 목록(코드 형식·version ≥ 1)
  - `next_question_json` → `SolutionQuestion`(factKey가 4개 사실 중 하나, options는 YES/NO/UNKNOWN)
- 저장값이 형식에 맞지 않으면 서버 오류(500)로 처리하고 내용 없이 상담 ID·컬럼 이름만 로그에 남긴다. 조용히 UNKNOWN이나 빈 배열로 바꾸지 않는다. 현재 이 컬럼을 쓰는 곳은 SOL-01뿐이라 옛 한글 키 형식 데이터는 없지만, 공유 DB에 수동 데이터가 있는지 머지 전에 확인한다.
- `status`가 `COMPLETED`여도 200으로 조회한다(완료 후 다시 보기). 완료 상담의 `nextQuestion`은 저장값대로 보낸다(SOL-05 구현 시 null로 비우는 규칙을 정함).
- `nextQuestion`은 저장값을 쓴다. 조회 때 사실에서 다시 계산하지 않는다(SOL-03이 질문을 바꿀 수 있음).

## 7. 파일별 개발 순서

| 순서 | 위치 | 내용 |
|---|---|---|
| 1 | `solution/domain/SolutionSession`·`CardReference`(가칭) | JSON 컬럼 읽기 메서드와 엄격 변환 |
| 2 | `solution/security/AnonymousOwnerIssuer`·`SolutionOwnerResolver` | 세션 속성만 읽는 조회 전용 소유자 확인(세션·UUID 생성 없음) |
| 3 | `solution/dto/SolutionStateResponse`(가칭) | SOL-01과 같은 Card·Question 형식 재사용 + Message 항목(결정 1) |
| 4 | `solution/service/SolutionQueryService` | 읽기 전용 트랜잭션, findOwned, 카드 재조회(결정 2), 404/503 |
| 5 | `solution/controller/SolutionController` | `GET /sessions/{id}`, id 직접 검증, `Cache-Control: no-store` |
| 6 | `common/config/SecurityConfig` | GET 공개 한 줄(LSH 확인) |
| 7 | 테스트 | 아래 8장 |
| 8 | `docs/sol-02-session-get.md`·solution README·`docs/api.md` | 구현 보고서와 상태 갱신 |

## 8. 테스트 계획

| 구분 | 필수 사례 |
|---|---|
| 도메인 단위 | 저장 JSON 3종의 정상 변환, 키 누락·추가·잘못된 값·잘못된 카드 version·잘못된 질문 factKey 거부, null 질문 |
| 소유자 단위 | 세션 없음 → 빈 값이고 `getSession(false)`만 호출, 속성 없음·UUID 아님 → 빈 값, Redis 미호출, 익명 UUID가 남아 있어도 회원만 사용(결정 3), 비활성 회원 401 |
| 서비스 단위 | 소유자 없음 → DB 미조회 404, 남의 상담 404, 메시지 sequence 순서, OPTION→CHOICE 변환, 카드 없음 503, AI 미호출, 상태·버전 불변 |
| MVC·보안 필터 | CSRF 토큰 없이 GET 200, 응답 형식(4개 사실·카드 필드·명시적 null 질문·메시지 필드), 소유자·UUID·version 미노출, `no-store`, `abc`·`0`·`-1`·범위 초과 id 404, 세션 없는 GET에 `Set-Cookie` 없음, 다른 메서드(PUT·DELETE) 차단, 비활성 회원 401 |
| PostgreSQL(`dbTest`) | SOL-01로 만든 회원·익명 상담을 다른 회원·다른 익명 UUID로 조회 불가, 읽기 후 `state_version` 불변, 메시지 정렬 |
| Redis 통합(`integrationTest`) | 비회원 GET csrf → POST 생성 → 같은 쿠키 GET 200, 다른 브라우저 쿠키 404, 쿠키 없음 404·새 세션 미생성, 로그인 뒤 이전 익명 상담 404(결정 3 확정 A)·회원 상담 200, 다른 회원 404 |

DB·Redis 테스트는 SOL-01과 같은 전용 컨테이너(127.0.0.1:55432, 127.0.0.1:56379)와 [SOL-01 보고서](sol-01-session-start.md) 9장의 명령으로만 실행한다. 일반 `./gradlew test` 통과만으로 완료 처리하지 않는다.

## 9. 범위 밖

- 상담 목록 조회(내 상담 목록), 익명 상담의 회원 이관, 메시지 페이지네이션.
- SOL-03(메시지·선택 응답), SOL-04(사실 수정), SOL-05(완료)의 상태 변경. 단, 이번에 만드는 조회 전용 소유자 확인·JSON 읽기 메서드는 이들이 재사용한다.
- 운영 카드 콘텐츠 등록(합의 4).

## 10. 확인·결정 항목 요약

1. **메시지 항목 형식**(결정 1): `sequenceNo·sender·inputKind(CHOICE 공개)·content·selectedCode` 추천. 프론트와 확인.
2. **카드 복원 기준**(결정 2): **방안 A로 확정.** 저장 코드로 현재 검토 카드를 보여주고, 카드가 없으면 503. LSH 카드 구조 변경 불필요.
3. **로그인 뒤 이전 익명 상담**(결정 3): **방안 A로 확정.** 회원 상담만 조회하고 이전 익명 상담은 404. 프론트에 로그인 전 안내 문구 요청.
4. **잘못된 id 형식**: 400 대신 404로 통일 추천.
5. **LSH 확인**: `SecurityConfig`에 GET 공개 규칙 추가(SOL-01 확인 요청과 함께).
6. **명세 정정**: 메시지 항목 형식, 503 `RESPONSE_CONTENT_UNAVAILABLE`, 잘못된 id도 404라는 점을 SOL-02 절에 추가.
