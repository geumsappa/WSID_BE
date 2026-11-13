# AI-01 상황 파악 개발·검증 안내

파일별 연결과 DB·FastAPI 계약은 [Spring·DB·FastAPI 연결 설계](ai-01-integration.md)를 함께 참고해요.

## 구현 범위

OHC 브랜치에서 Fake AI와 HTTP 어댑터, 사실 누적, 추가 질문 횟수·상태 판정, TRN-04·08, 이어하기 정보를 구현했어요. HTTP 어댑터는 가짜 HTTP 서버를 사용하는 계약 테스트로 검증하며 실제 FastAPI와의 필드·운영 계약은 합의가 필요해요. 로그인은 아직 개발 계정 방식이며 실제 회원 인증 구현을 완료한 것은 아니에요.

## 공개 API

| 메서드·경로 | 동작 |
|---|---|
| POST /api/trainings | DISCOVERY 훈련 생성, 첫 고정 질문 반환 |
| GET /api/trainings/active | 최근 활성 훈련 조회 |
| GET /api/trainings/{id} | 대화 이력·discoveryStatus·questionCount 복원 |
| POST /api/trainings/{id}/discovery/messages | AI-01 상황 파악 대화 |
| POST /api/trainings/{id}/messages | 가상 훈련 메시지 |

기존 `/api/training/sessions` 생성·조회와 `/{id}/turns`는 LegacyTrainingController에만 남겨 두었어요. /turns는 단계에 맞는 같은 서비스로 넘기고 기존 TurnResponse 형식을 반환해요. 새 `/api/trainings/{id}/turns` 경로는 만들지 않았어요. TODO(경로 전환): 프론트 이전 후 LegacyTrainingController와 변환 메서드를 제거해요.

TRN-04 요청은 기존 SubmitTurnRequest와 같아요. requestId(UUID), stateVersion(직전 응답 버전), message(공백 불가·최대 1000자)를 보내요.

```json
{"requestId":"11111111-1111-1111-1111-111111111111","stateVersion":0,"message":"최근 중고거래를 했어요."}
```

TRN-04 응답 예시:

```json
{
  "trainingId": 1,
  "stateVersion": 1,
  "phase": "DISCOVERY",
  "discoveryStatus": "ASKING",
  "questionCount": 1,
  "fallback": false,
  "messages": [{"sender":"AI","text":"물건을 사는 쪽이었나요, 파는 쪽이었나요?","source":"AI"}]
}
```

준비가 끝나면 임시 자동 시작으로 phase가 SIMULATION이 되고, messages는 SYSTEM 안내와 AI 첫 대사 순서의 2개예요. 첫 대사가 가상 훈련 1턴이에요. 다음 답변은 TRN-08로 보내요. 기존 생성·조회·가상 훈련 응답의 식별자 이름은 sessionId이고, 새 DiscoveryResponse는 설계대로 trainingId를 사용해요. 프론트 계약 통일은 별도 작업이에요.

## 횟수·준비 규칙

- 첫 고정 질문은 questionCount에 포함하지 않아요. 사용자 답변은 discovery_turn_count에 저장하고, 훈련 시작에서는 증가시키지 않아요.
- 답변 최소 3개. 1·2번째 답변은 AI가 충분하다고 해도 다음 질문을 해요.
- 3번째부터는 AI 충분 제안과 필수 사실(activity·role)을 모두 충족하면 READY예요.
- 추가 질문 3회는 목표·지표 기준이고 강제 중단 지점은 아니에요. 필요하면 최대 5회까지 질문해요.
- 6번째 답변에서는 AI 성공·실패와 무관하게 끝내요. 필수 사실이 있으면 READY, 없으면 DEFAULT_SCENARIO예요. 새 질문은 보내지 않아요.
- ASKING의 응답 questionCount는 이번 질문을 포함해 답변 수와 같고, READY·DEFAULT_SCENARIO에서는 답변 수 - 1이에요. 초기값은 0이에요.
- 상태는 기존 flow_state_json에 저장하며, 누락 필드 기본값과 기존 JSON 형식을 지원해요. 핵심 장면 상태와 상황 파악 상태를 각각 바꿀 때 다른 상태를 보존해요.

```yaml
wsid:
  training:
    discovery-base-questions: 3
    discovery-max-questions: 5
    discovery-min-answers: 3
    discovery-required-facts: [activity, role]
```

TrainingRules는 1 ≤ 기본 ≤ 최대, 1 ≤ 최소 답변 ≤ 최대 + 1 및 허용된 필수 사실 키를 검사해요. 부적절한 설정은 서버 시작 시 실패해요.

## AI-01 내부 계약과 파일

HttpAiClient.suggestDiscovery는 POST `/internal/ai/discovery/next`에 conversation, knownFacts, currentInput을 보내요. conversation은 현재 입력 이전의 DISCOVERY 대화이며, 사용자 입력은 user, 질문은 assistant로 보내요. X-Internal-Api-Key와 X-Request-Id 헤더를 사용해요. requiredFacts·questionCount 추가 필드는 AI 팀과 합의하기 전에는 보내지 않아요.

```json
{"success":true,"data":{"facts":{"activity":"중고거래"},"nextQuestion":"물건을 사는 쪽이었나요?","canGenerateScenario":false}}
```

성공 봉투·필드 유형·facts 객체·boolean을 검증해요. 질문은 필수이며 공백 불가·200자 이하여야 해요. 빈 본문·시간 초과·HTTP 실패·잘못된 JSON·봉투/필수 필드 위반을 AiUnavailableException으로 바꿔요. 예외에 HTTP 응답 원문을 보관하거나 로그로 출력하지 않아요.

ContextFacts는 activity, role, channel, item, counterpart의 문자열 값만 허용해요. 공백을 제거하고 빈 값·100자 초과·중첩 객체·배열·알 수 없는 키는 버려요. 같은 키는 새 값으로 덮어쓰고 기존 다른 키는 유지해요. 저장 JSON이 손상된 경우 실패하며 빈 사실로 덮어쓰지 않아요. AI가 추출하지 못한 이전 답변도 다음 호출의 대화 이력에 포함돼요. 단, 실제 FastAPI가 이전 이력에서 누락 사실을 추출하는 동작은 함께 검증해야 해요.

주요 파일은 ai/dto의 DiscoveryRequest·DiscoveryTurn·DiscoveryEnvelope·DiscoveryResult, domain의 ContextFacts·FlowState·DiscoveryStatus·DiscoveryInput·DiscoveryOutcome, service의 DiscoveryQuestions·TrainingService, dto의 DiscoveryResponse예요. 기존 AI-04의 HTTP 경로·맥락 전달은 이번 구현 범위에 포함하지 않았어요.

## 저장·실패·동시 요청

사용자 소유권·버전·만료·단계를 확인한 뒤 필요한 사실과 대화를 읽고, 트랜잭션 밖에서 AI를 호출해요. 저장 트랜잭션에서 상태를 다시 확인해 사용자 답변·사실·다음 질문·횟수·활동 시각을 함께 반영해요. 실패하면 전부 롤백돼요. AI를 기다리는 동안 바뀐 버전·만료·종료 상태에는 답변이나 대체 질문도 저장하지 않아요.

AI 실패 시 상한 전에는 사실을 바꾸지 않고 비어 있는 사실에 맞는 검토된 대체 질문을 반환해요. 답변과 질문을 함께 저장하고 fallback=true예요. 마지막 답변에서 실패하면 기존 사실로 종료 판정하고 안내와 첫 대사를 반환해요. 대체 질문은 추가하지 않아요. 문구는 ReviewedTexts에 있고 TODO(content)로 YAML 이전을 남겼어요.

요청 ID 유니크 제약은 DUPLICATE_REQUEST(409), 순번 제약과 상태 버전 충돌은 SESSION_VERSION_CONFLICT(409)로 처리해요. 알려지지 않은 DB 제약 오류는 중복으로 감추지 않아요. 낙관적 잠금 충돌 뒤에는 롤백된 트랜잭션 밖에서 동일 요청의 반영 여부를 확인해요. 중복 응답 재반환은 기존대로 미구현이며 프론트는 409 뒤 상태를 다시 조회해요.

다른 사용자 훈련은 404, 만료는 410, 종료는 409예요. 새 메시지 API에서 단계가 맞지 않으면 WRONG_PHASE(409)예요. DISCOVERY 안에서 이미 준비 완료인 상태는 DISCOVERY_COMPLETED(409)지만, 임시 자동 시작 후에는 SIMULATION이므로 WRONG_PHASE가 먼저 적용돼요.

## 임시 동작과 범위 제한

TODO(TRN-06, TRN-07): READY이면 현재는 고정 중고거래 유형으로 자동 시작해요. 이후 개인화 생성·시작 API로 바꿔야 해요. DEFAULT_SCENARIO도 같은 기본 유형을 사용하지만 안내와 저장된 상태는 구분돼요.

FakeAiClient는 키워드로 사실을 만들고 role이 없으면 부족으로 응답해요. `[AI_FAIL]`을 포함한 입력은 개발용 AI 실패를 재현해요. 실제 LLM의 판단 성능을 검증하는 구현은 아니에요.

OCR·개인화 시나리오·실제 피해 전환·민감정보 감지·호출 제한·세션 로그인은 미구현이에요. 운영 개인정보로 검증하지 말고 합성 입력으로 개발해요. 원문 보관·삭제와 비회원 피해 상담 저장 구조도 별도 작업이에요.

## 실행과 검증

기본 테스트는 DB 없이 돌아가고 db 태그를 제외해요.

```bash
./gradlew test
```

DB 연동은 `WSID_TEST_DB_URL`에 별도 PostgreSQL 테스트 DB를 지정하거나 기본 `jdbc:postgresql://localhost:5432/wsid_ai01_test`를 준비하고 실행해요. 계정은 기존 DB_USERNAME·DB_PASSWORD 설정을 사용해요. 테스트 계정·훈련을 추가하므로 공유·운영 DB를 지정하지 않아요. 현재 V1·V2가 Flyway로 적용되는 구조를 검증하며 팀용 DB 설정 완료를 뜻하지 않아요.

```bash
./gradlew dbTest
```

기본 테스트는 규칙·사실 필터·FlowState 호환성과 보존·Fake AI·HTTP 봉투/실패/시간 초과·웹 요청 검증·이전용 경로·제약 오류 변환을 확인해요. DB 테스트는 실제 PostgreSQL에서 JSONB 왕복, AI 호출 중 트랜잭션 부재, 사실 누적·이어하기·자동 시작·마지막 실패, 다른 소유자, 상태 변경, 중복·동시 요청을 확인해요.

실제 FastAPI의 필드·오류·인증·타임아웃 합의와 통합 검증은 별도로 남아 있어요. 쿠키 기반 회원 인증이 구현되면 실제 로그인과 접근 차단 테스트도 추가해야 해요.
