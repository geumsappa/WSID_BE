# SOL-05 피해 대응 상담 종료 구현·테스트 계획서

> **구현 완료(2026-11-17).** 실제 파일별 상호작용·테스트 결과·팀 전달 내용은 [SOL-05 구현 보고서](sol-05-session-complete.md)를 참고한다. 본문 없음/빈 객체 허용, 반복 무변경 200, 최초 종료 한 번만 시각·버전 갱신, 완료 후 질문 숨김을 채택했다. 아래는 구현 전 설계와 결정 근거를 보존한 내용이다.

작성일: 2026-11-17 · 상태: **구현 완료, 계획과 결정 근거 보존** · 코드 기준: OHC `29f957e`(SOL-04 구현 포함)

명세 출처는 팀 공유 API 명세의 SOL-05 절이다. [SOL-02 구현 보고서](sol-02-session-get.md), [SOL-03 구현 보고서](sol-03-session-message.md), [SOL-04 구현 보고서](sol-04-facts-update.md)와 현재 코드를 함께 확인했다. 명세에 없는 세부 동작은 **제안 정책**으로 구분한다. 최초 계획 작성에서는 문서만 변경했고, 이후 사용자 요청으로 상담 코드·테스트·보안 경로를 구현했다. 새 DB 마이그레이션은 없다.

## 1. 목표와 범위

`POST /api/solutions/sessions/{id}/complete`로 본인 상담을 ACTIVE에서 COMPLETED로 변경한다. 최초 종료 시각을 기록하고 이후 새 메시지·사실 수정을 차단한다. 회원·비회원 모두 기존 소유권과 CSRF 검사를 통과해야 한다.

AI·FastAPI·카드 카탈로그를 호출하지 않는다. 종료를 위해 사실을 다시 추출하거나 카드 승인을 기다리지 않는다. 새 보고서 생성·회원 로그아웃·Redis 세션 삭제·기존 대화 삭제도 수행하지 않는다. 관련 저장은 solution_sessions에 한정하는 안이다.

```text
브라우저(SESSION 쿠키 + X-CSRF-TOKEN, 본문 없음 또는 빈 JSON 객체)
  → SOL 전용 보안 체인: 회원·비회원 CSRF 검사
  → 컨트롤러: ID → 본문 없음/빈 객체 검사
  → 기존 회원/익명 소유자 결정
  → 짧은 READ COMMITTED 트랜잭션
     → findOwned로 본인 상담 확인
     → 이미 COMPLETED: 변경 없이 성공
     → ACTIVE: 소유권·ACTIVE 조건의 원자적 종료 UPDATE
     → 0행 경쟁은 캐시를 버리고 소유권/현재 상태 재조회
  → 200: solutionId / status=COMPLETED
```

개발 범위는 종료 서비스·응답 DTO·종료용 조건부 UPDATE·컨트롤러/전용 보안 경로·종료 후 복원 표시·테스트·구현 보고서다. 기존 ACTIVE 갱신 CAS를 종료용으로 억지로 사용하지 않고 같은 상담 행을 갱신하는 별도 메서드를 추가한다.

## 2. 현재 코드 상태와 재사용 경계

Java 경로의 공통 접두사는 `src/main/java/com/wsid/`다.

| 파일 | 현재 동작 | SOL-05 작업 |
|---|---|---|
| `solution/domain/SolutionSession.java` | ACTIVE/COMPLETED status, completed_at, updated_at, @Version 필드 존재. 종료 기능 없음 | 상태 읽기 재사용. 완료 시각을 테스트/검증할 읽기 접근자 필요 시 추가. 엔티티 직접 수정과 SQL 갱신을 혼합하지 않음 |
| `solution/domain/SolutionStatus.java` | ACTIVE·COMPLETED 두 값 | 그대로 사용. 새 상태/재개 상태를 임의 추가하지 않음 |
| `solution/repository/SolutionSessionRepository.java` | findOwned로 회원/익명 소유권 조회 | 그대로 사용 |
| `solution/repository/SolutionSessionUpdates.java`, `SolutionSessionUpdatesImpl.java` | ACTIVE·기대 버전 CAS로 사실/카드/질문/버전 갱신. JPA 캐시 정리 | completeActive 같은 종료 전용 메서드 추가. 기존 compareAndSet은 유지 |
| `solution/service/SendSolutionMessageService.java` | 새 요청은 ACTIVE 검사 후 버전 확인. UUID 중복 검사는 상태 검사보다 먼저 | 종료 후 새 입력 차단 재사용. 늦은 AI 응답과 종료 경쟁 테스트 추가 |
| `solution/service/UpdateSolutionFactsService.java` | ACTIVE 검사 후 버전 확인. 직접 수정·카드/질문·수정 기록 저장 | 종료 후 수정 차단 재사용. 종료와 수정의 저장 순서 검증 |
| `solution/service/SolutionQueryService.java` | 상태와 저장된 질문을 반환, 현재 검토 카드로 복원. REPEATABLE_READ | 완료 상담의 질문은 응답에서 null로 표시하는 안. 카드 복원 실패 정책은 별도로 유지 |
| `solution/controller/SolutionController.java` | 시작·복원·메시지·facts PATCH 구현. ID 검사·공유 JSON 파서 존재 | 본문 없음 또는 빈 객체를 받는 complete POST 추가. 본문이 있을 때만 엄격한 JSON 파싱 |
| `solution/security/SolutionOwnerResolver.java` | findExisting으로 기존 소유자만 결정. 활성 회원 우선 | 그대로 사용. 신규 익명 ID/상담 생성 금지 |
| `solution/security/SolutionSecurityConfig.java` | 구현된 SOL-01~04만 허용. complete POST는 차단 | 정확한 complete POST만 공개, CSRF 유지 |
| `common/error/ErrorCode.java` | SOLUTION_NOT_FOUND·SOLUTION_FINISHED·STATE_VERSION_CONFLICT·CSRF_TOKEN_INVALID 등 존재 | 기존 오류 재사용. 정상 중복 종료를 충돌 오류로 만들지 않음 |
| `src/main/resources/db/migration/V1__init_schema.sql` | status·completed_at·updated_at·state_version 컬럼 존재 | 새 테이블/컬럼 불필요. 기존 V1~V4 변경 없음 |

SOL-04는 현재 SOL-03과 같은 전체 카드·nextQuestion·stateVersion 응답을 사용한다. 과거 SOL-04 계획서의 요약 카드/GET 재조회 제안보다 실제 구현 보고서를 기준으로 연동한다. SOL-05 응답은 아래 명세의 두 필드를 유지한다.

## 3. 요청·응답 계약

요청은 경로 ID뿐이며 **본문·requestId·stateVersion은 요구하지 않는다.** 새로운 필수 입력을 조용히 추가하지 않는다.

```text
POST /api/solutions/sessions/301/complete
Cookie: SESSION=...
X-CSRF-TOKEN: ...
본문 없음
```

**채택할 본문 정책은 본문 없음 또는 빈 JSON 객체 `{}` 허용이다.** 프론트에는 본문 없는 호출을 기본으로 안내하되 HTTP 래퍼가 `{}`를 자동으로 붙여도 수락한다. 빈 객체는 바이트 표현이 아니라 JSON 파싱 결과로 판단하므로 앞뒤/객체 내부의 JSON 공백은 허용한다. 공백만 있는 본문, `null`, 배열, 문자열, 값이 있는 객체는 400 INVALID_REQUEST다. 특히 stateVersion·requestId를 담은 객체는 거부해 지원하지 않는 검사가 적용됐다고 오해하지 않게 한다.

컨트롤러는 Content-Length만 믿지 않고 요청 스트림을 최대 16KiB+1바이트까지 제한해 읽는다. 바이트가 0개면 본문 없음으로 수락하고, 그 외에는 strict JSON 파서로 빈 객체인지 확인한다. 크기 초과·깨진 JSON·후행 JSON·중복 키도 400이다. chunked 요청도 같은 규칙을 적용한다. 기존 JSON 파서의 후행 토큰/중복 키 검사 설정을 재사용하되, messages/facts용의 Content-Type 필수 검사를 그대로 적용하지 않는다. 종료는 Content-Type을 필수 조건으로 두지 않고 본문 내용으로 검사한다. `consumes=application/json`이나 필수 @RequestBody를 붙여 본문 없는 정상 요청을 막지 않는다.

검사 순서는 **CSRF → ID 형식 → 본문 → 소유권**이다. 토큰 없음은 먼저 403이다. 유효 토큰 아래 잘못된 ID는 404이고, 올바른 ID에 허용하지 않는 본문이 있으면 400이다. 올바른 ID·허용된 본문에서 없는/타인 상담은 같은 404다. ID 형식은 SOL-02~04의 양수 long 검사와 범위 초과 처리 방식을 재사용한다.

성공은 최초·반복 종료 모두 **200 OK**, 기존 ApiResponse로 감싼다.

```json
{
  "success": true,
  "data": {
    "solutionId": 301,
    "status": "COMPLETED"
  }
}
```

소유자·원문·완료 시각·stateVersion을 응답에 임의 추가하지 않는다. 버전/사실/기록을 다시 볼 필요가 있으면 SOL-02를 사용한다. 오류 형식은 기존 `{code,message}`를 유지한다. Spring Security의 no-store를 성공·오류 응답에서 검증한다.

## 4. 종료 상태와 반복 요청 정책

### 최초 종료

ACTIVE인 본인 상담에 대해 한 SQL에서 다음을 변경하는 안이다.

- status=COMPLETED
- completed_at=서버 Clock의 현재 시각
- updated_at=같은 시각
- state_version=현재 DB 값+1
- next_question_json=NULL

facts_json·card_codes_json·created_at·소유자는 유지한다. solution_messages에는 새 USER/SYSTEM 종료 문구를 추가하지 않고 기존 이력도 삭제하지 않는다. 원문을 새로 수집·마스킹·복사할 필요가 없다.

상담이 끝났다고 HTTP 로그인/익명 세션을 무효화하지 않는다. 같은 쿠키로 종료 상담 복원이나 다른 상담 이용이 가능해야 한다. 종료는 소유권 만료·로그아웃·데이터 삭제 기능이 아니다.

### 반복 종료: 변경 없는 200

제안: 이미 COMPLETED인 본인 상담이면 그대로 200을 반환한다. completed_at·updated_at·state_version·사실·카드·메시지는 다시 쓰지 않는다. 응답 유실 후 같은 URL·쿠키·토큰으로 재시도할 수 있다. 종료 UUID 저장이나 과거 응답 스냅샷 없이도 같은 완료 결과를 반환할 수 있다.

반복 요청도 소유권·활성 회원·CSRF 검사를 생략하지 않는다. 쿠키/소유권이 사라지면 완료 여부와 관계없이 404, 토큰이 없으면 403이다. 남의 완료 상담을 아는 것만으로 200을 받을 수 없다.

현재 SOL-05 이전에는 상담을 COMPLETED로 바꾸는 서비스 코드가 없으므로, 정상 애플리케이션 경로에서 생성된 과거 완료 행은 없다. completed_at 누락 행을 복구하는 기능을 이번 범위로 잡지 않는다. SOL-05 최초 종료의 완료 시각 저장과 반복 종료의 시각 불변을 검증한다.

### 완료 후 갱신과 복원

SOL-03·04의 ACTIVE 검사를 유지해 새 입력/수정은 409 SOLUTION_FINISHED로 거부한다. SOL-03의 이미 반영된 UUID 재전송은 현재 중복 검사 우선순위에 따라 DUPLICATE_REQUEST가 될 수 있지만 추가 저장은 없어야 한다. 종료 기능 때문에 기존 중복 요청 의미를 임의로 바꾸지 않는다.

SOL-02는 계속 본인 완료 상담을 조회할 수 있다. 새로 종료한 행은 질문이 NULL이다. 방어적으로 완료 상태에서는 공개 응답의 nextQuestion도 null로 고정하는 안이다. 현재 정상 경로에 질문이 남은 과거 완료 데이터가 있다는 전제는 아니며, 이 방어 처리는 테스트용 fixture 한 건으로 확인한다. GET이나 반복 POST에서 저장 질문을 별도로 고치지 않는다. 프론트도 status=COMPLETED이면 입력·정정 UI를 닫고 재개 요청을 자동으로 만들지 않는다.

카드는 종료 시 재계산하거나 삭제하지 않는다. 현재 SOL-02는 현재 검토 카드를 다시 조회하므로 콘텐츠가 없어지면 완료 후 복원도 기존대로 503이 될 수 있다. **종료 성공 여부는 이 복원 실패와 별개**다. 종료를 카드 조회 장애에 종속시키지 않는다.

## 5. 동시 수정·원자적 종료 설계

### 기대 버전 없이 최신 ACTIVE 상태 종료

명세에 stateVersion이 없으므로 화면에서 마지막으로 본 버전을 고정해 종료하지 않는다. 종료 UPDATE는 **ID·소유자·status=ACTIVE**를 조건으로 하고 기대 버전 조건은 넣지 않는 안이다.

```text
UPDATE solution_sessions
   SET status='COMPLETED', completed_at=?, updated_at=?,
       state_version=state_version+1, next_question_json=NULL
 WHERE id=? AND status='ACTIVE' AND (해당 회원 또는 익명 소유자 조건)
```

소유자 조건은 기존 custom repository처럼 회원 user_id 또는 anonymous_owner_id의 정적 분기로 작성하고 모두 바인딩한다. 최신 행의 state_version 산술 증가를 사용하며, 처음 읽은 버전에 1을 더한 값을 덮어쓰지 않는다. facts·카드는 SQL SET 대상에 넣지 않아 먼저 저장된 수정 결과를 보존한다.

기존 compareAndSet은 사실·카드·질문·기대 버전 갱신용이라 종료를 수행할 수 없다. 같은 SolutionSessionUpdates fragment에 종료 전용 메서드를 추가한다. JdbcTemplate는 기존과 같은 DataSource/JpaTransactionManager를 사용하고 UPDATE 뒤 EntityManager 캐시를 정리한다. 관리 엔티티의 status setter + dirty checking UPDATE를 중복 사용하지 않는다.

### 트랜잭션과 0행 처리

서비스는 NOT_SUPPORTED 진입점 + 짧은 TransactionTemplate의 **READ COMMITTED** 쓰기를 사용하는 안이다. SOL-02의 REPEATABLE_READ를 복사하지 않는다. 소유자 확인 뒤 쓰기 트랜잭션에서 findOwned로 본인 상담을 확인한다. 이미 완료면 무변경 성공, ACTIVE면 조건부 종료를 시도한다.

UPDATE 1행이면 최신 완료 상태를 확인하고 200을 반환한다. 0행이면 JPA 캐시를 버린 뒤 같은 READ COMMITTED 트랜잭션에서 새 statement로 findOwned를 재조회한다. 없는/남의 상담이면 404, COMPLETED면 다른 종료가 먼저 끝난 것이므로 200이다. 예외로 롤백된 트랜잭션 안에서 재조회하지 않는다. 정상적인 상태 전이로 설명할 수 없는 ACTIVE 0행이나 다른 DB 오류는 서버 오류로 보고하며 없는 상담으로 숨기지 않는다.

잠금 시간 초과·연결 오류는 정상 중복 종료가 아니므로 무조건 200/404로 바꾸지 않는다. 프론트는 성공 응답을 못 받았으면 같은 종료를 재시도할 수 있다. state_version int 상한에서 +1이 불가능한 경우에도 부분 종료 없이 롤백돼야 한다. 이번 설계는 버전 포화 상태를 성공으로 위장하거나 임의로 0으로 되돌리지 않는다.

### 경쟁 결과를 구별하는 기준

| 경쟁 상황 | 기대 결과 |
|---|---|
| 종료 두 건이 같은 ACTIVE 행을 읽음 | 최초 UPDATE만 1행·나머지 0행 후 완료 재조회. 둘 다 200, 버전/완료 시각 변경은 한 번 |
| SOL-03/04 수정이 먼저 커밋 | 종료가 최신 수정 사실·카드를 유지한 채 완료. 두 요청 모두 성공 가능하며 버전은 총 +2 |
| 종료가 먼저 커밋 | SOL-03/04는 ACTIVE 조건 또는 저장 전 검사에서 차단. 새 요청은 SOLUTION_FINISHED, 사실/메시지 추가 없음 |
| SOL-03이 AI 응답 대기 중 종료 성공 | 늦은 AI 응답은 저장 전 ACTIVE 검사에서 거부. 종료 상태·사실·버전·메시지 유지 |
| 종료 도중 상담 삭제/소유권 불일치 | 본인 조회 결과가 없으면 동일 404. 타인 상태를 노출하지 않음 |

**종료와 수정의 경쟁은 항상 "성공 한 건, 실패 한 건"이 아니다.** 수정이 먼저면 그 결과까지 포함해 종료하는 것이 버전 없는 명세의 의미다. 수정과 종료를 한 버전에 한 건만 허용하려면 stateVersion 입력과 409 계약을 별도로 추가해야 한다. 이것을 이번 구현의 숨은 필수 입력으로 넣지 않는다.

같은 상담 행의 UPDATE 잠금이 종료와 SOL-03·04의 순서를 결정한다. 메시지 기록이 있는 수정은 그 트랜잭션의 커밋까지 잠금을 유지하므로, 종료가 성공한 뒤 아직 저장 중인 수정 메시지가 별도 커밋되는 흐름이 생기지 않아야 한다.

## 6. 소유권·오류·개인정보

findExisting과 findOwned를 그대로 사용한다. 로그인 principal이 있으면 활성 회원으로만 처리하고 남은 익명 UUID로 우회하지 않는다. 로그인 전 익명 상담을 자동 이관하지 않는다. 신규 익명 소유자 발급용 resolve는 사용하지 않는다.

비회원은 기존 `/api/solutions/csrf`, 회원은 해당 경로 또는 `/api/auth/csrf`로 토큰을 받아 같은 SESSION 쿠키로 호출한다. AUTH의 로그인 정책·세션 TTL·쿠키 속성을 변경하지 않는다. Redis 소유자 확인과 PostgreSQL 커밋은 분산 트랜잭션이 아니므로 커밋과 정확히 동시에 일어나는 로그아웃/만료까지 원자적으로 묶는다고 보장하지 않는다.

| 상태·코드 | 조건 |
|---|---|
| 200 성공 | 본인 ACTIVE 상담 최초 종료 또는 본인 COMPLETED 상담 반복 종료 |
| 400 INVALID_REQUEST | 본문 없음/빈 JSON 객체 이외의 입력·크기 초과·요청 스트림 읽기 오류 |
| 401 기존 AUTH 오류 | 비활성/삭제 등 회원 인증 오류. 비회원으로 우회하지 않음 |
| 403 CSRF_TOKEN_INVALID | 회원·비회원 토큰 없음/불일치 |
| 404 SOLUTION_NOT_FOUND | ID 형식 오류·상담 없음·타인 상담·기존 익명 소유자 없음 |
| 500 서버 오류 | 정상 경쟁으로 설명되지 않는 상태·DB 오류 등. 종료 부분 저장 없음 |

정상 반복 종료에는 SOLUTION_FINISHED·DUPLICATE_REQUEST·STATE_VERSION_CONFLICT를 반환하지 않는 안이다. AI_SERVICE_UNAVAILABLE·RESPONSE_CONTENT_UNAVAILABLE도 종료 서비스의 의존성 오류로 추가하지 않는다. facts/card/question JSON을 파싱·정규화할 필요 없이 해당 컬럼을 그대로 보존/종료 처리하므로 손상된 옛 카드나 승인 콘텐츠 누락을 종료 불가 사유로 삼지 않는다. 기존 JSON 문제 자체의 복구는 별도 작업이다.

원문·프롬프트·요청 본문·세션 ID·CSRF 토큰·소유자 UUID를 로그에 남기지 않는다. 필요한 로그는 내부 상담 ID·실제 종료 전이 여부·처리 결과 정도다. 거부된 본문을 오류 메시지에 복사하지 않는다. 종료 시 원문을 끝까지 저장하지 않는 기존 정책을 유지하며, 로그아웃·데이터 삭제와 혼동하지 않는다.

## 7. 파일별 개발 순서와 후속 연계

신규 파일명은 제안이며 Java 경로 접두사는 `src/main/java/com/wsid/`다.

| 순서 | 파일 | 작업·상호작용·이후 주의점 |
|---|---|---|
| 1 | `solution/dto/CompleteSolutionResponse.java`(신규) | solutionId·COMPLETED만 반환. 본문 없는 API에 요청 DTO를 억지로 만들지 않음 |
| 2 | `solution/repository/SolutionSessionUpdates.java`, `SolutionSessionUpdatesImpl.java` | 소유권+ACTIVE 원자적 종료 메서드. DB 버전 산술 +1, 완료 시각/updated_at/질문 갱신, 캐시 정리. 기존 사실 CAS 유지 |
| 3 | `solution/domain/SolutionSession.java` | 완료 시각 읽기 접근자가 필요하면 추가. SQL과 별도 save를 섞어 버전이 두 번 증가하지 않게 함 |
| 4 | `solution/service/CompleteSolutionService.java`(신규) | 기존 소유자·본인 상담·상태 확인, 짧은 READ COMMITTED, 반복 200·0행 재조회. AiClient/카탈로그/메시지 저장 의존성 없음 |
| 5 | `solution/controller/SolutionController.java` | 정확한 complete POST, ID 먼저 검사·제한된 본문 읽기, 본문 없음/빈 객체만 수락. Content-Type을 필수로 요구하지 않음 |
| 6 | `solution/security/SolutionSecurityConfig.java` | 정확한 POST 경로만 permitAll, 회원·비회원 CSRF 검사 유지. 명세에 없는 경로/메서드는 계속 차단 |
| 7 | `solution/service/SolutionQueryService.java` | 완료 상담의 nextQuestion 응답을 null로 표시. GET에서 DB를 수정하거나 현재 카드 조회 정책을 바꾸지 않음 |
| 8 | `src/test/java/com/wsid/solution/` | 서비스·웹·DB·Redis 통합 테스트 추가. 기존 complete 경로 차단 테스트는 SOL-05 허용으로 변경. 차단 검증은 명세에 없는 PUT/DELETE `/sessions/{id}`, GET `/sessions/{id}/messages` 등으로 이동 |
| 9 | 구현 후 `docs/sol-05-session-complete.md`, `docs/api.md`, `solution/README.md`, `docs/README.md` | 실제 정책·파일별 상호작용·테스트 결과·완료 후 프론트 동작과 남은 작업 보고 |

기존 메시지/정정 서비스의 ACTIVE 검사와 CAS WHERE 조건을 제거하거나 바꾸지 않는다. 향후 상담 재개 기능을 만들려면 완료 시각·질문 재생·버전·소유권·사용자 동의 계약을 별도로 설계해야 하며, 반복 complete를 재개 기능으로 쓰지 않는다.

현재 DB에 status·completed_at·updated_at·state_version이 있어 **새 마이그레이션이 필요하지 않은 안**이다. V1~V4를 수정하지 않는다. DB 담당자가 완료 상태와 완료 시각의 정합성 CHECK를 새로 요구하면 과거 데이터 검토와 새 마이그레이션 번호 합의를 별도로 진행한다.

## 8. 구현·테스트 계획

| 범위 | 검증할 내용 |
|---|---|
| 서비스 단위 | 본인 ACTIVE→COMPLETED, 이미 완료면 무변경 200, 소유자 없음/타인 상담 404, 같은 Clock 시각 저장, AI·카드·메시지 미호출 |
| 요청/응답 MVC | 200 성공 봉투·두 필드, Content-Type 없는 빈 본문/빈 객체 수락, 공백을 포함한 빈 객체 수락, null/배열/문자열/공백만 있는 본문/값이 있는 객체 400, stateVersion 필드 거부, 잘못된 ID·본문의 우선순위, chunked 본문·16KiB 제한·후행 JSON/중복 키 검사 |
| 보안 | 회원·비회원 동일 CSRF, 토큰 없음/다른 세션 토큰 403, 다른 소유자/로그인 뒤 이전 익명 상담 404, 비활성 회원 401, no-store, 명세의 complete POST 허용 및 비명세 PUT/DELETE `/sessions/{id}`·GET `/sessions/{id}/messages` 차단 |
| PostgreSQL dbTest | status·completed_at·updated_at·질문 NULL·버전 +1, facts/card/created_at/소유자/메시지 불변, 반복 시 시간·버전 불변, 실제 DB 버전과 JPA 캐시 일치 |
| 롤백·경계 | UPDATE 뒤 실패를 주입하면 상태/시각/버전/질문 전체 롤백, 관련 없는 DB 오류를 404/200으로 숨기지 않음, 버전 int 상한 롤백 |
| 종료 두 건 동시성 | 별도 트랜잭션·동기화 장치로 같은 ACTIVE 행 종료 경쟁. 1행/0행 확인, 두 응답 200, 완료 시각·버전 한 번만 변경 |
| 종료 대 수정 경쟁 | SOL-03/04 수정 선행이면 수정과 종료 모두 성공·최신 사실 유지·버전 +2. 종료 선행이면 새 수정 거부·추가 메시지 없음. 두 저장 순서를 제어해 각각 검증 |
| 늦은 AI 응답 | 제어 가능한 AI 대역에서 SOL-03 호출 대기 → 종료 성공 → AI 반환. 늦은 입력 저장 없음·완료 상태 유지 |
| 복원·다른 상담 | 완료 상담 SOL-02 조회 가능·nextQuestion=null, 질문이 남은 완료 상태의 방어 처리는 인위적 fixture 한 건으로 확인·DB 무변경, 다른 본인 상담은 계속 사용 가능 |
| 콘텐츠 장애 독립성 | 필요한 카드가 없거나 저장 카드 JSON이 손상돼도 본인 상담 종료 자체는 성공. 이후 SOL-02의 기존 카드/JSON 오류는 별도로 유지 |
| 실제 Redis integrationTest | 비회원 CSRF→시작→종료→반복→복원, 같은 SESSION 유지·다른 상담 영향 없음, 만료 후 새 CSRF만 받아도 소유권 404, 회원 경로와 AUTH/SOL-01~04 회귀 |
| 개인정보 | 종료 메시지/원문 새 저장 없음, 본문 거부 시 로그·응답에 입력 없음, 쿠키·토큰·익명 소유자 로그 없음 |

실행은 `./gradlew test`, 프로젝트 전용 PostgreSQL의 `./gradlew dbTest`, 전용 PostgreSQL·Redis의 `./gradlew integrationTest`, `./gradlew bootJar` 순서로 한다. DB/Redis는 다른 프로젝트의 기본 5432/6379와 분리해 사용한다. mock 단위 테스트로 실제 행 잠금·버전 산술·원자성 검증을 대체하지 않는다.

완료 기준은 최초 종료 한 번만 상태/버전/시각이 바뀌고, 반복 종료는 동일한 성공 결과를 반환하며, 종료 이후 새 메시지·정정이 저장되지 않는 것이다. 실제 완료/수정의 커밋 순서를 구별해 검증하고, 로그인 세션·다른 상담·기존 사실/카드/기록이 유지됨을 확인한다. 최초 계획 작성 당시에는 테스트를 실행하지 않았다. 구현 후 실제 실행한 Gradle·DB·Redis·HTTP 검증 결과는 구현 보고서에 기록했다.

## 9. 구현 전 확정할 사항

1. **반복 종료:** 본인 COMPLETED에 변경 없는 200, 최초 완료 시각 유지 정책을 채택할지. 현재 정상 경로에 과거 완료 행은 없으며 별도 복구 기능은 추가하지 않는다.
2. **버전 없는 종료 의미:** 화면 버전과 관계없이 최신 ACTIVE 상태를 종료하고 수정 선행 시 둘 다 성공할 수 있는 안을 확인한다. 기대 버전 기반 종료가 필요하면 명세에 stateVersion/409를 추가해야 한다.
3. **종료 후 동작:** SOL-03·04 새 갱신 거부, SOL-02 복원 허용·질문 숨김, 재개 기능 없음, 기존 쿠키/다른 상담 유지 정책을 프론트와 맞춘다.
4. **본문 계약(추천안 반영):** 본문 없음 또는 빈 JSON 객체를 허용한다. 프론트에는 본문 없음 호출을 기본으로 안내하고 `{}` 자동 첨부도 수락한다고 공유한다. null·값이 있는 객체는 400이다. 구현 시 이 계약대로 테스트한다.
5. **콘텐츠·기록:** 종료 시 카드 재계산·AI·새 메시지 저장을 하지 않는 안, 완료 후 카드 복원 장애와 종료 성공을 구분하는 화면 처리를 확인한다.
6. **팀 명세 정정:** 오류 목록에 400 INVALID_REQUEST(본문/요청 오류), 401 기존 AUTH 오류(비활성 회원 등), 403 CSRF_TOKEN_INVALID를 추가한다. 본문 없음/빈 객체 허용과 반복 종료 200도 명시한다. 명세의 "완료 후 갱신 불가 정책은 팀 결정"은 SOL-03·04에서 이미 SOLUTION_FINISHED로 차단하도록 구현돼 있음을 알리고, SOL-05 도입 시 그 정책 유지 여부를 확정한다. 이 계획서에서 팀 공유 명세 파일 자체를 수정하지는 않는다.
