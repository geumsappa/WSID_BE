# 개발 문서 목차

| 문서 | 읽는 목적 |
|---|---|
| [API 기준과 구현 상태](api.md) | 확정한 경로·이용 정책과 기능별 구현 상태 확인 |
| [AI-01 개발·검증 안내](ai-01-discovery.md) | 질문 횟수·상태·API 응답·테스트 실행 방법 확인 |
| [AI-01 Spring·DB·FastAPI 연결 설계](ai-01-integration.md) | 어떤 파일이 어떤 데이터를 주고받고, 무엇을 저장해야 정상 동작하는지 확인 |
| [AI-02 OCR 추출 개발·연결 설계](ai-02-ocr.md) | FastAPI OCR(Tesseract)과 Spring 클라이언트의 HTTP 계약, 경우별 처리 확인 |
| [SOL-06 피해 대응 사진 입력](sol-06-session-images.md) | 사진 글자를 상담 사실 추출·근거 검색에 쓰는 흐름, TRN-05에서 옮긴 검증, 오류 코드·테스트 확인 |
| [SOL-06 검토·수정 보고서](sol-06-review-fix-report.md) | 대응 안내에 사진 종류·요약 반영, 긴 요약 처리, 대기 시간 조정과 남은 작업·머지 순서 확인 |
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
| [SOL-04 피해 사실 수정 구현·테스트 계획서](sol-04-facts-update-plan.md) | 직접 정정·전체 카드/질문 재계산·UNKNOWN/수정 이력·응답/재시도·소유권/CSRF·SOL-03과의 버전 경쟁·파일별 개발 및 테스트 계획 확인 |
| [SOL-04 피해 사실 수정 구현 보고서](sol-04-facts-update.md) | 직접 정정 API 계약·SOL-03과 같은 응답·UNKNOWN 정정 기록·CAS/경쟁·파일별 상호작용·테스트 결과와 남은 일 확인 |
| [SOL-05 피해 대응 상담 종료 구현·테스트 계획서](sol-05-session-complete-plan.md) | 본문 없음/빈 객체 종료·반복 200·완료 시각/버전·수정과 종료의 경쟁·소유권/CSRF·완료 후 복원·파일별 개발 및 테스트 계획 확인 |
| [SOL-05 피해 대응 상담 종료 구현 보고서와 연동 안내](sol-05-session-complete.md) | 실제 종료/반복 처리·완료와 수정의 저장 순서·변경 파일·900건 검증 결과·프론트/명세 전달 내용 확인 |
| [상담 LangGraph 도입·추가 질문·근거 답변 연결 및 영향 범위](solution-langgraph-integration-plan.md) | 실제 Python/Spring 변경 파일·AI-06/07 유지·근거 승인·SOL-03 확장·안전한 대화 상태·프론트/DB/종료 연계와 개발 순서, 계약 초안 v1(내부 PLAN/ANSWER 3.1장·공개 purpose/questionId/assistant 5.1장) 확인 |
| [SOL-03 자유 질문·추가 질문 수정 계획서](solution-assist-question-plan.md) | purpose 확장·assistant 응답·대기 질문 저장·FastAPI 연결·시간 제한 설계 확인 |
| [SOL-03 자유 질문·추가 질문 구현 보고서](solution-assist-question.md) | 실제 요청·응답·오류, 처리 흐름, V5 저장·SOL-02/04/05 연동, 설정, 테스트 결과와 남은 제한 확인 |
| [상담 assist Spring 시간 제한 보고서](solution-assist-spring-timeout-report.md) | assist 전용 read timeout과 로컬 FastAPI 계약 테스트 결과 확인 |
| [SOL-03 자유 질문 확장 및 FastAPI 연결 수정 계획서](solution-assist-question-plan.md) | messages의 purpose 확장·OHC app.main 연결·PLAN/ANSWER 예시·대기 상태/CAS·HTTP 모드 선택·변경 파일과 테스트 확인 |
| [상담 assist Spring 시간 제한 수정 보고서](solution-assist-spring-timeout-report.md) | 내부 클라이언트 시간 설정·Python과의 관계·테스트 및 미구현 상담 연결 범위 확인 |

AI-01을 개발하거나 다른 팀의 FastAPI·DB와 연결할 때는 연결 설계를 먼저 읽고, 필드·규칙은 개발·검증 안내와 API 기준을 함께 확인해요. 현재 FastAPI 저장소는 이 프로젝트에 없으므로 연결 설계의 Python 파일 구조는 역할을 설명하기 위한 제안이에요.


## 훈련 발신자 표시

TRN-06 생성 및 TRN-03 복원의 `scenario`에 `senderName`, `organizationName`이 추가된다.
예: `{"title":"채용 확인 훈련","description":"가상 상황","senderName":"기업은행 채용 담당자","organizationName":"기업은행"}`.
FE는 발신자 이름을 시나리오에서 표시하고, 채팅 스크롤 밖에 해당 기관과 무관한 가상 훈련임을 안내한다.
기존 시나리오에 인물 정보가 없으면 `상대방`으로 표시한다. 실제 기관 로고·인증 배지·공식 링크는 추가하지 않는다.


## 훈련 링크 표시

`[모의링크]`는 화면용 텍스트가 아닌 내부 링크 표시다. FE는 이를 외부 주소 없이 `본인 확인하기` 또는 `지원자 본인 확인` 버튼으로 바꾼다.
TRN-07 시작 응답에도 `mockPage`가 포함되며, 첫 대사·반복 요청·TRN-03 복원의 링크 모두 같은 고정 내부 화면을 사용한다.
클릭과 입력 시도는 기존 TRN-10 행동 코드로 기록한다. 종료된 훈련에서는 링크 버튼을 비활성화한다.


## 확인 페이지 제출 후 진행과 리포트

TRN-03의 `mockPageSubmitted`는 서버가 저장한 확인 화면 제출 여부다. FE는 새 탭을 다시 열어도 제출 완료 화면으로 복원한다.
AI-04에는 입력값 없이 SYSTEM 행동 사건과 선택적 `currentScene`을 전달하고, 응답 `nextScene`을 저장한다. 같은 확인 페이지의 재제출을 요구하지 않는다.
평가 요청은 전용 대기시간을 사용한다(기본 AI 생성 25초, Spring 응답 대기 40초). 시나리오 생성은 Spring에서 최대 60초 대기한다.
리포트 생성 실패 시 훈련을 완료 처리하지 않으므로 같은 훈련에서 재시도할 수 있다.
