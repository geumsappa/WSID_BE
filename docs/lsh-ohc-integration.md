# LSH·OHC 통합

- 기준: LSH `312bd39`, OHC `23644e7`.
- GUIDE: LSH의 단일 `GuideController`/`GuideCatalog` 및 `content/guides.yml`을 사용한다. OHC의 중복 GUIDE 컨트롤러·카탈로그 빈·전용 로더는 제거했다. 기존 미검토 유형 YAML·계획/구현 문서는 이력 자료이며 현재 API에 등록되지 않는다. 공개할 데이터는 기존 출처·검토 기준을 지켜 `guides.yml`에 등록한다.
- AUTH: 동일한 회원 principal·Spring Session Redis·SESSION 쿠키 설정을 공유한다. SOL 전용 체인은 유지하며 회원/비회원 모두 상담 변경 요청에 CSRF를 검사한다.
- AI·SOL: OHC의 AI-01~07 DTO/클라이언트와 SOL-01/02, V3 소유권 마이그레이션을 유지한다. 승인된 상담 대응 카드가 없을 때의 503 정책도 유지한다.
- 훈련: LSH의 `/api/trainings/**`와 리포트 기능을 유지한다. OHC의 사실 누적 discovery는 `/api/training/sessions/{id}/discovery/messages`, `/messages`, `/turns`에서 기존 계약으로 사용할 수 있다. `/api/trainings`는 `content`/`data` 봉투 계약을, 기존 경로는 `message`/기존 응답 계약을 사용한다.
- 진행 상태에는 `discoveryStatus`와 `finishSuggested`를 모두 보관한다. JSON 원문 getter는 `getContextFactsJson`, 기존 허용 사실 조회는 `getContextFacts`로 구분한다.
- 검증: `./gradlew test dbTest integrationTest --max-workers=2`. DB_URL과 WSID_TEST_DB_URL은 전용 PostgreSQL, REDIS_HOST/REDIS_PORT는 테스트 Redis를 사용한다. CI에서도 세 테스트 작업을 모두 실행한다.

실제 FastAPI·SMTP·운영 콘텐츠 승인은 이 통합의 검증 범위에 포함되지 않는다. 로그인 실패 제한과 AUTH의 CSRF 오류 형식은 기존 리뷰의 후속 개선 항목이다.
