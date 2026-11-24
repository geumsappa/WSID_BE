# solution — 실제 피해 대응

사용자가 '사기가 의심돼요 / 피해를 당했어요'를 누른 뒤의 흐름을 맡아요.
틀리면 실제 피해로 이어지는 부분이라, **카드 선택과 긴급 여부는 코드 규칙으로만 정해요.**

## 명세서 매핑

| 명세서 항목 | ID | 우선순위 | 상태 | 관련 코드 |
|---|---|---|---|---|
| 피해 설명 또는 선택으로 시작 | SOL-003 | 상 | SOL-01 구현 | `StartSolutionService`, `SolutionController` |
| 설명에서 확인 항목 추출 | SOL-003 · I5 | 상 | SOL-01·03에 연결 | `StartSolutionService`, `SolutionFacts` |
| 필요한 항목 추가 질문 | SOL-003 | 상 | SOL-03 후속 질문·UNKNOWN 선택 건너뛰기 구현 | `SolutionQuestion` |
| 파악된 내용 수정 | SOL-003 | 상 | 미구현 | - |
| 분석 실패 시 직접 선택 | SOL-003 | 상 | 시작 시 대체·SOL-03 직접 선택 구현 | `StartSolutionService`, `InputKind.OPTION` |
| 긴급 안내 우선 표시 | SOL-007 | 상 | 규칙·정렬 구현, guides.yml 카드 연결 | `ResponseRules` |
| 필요한 대응 카드 제공 | SOL-004 | 상 | GuideCatalog 공개 카드 2장 연결 | `ResponseRules`, `ResponseCardCatalog` |
| 확인한 사실과 카드 다시 보기 | SOL-004 | 중 | SOL-02 구현 | `SolutionQueryService` |
| 공식 근거 문단 검색·설명 | SOL-004 · I6 | 중 | AI-07 설명 클라이언트만 구현; 검색·상담 미연결 | `AiClient.explainSolution`, `reference_codes_json` 컬럼 |
| 피해 설명의 민감정보 처리 | 비기능 · I5 | 상 | 초안 거부 규칙 구현 | `SensitiveDescriptionGuard` |

## 이용 정책과 목표 API

피해 대응 AI 상담은 비회원도 이용할 수 있어요. 아래 SOL API의 로그인 요구는 최신 사용자 결정에 따라 불필요로 정정했어요. 비회원 상담도 복원·변경·종료 시 해당 상담의 소유권을 확인해야 해요.

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| SOL-01 | POST | `/api/solutions/sessions` | 불필요 (비회원 상담 소유권 확인 필요) | 구현([보고서](../../../../../../docs/sol-01-session-start.md)) |
| SOL-02 | GET | `/api/solutions/sessions/{id}` | 불필요 (비회원 상담 소유권 확인 필요) | 구현([보고서](../../../../../../docs/sol-02-session-get.md)) |
| SOL-03 | POST | `/api/solutions/sessions/{id}/messages` | 불필요 (비회원 상담 소유권 확인 필요) | 구현([보고서](../../../../../../docs/sol-03-session-message.md)) |
| SOL-04 | PATCH | `/api/solutions/sessions/{id}/facts` | 불필요 (비회원 상담 소유권 확인 필요) | 구현([보고서](../../../../../../docs/sol-04-facts-update.md)) |
| SOL-05 | POST | `/api/solutions/sessions/{id}/complete` | 불필요 (비회원 상담 소유권 확인 필요) | 구현([보고서](../../../../../../docs/sol-05-session-complete.md)) |
| SOL-06 | POST | `/api/solutions/sessions/{id}/images` | 불필요 (비회원 상담 소유권 확인 필요) | 구현, 초안 ID([설계](../../../../../../docs/sol-06-session-images.md)) |

소유자는 `SolutionOwnerResolver`가 정해요. 회원은 로그인 principal, 비회원은 HTTP 세션에 발급한 익명 UUID(`anonymous_owner_id`, V3)예요. 비회원은 `GET /api/solutions/csrf`로 세션·토큰을 받은 뒤 같은 쿠키와 `X-CSRF-TOKEN`으로 요청해요. 보안 규칙은 AUTH의 `SecurityConfig`가 아니라 `security/SolutionSecurityConfig`(`/api/solutions/**` 전용 체인)에 있어요. 조회는 `SolutionOwnerResolver.findExisting`(새 세션·UUID를 만들지 않음)과 `SolutionSessionRepository.findOwned`로만 하고, 상담 ID만 받아 타인 상담을 조회하게 만들지 않아요.

## 테이블

- `solution_sessions`: 확인한 사실, 적용된 카드, 다음 질문 → `domain/SolutionSession`
- `solution_messages`: 대화 한 줄. `safe_content`에는 고정 접수 문구·선택 표현만 저장(일반 원문도 저장하지 않음) → `domain/SolutionMessage`

## 개발 순서

training 패키지와 같은 모양으로 만들어요.

1. **domain 규칙부터**: 사실을 값 객체로 만들어요. 항목은 송금, 정보 제공, 앱 설치, 연락 지속이고 값은 예·아니오·모름이에요.
   현재 `SolutionFacts.withChoice/mergeExtracted`와 `ResponseRules`가 선택·AI 병합·카드 규칙을 맡아요. SOL-04 직접 정정은 `SolutionFacts.withChanges`로 기존 확정값도 바꿔요(AI 병합 보호와 별개).
2. **단위 테스트**: "보냈다", "보내려다 멈췄다", "송금을 언급하지 않았다"가 구분되는지, 긴급 카드가 맨 앞에 오는지, 모름일 때 더 안전한 쪽 안내가 나오는지 검증해요.
3. **AI 연결**: 구현된 `AiClient.extractSolutionFacts`에 새 설명과 현재 사실 Map을 전달해요. 개인정보 최소 처리 후 호출하고 실패하면 기존 사실을 유지하며 직접 선택하게 해요. 선택으로 확인한 사실도 같은 도메인 규칙에 넣어요. AI의 UNKNOWN이나 충돌하는 제안으로 사용자 확정값을 조용히 덮어쓰지 않아요.
4. **service**: 상담 소유자 확인 → 읽기 → AI 호출(트랜잭션 밖) → 사실 합치기 → 규칙 적용 → 저장. 시작 요청에 설명이 있으면 바로 AI-06으로 사실을 추출해요. `content`에서 카드 내용을 가져와 응답을 만들어요.
5. **controller**: SOL-01~05의 시작·복원·메시지·사실 수정·완료를 구현해요. 비회원 소유권을 유지하며, 완료 상태와 완료 후 허용 요청·응답은 계약으로 정의해요.
6. **근거 검색·설명(I6)**: 승인 근거 검색·선별은 아직 구현하지 않았어요. 긴급 카드가 없고 승인된 설명용 문단이 있을 때만 구현된 `AiClient.explainSolution`에 사실 Map과 문단을 전달해요. 긴급 카드는 검색을 기다리지 않아요. 근거가 없거나 사실이 전부 UNKNOWN이면 질문·검토된 안내를 우선하고 설명 생성을 생략해요.

## 규칙

AI-06 요청·응답 DTO, 현재 파일별 책임, 상담 서비스 연결 기준은 [AI-06 구현 보고서](../../../../../../docs/ai-06-solution-extract.md)의 3~8장을 참고해요. 별도 FastAPI 저장소에서 구현할 스키마·라우터·추출 서비스·검증 테스트는 같은 문서 9장에 정리했어요. DTO와 HTTP 클라이언트는 DB에 접근하지 않으며, `StartSolutionService`와 `SendSolutionMessageService`가 AI-06을 호출하고 Spring 규칙으로 저장해요.

AI-07은 [구현 보고서](../../../../../../docs/ai-07-solution-explain.md)의 8~10장을 참고해요. 연락처·URL 없는 승인 설명용 문단을 선택하고, 호출 전후 상담/근거 버전·소유권·완료 상태를 확인해야 해요. `referenceCodesJson`은 현재 필드만 있으므로 저장·조회·코드/버전 검증을 추가해야 해요. 실패 시 사실·카드는 유지하고 설명만 생략해요. 실제 근거 승인·기관명 판별·의미 검증은 HTTP 클라이언트가 대신하지 않아요.

- 기관명, 연락처, 링크는 `content`의 검토된 카드에서만 가져와요. AI가 만든 값은 쓰지 않아요.
- 피해 설명 원문과 추출 근거는 DB와 로그에 남기지 않아요.
- 확인하지 못한 항목은 '모름'으로 유지해요. 추측해서 '아니오'로 바꾸지 않아요.
- 지난 질문의 선택지는 다시 적용하지 않아요. `next_question_json`의 factKey와 요청 factKey를 대조하고, 버전이 오래되면 최신 상태를 다시 불러오게 해요.

## 정할 것

- 실제 운영 카드 카탈로그·질문 문구 검토, UNKNOWN 정정을 "다시 질문"으로 바꿀지(현재는 SOL-04 정정 기록도 답변 완료로 읽음)
- 훈련 중 실제 피해를 밝혔을 때 어느 훈련에서 넘어왔는지 연결할 컬럼
- 근거 문단의 화면 표시 여부와 공개 API 필드

## SOL-03 변경·후속 개발 기준

[구현 보고서](../../../../../../docs/sol-03-session-message.md)의 5·7·8장을 먼저 읽어요. SOL-03은 READ COMMITTED의 조건부 UPDATE로 버전을 한 번 올린 뒤 같은 트랜잭션에서 메시지를 저장해요. `SolutionSessionUpdates`와 JPA 엔티티 dirty checking을 섞지 않아요. SOL-04·05도 소유권·ACTIVE·버전 경쟁 규칙을 지켜야 해요. 선택 factKey는 V4의 메시지 컬럼에 저장하며 원문이나 선택 키를 reference_codes_json에 넣지 않아요.

TEXT의 knownFacts에는 YES/NO만 보내고 기존 YES/NO는 AI로 바꾸지 않아요. 같은 requestId가 이미 반영됐으면 409 DUPLICATE_REQUEST 후 SOL-02로 복원해요. AI 실패는 503이며 상태를 초기화하지 않아요. UNKNOWN 선택은 다음 질문으로 넘어가고, 그대로 유지된 질문은 SYSTEM 메시지로 반복 저장하지 않아요. 운영 카탈로그는 아직 비어 있어 필요한 카드가 없으면 503이에요.

## SOL-05 종료·후속 개발 기준

[구현 보고서](../../../../../../docs/sol-05-session-complete.md)에 파일별 역할과 프론트·명세 전달 내용을 정리했어요. `CompleteSolutionService`는 기존 소유자를 확인하고 `completeActive`로 최신 ACTIVE 상태만 한 번 종료해요. 본문 없음 또는 빈 JSON 객체를 받으며 stateVersion을 요구하지 않아요. 반복 종료는 시각·버전 변경 없이 200이에요.

최초 종료는 completed_at·updated_at·state_version+1·next_question_json=NULL을 한 SQL로 저장해요. 사실·카드·메시지·HTTP 세션은 그대로예요. SOL-03·04 수정이 먼저 커밋되면 그 상태까지 포함해 종료하고, 종료가 먼저면 뒤늦은 수정은 SOLUTION_FINISHED예요. 기대 버전 기반 사실 CAS와 버전 입력 없는 종료를 혼동하지 않아요.

완료 상담의 SOL-02 응답은 nextQuestion=null이에요. 종료 시 AI/카드 조회·리포트 생성·로그아웃·데이터 삭제는 하지 않아요. 재개 기능이나 보관/삭제 정책은 별도로 설계해야 해요.
