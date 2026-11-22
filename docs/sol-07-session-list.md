# SOL-07 완료 상담 목록 구현 보고서

작성일: 2026-11-19 · 범위: Spring 완료 상담 목록 및 상세 시각 필드

## 동작

`GET /api/solutions/sessions?size=10&cursor=...`는 로그인한 활성 회원 본인의 완료 상담만 반환한다. size는 기본 10, 1~50이다. 비회원은 401, 잘못된 size나 커서는 400이다. 응답은 `success/data` 봉투이며 `items`, `nextCursor`, `hasNext`를 포함하고 캐시하지 않는다.

완료 시각 내림차순, 같은 시각이면 ID 내림차순으로 정렬한다. size+1개를 읽어 다음 페이지 존재 여부를 판단하고 마지막 항목의 완료 시각·ID를 커서로 사용한다. 조회 자체는 데이터를 바꾸거나 AI를 호출하지 않는다. 목록에는 원문·소유자·사실·카드를 반환하지 않는다.

제목은 상담을 시작할 때 입력한 피해 설명의 앞 40자다(마이페이지 '긴급 대응 기록' 화면 요청). 줄바꿈·연속 공백은 한 칸으로 줄이고, 40자를 넘으면 자른 뒤 `…`를 붙인다. 이 값은 민감정보 검사(`SensitiveDescriptionGuard`)를 통과한 설명에서만 만들고, 새 컬럼 `solution_sessions.title`(V7)에 저장한다. 전체 원문과 메시지 기록에는 여전히 원문을 남기지 않는다. 검사가 이름·주소 같은 정보까지 잡지는 않으므로 그런 내용은 제목에 남을 수 있다(팀 결정으로 허용). 설명 없이 시작한 상담과 V7 이전 상담은 title이 null이고, 확정 사실 중 YES인 항목을 송금·정보 제공·앱 설치·연락 지속 순으로 최대 두 개 조합한 제목을 쓴다. YES가 없으면 `피해 대응 상담`이다. SOL-02 상세 응답에는 `createdAt`, `completedAt`을 추가했으며 진행 중 상담의 완료 시각은 null이다.

## 파일과 연관 작업

| 파일 | 역할 |
| --- | --- |
| `solution/controller/SolutionListController.java` | 기존 세션 목록 경로와 no-store 응답 |
| `solution/service/SolutionListService.java` | 활성 회원 확인·입력 검사·목록 및 페이지 결과 생성 |
| `solution/repository/SolutionSessionRepository.java` | 회원 소유 COMPLETED 행을 필요한 컬럼만 조회 |
| `solution/domain/SolutionTitles.java`, `SolutionCursor.java` | 설명 앞 40자 제목·사실 기반 대체 제목과 커서 형식 |
| `db/migration/V7__add_solution_session_title.sql`, `StartSolutionService`, `SolutionSession` | 상담 시작 때 제목 저장 |
| `solution/security/SolutionSecurityConfig.java` | GET 경로를 허용하고 서비스에서 회원 확인 |
| `solution/service/SolutionQueryService.java`, `dto/SolutionStateResponse.java` | SOL-02 생성·완료 시각 반환 |
| `V6__index_completed_solution_sessions.sql` | 완료 목록용 부분 인덱스. 다른 브랜치와 버전 충돌 여부는 합치기 전에 확인 |
| `SolutionListServiceTest`, `SolutionListDatabaseTest`, `SolutionWebSecurityTest` | 제목·커서·조회·페이지 및 보안 검증 |

## 한계 및 후속 확인

커서는 Base64URL로 위치를 인코딩한 값이다. 형식이 잘못된 값은 거부하지만 **암호학적 서명이 없으므로 형식이 올바른 값의 변조 자체를 탐지하지 않는다.** 쿼리는 항상 현재 회원 ID를 함께 사용하므로 커서만으로 다른 회원의 상담을 조회할 수 없다. 서명 필요 여부는 팀과 합의해야 한다.

비회원 상담은 로그인 회원에게 자동 이전하지 않는다. SOL-02는 저장된 안전 문구와 선택값만 복원하며 사용자 원문·AI 답변을 복원하지 않는다. 카드가 필요한 완료 상담에서 현재 검토 카드가 없으면 기존 정책대로 503이다. 브랜치 합치기 전 V6 번호와 프론트 응답 형식을 확인한다.

## 검증

WSID 전용 PostgreSQL·Redis에서 단위 1,032건, DB 51건, 통합 33건이 모두 통과했다(실패·건너뜀 0). 목록 전용 DB 테스트는 회원·비회원 분리와 동일 시각 페이지 이동을 확인했다. 인덱스 사용 계획의 EXPLAIN 검증은 별도로 실행하지 않았다.
