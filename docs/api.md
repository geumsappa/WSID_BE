# API 기준과 구현 상태

사용자가 확정한 API 목록과 이용 정책을 개발 기준으로 정리했어요. 이 문서의 경로는 목표 계약이며, 아래 상태 열은 현재 뼈대 구현과의 차이예요. 요청·응답 필드는 아직 별도 정의가 필요해요.

## 확정 정책

- 로그인은 서버 세션과 쿠키로 확인해요. Access Token 재발급 API는 없어요.
- 훈련(TRN)과 리포트(RPT), 내 정보·로그인 상태 확인·로그아웃은 로그인한 사용자만 이용해요.
- 피해 대응 상담(SOL)은 로그인하지 않아도 시작할 수 있어요. 복원·메시지·사실 수정·종료는 해당 상담의 소유자만 할 수 있어요. 비회원 소유자 식별 방법과 저장 구조는 구현 전에 확정해야 해요. 상담 ID만 안다고 다른 상담을 조회할 수 있게 만들지 않아요.
- GUIDE는 공개 조회예요. 내부 AI API는 Spring과 AI 서비스 사이의 호출이며 사용자에게 직접 공개하지 않아요.
- AUTH-05는 기존 로그인 상태 확인용 GET이에요. 로그인 세션을 새로 생성하거나 토큰을 발급하지 않아요. 로그인 세션이 없거나 만료되면 401을 반환하는 계약으로 만들어요.
- 로그인 성공 시 세션 쿠키를 발급하고, 로그아웃 시 서버 로그인 세션과 쿠키를 폐기해요. 로그인 세션 만료 시간·쿠키 설정·저장소는 별도로 정해요. 훈련 무활동 24시간 초안과 별개예요.

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
| AI-03 | POST | `/internal/ai/scenarios/generate` | 내부 | 미구현; 고정 유형·상수 문구로 대체 |
| AI-04 | POST | `/internal/ai/simulation/reply` | 내부 | 어댑터 구현; 실제 호출은 /internal/training/turn으로 불일치, 맥락 필드 TODO |
| AI-05 | POST | `/internal/ai/training/evaluate` | 내부 | 미구현; report 문서 계획 |
| AI-06 | POST | `/internal/ai/solutions/extract` | 내부 | 미구현; AiClient TODO |
| AI-07 | POST | `/internal/ai/solutions/explain` | 내부 | 미구현; 문서는 I6 근거 검색으로 설명 |

## 회원·인증

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| AUTH-01 | POST | `/api/auth/signup` | 불필요 | User.signUp만; API·서비스 없음 |
| AUTH-02 | POST | `/api/auth/email/resend` | 불필요 | 미구현; 인증 코드 저장소도 아직 없음 |
| AUTH-03 | POST | `/api/auth/email/verify` | 불필요 | User.verifyEmail만; 코드 확인·API 없음 |
| AUTH-04 | POST | `/api/auth/login` | 불필요 | 세션 방식 계획 일치; 로그인 구현 없음 |
| AUTH-05 | GET | `/api/auth/session` | 필요 | 목표 API 문서화; 실제 세션 확인 API는 미구현 |
| AUTH-06 | POST | `/api/auth/logout` | 필요 | 미구현; 문서에는 세션 폐기 계획 |
| AUTH-07 | GET | `/api/users/me` | 필요 | 목표 API 문서화; 실제 회원정보 API는 미구현 |

## 유형·가이드

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| GUIDE-01 | GET | `/api/guides/fraud-types` | 불필요 | content 문서 계획; 로더·API 없음, 현재 보안 규칙에 공개 경로 등록 필요 |
| GUIDE-02 | GET | `/api/guides/fraud-types/{code}` | 불필요 | content 문서 계획; 로더·API 없음 |
| GUIDE-03 | GET | `/api/guides/response-cards` | 불필요 | 카드 형식 README만; 적용 조건의 요청·응답 정의 필요 |
| GUIDE-04 | GET | `/api/guides/institutions` | 불필요 | content 문서 계획; API 없음 |

## 리포트

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| RPT-01 | GET | `/api/trainings/{id}/report` | 필요 | 엔티티·repository만; 목표 경로의 서비스·API 미구현 |
| RPT-02 | POST | `/api/reports/{id}/save` | 필요 | saveToMyReports 엔티티 메서드만; 원문 삭제·API 없음 |
| RPT-03 | GET | `/api/reports` | 필요 | repository TODO; API 없음 |
| RPT-04 | GET | `/api/reports/{id}` | 필요 | 문서 계획; 소유권 조회 서비스·API 없음 |
| RPT-05 | GET | `/api/reports/{id}/pdf` | 필요 | 문서 계획; PDF 생성·API 없음 |

## 피해 대응

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| SOL-01 | POST | `/api/solutions/sessions` | 불필요 (비회원 상담 소유권 확인 필요) | SolutionSession.start만; 시작 설명 즉시 추출 없음 |
| SOL-02 | GET | `/api/solutions/sessions/{id}` | 불필요 (비회원 상담 소유권 확인 필요) | 엔티티·소유자 조건 repository만; 서비스·API 없음 |
| SOL-03 | POST | `/api/solutions/sessions/{id}/messages` | 불필요 (비회원 상담 소유권 확인 필요) | 메시지 엔티티만; 서비스·API 없음 |
| SOL-04 | PATCH | `/api/solutions/sessions/{id}/facts` | 불필요 (비회원 상담 소유권 확인 필요) | factsJson 컬럼만; 병합·재판정·API 없음 |
| SOL-05 | POST | `/api/solutions/sessions/{id}/complete` | 불필요 (비회원 상담 소유권 확인 필요) | completedAt 컬럼만; 종료 메서드·API 없음 |

## 훈련

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| TRN-01 | POST | `/api/trainings` | 필요 | 새 경로 구현; local 개발 계정 기준 DISCOVERY 생성 |
| TRN-02 | GET | `/api/trainings/active` | 필요 | 새 경로 구현; 소유자 조건 활성 훈련 조회 |
| TRN-03 | GET | `/api/trainings/{id}` | 필요 | 새 경로 구현; 대화·상황 파악 상태·질문 수 복원, 선택지 미구현 |
| TRN-04 | POST | `/api/trainings/{id}/discovery/messages` | 필요 | 여러 답변의 사실 누적·상태 판정·대체 질문 구현; 실제 회원 인증은 미구현 |
| TRN-05 | POST | `/api/trainings/{id}/discovery/images` | 필요 | 미구현; 파일 제한 검증·임시 보관·삭제 구현 필요 |
| TRN-06 | POST | `/api/trainings/{id}/scenario` | 필요 | 별도 API 없음; 기본 유형 고정 |
| TRN-07 | POST | `/api/trainings/{id}/start` | 필요 | 별도 API 없음; finishDiscovery가 beginSimulation 직접 호출 |
| TRN-08 | POST | `/api/trainings/{id}/messages` | 필요 | 새 경로·단계 검사 구현; 기존 AI-04 HTTP 경로·맥락 TODO 유지 |
| TRN-09 | GET | `/api/trainings/{id}/mock-page` | 필요 | 템플릿 코드 컬럼만; 응답 DTO·조회 없음 |
| TRN-10 | POST | `/api/trainings/{id}/actions` | 필요 | InputKind.ACTION·JSON 컬럼만; API·동작 없음 |
| TRN-11 | POST | `/api/trainings/{id}/finish` | 필요 | 자동 종료만 있음; 직접 종료·리포트 생성·API 없음 |
