# 개발 문서 목차

| 문서 | 읽는 목적 |
|---|---|
| [API 기준과 구현 상태](api.md) | 확정한 경로·이용 정책과 기능별 구현 상태 확인 |
| [AI-01 개발·검증 안내](ai-01-discovery.md) | 질문 횟수·상태·API 응답·테스트 실행 방법 확인 |
| [AI-01 Spring·DB·FastAPI 연결 설계](ai-01-integration.md) | 어떤 파일이 어떤 데이터를 주고받고, 무엇을 저장해야 정상 동작하는지 확인 |
| [AI-02 OCR 추출 개발·연결 설계](ai-02-ocr.md) | Spring 클라이언트를 어떻게 만들었는지, FastAPI 예시 코드와 HTTP 계약, 경우별 처리 확인 |
| [AI-03 개인화 훈련 시나리오 생성 구현 계획서](ai-03-scenario-plan.md) | 구현 범위·API 계약·Spring/FastAPI 역할·훈련 연결 순서·검증 계획 확인 |
| [AI-03 시나리오 생성 구현·연결 안내](ai-03-scenario.md) | 구현한 코드·DTO·HTTP 검증·설정·관련 코드의 역할·FastAPI 및 훈련 연결 작업·검증 결과 확인 |
| [AI-04 역할극 응답·행동 분석 구현 계획서](ai-04-simulation-plan.md) | 새 내부 API 계약·기존 코드 전환·행동/종료/모의 페이지 정책·Spring/FastAPI 역할·검증 계획 확인 |
| [AI-04 구현 보고서와 연동 개발 안내](ai-04-simulation.md) | 구현한 DTO·HTTP 처리·테스트 결과와 파일별 상호작용, 이후 서비스·DB·FastAPI 개발 시 참고사항 확인 |
| [AI-05 훈련 평가 구현·테스트 계획서](ai-05-evaluation-plan.md) | 평가 계약·원문 보호·행동 근거·리포트 생성/저장·단계별 테스트와 결정 항목 확인 |
| [AI-05 구현 보고서와 연동 개발 안내](ai-05-evaluation.md) | 구현한 DTO·HTTP 검사·테스트 결과와 파일별 상호작용, 리포트 서비스·저장·조회를 나중에 구현하는 방법 확인 |
| [AI-06 피해 사실 추출 구현·테스트 계획서](ai-06-solution-extract-plan.md) | 단계별 범위·개발 순서·계약·테스트 기준과 FastAPI·상담 연결 전 결정 항목 확인 |
| [AI-06 피해 사실 추출 구현 보고서와 연동 개발 안내](ai-06-solution-extract.md) | 사실·미확정 항목 계약, DTO·HTTP 처리·테스트 결과, 상담 사실 누적·수정·카드 선택 및 FastAPI 디렉토리별 후속 작업 확인 |
| [AI-07 피해 대응 근거 설명 구현·테스트 계획서](ai-07-solution-explain-plan.md) | 승인 근거 전달·참조 코드 검증·출력 보호·긴급 카드 우선 정책과 Spring/FastAPI 개발 순서·테스트 계획 확인 |
| [AI-07 피해 대응 근거 설명 구현 보고서와 연동 개발 안내](ai-07-solution-explain.md) | 실제 DTO·HTTP·코드/출력 검사·테스트 결과와 콘텐츠·상담·DB·FastAPI 파일별 후속 작업 확인 |
| [GUIDE-01 사기 유형 목록 조회 개발·테스트 계획서](guide-01-fraud-types-plan.md) | 검토된 YAML·기동 검증·공개 응답·익명 접근·파일별 개발 순서·테스트·패키징 계획 확인 |
| [GUIDE-01 사기 유형 목록 조회 구현 보고서와 연동 안내](guide-01-fraud-types.md) | 실제 YAML·기동 카탈로그·공개 응답·보안·테스트/패키징 결과와 파일별 후속 작업 확인 |
| [GUIDE-02 사기 유형 상세 조회 개발·테스트 계획서](guide-02-fraud-type-detail-plan.md) | 상세 YAML·참고 출처·이전 목록 호환·404·익명 접근·파일별 개발 순서와 회귀 테스트 계획 확인 |
| [SOL-01 피해 대응 상담 시작 구현·테스트 계획서](sol-01-session-start-plan.md) | 회원/비회원 소유권·CSRF·원문 보호·AI-06·승인 카드·초기 저장·DB/보안 테스트와 선행 결정 사항 확인 |
| [SOL-01 피해 대응 상담 시작 구현 보고서](sol-01-session-start.md) | 비회원 CSRF→상담 시작 흐름, 요청·응답·오류, 소유권·V3 마이그레이션, 바뀐 파일, 테스트 결과와 LSH 확인 항목 확인 |
| [SOL-02 피해 대응 상담 복원 구현·테스트 계획서](sol-02-session-get-plan.md) | 조회 전용 소유권 확인·비회원 복원 범위·응답/메시지 형식·카드 복원 기준·보안·테스트와 결정 항목 확인 |
| [SOL-02 피해 대응 상담 복원 구현 보고서](sol-02-session-get.md) | 복원 API 계약·404 정책·LSH 파일 원복과 SOL 전용 보안 체인·파일별 상호작용·REPEATABLE_READ 근거·테스트 결과 확인 |
| [SOL-03 피해 대응 상담 메시지 구현·테스트 계획서](sol-03-session-message-plan.md) | TEXT/CHOICE 계약·사실 병합·UNKNOWN 질문 진행·중복/버전/동시성·원문 보호·파일별 개발 순서와 테스트 계획 확인 |
| [SOL-03 피해 대응 상담 메시지 구현 보고서와 연동 안내](sol-03-session-message.md) | 실제 TEXT/CHOICE 처리·CAS/중복/버전/롤백·테스트 결과·파일별 상호작용과 FastAPI·프론트·SOL-04/05 후속 작업 확인 |

AI-01을 개발하거나 다른 팀의 FastAPI·DB와 연결할 때는 연결 설계를 먼저 읽고, 필드·규칙은 개발·검증 안내와 API 기준을 함께 확인해요. 현재 FastAPI 저장소는 이 프로젝트에 없으므로 연결 설계의 Python 파일 구조는 역할을 설명하기 위한 제안이에요.
