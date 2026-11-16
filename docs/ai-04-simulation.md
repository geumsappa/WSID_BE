# AI-04 구현 보고서와 연동 개발 안내

작성일: 2026-11-16 · 상태: Spring 1차 클라이언트 구현 완료

명세 출처는 **AI-04 내부 API 명세 초안(2026-11-16 제공본)**이다. 구현 범위는 [AI-04 구현 계획서](ai-04-simulation-plan.md)의 1차이며, 실제 FastAPI의 스키마·운영 오류 정책·LLM 품질 검증은 별도로 필요하다.

## 1. 구현 결과와 적용 범위

`POST /internal/ai/simulation/reply`에 시나리오·이전 역할극 대화·현재 입력·현재 턴 수를 보내고, 다음 대사와 행동 분석·종료 제안을 받는 Spring 클라이언트를 구현했다.

| 항목 | 결과 |
|---|---|
| 새 호출 메서드 | `AiClient.replySimulation(SimulationReplyRequest)` |
| 요청 DTO | 시나리오·이력·입력·턴 수·요청 ID의 검증과 이력 방어적 복사 |
| 응답 DTO | 대사·행동 코드 배열·종료 제안·null 전용 mockPage |
| fake | 결정적인 가상 대사, 공식 채널 키워드 분류, 개발용 실패·종료 제안 표시 |
| HTTP | 새 경로·내부 키·요청 ID·JSON 전송, 원본 JSON 타입 검사 |
| 실패 | `AiUnavailableException`으로 변환, 원문·원인 예외 비보관, 자동 재시도 없음 |
| 시간 제한 | 기존 훈련용 `restClient` 사용. AI-03 생성용 제한과 분리 |
| 테스트 | 신규 83개, 기존 포함 192개 통과 |
| 기존 훈련 | 기존 메서드·경로·서비스 흐름 유지 |

현재 운영 코드의 `TrainingService`는 계속 `suggestTrainingTurn()`과 옛 `/internal/training/turn` 경로를 사용한다. 새 `replySimulation()`의 호출자는 이번에 추가한 테스트다. 새 클라이언트만 추가한 상태이므로 실제 훈련이 새 명세로 전환됐거나 AI-03 시나리오를 DB에서 읽어 사용한다고 보지 않는다.

FastAPI 서버·LLM·새 행동 매핑·복수 행동 저장·TRN-08 전환·모의 페이지 연결은 이번 구현에 포함하지 않았다. DB 엔티티·마이그레이션·공개 API 응답도 변경하지 않았다.

## 2. 변경한 파일과 변경 이유

| 파일 | 내용 | 설계 이유 |
|---|---|---|
| [SimulationMessage](../src/main/java/com/wsid/ai/dto/SimulationMessage.java) | 이전 메시지의 role·content 검증 | 훈련 엔티티 대신 API 계약에 필요한 값만 전달 |
| [SimulationReplyRequest](../src/main/java/com/wsid/ai/dto/SimulationReplyRequest.java) | 시나리오·이력·현재 입력·턴 수·ID | 요청 전 입력 오류를 발견하고 불변 이력 보관 |
| [SimulationReplyResult](../src/main/java/com/wsid/ai/dto/SimulationReplyResult.java) | reply·recognizedActions·shouldFinish·mockPage | 수신 형식과 훈련의 의미 판단을 구분 |
| [AiClient](../src/main/java/com/wsid/ai/AiClient.java) | 새 메서드 추가 | 훈련 서비스가 HTTP 구현에 직접 의존하지 않도록 함 |
| [FakeAiClient](../src/main/java/com/wsid/ai/FakeAiClient.java) | 새 fake 구현 | FastAPI가 없어도 계약과 호출 코드를 개발 가능 |
| [HttpAiClient](../src/main/java/com/wsid/ai/HttpAiClient.java) | 새 경로와 엄격한 응답 검사 | 잘못된 타입의 자동 변환과 불완전한 응답 채택 방지 |

`AiProperties`·`AiClientConfig`·`application.yml`은 기존 구성을 재사용한다. 새 타임아웃 설정이나 별도 라이브러리를 추가하지 않았다.

### 실제 호출 구조

```text
호출자 → SimulationReplyRequest 생성
       → AiClient.replySimulation(request)
          ├─ fake: FakeAiClient가 개발용 결과 생성
          └─ http: HttpAiClient의 restClient
                    → FastAPI 목표 경로
                    → JsonNode 타입 검사
                    → SimulationReplyResult 생성자 검증
       ← 결과 DTO 또는 예외
```

DTO를 받는 것은 Java 메모리에서 결과를 다룰 수 있게 된다는 의미다. DTO가 DB에 접근하거나 훈련 상태를 자동 변경하지 않는다. 저장·종료·대체 흐름은 후속 호출 서비스의 책임이다.

## 3. 요청은 어떻게 구성하고 검증하나

```http
POST /internal/ai/simulation/reply
Content-Type: application/json
X-Internal-Api-Key: <AI_API_KEY>
X-Request-Id: 11111111-1111-1111-1111-111111111111
```

```json
{
  "scenario": {
    "title": "중고거래 안전 확인 훈련",
    "description": "가상 거래 상황"
  },
  "messages": [
    {"role": "assistant", "content": "거래 안내를 확인하셨나요?"},
    {"role": "user", "content": "확인 방법을 알고 싶어요."},
    {"role": "assistant", "content": "어떤 방법으로 확인하시겠어요?"}
  ],
  "userInput": "공식 채널을 확인하겠습니다.",
  "turnCount": 2
}
```

| 값 | 실제 구현 규칙 |
|---|---|
| `scenario` | null 불가. AI-03의 `ScenarioGenerateResult.Scenario` 값 타입 재사용 |
| 시나리오 제목·설명 | 기존 생성자 규칙 재사용. 공백 불가, 제목 100자·설명 1,000자 이하 |
| `messages` | null 불가, 빈 목록 허용, 최대 20개, null 요소 거부 |
| `role` | `user`, `assistant`만 허용. 대소문자·앞뒤 공백도 정확히 일치해야 함 |
| `content` | null·공백 거부, 최대 1,000자 |
| `userInput` | null·공백 거부, 최대 1,000자 |
| `turnCount` | 1 이상. 이력 개수와 같은지 검사하거나 이력에서 재계산하지 않음 |
| `requestId` | UUID 필수. `@JsonIgnore`로 JSON에서 제외, HTTP 헤더에만 사용 |

문자열은 Java의 `String.length()`로 검사하고 내용을 자르거나 `strip()`으로 바꾸지 않는다. 공백만 있는 문자열은 거부하지만 유효한 문장 앞뒤 공백은 그대로 유지한다. AI-03의 사실 Map 정규화와는 다른 동작이다.

이력은 `List.copyOf()`로 복사한다. 메시지와 시나리오도 불변 record 값이어서 호출자가 원본 목록을 바꿔도 요청 내용이 바뀌지 않는다. DTO는 training 패키지를 import하지 않는다.

설명용 호출 예시는 다음과 같다. 아래 requestId는 현재 메시지 요청에서 받은 UUID다.

```text
var request = new SimulationReplyRequest(
        storedScenario,
        selectedHistory,
        currentUserInput,
        session.getSimulationTurnCount(),
        requestId);
SimulationReplyResult result = aiClient.replySimulation(request);
```

이 코드는 후속 서비스 연결 예시다. 현재 `TrainingService`에 추가되어 있지 않으며, 저장 시나리오 조회·이력 선택·사용 가능성 검사가 선행되어야 한다.

### DTO가 하지 않는 일

DTO는 이력을 DB에서 조회·20개로 잘라내거나 과거 긴 대사를 제외하지 않는다. SYSTEM 안내 제거, 시간순 정렬, 현재 userInput의 중복 전송 방지도 호출자의 역할이다. 같은 문장이 과거에도 있을 수 있으므로 단순 문자열 비교로 현재 입력과 과거 메시지가 같다고 거부하지 않는다.

예시처럼 전체 이력이 있으면 현재 AI 대사 수와 turnCount가 대응한다. 최근 이력만 선택하거나 긴 대사를 제외하면 배열의 assistant 개수는 더 적을 수 있다. 이 경우에도 DB의 `simulationTurnCount`를 전송한다. DTO에 최대 턴 15를 고정하지 않고 훈련 규칙에서 상한을 적용한다.

## 4. 응답 처리와 타입 검사 순서

```json
{
  "success": true,
  "data": {
    "reply": "공식 고객센터로 확인해 보세요.",
    "recognizedActions": ["OFFICIAL_CHANNEL_CHECK"],
    "shouldFinish": false,
    "mockPage": null
  }
}
```

`HttpAiClient.replySimulation()`은 다음 순서로 처리한다.

1. null 요청은 로컬 입력 예외로 거부한다.
2. 훈련용 `restClient`로 새 경로에 POST한다.
3. 응답을 `JsonNode`로 받는다.
4. 최상위 객체, 실제 boolean `success=true`, 객체 `data`를 확인한다.
5. 문자열 reply, 배열 recognizedActions, 실제 boolean shouldFinish를 확인한다.
6. mockPage 필드가 존재하고 값이 명시적 null인지 확인한다.
7. 행동 배열의 각 요소가 실제 문자열인지 확인한다.
8. `treeToValue(data, SimulationReplyResult.class)`로 변환한다.
9. 결과 생성자가 대사 길이·공백·행동 코드 형식·개수를 검사하고 목록을 복사한다.

AI-03처럼 **원본 JSON을 먼저 검사**한다. `AiEnvelope`나 boolean 기본형 DTO로 바로 변환하지 않으므로 문자열 `"true"`·`"false"`나 숫자가 자동 변환되어 통과하지 않는다. `AiEnvelope`를 쓰는 기존 AI-02 동작까지 바꾼 것은 아니다.

| 응답 항목 | 실제 규칙 |
|---|---|
| `reply` | 공백 불가, 최대 1,000자 |
| `recognizedActions` | 빈 배열 허용, 최대 10개 |
| 행동 코드 | 각 문자열이 `[A-Z0-9_]{1,100}` 형식 |
| 코드의 의미 | 1차에서 매핑하지 않음. 모르는 코드와 중복도 순서대로 유지 |
| `shouldFinish` | boolean 값을 결과로 보관. 훈련 종료 실행 없음 |
| `mockPage` | Java에서는 `Void`, 현재 null만 표현 |
| 추가 응답 필드 | 사용하지 않고 무시 |

길이 초과나 잘못된 코드를 조용히 자르거나 지우지 않는다. 행동 코드의 **형식 검사**와 **의미 매핑**을 구분한다. 예를 들어 `SOMETHING_NEW`는 형식이 맞아 결과에 남지만, 그 코드가 실제 행동으로 인정되는 것은 아니다.

`Void mockPage`를 사용해 현재 지원 범위를 명시했다. null 아닌 객체·문자열·배열·boolean은 DTO 변환 전에 거부한다. 이후 모의 페이지를 지원할 때는 별도 DTO와 계약으로 이 필드를 확장해야 한다. 임의 Map을 받아 URL·HTML을 그대로 보여주는 방식으로 연결하지 않는다.

## 5. 실패·로그·개발용 fake

| 상황 | 처리 |
|---|---|
| 요청 DTO의 잘못된 값 | `IllegalArgumentException`. 네트워크 요청 전 실패 |
| `replySimulation(null)` | `IllegalArgumentException` |
| HTTP 4xx·5xx | `AiUnavailableException` |
| HTTP 200의 success=false | `AiUnavailableException` |
| 빈 본문·HTML·잘못된 JSON·타입 | `AiUnavailableException` |
| 필수 필드 누락·길이/형식 초과 | `AiUnavailableException` |
| mockPage 누락 또는 null 아닌 값 | `AiUnavailableException` |
| 네트워크 오류·응답 제한 초과 | `AiUnavailableException` |

HTTP/JSON 예외는 본문을 포함할 수 있어 원인 예외를 붙이지 않는다. 오류 응답의 message나 모델 대사를 예외 메시지로 복사하지 않는다. 새 메서드에는 본문 로깅을 추가하지 않았다.

`SimulationMessage.toString()`은 역할과 길이만, 요청 DTO는 메시지 수·입력 길이·턴·요청 ID만 출력한다. 결과 DTO는 대사 길이·행동 개수·종료 제안만 출력한다. 원문이나 미확정 행동 코드 값도 `toString()`에 남기지 않는다. JSON 본문을 별도로 로깅하면 이 보호를 우회하므로 이후 호출자도 원문 로깅을 피해야 한다.

### FakeAiClient.replySimulation

- 현재 입력에 `공식`과 `확인`이 모두 있으면 `OFFICIAL_CHANNEL_CHECK`를 반환한다. 의미 이해나 부정문 판정까지 수행하는 분석은 아니다.
- 그 외 입력에서는 행동 배열이 비어 있다.
- 대사는 준비된 고정 문장을 사용하고 사용자 입력·시나리오 원문을 그대로 넣지 않는다.
- `[AI_FAIL]`은 사용 불가 예외를 발생시킨다.
- `[AI_FINISH]`는 종료 **제안**을 true로 만든다. 서버 종료를 실행하지 않는다.
- 모의 페이지는 항상 null이다.

위 표시와 키워드는 개발용이다. FastAPI가 동일한 표시를 구현하거나 운영용 판단에 사용하는 계약이 아니다. fake 테스트는 LLM의 페르소나·안전 가드레일 품질을 증명하지 않는다.

## 6. 현재 직접 상호작용하는 파일

| 파일 | 상호작용 | 이후 개발 시 참고할 점 |
|---|---|---|
| [AiClient](../src/main/java/com/wsid/ai/AiClient.java) | 호출자와 구현 사이의 인터페이스 | 새 메서드는 replySimulation. 기존 suggestTrainingTurn은 서비스 전환 전 제거 금지 |
| [AiClientConfig](../src/main/java/com/wsid/ai/AiClientConfig.java) | mode에 따라 fake/HTTP 객체 제공 | 새 빈이나 컨트롤러가 필요하지 않음. 현재 Spring 설정의 동일한 AiClient를 사용 |
| [AiProperties](../src/main/java/com/wsid/ai/AiProperties.java)·[application.yml](../src/main/resources/application.yml) | base URL·내부 키·시간 제한 제공 | AI-04는 readTimeout, AI-03은 scenarioReadTimeout. 두 제한을 혼동하지 않음 |
| [HttpAiClient](../src/main/java/com/wsid/ai/HttpAiClient.java) | JSON 전송·검사·변환 | 새 응답의 모든 타입을 JsonNode 단계에서 확인. 커스텀 RestClient.Builder는 전송 설정도 호출자가 책임짐 |
| [ScenarioGenerateResult.Scenario](../src/main/java/com/wsid/ai/dto/ScenarioGenerateResult.java) | AI-03과 AI-04의 최소 시나리오 값 공유 | 제목·설명 한도 변경은 두 API에 영향. 페르소나 맥락 확장 시 생성·저장·요청을 함께 바꿔야 함 |
| [SimulationMessage](../src/main/java/com/wsid/ai/dto/SimulationMessage.java) | 요청 목록의 요소 | MAX_CONTENT_LENGTH는 1,000. 후속 이력 구성에서도 같은 기준 사용 |
| [SimulationReplyRequest](../src/main/java/com/wsid/ai/dto/SimulationReplyRequest.java) | 입력 경계 | MAX_MESSAGES는 20. 목록 선택은 DTO가 아니라 서비스에서 수행 |
| [SimulationReplyResult](../src/main/java/com/wsid/ai/dto/SimulationReplyResult.java) | 형태가 검증된 결과 | 미확정 코드·중복을 유지. 종료·가드레일 판단이 끝났다는 표시는 아님 |
| [AiUnavailableException](../src/main/java/com/wsid/ai/AiUnavailableException.java) | 새 호출의 실패 통로 | 호출 서비스가 잡아 검토된 대체 흐름으로 전환. cause 없는 예외라는 정책 유지 |
| [TrainingTurnRequest](../src/main/java/com/wsid/ai/dto/TrainingTurnRequest.java)·[TrainingTurnSuggestion](../src/main/java/com/wsid/ai/dto/TrainingTurnSuggestion.java) | 옛 서비스 호출에서 계속 사용 | 새 DTO와 의미가 달라 자동 대체 금지. 특히 guardrailPassed=true를 임의로 채워 넣지 않음 |

설정은 기존 연결 제한 2초·훈련 응답 제한 20초를 사용한다. AI-03 전용 응답 제한을 늘려도 새 AI-04는 그 영향을 받지 않는다. 실제 HTTP 테스트에서 AI-03 제한이 더 짧은 경우와 더 긴 경우를 모두 확인했다. 전체 사용자 턴 25초는 기존 제안값이며, 이번에 별도의 전체 시간 예산을 구현한 것은 아니다.

## 7. 훈련·DB 연결 시 파일별 작업

아래는 **후속 3차 구현 지침**이다. 이번 구현에서 해당 서비스·엔티티를 수정하지 않았다.

| 파일 | 현재 상태 | 후속 작업·주의점 |
|---|---|---|
| [TrainingController](../src/main/java/com/wsid/training/controller/TrainingController.java) | TRN-08 메시지를 기존 서비스로 전달 | requestId·stateVersion·소유권 정책 유지. 새 내부 API 경로를 공개 컨트롤러로 복제하지 않음 |
| [TrainingService](../src/main/java/com/wsid/training/service/TrainingService.java) | askAi가 옛 메서드를 호출 | 저장 시나리오·이력 구성 후 replySimulation 호출. 변환·실패 처리·상태 저장을 함께 전환 |
| [TrainingSession](../src/main/java/com/wsid/training/domain/TrainingSession.java) | 단일 행동으로 턴·종료 결정 | 행동 집합을 받을 수 있게 확장. 서버의 종료·핵심 장면·상한 규칙 유지 |
| [TrainingMessageRepository](../src/main/java/com/wsid/training/repository/TrainingMessageRepository.java) | 단계별 전체 이력 조회만 있음 | 최신 20개 선택 쿼리 또는 서비스 처리 추가. 현재 입력을 이력에 중복 삽입하지 않음 |
| [TrainingMessage](../src/main/java/com/wsid/training/domain/TrainingMessage.java) | 단일 action_code, action_meta_json 공간 존재 | 복수 행동 메타데이터 설정/조회와 JSONB 왕복 구현 |
| [TurnInput](../src/main/java/com/wsid/training/domain/TurnInput.java)·[UserAction](../src/main/java/com/wsid/training/domain/UserAction.java) | 단일 행동·핵심 장면 제안·대사 사용 여부 | 행동 집합·의미 매핑·조기 종료를 같은 변경으로 전환 |
| [TrainingSessionRepository](../src/main/java/com/wsid/training/repository/TrainingSessionRepository.java) | 소유자 조건 세션 조회·저장 | 저장 전 상태·버전·만료 재확인. AI 호출 동안 트랜잭션 유지 금지 |
| [TurnResponse](../src/main/java/com/wsid/training/dto/TurnResponse.java)·[MessageDto](../src/main/java/com/wsid/training/dto/MessageDto.java) | 적용한 대사·finished·keyScene 반환 | AI 제안이 아닌 서버 적용 결과를 응답. raw shouldFinish를 finished에 대입하지 않음 |
| [SessionStateResponse](../src/main/java/com/wsid/training/dto/SessionStateResponse.java) | 대화·진행 상태 복원 | 저장 시나리오·필요한 모의 페이지 준비 상태의 복원 계약 합의 |
| [ReviewedTexts](../src/main/java/com/wsid/training/service/ReviewedTexts.java)·[시나리오 YAML](../src/main/resources/content/scenarios/used-goods-seller.yml) | 고정 문구·미검토 사례 초안 | 대체 흐름은 검토된 콘텐츠로 구성. reviewed:false 초안을 승인된 기본값으로 사용하지 않음 |
| [AI-03 구현](ai-03-scenario.md) | 시나리오 생성 클라이언트만 연결 완료 | TRN-06·07에서 시나리오 저장·시작과 역할극 맥락 확보를 먼저 연결 |

### 7.1 이력 선택과 기존 긴 대사

후속 이력 구성은 다음 순서를 따른다.

1. 현재 사용자 소유의 SIMULATION 이력에서 USER·AI 메시지만 대상으로 한다.
2. sequenceNo 내림차순으로 최근 최대 20개를 선택한다. 현재의 전체 이력 조회 메서드만으로는 이 제한을 보장하지 않는다.
3. null·공백·1,000자 초과 등 새 계약에 맞지 않는 과거 메시지는 메시지 단위로 제외한다. DB 원문은 바꾸거나 중간을 잘라 전송하지 않는다.
4. 제외분을 더 오래된 대화로 다시 채우지 않는다. 모두 제외되면 빈 목록을 사용한다.
5. sequenceNo 오름차순으로 정렬해 SimulationMessage 목록을 만든다.
6. turnCount는 이력 개수가 아닌 세션의 현재 simulationTurnCount를 사용한다.

현재 입력은 기존 SubmitTurnRequest와 새 DTO가 엄격하게 검증한다. 과거 이력의 부적합 값을 DTO 생성 예외까지 그대로 흘려 사용자 훈련을 멈추게 하지 않는다. 제외가 발생한 요청에서는 trainingId·requestId·excludedMessageCount만 운영 로그에 남기고 별도 DB 저장은 하지 않는 계획이다. 원문·대사·예외 본문은 로그에 넣지 않는다. 이 로그 처리는 아직 구현하지 않았다.

### 7.2 행동 매핑과 대표값

외부 명세의 `OFFICIAL_CHANNEL_CHECK`는 내부 `VERIFY_OFFICIAL_CHANNEL`에 명시적으로 매핑할 계획이다. 변환은 training 계층에서 담당하며 ai DTO가 UserAction을 import하지 않는다. 추가 코드 목록은 FastAPI와 합의해야 한다.

- 알려진 코드만 의미 있는 행동으로 매핑한다.
- 미확정 코드도 형식 검사를 통과한 문자열은 메타데이터에 따로 보관하고 의미를 추측하지 않는다.
- 알려진 코드와 미확정 코드가 섞여 있어도 알려진 행동을 버리지 않는다.
- 중복 제거한 전체 행동 집합을 도메인 규칙으로 전달한다.
- action_meta_json에 알려진 외부 코드·내부 코드·미확정 코드·분석 출처를 구조화해 보관한다. 모델 분석 원문은 보관하지 않는다.
- “확인하겠습니다”라는 말은 텍스트 의사 표현이다. 페이지 열기·버튼 클릭 같은 실제 이벤트와 구분한다.

기존 action_code의 대표값을 여러 행동일 때 UNCLEAR로 기록하는 정책은 **TurnInput 집합 전환·TrainingSession 조기 종료 변경과 함께 적용**해야 한다. 현재 도메인은 단일 END_CONTACT_AND_VERIFY일 때 조기 종료하므로 대표값만 먼저 바꾸면 조기 종료가 사라진다. 복수 행동에도 합의한 종료 조건이 유지되는지 회귀 테스트한다. 새 코드의 의미와 충돌 조합 정책이 확정되기 전에는 종료 행동을 임의로 합성하지 않는다.

### 7.3 종료·핵심 장면·가드레일

shouldFinish는 제안이다. 공식 확인 행동만으로 연락을 끊었다고 간주하거나 shouldFinish만으로 REPORT/COMPLETED를 저장하지 않는다. 서버의 확정한 조기 종료·최대 턴·직접 종료 규칙을 적용한다.

새 응답에는 suggestKeyScene이 없어 초기 연결에서는 기존 기한·1회 규칙을 서버가 유지한다. 조기 핵심 장면 제안을 유지하려면 별도 계약을 합의해야 한다. mockPage나 대사 키워드를 핵심 장면 표시로 사용하지 않는다. 서버는 핵심 장면 12턴 기한과 최대 15턴 규칙을 계속 관리한다.

새 응답에는 guardrailPassed·realIncident도 없다. FastAPI가 출력 검증을 통과했을 때만 success=true를 반환한다는 운영 계약이 필요하다. Spring의 JSON 검사만으로 출력이 안전하다고 판단하지 않는다. 실제 피해 감지나 기존 옛 DTO의 누락 필드를 기본값으로 채워 기능이 유지됐다고 표시하지 않는다.

### 7.4 트랜잭션과 기존 세션

```text
요청 ID·소유권·버전·만료·단계 확인
  → 저장 시나리오와 최근 이력 읽기
  → 트랜잭션 밖 AI 호출
  → 실패 시 검토된 대체 흐름
  → 저장 트랜잭션에서 상태·버전·중복·만료 재확인
  → 사용자 입력·행동·적용 대사·턴·상태를 함께 저장
```

호출 중 다른 요청이 상태를 바꾸거나 세션이 종료·만료되었으면 반환된 대사·행동도 저장하지 않는다. DB 롤백이 이미 실행한 LLM 호출까지 취소하지는 않는다. 같은 요청 ID 재사용은 추적 규약이며, 중복 저장 방지는 기존 버전·유니크 제약·서비스 검사를 확장해서 구현한다.

시나리오가 없는 기존 세션에 빈 객체를 보내 새 API로 전환하지 않는다. TRN-06·07의 저장 시나리오를 확보하거나 검토된 기본 시나리오 복원·재시작 안내를 정한다. 기존 유형 USED_GOODS_TRADE와 AI-03의 SECONDHAND_TRADE 호환 문제도 AI-03 연결 단계에서 해결한다.

action_meta_json 같은 저장 공간은 이미 있지만 엔티티 메서드와 스키마 규약은 미완성이다. 새 DB 구조가 필요하면 새 Flyway 마이그레이션을 추가한다. 적용된 기존 SQL을 수정하지 않는다. 원문 삭제 시 행동 메타데이터에 원문 사본이 남지 않는지도 확인한다.

## 8. 테스트 파일과 이후 수정 시 참고사항

| 파일 | 실제 검증 | 이후 변경 시 참고 |
|---|---|---|
| [SimulationReplyDtoTest](../src/test/java/com/wsid/ai/SimulationReplyDtoTest.java) | 요청·메시지·결과의 입력 경계, 복사·원문 비노출·미확정/중복 코드 유지 | 시나리오·길이·개수·목록 정책 변경 시 경계를 함께 수정 |
| [FakeAiClientSimulationTest](../src/test/java/com/wsid/ai/FakeAiClientSimulationTest.java) | 공식 키워드·빈 행동·결정성·원문 재출력 방지·개발용 표시 | 단순 키워드 테스트를 LLM 정확도 검증으로 해석하지 않음 |
| [HttpAiClientSimulationTest](../src/test/java/com/wsid/ai/HttpAiClientSimulationTest.java) | 새 JSON·헤더·엄격한 타입·실패·재전달·기존 경로 유지 | mockPage 확장·새 필수 필드·오류 정책 변경 시 계약 테스트도 변경 |
| [HttpAiClientSimulationTransportTest](../src/test/java/com/wsid/ai/HttpAiClientSimulationTransportTest.java) | 실제 Java HTTP의 제한 분리·UTF-8 본문·헤더 | 클라이언트 설정 변경 시 새 AI-04가 어떤 제한을 사용하는지 재확인 |
| [TrainingSessionTest](../src/test/java/com/wsid/training/domain/TrainingSessionTest.java) | AI 없이 도메인의 TurnInput·종료·핵심 장면 직접 검사 | 3차 집합 입력 시그니처·조기 장면 테스트·종료 회귀를 함께 수정 |
| [DiscoveryRulesTest](../src/test/java/com/wsid/training/domain/DiscoveryRulesTest.java) | fake 호출 없이 도메인 상태 검사 | TurnInput 변경으로 영향을 받는 직접 생성 코드 확인 |
| [DiscoveryDatabaseTest](../src/test/java/com/wsid/training/DiscoveryDatabaseTest.java) | Mockito AiClient를 사용하고 옛 메서드 응답을 fake에 위임 | 1차에는 변경 없음. 3차에서 새 메서드 응답 설정 추가 필요 |
| [TrainingControllerTest](../src/test/java/com/wsid/training/controller/TrainingControllerTest.java) | 공개 요청·응답·단계별 호출 | 공개 DTO 확장 시 기존 경로·인증·버전 호환성 확인 |

DiscoveryDatabaseTest는 실제 AiClient 빈을 fake로 교체하는 테스트가 아니다. `@MockitoBean AiClient`에 `when(ai.suggestTrainingTurn(any()))`을 설정하고 해당 응답을 FakeAiClient로 위임한다. 3차에서 서비스가 replySimulation을 호출하도록 바뀌면 `when(ai.replySimulation(any()))`도 새 fake 결과를 반환하도록 설정해야 한다. 빠뜨리면 Mockito 기본값 null 때문에 실제 AI 장애와 다른 실패가 발생한다.

기존 도메인 테스트의 SUGGEST_KEY_SCENE·suggestKeyScene=true는 테스트가 직접 넣는 값이다. 새 API에서 이 필드가 없다는 이유만으로 테스트를 삭제하지 말고, 도메인의 조기 장면 입력을 유지할지 제거할지 결정한 뒤 테스트 목적을 맞춘다.

## 9. FastAPI 개발 디렉토리의 구현·검증 항목

FastAPI 코드는 이 저장소에 추가하지 않았다. 실제 AI 저장소에서 다음 책임을 구현한다.

| 제안 위치 | 해야 할 일 |
|---|---|
| `schemas/simulation.py` | 요청·응답 camelCase, boolean·배열·문자열 타입, 20개/1,000자/행동 10개와 100자 계약 정렬 |
| `routers/simulation.py` | 정확한 새 경로·내부 키 검증·헤더 UUID 수신·요청 추적 |
| `services/simulation_reply.py` | 시나리오와 선택된 이력으로 페르소나 유지, 현재 userInput을 한 번만 사용 |
| 행동 분석 모듈 | 합의한 코드 생성·의사 표현과 실제 행동 구분 |
| 출력 검증 모듈 | 가상 훈련 범위·민감정보·실행 가능한 사기 수법·실제 링크 차단 |
| 공통 오류 처리 | 공급자 장애·시간 초과·검증 실패를 실패 봉투로 반환 |

success는 FastAPI가 처리 결과로 설정한다. 문자열 대신 Python True/False를 사용하고 실제 JSON이 boolean인지 테스트한다. shouldFinish도 검증된 boolean 제안이어야 한다. Spring 요청의 ID는 JSON 필드가 아니라 X-Request-Id 헤더에 있다.

**mockPage는 null이어도 응답 필드로 반드시 남긴다.** response_model_exclude_none=True 등 null 필드 생략 설정을 이 응답에 적용하지 않는다. 실제 JSON에 mockPage:null이 있는지 테스트한다. 현재 Spring은 누락도 계약 오류로 처리한다.

1차는 제목·설명 시나리오를 재사용한다. 페르소나·상대 역할·핵심 장면·진행 상태가 필요한 실제 역할극에서는 시나리오나 별도 진행 맥락 계약을 확장해야 한다. 최근 20개 이력만으로 이미 보여준 장면과 역할 설정을 완전히 복원할 수 있다고 가정하지 않는다. 새 필드는 AI-03 생성·저장·AI-04 요청·검증을 함께 변경한다.

FastAPI는 사용자 입력·이력을 시스템 지시로 실행하지 않고, 가상 설정과 사용자가 밝힌 사실을 구분해야 한다. 검증 실패 시 안전하지 않은 대사를 성공 응답으로 내보내지 않는다. Spring에는 문법·길이 검사만 있으므로 운영 전에는 LLM 페르소나·가드레일·프롬프트 공격 평가가 필요하다.

생성 장애는 503·AI_SERVICE_UNAVAILABLE 오류 봉투를 제안하지만 최종 HTTP 상태·실패 필드는 AI 담당자와 확정해야 한다. 현재 Spring은 상태 오류와 success=false를 사용 불가로 변환하고 오류 코드의 의미를 사용자에게 그대로 전달하지 않는다.

## 10. 검증 결과와 실행 방법

2026-11-16 Java 21 환경에서 `./gradlew test --no-daemon`을 실행했다. **전체 192개, 실패 0개·오류 0개·건너뜀 0개**다. 신규 AI-04 테스트는 83개다.

| 신규 테스트 | 개수 |
|---|---|
| SimulationReplyDtoTest | 26 |
| FakeAiClientSimulationTest | 3 |
| HttpAiClientSimulationTest | 52 |
| HttpAiClientSimulationTransportTest | 2 |

HTTP 계약 테스트는 기존 패턴인 MockRestServiceServer를 사용했다. 별도 전송 테스트는 localhost 임의 포트의 JDK HttpServer로 실제 HTTP 요청과 시간 초과를 검증했다. AI-03 전용 제한이 더 짧아도 AI-04는 정상 완료하고, 더 길어도 AI-04는 훈련용 제한에서 실패하는 것을 확인했다.

이 결과는 로컬 DTO·클라이언트·설정·기존 코드 회귀에 대한 증거다. 실제 FastAPI·외부 LLM·콘텐츠 안전성·DB 저장·행동 매핑·모의 페이지 기능을 검증한 것은 아니다. DB 관련 코드를 바꾸지 않았고 기본 작업은 db 태그를 제외하므로 이번에는 dbTest를 실행하지 않았다.

```bash
# 전체 기본 테스트
./gradlew test

# 신규 AI-04 테스트만 실행
./gradlew test --tests 'com.wsid.ai.*Simulation*'
```

후속 연결은 FastAPI 스키마·내부 인증·null 필드 계약 확정 → 합성 입력으로 실제 HTTP 왕복 검증 → 시나리오 저장·기존 세션 정책 확정 → TRN-08·행동 집합·저장 동시 전환 → PostgreSQL dbTest → 실제 LLM의 페르소나·가드레일 평가 순서로 진행한다.
