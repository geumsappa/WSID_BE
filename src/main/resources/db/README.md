# DB 스키마 관리 (Flyway)

서버를 켜면 Flyway가 `migration/` 폴더의 SQL을 **번호 순서대로, 아직 실행하지 않은 것만** 실행해요.
어떤 파일까지 실행했는지는 DB의 `flyway_schema_history` 테이블에 기록돼요.

## 파일

| 파일 | 내용 |
|---|---|
| `V1__init_schema.sql` | 팀 ERD(GSB.sql)의 테이블 6개. 백틱 제거와 `card_codes_json` 기본값(`''` → `'[]'`)만 고쳤어요 |
| `V2__add_constraints.sql` | ERD에 없던 FK, 유니크, 인덱스 제안. 항목마다 이유를 주석으로 적었어요 |

## 테이블을 바꾸는 방법

1. `V3__add_email_verifications.sql`처럼 **다음 번호의 새 파일**을 만들어요. 이름은 `V숫자__설명.sql` (밑줄 두 개)
2. 엔티티를 같이 고쳐요.
3. 서버를 켜면 새 파일만 실행돼요.

**다른 사람과 공유했거나 유지할 DB에 적용한 V 파일은 브랜치와 무관하게 수정하지 않아요.** 새 마이그레이션을 추가해요. 내용이 바뀌면 다른 팀원의 DB와 체크섬이 달라져 서버가 켜지지 않아요.
아직 공유하지 않았고 폐기 가능한 개인 DB에만 적용한 파일은 수정할 수 있어요. 이 경우에만 `docker compose down -v`가 로컬 데이터를 모두 삭제한다는 점을 확인한 뒤 초기화해요.

훈련은 회원 전용이라 user_id 소유권을 유지해요. 피해 대응은 비회원 허용으로 확정했으나 현재 solution_sessions.user_id는 NOT NULL·외래키 구조라 그대로는 지원하지 못해요. 비회원 소유자 식별과 저장 구조를 결정하고 새 마이그레이션으로 반영해야 해요.

## 엔티티와 테이블이 안 맞으면

`ddl-auto: validate`라서 서버가 시작할 때 오류로 알려줘요. 오류 메시지에 테이블과 컬럼 이름이 나와요.
엔티티의 `@Column` 이름·타입과 SQL을 맞추면 돼요.

## 왜 `ddl-auto: update`를 쓰지 않나요

엔티티만 고치면 테이블이 따라 바뀌어 편하지만, 여러 명이 쓰면 문제가 생겨요.

- 누가 언제 무엇을 바꿨는지 기록이 남지 않아요.
- 컬럼 이름을 바꾸면 기존 컬럼은 남기고 새 컬럼을 만들어서 데이터가 사라진 것처럼 보여요.
- 로컬, 개발 서버, 운영 DB가 서로 달라져요.

Flyway는 SQL 파일이 git에 남아서 PR로 리뷰할 수 있고, 모든 환경이 같은 순서로 같은 SQL을 실행해요.

## JSONB 컬럼

엔티티에서는 `String`으로 받고, 쓸 때 `cast(? as jsonb)`로 넣어요 (`@ColumnTransformer`).
Spring Boot 4는 Jackson 3을 쓰는데, Hibernate의 JSON 자동 변환이 Jackson 3을 찾지 못하는 문제가 보고돼 있어서 이 방식을 택했어요.
객체로 바꾸는 일은 service나 domain에서 해요 (예: `training/domain/FlowState`).
