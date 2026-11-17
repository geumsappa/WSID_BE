# SOL-05 피해 대응 상담 종료 구현 보고서와 연동 안내

작성일: 2026-11-17 · 기준 브랜치: OHC · [구현 계획서](sol-05-session-complete-plan.md)

`POST /api/solutions/sessions/{id}/complete`를 구현했다. 회원·비회원 모두 본인 상담을 종료할 수 있고, 반복 종료도 변경 없이 200을 반환한다. 최초 종료에만 완료 시각·버전·질문을 갱신하며 기존 사실·카드·메시지·HTTP 세션은 유지한다.

단위 825건·실제 PostgreSQL DB 45건·Redis/실제 서버 통합 30건, **총 900건이 모두 통과**했다. 실패·오류·건너뜀은 0건이며 bootJar 빌드도 성공했다. 실제 프론트 화면이나 배포 환경 검증은 이번 범위가 아니다.

## 1. 요청·응답·오류

```text
POST /api/solutions/sessions/301/complete
Cookie: SESSION=...
X-CSRF-TOKEN: ...
본문 없음 또는 {}
```

본문은 없어도 되고, 있으면 빈 JSON 객체만 받는다. 공백을 포함한 ` { } `도 허용한다. Content-Type은 필수가 아니다. `null`, 배열, 문자열, 공백만 있는 본문, stateVersion/requestId 등 값이 있는 객체, 깨진/후행/중복 키 JSON은 400이다. 요청 본문은 최대 16KiB로 제한하고 Content-Length가 없는 전송도 스트림을 제한해 검사한다. SOL-03·04의 JSON Content-Type 필수 규칙은 바꾸지 않았다.

성공은 최초·반복 모두 200이며 기존 ApiResponse를 사용한다.

```json
{
  "success": true,
  "data": {
    "solutionId": 301,
    "status": "COMPLETED"
  }
}
```

완료 시각·버전·소유자는 이 응답에 추가하지 않았다. 필요하면 SOL-02로 상태를 다시 조회한다. 오류는 기존 `{code,message}` 형식이다.

| 상태·코드 | 조건 |
|---|---|
| 400 INVALID_REQUEST | 허용되지 않은 본문·크기 초과·읽기 오류 |
| 401 기존 AUTH 오류 | 비활성 회원 등 인증 오류. 익명 소유자로 우회하지 않음 |
| 403 CSRF_TOKEN_INVALID | 회원·비회원 토큰 없음/불일치 |
| 404 SOLUTION_NOT_FOUND | ID 형식 오류·없는 상담·다른 소유자·기존 익명 소유자 없음 |
| 500 서버 오류 | 설명되지 않는 저장 결과·DB 오류 등. 부분 종료 없이 롤백 |

검사 순서는 **CSRF → ID 형식 → 본문 → 소유권**이다. 유효한 토큰에서 잘못된 ID+잘못된 본문은 404, 올바른 ID+잘못된 본문은 400이다. 정상 반복 종료에는 SOLUTION_FINISHED·DUPLICATE_REQUEST·STATE_VERSION_CONFLICT를 반환하지 않는다. 카드·AI를 호출하지 않으므로 그 장애로 종료를 막지도 않는다.

## 2. 실제 처리와 저장 내용

1. `SolutionSecurityConfig`가 complete POST를 허용하고 회원·비회원 모두 CSRF를 검사한다.
2. `SolutionController`가 ID를 검사하고 본문을 제한해서 읽는다. 바이트가 없으면 통과, 있으면 기존 strict JsonMapper로 빈 객체인지 검사한다.
3. `SolutionOwnerResolver.findExisting`과 `findOwned`로 본인 상담을 확인한다. 신규 익명 UUID를 만들지 않으며 로그인 회원이 있으면 회원이 우선이다.
4. `CompleteSolutionService`의 짧은 READ COMMITTED 트랜잭션에서 이미 COMPLETED면 무변경 성공한다. ACTIVE면 종료 전용 SQL을 실행한다.
5. UPDATE 1행이면 완료 상태를 확인한다. 0행이면 JPA 캐시를 버린 뒤 새 statement로 본인 상담을 다시 조회해, 완료면 200·없으면 404를 반환한다. ACTIVE 0행 등 설명되지 않는 결과는 서버 오류다.
6. 커밋에 성공한 뒤 상담 ID·실제 전이 여부만 로그에 남기고 응답한다. 커밋 실패를 성공으로 기록하지 않는다.

| 데이터 | 최초 종료 | 반복 종료 |
|---|---|---|
| status | COMPLETED | 유지 |
| completed_at | 서버 Clock 시각 | 최초 시각 유지 |
| updated_at | 같은 서버 시각 | 유지 |
| state_version | 현재 DB 값 +1 | 유지 |
| next_question_json | NULL | 별도 수정 없음 |
| facts_json·card_codes_json·created_at·소유자 | 유지 | 유지 |
| solution_messages | 추가·삭제·수정 없음 | 동일 |
| 로그인/익명 HTTP 세션 | 유지 | 유지 |

종료는 로그아웃·상담 삭제·리포트 생성 기능이 아니다. 같은 SESSION으로 다른 상담을 시작할 수 있다. SOL-02는 완료 상담도 복원하며 nextQuestion을 null로 반환한다. 방어적으로 질문이 남은 완료 상태도 화면에서 질문을 숨기지만 GET으로 DB를 수정하지 않는다. 그런 과거 데이터가 현재 정상 서비스 경로에서 생성돼 있었다는 뜻은 아니며, 해당 방어는 fixture 한 건으로 검사했다.

## 3. 동시성·트랜잭션 설계

명세에 stateVersion이 없으므로 **최신 ACTIVE 상담을 종료**한다. 요청 DTO에 기대 버전을 추가하지 않았다. 종료 SQL의 조건은 ID·회원/익명 소유자·ACTIVE이고, 사실 갱신용 compareAndSet과 달리 기대 버전 조건이 없다.

SQL은 상태·완료 시각·updated_at·버전 산술 증가·질문 NULL만 갱신한다. 처음 읽은 버전에 1을 더해 덮어쓰거나 사실/카드 JSON 전체를 다시 저장하지 않는다. 따라서 앞서 커밋된 수정 내용이 보존된다.

`SolutionSessionUpdatesImpl.completeActive`는 기존과 같은 JdbcTemplate/DataSource를 사용한다. UPDATE 뒤 EntityManager.clear로 읽기 캐시를 버린다. 관리 엔티티의 status를 바꿔 JPA 자동 UPDATE를 중복 실행하지 않는다. 서비스는 NOT_SUPPORTED 진입점과 내부 TransactionTemplate의 READ COMMITTED를 사용해 호출자의 트랜잭션에 합류하지 않는다.

| 저장 순서 | 실제 검증한 결과 |
|---|---|
| 종료 두 건 경쟁 | 두 요청 모두 성공. UPDATE는 한 건만 1행, 다른 건은 0행 후 완료 조회. 버전은 +1 |
| SOL-03/04 수정이 먼저 | 수정과 종료 모두 성공. 최신 수정값·기록을 유지하고 완료. 버전 총 +2 |
| 종료가 먼저 | 수정의 ACTIVE 조건이 실패하고 SOLUTION_FINISHED. 새 수정/메시지 저장 없음 |
| SOL-03 AI 대기 중 종료 | 늦은 AI 응답은 저장 직전 상태 검사에서 거부. 종료 상태·버전·메시지 유지 |

실제 PostgreSQL 테스트에서 별도 스레드·트랜잭션과 latch/barrier로 UPDATE/커밋 순서를 제어했다. 수정과 종료가 언제나 하나만 성공한다고 가정하지 않는다. 기대 버전 기반 종료가 필요해지면 요청·409 정책·테스트를 별도로 변경해야 한다.

예외로 롤백된 트랜잭션에서 재조회하지 않는다. 0행은 정상 SQL 실행 결과이므로 같은 READ COMMITTED 트랜잭션의 새 statement에서 확인할 수 있다. 잠금 시간 초과·DB 연결 오류를 무조건 200/404로 바꾸지 않았다. 버전 int 상한에서도 SQL 전체가 롤백되고 부분 완료가 남지 않는 것을 확인했다.

Redis 소유권 확인과 PostgreSQL 커밋은 분산 트랜잭션이 아니다. 완료와 정확히 동시에 발생한 로그아웃·세션 만료까지 원자적으로 묶는다고 보장하지 않는다. 반복 요청에서도 활성 회원·기존 소유권·CSRF를 다시 확인한다.

## 4. 변경 파일과 상호작용·이후 개발 시 주의점

Java 경로 접두사는 `src/main/java/com/wsid/`다.

| 파일 | 역할·호출 관계 | 이후 참고할 점 |
|---|---|---|
| `solution/controller/SolutionController.java` | ID/본문 검사 → CompleteSolutionService → ApiResponse | 빈 요청에 consumes=application/json·필수 @RequestBody를 추가하면 호출이 깨질 수 있음. 공통 제한 읽기/파싱을 SOL-03·04와 공유하되 Content-Type 규칙은 구별 |
| `solution/dto/CompleteSolutionResponse.java`(신규) | solutionId·SolutionStatus만 반환 | 본문 없는 API에 임의의 필수 요청 DTO/UUID/버전을 추가하지 않음 |
| `solution/service/CompleteSolutionService.java`(신규) | 소유자 확인·ACTIVE 종료·반복 성공·0행 재조회·커밋 후 로그 | AiClient·ResponseCardCatalog·메시지 저장 의존성 없음. 정상 반복 요청을 409로 바꾸지 않음 |
| `solution/repository/SolutionSessionUpdates.java` | completeActive 경계 추가 | 기존 compareAndSet은 사실 갱신의 기대 버전 계약을 유지. 두 API의 조건을 혼동하지 않음 |
| `solution/repository/SolutionSessionUpdatesImpl.java` | 소유권/ACTIVE 종료 SQL과 JPA 캐시 정리 | 현재 DB 버전 산술 +1, 같은 트랜잭션의 행 잠금, 사실/카드 보존. 별도 커넥션·수동 커밋을 넣지 않음 |
| `solution/security/SolutionSecurityConfig.java` | 정확한 complete POST만 공개 | permitAll은 CSRF 면제가 아님. 명세에 없는 PUT/DELETE/GET messages는 계속 차단 |
| `solution/service/SolutionQueryService.java` | 완료 상담 nextQuestion=null 응답 | 기존 사실/현재 검토 카드/메시지 조회 정책은 유지. 조회 중 질문을 DB에서 지우지 않음 |

다음 연관 코드는 수정하지 않았다.

- `SolutionOwnerResolver`: 기존 회원 우선·활성 회원 검사·익명 소유자 조회를 재사용한다. 완료가 새 소유권을 만들어서는 안 된다.
- `SendSolutionMessageService`, `UpdateSolutionFactsService`: ACTIVE 검사·조건부 UPDATE로 이미 완료 후 변경을 막는다. 기존 SOL-03 중복 UUID 검사 우선순위는 그대로다.
- LSH AUTH 파일·공통 ErrorCode·GlobalExceptionHandler·DB 마이그레이션·AI 설정·Gradle 의존성: 변경 없음.

DB에는 기존 status·completed_at·updated_at·state_version·next_question_json을 사용하며 새 마이그레이션이 없다. 종료 시 기존 사실/카드 JSON을 파싱·복구하지 않으므로 손상된 JSON·미등록 카드가 종료 자체를 막지 않는다. 다만 이후 SOL-02 복원은 원래의 JSON/카탈로그 오류를 반환할 수 있다. 종료 성공을 그 복원 실패로 취소됐다고 표시하지 않아야 한다.

## 5. 검증 내역

실행: `./gradlew test dbTest integrationTest bootJar`. Java 21과 WSID 전용 PostgreSQL 17(127.0.0.1:55432)·Redis 7(127.0.0.1:56379)을 사용했다. dbTest는 wsid_ai01_test, integrationTest는 wsid_auth_test를 사용하며 다른 프로젝트의 기본 5432/6379에는 쓰지 않았다.

| 검증 | 결과 |
|---|---|
| 전체 단위 테스트 | **825건 통과** |
| 전체 dbTest | **45건 통과** |
| 전체 integrationTest | **30건 통과** |
| 합계 | **900건, 실패·오류·건너뜀 0** |
| bootJar | 빌드 성공 |

SOL-05 관련 테스트는 다음과 같다.

| 파일 | 새로 확인한 내용 |
|---|---|
| `CompleteSolutionServiceTest`(신규, 11건) | 서버 Clock·반복 무변경·소유권·0행 재조회·비정상 결과·SQL 오류 전파·READ COMMITTED |
| `CompleteSolutionDatabaseTest`(신규, 12건) | 상태/시각/버전/질문 갱신·다른 데이터 불변·종료 두 건 경쟁·SOL-03/04 양방향 커밋 순서·늦은 AI·UPDATE 뒤 실패 롤백·버전 상한·카드/JSON 독립성·회원 SQL 분기 |
| `SolutionQueryServiceTest` | 완료 상태의 질문 숨김 fixture 1건 추가, 저장값 무변경 |
| `SolutionWebSecurityTest` | 새 14건: 본문 없음/빈 객체·그 외 입력·크기·ID/본문 우선순위·회원/익명 소유권·토큰·완료 후 갱신 거부. 기존 차단 대상은 비명세 경로로 이동 |
| `SolutionIntegrationTest` | 새 2건과 기존 회원 흐름 확장: 실제 서버·Redis의 반복 종료/복원·세션 유지·새 상담·만료·거부 본문·길이가 미리 정해지지 않은 chunked 전송 |

JSON 예시·문서 링크·IntelliJ 문서 검사와 `git diff --check`도 통과했다. IntelliJ Java 검사는 프로젝트 content root 설정 때문에 분석되지 않았으며, Java 검증은 Gradle 컴파일·테스트·빌드 결과를 기준으로 한다.

실제 HTTP/Redis 흐름은 검증했으나 프론트 화면·배포 환경·운영 프록시/APM 설정은 검증하지 않았다. SOL-05는 LLM을 호출하지 않아 별도 모델 정확도 평가가 필요한 API는 아니다. 원문·토큰·소유자는 로그 인자로 사용하지 않으며 요청 본문은 거부 시에도 오류 응답에 복사하지 않는다.

## 6. 프론트·명세 담당자에게 전달할 내용

아래는 전달용 정리이며 외부 메시지를 실제로 보내지는 않았다.

### 프론트 안내

- POST `/api/solutions/sessions/{id}/complete`는 본문 없이 보내면 된다. HTTP 래퍼가 `{}`를 붙여도 통과한다. 쿠키·CSRF는 기존 상담 요청과 동일하다.
- 성공 봉투의 data는 `{solutionId, status: "COMPLETED"}`다. 이미 완료된 상담을 다시 종료해도 200이다. 응답 유실 시 같은 URL·쿠키·유효한 토큰으로 재시도할 수 있다.
- 완료 후 새 메시지·사실 수정은 409 SOLUTION_FINISHED다. 입력·정정 UI를 닫는다. 이미 반영된 SOL-03 UUID 재전송은 기존 DUPLICATE_REQUEST가 나올 수 있다.
- SOL-02로 완료 상담을 다시 볼 수 있으며 nextQuestion은 null이다. 카드 복원 실패가 나더라도 성공한 종료를 취소됐다고 보지 않는다.
- 로그인·익명 세션은 유지되므로 새 상담을 시작할 수 있다. 이 API에는 stateVersion 입력이 없어, 다른 탭의 수정이 먼저 커밋됐다면 그 최신 상태까지 포함해 종료한다.

### 팀 명세 정정

- SOL-05 오류 목록에 400 INVALID_REQUEST, 401 기존 AUTH 오류, 403 CSRF_TOKEN_INVALID를 추가한다.
- 본문 없음/빈 JSON 객체 허용, 반복 종료 200·최초 시각 유지·버전 한 번 증가를 명시한다.
- "완료 후 갱신 불가 정책은 팀 결정"은 SOL-03·04에 이미 구현된 SOLUTION_FINISHED 정책을 유지한 것으로 맞춘다. 응답에 별도 요청 버전은 추가하지 않았다.
- 기존 SOL-04 응답의 nextQuestion·카드 전체 필드·송금/앱 설치 모두 YES일 때 두 카드 예시도 아직 팀 명세에 없다면 함께 반영한다. 이 작업에서 공유 WSID-API-Spec.md는 수정하지 않았다.

## 7. 남은 일

프론트 실제 종료 화면과 팀 명세 반영은 후속 확인 사항이다. 상담의 운영 카드 카탈로그 연결과 실제 FastAPI 품질 검증은 기존 별도 과제이며 SOL-05가 대신 완료하지 않는다. 재개·삭제·보관 기간 정책도 이번 종료 API에 추가하지 않았다.

계획서와 구현은 별도 커밋으로 관리한다. 커밋·원격 반영 상태는 Git 이력을 기준으로 확인한다.
