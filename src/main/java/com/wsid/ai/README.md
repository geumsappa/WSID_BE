# ai — AI 서비스 호출

AI 서비스(FastAPI)를 부르는 유일한 통로예요. 다른 패키지는 `AiClient` 인터페이스만 알아요.

## 명세서 매핑

명세서 비고의 I1~I6은 AI 서비스의 내부 API 번호로 보고 정리했어요. AI repo와 맞춰 확정해요.

| 내부 API | 하는 일 | 쓰는 기능 | 상태 |
|---|---|---|---|
| I1 | 일상 대화: 후속 질문, 사실·위험 요소 추출, 판단 지점 선정, OCR | SIM-001, 002, 004 | AI-01 fake/HTTP 어댑터 구현; 위험 요소·판단 지점·OCR은 후속 작업 |
| AI-02 | POST | `/internal/ai/ocr/extract` | 내부 | 어댑터 구현(`AiClient.extractText`, fake/HTTP multipart); TRN-05 연결·AI 서비스 통합 검증 미완료 |
| I2 | 개인화 시나리오 생성 | SIM-005 | AI-03 fake/HTTP 클라이언트 구현. 실제 FastAPI·훈련 저장 연결은 미완료 |
| I3 | 가상 훈련 한 턴: 다음 대사, 행동 판정, 핵심 장면 제안, 가드레일 | SIM-007, 008, 011 | AI-04 새 클라이언트 구현. 기존 서비스 전환·실제 가드레일·핵심 장면 맥락 연결은 미완료 |
| I4 | 리포트 평가 | SIM-010 | AI-05 fake/HTTP 클라이언트 구현. 실제 FastAPI·리포트 생성/저장 연결은 미완료 |
| I5 | 피해 사실 추출 | SOL-003 | AI-06 fake/HTTP 클라이언트 구현. 실제 FastAPI·상담 사실 누적/저장은 미연결 |
| I6 | 공식 근거 문단 검색 | SOL-004 | 미구현 |
| - | AI 호출 실패 시 대체 흐름 | 비기능 | 구현 (`AiUnavailableException`) |

## 목표 내부 API

I1~I6은 엑셀의 기능 구분이고 AI-01~07은 API ID예요. I1은 AI-01(상황 파악)과 AI-02(OCR), I2~I6은 각각 AI-03~07에 대응해요. 내부 호출은 사용자 로그인 여부와 별개인 서버 간 인증을 사용해요.

AI-01 개발 흐름과 요청·응답 초안은 [상황 파악 다음 질문 개발 문서](../../../../../../docs/ai-01-discovery.md)를 참고해요.

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| AI-01 | POST | `/internal/ai/discovery/next` | 내부 | fake 및 HTTP 어댑터·응답 계약 테스트 구현; 실제 FastAPI 통합 검증은 미완료 |
| AI-02 | POST | `/internal/ai/ocr/extract` | 내부 | 어댑터 구현(`AiClient.extractText`, fake/HTTP multipart); TRN-05 연결·AI 서비스 통합 검증 미완료 |
| AI-03 | POST | `/internal/ai/scenarios/generate` | 내부 | fake/HTTP 클라이언트·응답 타입 검사·요청 ID·전용 타임아웃 구현. 실제 FastAPI·TRN-06 연결은 미완료 |
| AI-04 | POST | `/internal/ai/simulation/reply` | 내부 | replySimulation fake/HTTP 클라이언트·엄격한 응답 검사 구현. 기존 훈련 서비스는 옛 메서드·경로 유지, 실제 FastAPI·서비스 전환 미완료 |
| AI-05 | POST | `/internal/ai/training/evaluate` | 내부 | evaluateTraining fake/HTTP 클라이언트·엄격한 응답 검사·전용 타임아웃 구현. 실제 FastAPI·리포트 서비스 연결은 미완료 |
| AI-06 | POST | `/internal/ai/solutions/extract` | 내부 | extractSolutionFacts fake/HTTP 클라이언트·엄격한 타입/미확정 집합 검사 구현. 실제 FastAPI·상담 서비스·저장·카드 선택은 미연결 |
| AI-07 | POST | `/internal/ai/solutions/explain` | 내부 | 미구현; 문서는 I6 근거 검색으로 설명 |

새 `replySimulation`은 목표 AI-04 경로를 호출해요. 현재 훈련 서비스는 이전 `suggestTrainingTurn`과 `/internal/training/turn`을 계속 사용해요. 실제 AI 서비스 통합과 시나리오 저장·이력 구성·서비스 전환은 후속 작업이에요. 새 DTO에 시나리오·최근 대화를 담을 수 있지만 기존 서비스가 자동으로 이 맥락을 전달하지는 않아요.

## AI-02 OCR 계약

자세한 흐름·FastAPI 제안 구조·남은 작업은 [AI-02 OCR 추출 개발·연결 설계](../../../../../../docs/ai-02-ocr.md)를 참고해요.

훈련 이미지에서 문자를 읽고 민감정보를 검사해요. 외부 사용자가 직접 부르지 않고, 업로드를 받는 TRN-05가 검증을 마친 이미지만 넘겨요.

- 요청: `multipart/form-data`, 파트 이름 `images`(여러 장)
- 응답: `{"success":true,"data":{"recognizedText":"거래 관련 안내","usable":true}}`
- `usable=false`는 민감정보가 걸렸다는 뜻이에요. 그 문자는 저장하거나 다음 AI 요청에 넣지 않아요.
- `{"success":false,"error":{"code":"OCR_FAILED",...}}`는 예외가 아니라 `OcrExtractResult.failed()`로 돌려줘요. 화면은 "이미지를 읽지 못했어요"로 이어가요. 실패 본문 형식은 가정이라 AI repo와 확정해야 해요.
- 그 밖의 오류 코드, 타임아웃, JSON이 아닌 본문, `data` 없음은 `AiUnavailableException`이에요.
- 원본 이미지는 호출 동안 메모리에만 두고 DB·디스크·로그에 남기지 않아요. `OcrImage`·`OcrExtractResult`의 `toString`은 바이트와 인식 문자를 출력하지 않아요.
- `FakeAiClient`는 파일 이름에 `ocr-fail`이 있으면 실패 결과를 돌려줘요.

AI-02는 범용 껍데기 `AiEnvelope<T>`를 써요. AI-01은 필드를 엄격히 검사하는 `DiscoveryEnvelope`를 따로 써요. 새 AI-04는 `JsonNode`로 성공 봉투를 검사한 뒤 data를 변환하고, 기존 훈련 메서드는 여전히 껍데기 없는 본문을 받아요.

## AI-03 시나리오 생성 계약

자세한 코드 흐름·관련 코드·후속 처리·검증 결과는 [AI-03 구현·연결 안내](../../../../../../docs/ai-03-scenario.md)를 참고해요.

- `AiClient.generateScenario(ScenarioGenerateRequest)`를 호출해요. 현재 운영 호출자는 없고 TRN-06에서 연결해야 해요.
- 요청은 `facts`와 `fraudTypeCode`예요. 현재 `SECONDHAND_TRADE`만 지원하고, 사실은 activity 필수·허용 키의 1~100자 문자열만 받아요. 잘못된 값은 버리지 않고 입력 예외로 거부해요.
- `requestId`는 JSON 본문에 넣지 않고 `X-Request-Id` 헤더로 보내요. 내부 키는 기존과 같아요.
- 응답을 `JsonNode`로 받아 boolean success·객체·문자열 타입을 확인한 뒤 `ScenarioGenerateResult`로 변환해요. `AiEnvelope`로 바로 변환하지 않아요.
- 제목은 100자, 설명은 1,000자 이하이고 공백만 있으면 안 돼요. 템플릿 코드는 `[A-Z0-9_]{1,50}` 형식만 검사해요. 실제 등록·유형별 허용 확인은 후속 카탈로그 연결이 필요해요.
- 호출·응답 오류는 원문이나 원인 예외를 보관하지 않고 `AiUnavailableException`으로 바꿔요. 자동 재시도는 하지 않아요.
- AI-03용 `scenarioRestClient`는 `wsid.ai.scenario-read-timeout`을 사용해요. `AI_SCENARIO_READ_TIMEOUT`으로 조정하며 기본은 20초예요. 다른 AI API의 응답 제한은 유지해요.
- fake는 역할별 고정 가상 설명과 예시 코드 `BANK_NOTICE`를 반환해요. 실제 모의 화면이나 검토 완료 콘텐츠를 제공하는 것은 아니에요.
- 기존 training의 `USED_GOODS_TRADE`는 새 요청에서 거부해요. 후속 연결 시 명시적인 코드 매핑 또는 데이터 호환 처리가 필요해요.

## AI-04 역할극 응답 계약

자세한 처리 흐름과 파일별 후속 작업은 [AI-04 구현 보고서](../../../../../../docs/ai-04-simulation.md)를 참고해요.

- 새 메서드는 `AiClient.replySimulation(SimulationReplyRequest)`예요. 기존 `suggestTrainingTurn`은 훈련 서비스 전환 전까지 유지해요.
- 요청은 시나리오 title·description, 이전 이력 messages, 현재 userInput, 현재 turnCount예요. 요청 ID는 JSON에서 제외하고 `X-Request-Id`로 보내요.
- 이력은 user/assistant 최대 20개·각 1,000자, 현재 입력도 1,000자 이하예요. 이력 선택·긴 과거 대사 제외는 후속 서비스의 책임이에요.
- 응답의 success·shouldFinish는 실제 boolean, reply는 문자열, recognizedActions는 문자열 배열이어야 해요. `JsonNode`에서 타입을 확인한 뒤 DTO로 변환해요.
- 대사는 1,000자 이하, 행동은 최대 10개·각 `[A-Z0-9_]{1,100}`이에요. 모르는 코드·중복은 유지하고 training에서 의미를 매핑해야 해요.
- `mockPage`는 필수 필드이며 현재 명시적 null만 지원해요. FastAPI에서도 null 필드를 생략하면 안 돼요.
- `shouldFinish`는 제안만 반환하며 서버 종료·DB 저장은 수행하지 않아요. 가드레일 통과나 실제 이벤트 수행을 뜻하지 않아요.
- 기존 훈련용 restClient와 readTimeout을 사용해요. AI-03 전용 제한과 분리돼요.
- fake는 공식·확인 키워드, `[AI_FAIL]`, `[AI_FINISH]`로 개발 흐름을 재현해요. LLM 품질 검증이나 운영 안전성 보장은 아니에요.

## AI-05 평가 계약과 FastAPI 개발 시 참고

Spring 1차 클라이언트만 구현했고 실제 리포트 생성·저장은 아직 연결하지 않았어요. 자세한 파일별 책임은 [AI-05 구현 보고서](../../../../../../docs/ai-05-evaluation.md)의 6·7장, FastAPI 개발 디렉토리와 응답 계약은 9장을 참고해요.

- FastAPI의 `schemas/evaluation.py`는 `TrainingEvaluateRequest`·`SimulationMessage`·`EvaluationAction`·`TrainingEvaluateResult`와 대응해요. 경로는 별도 AI 저장소에 적용할 제안이에요.
- `strengths`·`improvements`·`recommendations`는 문자열 배열, `turnFeedback`은 현재 빈 배열만 지원해요.
- success와 모의 페이지 결과 3개는 실제 boolean이어야 해요. false나 빈 배열이어도 필수 필드를 생략하면 안 돼요.
- `routers/training.py`는 HttpAiClient가 보내는 내부 키·X-Request-Id 헤더와 성공 봉투를 맞춰야 해요. 요청 ID는 JSON 본문에 없어요.
- 응답 구조가 바뀌면 결과 DTO만 바꾸지 말고 HttpAiClient의 JsonNode 검사와 계약 테스트도 함께 바꿔요. 원문 복사·행동 근거 검증은 FastAPI와 후속 리포트 검증 계층에서 수행해야 해요.
- 503 오류와 HTTP 200의 success=false는 현재 모두 사용 불가로 처리해요. 성공 응답의 필드 타입·필수 여부와 오류 상태 선택을 구분해 합의해요.

## AI-06 피해 사실 추출 계약과 FastAPI 개발 시 참고

Spring 클라이언트만 구현했고 실제 상담 API·사실 누적·카드 선택은 아직 연결하지 않았어요. 파일별 상호작용은 [AI-06 구현 보고서](../../../../../../docs/ai-06-solution-extract.md)의 7·8장, FastAPI 개발 디렉토리별 계약은 9장을 참고해요.

- `AiClient.extractSolutionFacts(SolutionExtractRequest)`에 설명·현재 사실 Map·UUID를 전달해요. 설명은 1,000자 이하, knownFacts는 빈/부분 Map을 허용해요. DTO가 개인정보를 자동으로 가리지 않으므로 호출 전에 필요한 상황만 남겨야 해요.
- 사실 키는 transferredMoney·contactContinues·sharedInformation·installedApp, 값은 YES·NO·UNKNOWN 문자열만 받아요. 응답은 4개 필드 전체와 uncertainFields를 필수로 반환해요.
- 실제 boolean success와 실제 문자열 사실 값을 JsonNode로 확인한 뒤 변환해요. uncertainFields는 중복 없이 UNKNOWN인 키와 정확히 같아야 하며, 전부 확정되어도 빈 배열을 생략하면 안 돼요.
- requestId는 JSON에서 제외하고 X-Request-Id 헤더로 보내요. 기존 공통 restClient/readTimeout을 사용하며 자동 재시도는 하지 않아요.
- 결과는 제안이에요. 사용자 확정값 보호·충돌 확인·실패 시 직접 선택·대응 카드 결정·DB 저장은 후속 solution 서비스에서 처리해요. 원문·추출 근거는 저장하거나 로그에 남기지 않아요.
- FastAPI의 제안 경로 `schemas/solution_extract.py`·`routers/solutions.py`·`services/solution_extractor.py`에 계약과 추정 금지 규칙을 적용해야 해요. Python 코드는 이 저장소에 없어요. 형식 검증만으로 실제 LLM의 추정 방지를 보장할 수는 없어요.

## 파일

| 파일 | 역할 |
|---|---|
| `AiClient` | 인터페이스 |
| `FakeAiClient` | 가짜 구현. `wsid.ai.mode=fake`(기본값)일 때 사용 |
| `HttpAiClient` | 실제 호출. 연결 2초·응답 기본 20초. AI-03은 별도 클라이언트와 응답 제한 사용 |
| `AiClientConfig`, `AiProperties` | 설정으로 구현 선택 |
| `dto/` | AI 서비스와 주고받는 형식. AI repo와의 계약이에요 (`AiEnvelope`: 공통 응답 껍데기, `OcrImage`·`OcrExtractResult`: AI-02) |

## 내부 API를 하나 추가하는 방법

1. `dto/`에 요청·응답 record를 만들어요. 응답의 타입·필수 값과 실패 정책을 정해요. AI-03처럼 필수 계약이 깨지면 사용 불가로 처리하고, 기본 콘텐츠 선택은 호출 서비스에 맡겨요.
2. `AiClient`에 메서드를 추가해요.
3. `FakeAiClient`에 항상 같은 답을 돌려주는 구현을 넣어요. 이걸로 화면과 서비스 개발을 먼저 진행해요.
4. `HttpAiClient`에 실제 호출을 넣고, 실패는 `AiUnavailableException`으로 바꿔 던져요.
5. 호출하는 service에서 예외를 잡아 대체 흐름으로 이어가요.

## 규칙

- **AI는 제안만 해요.** 다음 단계, 종료, 긴급 안내, 기관명·링크는 AI 응답으로 정하지 않아요.
- **AI 호출은 트랜잭션 밖에서 해요.**
- 목표 계약에서 AI 서비스는 상태를 저장하지 않아요. 필요한 맥락은 요청마다 보내도록 구현해야 해요. 현재 맥락 DTO는 미완성이에요.
- 요청·응답 본문은 로그에 남기지 않아요.

## 정할 것

- 필드 이름 표기(camelCase 또는 snake_case)를 AI repo와 맞춰요. 지금 dto는 camelCase예요.
- 외부 LLM 공급자의 요청 한도에 걸렸을 때 재시도를 어느 쪽에서 할지
