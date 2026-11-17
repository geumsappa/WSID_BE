# API 기준과 구현 상태

사용자가 확정한 API 목록과 이용 정책을 개발 기준으로 정리했어요. 이 문서의 경로는 목표 계약이며, 아래 상태 열은 현재 뼈대 구현과의 차이예요. 요청·응답 필드는 아직 별도 정의가 필요해요.

## 확정 정책

- 로그인은 서버 세션과 쿠키로 확인해요. Access Token 재발급 API는 없어요.
- 훈련(TRN)과 리포트(RPT), 내 정보·로그인 상태 확인·로그아웃은 로그인한 사용자만 이용해요.
- 피해 대응 상담(SOL)은 로그인하지 않아도 시작할 수 있어요. 복원·메시지·사실 수정·종료는 해당 상담의 소유자만 할 수 있어요. 비회원은 서버가 HTTP 세션에 발급·관리하는 익명 UUID로 소유권을 확인해요(V3). 상담 ID만 안다고 다른 상담을 조회할 수 있게 만들지 않아요.
- GUIDE는 공개 조회예요. 내부 AI API는 Spring과 AI 서비스 사이의 호출이며 사용자에게 직접 공개하지 않아요.
- AUTH-05는 기존 로그인 상태 확인용 GET이에요. 로그인 세션을 새로 생성하거나 토큰을 발급하지 않아요. 로그인 세션이 없거나 만료되면 401을 반환하는 계약으로 만들어요.
- 로그인 성공 시 세션 쿠키를 발급해요. 저장 기반은 Spring Session Redis이고 SESSION 쿠키(HttpOnly, SameSite=Lax)를 사용해요. Secure는 기본 true, local HTTP에서만 false예요. 로그인 세션 무활동 만료 기본값은 30분이에요. 로그아웃하면 서버 세션과 쿠키를 폐기해요.

## 이름과 식별자

URL의 `sessions`는 리소스 이름이에요. 로그인 세션 기능을 켜는 옵션이 아니에요. 같은 API를 여러 훈련·상담이 사용하고 `{id}` 값으로 개별 건을 구분해요. 훈련은 `/api/trainings/{id}`, 상담은 `/api/solutions/sessions/{id}`를 사용해요.

엑셀의 AUTH-001/SIM-*/RPT-001/SOL-*와 I1~I6은 기능 식별자이고, 아래 AUTH-01/TRN-*/RPT-01/GUIDE-*/AI-*는 API 식별자예요. 같은 번호 체계로 취급하지 않아요.

## 목표 훈련 흐름

로그인 → 새 훈련 생성·첫 질문 → 답변에 따른 후속 질문과 확인된 사실 누적 → 시나리오 생성 → 가상 훈련 시작 → 훈련 메시지·행동 기록 → 종료·전체 평가 리포트예요. API를 나눠도 화면에서 매번 버튼을 눌러야 하는 것은 아니고 프론트가 순서대로 호출할 수 있어요.

훈련 메시지와 진행 상태는 DB에 저장하고, 확인된 사실·시나리오·필요한 최근 대화를 LLM 요청에 넣어요. 이번 요청에서 이미 가진 정보를 저장 후 다시 조회할 필요는 없어요. 다음 요청에서는 저장한 맥락을 복원해 새 답변과 합쳐요. AI 호출 중에는 DB 트랜잭션을 유지하지 않아요.

현재 코드는 첫 상황 답변 뒤 고정 시나리오로 바로 넘어가는 임시 흐름이에요. 실제 후속 질문·개인화와 단계별 목표 API는 아직 연결되지 않았어요. 리포트는 잘한 행동·놓친 위험 신호·권장 대응을 다루고, 턴별 피드백을 제공하는 요구사항은 제외했어요.

## 내부 AI

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| AI-01 | POST | `/internal/ai/discovery/next` | 내부 | fake 및 HTTP 어댑터·응답 계약 테스트 구현; 실제 FastAPI 통합 검증은 미완료 |
| AI-02 | POST | `/internal/ai/ocr/extract` | 내부 | 어댑터 구현(`AiClient.extractText`, fake/HTTP multipart); TRN-05 연결·AI 서비스 통합 검증 미완료 |
| AI-03 | POST | `/internal/ai/scenarios/generate` | 내부 | fake/HTTP 클라이언트·엄격한 응답 타입 검사·요청 ID·전용 타임아웃 구현. FastAPI·훈련 생성/저장은 미연결. [구현 안내](ai-03-scenario.md) |
| AI-04 | POST | `/internal/ai/simulation/reply` | 내부 | 새 replySimulation fake/HTTP 클라이언트·응답 타입 검사·계약 테스트 구현. 기존 훈련 서비스는 옛 /internal/training/turn 유지, FastAPI·TRN-08 전환 미완료. [구현 보고서](ai-04-simulation.md) |
| AI-05 | POST | `/internal/ai/training/evaluate` | 내부 | evaluateTraining fake/HTTP 클라이언트·엄격한 응답 타입 검사·요청 ID·전용 타임아웃 구현. FastAPI·리포트 생성/저장/조회는 미연결. [구현 보고서](ai-05-evaluation.md) |
| AI-06 | POST | `/internal/ai/solutions/extract` | 내부 | extractSolutionFacts fake/HTTP 클라이언트·엄격한 타입/미확정 집합 검사·요청 ID 구현. SOL-01·03의 추출/저장에 연결. 실제 FastAPI·운영 카드 콘텐츠는 미연결. [구현 보고서](ai-06-solution-extract.md) |
| AI-07 | POST | `/internal/ai/solutions/explain` | 내부 | explainSolution fake/HTTP 클라이언트·엄격한 타입·요청 근거 코드 대조·출력 패턴 검사 구현. 실제 FastAPI/RAG·승인 카탈로그·상담·저장은 미연결. [구현 보고서](ai-07-solution-explain.md) |

## 회원·인증

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| AUTH-01 | POST | `/api/auth/signup` | 불필요 | 구현. 가입 신청 및 인증 메일 발송 |
| AUTH-02 | POST | `/api/auth/email/resend` | 불필요 | 구현. 인증번호 재발송 |
| AUTH-03 | POST | `/api/auth/email/verify` | 불필요 | 구현. 인증번호 확인 및 계정 활성화 |
| AUTH-04 | POST | `/api/auth/login` | 불필요 | 구현. 이메일과 비밀번호로 로그인하고 세션 쿠키 발급 |
| AUTH-05 | GET | `/api/auth/session` | 필요 | 구현. 기존 세션으로 로그인 상태 확인 |
| AUTH-06 | POST | `/api/auth/logout` | 필요 | 구현. 서버 세션과 쿠키 폐기 |
| AUTH-07 | GET | `/api/users/me` | 필요 | 구현. 로그인한 회원 정보 반환 |

AUTH-04는 JSON의 `email`, `password`를 받아 활성 계정에 `SESSION` 쿠키를 발급해요. 비밀번호가 틀리거나 이메일이 등록되지 않았으면 401 `INVALID_CREDENTIALS`, 올바른 비밀번호를 입력했지만 이메일 인증 전이면 403 `EMAIL_NOT_VERIFIED`를 반환해요.

AUTH-04의 성공 응답은 `data.user`에 `id`, `email`, `gender`를 담아요. AUTH-05의 성공 응답은 같은 `data.user`와 `data.authenticated: true`를 담아요. 세션이 없거나 만료되면 401 `LOGIN_REQUIRED`를 반환해요.

AUTH-06은 본문 없이 호출해요. 먼저 세션 쿠키로 `GET /api/auth/csrf`를 호출해 `data.headerName`, `data.token`을 받고, 로그아웃 요청에 해당 헤더를 넣어요. 토큰이 없거나 유효하지 않으면 403, 성공하면 204를 반환하고 세션 쿠키를 만료시켜요. 이 토큰 전달 방식은 노션에서 미정이므로 팀 확인이 필요해요.

AUTH-07은 `data`에 `id`, `email`, `gender`, `status`, `createdAt`을 반환해요. 두 API 모두 로그인 세션이 없으면 401 `LOGIN_REQUIRED`를 반환해요.

가입·인증·로그인 POST는 JSON을 받아요. 로컬에서는 기본으로 `http://localhost:8443`의 프론트 요청과 세션 쿠키를 허용해요. 운영에서 프론트와 API의 출처가 다르면 `WSID_CORS_ALLOWED_ORIGINS`에 허용할 프론트 HTTPS 출처를 쉼표로 구분해 설정해요. 프론트와 `/api`가 같은 출처라면 이 설정은 비워두면 돼요.

현재 구현된 `/api/training/**`는 로컬에서도 로그인 세션으로 소유자를 확인해요. 변경 요청은 로그아웃과 같은 CSRF 헤더를 요구해요. 인증 없이 조회하면 401, 다른 회원의 훈련을 조회하면 404를 반환해요.

## 유형·가이드

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| GUIDE-01 | GET | `/api/guides/fraud-types` | 불필요 | 유형 목록 |
| GUIDE-02 | GET | `/api/guides/fraud-types/{code}` | 불필요 | 유형 상세, 미공개·미등록 코드는 404 `GUIDE_NOT_FOUND` |
| GUIDE-03 | GET | `/api/guides/response-cards` | 불필요 | `codes` 쉼표 구분 필터. 생략하면 전체 공개 카드 |
| GUIDE-04 | GET | `/api/guides/institutions` | 불필요 | 기관 목록 |

가이드는 `content/guides.yml`에서 팀 검토 완료 항목을 읽어요. 승인된 데이터가 등록되기 전에는 빈 목록을 반환해요.

## 리포트

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| RPT-01 | GET | `/api/trainings/{id}/report` | 필요 | 본인 훈련의 리포트 조회. 저장 전에도 가능 |
| RPT-02 | POST | `/api/reports/{id}/save` | 필요 | CSRF 헤더 필요. 저장과 원문 삭제를 한 트랜잭션으로 처리 |
| RPT-03 | GET | `/api/reports` | 필요 | 본인이 저장한 리포트를 완료 최신순으로 조회. `size` 1~100, 기본 10 |
| RPT-04 | GET | `/api/reports/{id}` | 필요 | 본인이 저장한 리포트 상세 조회 |
| RPT-05 | GET | `/api/reports/{id}/pdf` | 필요 | 본인 리포트의 PDF 바이너리 다운로드 |

TRN-11이 완료되면 리포트를 만들어요. 기본 모드는 규칙 기반 평가를 생성하고, HTTP 모드는 AI-05를 호출해요. RPT-03의 노션 오류 예시 `TOKEN_EXPIRED`는 서버 세션 정책에 맞춰 `401 LOGIN_REQUIRED`로 보완할 필요가 있어요. 다음 페이지는 `nextCursor` 값을 그대로 보내요.

## 피해 대응

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| SOL-01 | POST | `/api/solutions/sessions` | 불필요 (비회원 상담 소유권 확인 필요) | 구현: 회원/비회원 소유자·CSRF·민감정보 거부·AI-06 추출·질문·카드 규칙. 운영 카드 콘텐츠 없음([보고서](sol-01-session-start.md)) |
| SOL-02 | GET | `/api/solutions/sessions/{id}` | 불필요 (비회원 상담 소유권 확인 필요) | 구현: 조회 전용 소유권 확인·같은 404·현재 검토 카드·메시지 기록([보고서](sol-02-session-get.md)) |
| SOL-03 | POST | `/api/solutions/sessions/{id}/messages` | 불필요 (비회원 상담 소유권 확인 필요) | 구현: TEXT AI-06·CHOICE·확정값 보호·UNKNOWN 질문 진행·소유권/CSRF·CAS/중복/버전. 운영 카드/FastAPI 별도([보고서](sol-03-session-message.md)) |
| SOL-04 | PATCH | `/api/solutions/sessions/{id}/facts` | 불필요 (비회원 상담 소유권 확인 필요) | 구현: 직접 정정·전체 카드/다음 질문 재계산·SOL-03과 같은 응답·UNKNOWN 정정 이력·CAS/버전. 운영 카드 별도([보고서](sol-04-facts-update.md)) |
| SOL-05 | POST | `/api/solutions/sessions/{id}/complete` | 불필요 (비회원 상담 소유권 확인 필요) | 구현: 본문 없음/빈 객체·소유권/CSRF·반복 무변경 200·최초 시각/버전 갱신·질문 종료. AI/카드 미호출([보고서](sol-05-session-complete.md)) |

## 훈련

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| TRN-01 | POST | `/api/trainings` | 필요 | 구현. CSRF 헤더와 JSON `{}`를 보내면 새 훈련과 첫 질문을 반환 |
| TRN-02 | GET | `/api/trainings/active` | 필요 | 구현. 만료되지 않은 최근 훈련을 반환하며 없으면 `200`과 `data: null` |
| TRN-03 | GET | `/api/trainings/{id}` | 필요 | undefined |
| TRN-04 | POST | `/api/trainings/{id}/discovery/messages` | 필요 | 구현. 상황 파악 답변과 다음 질문 저장, 민감정보 및 중복 요청 차단 |
| TRN-05 | POST | `/api/trainings/{id}/discovery/images` | 필요 | 구현. PNG/JPEG 최대 3장, 장당 5MB. 실제 OCR은 AI-02 필요 |
| TRN-06 | POST | `/api/trainings/{id}/scenario` | 필요 | 구현. 상황 파악 완료 후 시나리오 생성과 SCENARIO_READY 전환 |
| TRN-07 | POST | `/api/trainings/{id}/start` | 필요 | 구현. 준비된 시나리오로 가상 훈련 시작 |
| TRN-08 | POST | `/api/trainings/{id}/messages` | 필요 | 구현. 텍스트·선택지 요청 처리, 다음 대사·판정·종료 제안 반환 |
| TRN-09 | GET | `/api/trainings/{id}/mock-page` | 필요 | 구현. 가상 금융 모의 페이지 조회 |
| TRN-10 | POST | `/api/trainings/{id}/actions` | 필요 | 구현. 가상 화면 행동과 허용된 메타데이터만 저장 |
| TRN-11 | POST | `/api/trainings/{id}/finish` | 필요 | 구현. 훈련 종료와 리포트 저장을 함께 처리. 반복 종료 시 같은 리포트 반환 |

훈련 변경 요청에는 로그인 세션의 CSRF 헤더가 필요해요. `stateVersion` 충돌은 409 `STATE_VERSION_CONFLICT`, 이미 저장된 메시지·행동 요청 ID는 409 `DUPLICATE_REQUEST`를 반환해요.

기본 `AI_MODE=fake`에서는 상황 답변 3회 뒤 검토된 중고거래 예시로 진행하고, OCR은 직접 입력을 안내해요. `AI_MODE=http`에서는 노션의 AI-01~05 경로와 `success/data` 형식으로 FastAPI를 호출해요. AI 평가가 실패하면 훈련을 완료 처리하지 않아 다시 종료 요청을 보낼 수 있어요.

팀 확인 항목은 내부 AI의 세부 필드(사기 유형 코드, 시나리오 첫 대사), 금융 외 모의 페이지 코드, 프론트 상태 복구에 쓰는 TRN-03 추가 필드(`scenario`, `messages[].phase`, `canGenerateScenario`, `shouldFinish`)예요.

## LSH·OHC 통합

GUIDE-01~04는 LSH의 `GuideCatalog`와 `content/guides.yml`을 사용해요. OHC의 기존 유형 YAML 초안은 참고 자료로 남지만 공개 목록 로더에는 연결하지 않아요.

훈련 공개 API `/api/trainings/**`는 LSH 계약을 사용해요. OHC의 상황 파악·사실 누적 기능은 기존 `/api/training/sessions` 경로의 `/turns`, `/discovery/messages`, `/messages`에서 유지해요. 두 경로의 요청·응답 계약은 서로 달라요.

상담은 OHC의 전용 보안 체인과 `/api/solutions/csrf`를 유지해요. 회원은 `/api/auth/csrf`에서 받은 토큰도 같은 세션 쿠키와 함께 사용할 수 있어요.
