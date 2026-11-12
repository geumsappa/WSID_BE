# wsid-backend

WSID의 백엔드예요. 세션과 진행 규칙, 공식 안내를 맡고, AI 서비스(FastAPI)는 제안만 받아 와요.

- Java 21, Spring Boot 4.1 (Spring MVC + 가상 스레드), JPA, PostgreSQL, Flyway
- 요구사항·기능 명세 v0.3의 기능 ID(AUTH, SIM, RPT, SOL)와 팀 ERD(GSB.sql)를 기준으로 만들었어요.

## 시스템 구성

```
React ──HTTPS──▶ Spring Boot ──내부 HTTP──▶ FastAPI ──▶ LLM API
                   │    │                       └──▶ 근거 문단 검색 (RAG, 후속)
                   │    └──▶ 가이드 데이터 YAML (카드·기관·연락처)
                   ├──▶ PostgreSQL (회원·서비스 데이터)
                   └──▶ Redis (로그인 세션, AUTH-002를 만들 때 추가)
```

- 브라우저는 Spring Boot만 호출해요. FastAPI는 외부에 공개하지 않아요.
- 대응 카드, 기관명, 연락처, 공식 링크는 Spring이 YAML에서 직접 골라요. FastAPI의 RAG는 근거 문단을 찾아 설명에 붙이는 역할만 해요.
- Redis와 RAG는 해당 기능을 만들 때 붙여요. 지금 뼈대에는 PostgreSQL만 있어요.

## 실행

```bash
docker compose up -d     # 로컬 PostgreSQL
./gradlew bootRun        # http://localhost:8080  (처음 켤 때 테이블이 자동으로 만들어져요)
./gradlew test           # 단위 테스트 (DB 없이 돌아가요)
```

프로필을 지정하지 않으면 `local`로 켜져요. `local`에서는 로그인 없이 개발용 계정(`dev@wsid.local`)으로 동작하고,
AI도 가짜 구현(`wsid.ai.mode=fake`)이라 AI 서비스 없이 훈련 흐름이 끝까지 돌아요.

```bash
# 1. 훈련 시작 → 응답의 sessionId, stateVersion을 다음 요청에 써요
curl -X POST localhost:8080/api/training/sessions

# 2. 답변 전송 (requestId는 메시지마다 새 UUID)
curl -X POST localhost:8080/api/training/sessions/1/turns \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"11111111-1111-1111-1111-111111111111","stateVersion":0,"message":"동네 카페에 갔어요"}'

# 3. 이어하기
curl localhost:8080/api/training/sessions/active
```

AI 서비스에 연결할 때는 `AI_MODE=http AI_BASE_URL=http://localhost:8000 ./gradlew bootRun`으로 실행해요.

## 서버를 켜면 테이블이 만들어지는 방식

Flyway가 서버 시작 때 `src/main/resources/db/migration`의 SQL을 번호 순서대로 실행해요.
이미 실행한 파일은 DB가 기억하고 있어서, 새로 추가된 파일만 실행돼요. 자세한 내용은 [db/README.md](src/main/resources/db/README.md)에 있어요.

- `V1__init_schema.sql`: 팀 ERD(GSB.sql)의 테이블 6개
- `V2__add_constraints.sql`: FK, 유니크, 인덱스 (ERD에 없던 제안)

엔티티에서 테이블을 자동 생성하는 방식(`ddl-auto: update`)은 쓰지 않아요. `ddl-auto: validate`로 엔티티와 테이블이 맞는지만 시작할 때 검사해요.

## 폴더 구조

기능 단위로 먼저 나누고, 그 안에서 계층으로 나눠요. **기능 폴더마다 README.md가 있어요.** 무엇을 만드는 곳인지, 어떤 순서로 개발하는지, 명세서의 어느 항목인지 적혀 있어요.

```
src/main/java/com/wsid
├─ common/      모든 기능이 같이 쓰는 것 (오류 응답, 보안 설정, 공통 enum)
├─ ai/          AI 서비스 호출. AiClient 인터페이스 + 가짜 구현 + HTTP 구현
├─ auth/        회원, 현재 사용자 (AUTH)
├─ training/    상황 대화, 가상 훈련 (SIM)     ← 끝까지 구현된 본보기
│   ├─ controller/   요청 검증, 응답 변환
│   ├─ service/      흐름 조율, 트랜잭션, AI 호출
│   ├─ domain/       엔티티와 규칙
│   ├─ repository/   DB 저장·조회
│   └─ dto/          요청·응답 형식
├─ report/      결과 리포트, 마이페이지 (RPT)
├─ solution/    실제 피해 대응 (SOL)
└─ content/     사기 유형, 대응 카드, 기관 (검토된 YAML)

src/main/resources
├─ application.yml
├─ db/migration/      Flyway SQL. 테이블은 여기서만 만들어요
└─ content/           팀이 PR로 검토하는 시나리오·대응 카드 YAML
```

## 명세서와 디렉토리 매핑

| 명세서 대분류 | 기능 ID | 디렉토리 | 테이블 | 상태 |
|---|---|---|---|---|
| 홈 (이어하기) | - | [training](src/main/java/com/wsid/training/README.md) | training_sessions | 구현 |
| 회원 | AUTH-001~004 | [auth](src/main/java/com/wsid/auth/README.md) | users | 엔티티만 |
| 시뮬레이션 시작, 훈련 재개 | SIM-001 | training | training_sessions, training_messages | 구현 |
| 상황 대화 | SIM-001, 002, 004 | training + [ai](src/main/java/com/wsid/ai/README.md) | training_sessions.context_facts | 임시 (첫 답변 뒤 바로 훈련) |
| 가상 훈련 | SIM-005~009, 011, 014 | training + ai | training_sessions, training_messages | 턴 규칙 구현, 나머지 미구현 |
| 결과 리포트, 마이페이지 | SIM-010, RPT-001~004 | [report](src/main/java/com/wsid/report/README.md) | training_reports | 엔티티만 |
| 피해 대응 진입·대화·결과 | SOL-003, 004, 007 | [solution](src/main/java/com/wsid/solution/README.md) | solution_sessions, solution_messages | 엔티티만 |
| 대응 정보, 기관 연결 | SOL-001, 002, 004~006 | [content](src/main/java/com/wsid/content/README.md) | 없음 (YAML) | 미구현 |
| 세션 관리, 개인정보 보호, 장애 대응 | 비기능 | [common](src/main/java/com/wsid/common/README.md) + 각 기능 | - | 일부 구현 |

기능별 README에 명세서 항목 하나하나의 상태가 있어요.

## 계층별 규칙

| 계층 | 하는 일 | 하지 않는 일 |
|---|---|---|
| controller | 요청 검증, DTO 변환, 상태 코드 | 규칙 판단, DB 접근 |
| service | 읽기 → AI 호출 → 저장 순서와 트랜잭션, 소유권 확인 | 턴·종료 규칙을 직접 계산 |
| domain | 엔티티와 규칙 판단. DB·Spring·AI 없이 테스트 가능 | HTTP 호출, 다른 기능의 repository 사용 |
| repository | 저장·조회 | 비즈니스 판단 |

꼭 지키는 것 다섯 가지예요.

1. **AI는 제안만, 결정은 domain이 해요.** 핵심 장면 노출, 종료, 긴급 안내는 코드 규칙으로 정해요.
2. **AI 호출은 트랜잭션 밖에서 해요.** `TrainingService.submitTurn`의 순서를 따라 하면 돼요.
3. **AI가 실패해도 다음 화면이 나와야 해요.** `AiUnavailableException`을 잡아 검토된 문구나 직접 선택으로 이어가요.
4. **조회는 항상 소유자와 함께 해요.** `findByIdAndUserId`처럼 사용자 ID를 조건에 넣고, 없으면 404예요.
5. **피해 설명 원문과 민감정보는 DB와 로그에 남기지 않아요.**

## 기능 하나를 추가하는 순서

예: RPT-001 "내 리포트에 저장"

1. **테이블이 바뀌면** `db/migration/V3__설명.sql` 추가 (기존 V 파일은 수정하지 않아요)
2. **domain**: 엔티티에 규칙 메서드 작성 (이미 `TrainingReport.saveToMyReports`가 있어요)
3. **테스트**: `src/test/.../report/domain/TrainingReportTest.java`로 규칙 검증
4. **repository**: 필요한 조회 메서드 추가
5. **service**: `CurrentUser`로 소유권 확인 → domain 메서드 호출 → 저장
6. **dto + controller**: 요청·응답 형식과 엔드포인트
7. 새 오류가 필요하면 `common/error/ErrorCode.java`에 추가
8. 기능 폴더 README의 상태 표를 고쳐요

## 브랜치와 PR

- `main`은 직접 push하지 않고 PR로만 합쳐요. CI(`./gradlew test`)가 통과해야 해요.
- 브랜치 이름: `feat/SIM-007-turn`, `fix/AUTH-004-resend`처럼 기능 ID를 넣어요.
- PR 하나는 기능 ID 하나를 기준으로 작게 올려요.

## 팀에서 정할 것

ERD와 명세서를 맞춰 보다가 나온 항목이에요.

- [ ] **로그인 없이 쓸 수 있는 범위**: ERD는 `training_sessions.user_id`, `solution_sessions.user_id`가 NOT NULL이라 훈련과 피해 대응 모두 로그인이 필요해요. 명세서에는 "훈련 결과에서 로그인했다면 같은 결과 화면으로 돌아간다"(AUTH-002)가 있어서 비회원 훈련을 전제한 것처럼 읽혀요. 피해 대응은 급한 상황인데 가입과 이메일 인증을 먼저 요구하게 되는 점도 같이 정해야 해요.
- [ ] **이메일 인증 코드 테이블**: AUTH-004(유효기간, 재발송 시 이전 코드 무효화, 재사용 금지)를 저장할 테이블이 ERD에 없어요.
- [ ] **로그인 세션 저장 위치**: 서버 메모리에 두면 재시작할 때 모두 로그아웃돼요. DB에 두려면 Spring Session 테이블이 필요해요.
- [ ] **phase, status, sender, input_kind의 값 이름**: 코드의 enum 값은 초안이에요. API 명세와 맞춰 확정해요.
- [ ] **훈련 중 실제 피해 전환**: 어느 훈련에서 넘어온 대응인지 연결할 컬럼과 훈련의 일시 중지 상태가 ERD에 없어요.
- [ ] **V2 제약 조건**: FK, 유니크, 인덱스는 제안이에요. 동의하면 ERD에도 반영해 주세요.
