# AI-04 역할극 응답·행동 분석 구현 계획서

작성일: 2026-11-16 · 상태: 1차 Spring 클라이언트 구현 완료, 2·3차 후속 계획

실제 구현·결정 사항·파일별 상호작용·검증 결과는 [AI-04 구현 보고서](ai-04-simulation.md)를 참고한다. 새 DTO·fake/HTTP·엄격한 응답 검사와 테스트를 구현했으며 기존 서비스 호출은 유지했다. 아래 2장의 현재 코드 비교는 계획 수립 당시 기준이다.

명세 출처: **AI-04 내부 API 명세 초안(2026-11-16 제공본)**. `recognizedActions`, `shouldFinish`, `mockPage`, `OFFICIAL_CHANNEL_CHECK`는 이 초안에 정의된 신규 계약이며, 저장소의 기존 구현에서 확인된 계약은 아니다. 실제 FastAPI의 스키마·응답 예시와 대조해 확정한다.

## 1. 목표와 개발 범위

`POST /internal/ai/simulation/reply`는 저장된 시나리오와 최근 훈련 대화, 현재 사용자 입력을 받아 다음 역할극 메시지와 행동 분석을 제안하는 FastAPI 내부 API다. Spring은 사용자·훈련 상태를 확인하고 AI 제안을 규칙에 맞춰 적용·저장한다.

이 문서는 구현 계획과 후속 작업을 기록한다. 1차 클라이언트 코드는 구현했으며 기존 훈련 서비스·DB·공개 API 동작은 변경하지 않았다.

| 단계 | 작업 | 완료 기준 |
|---|---|---|
| 1차: Spring 클라이언트 | 새 DTO, `AiClient.replySimulation`, fake/HTTP 구현, 엄격한 응답 검사, 계약 테스트 | 목표 AI-04 경로를 합의한 형식으로 호출하고 결과를 반환 |
| 2차: FastAPI | 내부 인증, 요청 추적, 페르소나 유지, 행동 분석, 출력 가드레일, 실패 봉투 | 안전성이 검증된 역할극 응답 제공 |
| 3차: 훈련 연결 | 시나리오·이력 구성, 행동 매핑·복수 행동 저장, 서버 규칙 적용, 동시 요청 처리 | TRN-08에서 새 AI-04를 사용하고 상태·대화를 일관되게 저장 |
| 후속: 모의 페이지 | `mockPage` 객체 스키마, 템플릿 카탈로그, 링크·행동 이벤트 연결 | TRN-09·10과 리포트까지 연결 |

기존 호출은 이미 `TrainingService`에서 사용 중이다. **1차에서는 새 메서드를 병행 추가하고 기존 `suggestTrainingTurn`을 유지한다.** 경로만 먼저 바꿔 기존 서비스를 깨뜨리지 않는다. 3차에서 서비스와 테스트를 전환한 뒤 기존 DTO·메서드·옛 HTTP 경로를 제거한다.

## 2. 현재 구현과 새 명세의 차이

| 항목 | 현재 코드 | 새 명세와 필요한 변경 |
|---|---|---|
| HTTP 경로 | `/internal/training/turn` | `/internal/ai/simulation/reply` |
| 요청 | `fraudTypeCode`, `phase`, `simulationTurnCount`, `keySceneShown`, `userMessage` | `scenario`, `messages`, `userInput`, `turnCount` |
| 응답 봉투 | `TrainingTurnSuggestion` 본문을 바로 받음 | `success`, `data` 봉투 검사 필요 |
| 행동 | `userAction` 문자열 하나 | `recognizedActions` 문자열 배열 |
| 공식 채널 확인 | 내부 `VERIFY_OFFICIAL_CHANNEL` | 명세 예시 `OFFICIAL_CHANNEL_CHECK`와 명시적으로 매핑 필요 |
| 종료 | 서버가 행동·최대 턴으로 결정 | `shouldFinish`는 추가 제안이며 최종 상태는 서버가 결정 |
| 가드레일 | `guardrailPassed`로 `replyUsable()` 판정 | 새 응답에 해당 필드가 없음. FastAPI 성공 응답의 안전성 보장 조건 합의 필요 |
| 핵심 장면 | `suggestKeyScene` + 서버의 12턴 기한·1회 규칙 | 새 응답에 제안 필드가 없음. 기존 강제 장면 규칙 유지 필요 |
| 실제 피해 감지 | `realIncident` 필드, 연결은 TODO | 새 응답에 없음. 자동으로 false라고 해석해 기능 완료로 표시하지 않음 |
| 모의 페이지 | 템플릿 코드 컬럼만 존재 | `mockPage`의 null 아닌 객체 스키마는 미정 |

관련 코드: [TrainingTurnRequest](../src/main/java/com/wsid/ai/dto/TrainingTurnRequest.java), [TrainingTurnSuggestion](../src/main/java/com/wsid/ai/dto/TrainingTurnSuggestion.java), [HttpAiClient](../src/main/java/com/wsid/ai/HttpAiClient.java), [TrainingService](../src/main/java/com/wsid/training/service/TrainingService.java).

## 3. 요청 계약

```http
POST /internal/ai/simulation/reply
Content-Type: application/json
X-Internal-Api-Key: <환경 설정값>
X-Request-Id: <현재 사용자 메시지의 UUID>
```

```json
{
  "scenario": {
    "title": "중고거래 안전 확인 훈련",
    "description": "가상 거래 상황"
  },
  "messages": [
    {"role": "assistant", "content": "거래 관련 안내를 확인하셨나요?"},
    {"role": "user", "content": "확인 방법을 알고 싶어요."},
    {"role": "assistant", "content": "어떤 방법으로 확인하시겠어요?"}
  ],
  "userInput": "공식 채널을 확인하겠습니다.",
  "turnCount": 2
}
```

원래 예시의 `scenario: {}`와 `messages: []`는 구조를 보여주는 초안이다. 1차의 `scenario`는 AI-03과 같은 제목·설명 객체를 최소 계약으로 제안한다. 실제 역할극 연결에는 등장인물·가상 설정·허용 행동·핵심 장면 등도 필요하므로 9장의 시나리오 확정이 선행되어야 한다. 빈 객체만 보내도 개인화된 역할극이 가능하다고 가정하지 않는다.

| 항목 | 1차 검증안 |
|---|---|
| `scenario` | null 불가. 최소 title·description의 문자열·공백·길이 규칙은 AI-03과 일치 |
| `messages` | null 불가. 빈 배열 허용. 역할극 이전 이력만 시간순으로 전달 |
| 메시지 역할 | `user`, `assistant`만 허용. 서비스 안내인 `SYSTEM`은 제외 |
| 메시지 내용 | 공백 불가, 1,000자 이하 제안 |
| 이력 개수 | 최근 최대 20개 제안. 역할극 이력만 포함 |
| `userInput` | 공백 불가, 최대 1,000자. 기존 `SubmitTurnRequest`와 같은 기준 |
| `turnCount` | 1 이상의 정수. Spring이 읽은 현재 `simulationTurnCount` |
| `requestId` | UUID 필수. Java DTO에 보관하되 JSON 본문에서는 제외하고 헤더로 전달 |

20개·1,000자는 신규 계약 제안값이다. FastAPI와 확정하고, 저장된 이력의 길이·모델 입력 예산에 맞춰 조정한다. DTO는 잘못된 값을 조용히 버리지 않고 입력 예외로 거부한다. 이력 선택과 DB 값 변환은 호출 서비스가 담당한다. 목록은 방어적으로 복사한다.

### 현재 입력 중복과 턴 수 정의

- `messages`에는 이번 `userInput`을 넣지 않는다. 이번 입력은 별도 필드로 한 번만 전송한다.
- 첫 훈련 대사는 현재 코드에서 이미 1턴이다. 저장된 AI 대사 수가 3이면 `turnCount=3`으로 보내고, 이번 입력 처리 후 서버 규칙이 다음 턴을 적용한다.
- 위 요청 예시는 assistant 메시지가 2개이므로 `turnCount=2`다. 실제 요청에서 이력을 제한하거나 제외한 경우에도 `turnCount`는 배열의 assistant 개수가 아니라 DB의 현재 `simulationTurnCount`를 사용한다.
- 현재 `TrainingSession.applyTurn()`은 턴 적용 시 카운트를 증가시킨다. 호출 전에 증가시키거나 AI 응답에서 턴 수를 받아 덮어쓰지 않는다.
- 현재 사용자 요청의 `requestId`를 그대로 헤더로 전달한다. 같은 요청의 재전송은 같은 ID, 새 메시지는 새 ID다. ID만으로 중복 실행이 방지되지는 않는다.

### 과거 이력의 길이 초과 처리

옛 `suggestTrainingTurn`은 응답 길이를 검사하지 않아 DB에 1,000자를 넘는 AI 대사가 있을 수 있다. **3차 서비스는 DTO를 만들기 전에 길이가 초과된 과거 메시지를 LLM 전송 이력에서 메시지 단위로 제외한다.** DB 원문은 유지하고 문장 중간을 잘라 전송하지 않는다. null·공백 등 새 계약에 맞지 않는 과거 메시지도 같은 구성 단계에서 제외한다. 모두 제외되면 허용된 빈 messages 배열을 사용한다.

제외 건수는 3차 서비스에서 구조화된 운영 로그로 기록한다. 제외가 발생한 요청에 한해 trainingId·requestId·excludedMessageCount만 남기고, DB에는 별도 저장하지 않는다. 사용자 입력·제외된 원문·대사·예외 원문은 로그에 넣지 않는다. 현재 userInput의 엄격한 입력 검증은 유지한다. 과거 이력의 길이 초과가 DTO의 `IllegalArgumentException`까지 전달되어 사용자 요청을 중단시키지 않도록 서비스에서 처리한다.

## 4. 응답 계약과 엄격한 타입 검사

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

| 필드 | 1차 처리안 |
|---|---|
| `success` | JSON boolean `true` 필수. 문자열 `"true"`, 숫자, 누락은 실패 |
| `data` | 객체 필수 |
| `reply` | 실제 문자열, 공백 불가, 최대 1,000자 제안 |
| `recognizedActions` | 배열 필수. 각 요소는 공백 없는 행동 코드 문자열. 최대 10개·코드당 100자·`[A-Z0-9_]+` 제안 |
| `shouldFinish` | 실제 JSON boolean 필수 |
| `mockPage` | 필드 필수. 1차는 명시적 null만 지원 |
| 추가 필드 | 필요한 필드를 대체하지 못하며 사용하지 않고 무시 |

`HttpAiClient.replySimulation()`은 **응답을 먼저 `JsonNode`로 받아 모든 타입을 확인한 후 `treeToValue`로 결과 DTO를 만든다.** `AiEnvelope`로 바로 변환해 문자열·숫자 자동 변환을 허용하지 않는다. `recognizedActions`도 배열 여부뿐 아니라 요소 각각의 타입을 검사한다. 길이 초과는 잘라서 성공으로 바꾸지 않는다.

`mockPage`의 null 아닌 구조는 현재 명세에 없다. 임의의 Map·URL·HTML을 받아 서비스에 넘기지 않는다. **1차에서 null 아닌 값이 오면 미지원 응답으로 보고 `AiUnavailableException` 처리한다.** 후속 모의 페이지 기능은 객체 스키마를 확정하고 DTO·검증·테스트를 함께 확장한다. 이를 통해 1차에서는 모의 페이지 제안을 지원하지 않는다는 범위가 분명해진다.

행동 배열은 구조가 맞으면 DTO로 받을 수 있지만, 각 코드의 의미까지 확정된 것은 아니다. 알 수 없는 코드를 기존 enum 이름으로 추측하거나 종료 근거로 쓰지 않는다. 행동 매핑은 6장의 훈련 계층에서 처리한다.

### 실패 계약

생성 장애는 `503 + AI_SERVICE_UNAVAILABLE` 오류 봉투를 제안한다. 최종 HTTP 상태와 실패 필드는 FastAPI 담당자와 확정한다. 어댑터는 HTTP 오류·HTTP 200의 실패 봉투·빈 본문·잘못된 JSON·필드 타입/길이 위반·시간 초과를 `AiUnavailableException`으로 변환한다.

원본 HTTP/변환 예외에는 사용자 입력·대사가 들어갈 수 있어 cause와 원문을 보관하지 않는다. 로컬 요청 DTO 오류는 `IllegalArgumentException`으로 구분한다. 자동 재시도는 1차에 넣지 않는다.

## 5. 1차 Spring 구현 파일과 순서

| 파일 | 변경 계획 |
|---|---|
| `ai/dto/SimulationReplyRequest.java` | 시나리오·메시지·입력·턴 수·헤더용 요청 ID. training 타입 import 금지 |
| `ai/dto/SimulationMessage.java` | 메시지 role·content, 검증·원문 비노출 `toString` |
| `ai/dto/SimulationReplyResult.java` | reply·행동 배열·종료 제안·현재 null 전용 mockPage 계약 |
| `ai/AiClient.java` | `replySimulation` 추가. 기존 `suggestTrainingTurn`은 전환 전 유지 |
| `ai/FakeAiClient.java` | 새 계약의 결정적 개발용 결과. 공식 채널 확인 입력의 행동 분류·실패 흐름 지원 |
| `ai/HttpAiClient.java` | 새 경로·내부 키·요청 ID, JsonNode 선검사, 실패 변환 |
| `src/test/java/com/wsid/ai/` | 요청 검증·fake·HTTP 계약 테스트 |
| 문서 | 현재 구현 상태, 기존 호출과의 차이, 전환 방법·검증 결과 갱신 |

1. 9장의 계약 결정 중 1차에 필요한 스키마·길이·null 정책을 확정한다.
2. 요청·응답 DTO와 인터페이스를 추가한다. 최소 시나리오를 AI-03의 `Scenario`로 재사용할지 공통 ai DTO로 추출할지 정하고, 훈련 엔티티를 직접 넘기지 않는다.
3. fake를 작성한다. 사용자 입력이나 시나리오 설명을 그대로 되풀이하지 않고 준비된 가상 대사를 사용한다. 안전성 검증을 마친 운영 콘텐츠로 취급하지 않는다.
4. 새 HTTP 어댑터를 추가하고 기존 AI-03과 같은 타입·원문 비노출·요청 ID 규칙을 적용한다.
5. AI-04는 훈련 턴용 기존 `restClient`·응답 제한 20초를 사용한다. AI-03의 생성 전용 클라이언트·시간 제한은 사용하지 않는다. 사용자 턴 전체 25초는 기존 제안값이며 별도 전체 예산 구현 여부를 확인한다.
6. 계약 테스트와 기존 기본 테스트를 실행한다. 기존 AI-01·02·03과 옛 훈련 흐름을 깨뜨리지 않는지 확인한다.

## 6. 행동·종료·핵심 장면을 어떻게 적용하나

### 행동 코드와 복수 행동

AI-04 명세 초안의 외부 코드와 기존 내부 코드는 다르다.

| FastAPI 외부 코드 | 내부 처리 |
|---|---|
| `OFFICIAL_CHANNEL_CHECK` | `UserAction.VERIFY_OFFICIAL_CHANNEL` |
| 빈 배열 | `UNCLEAR`, 확인된 행동 없음 |
| 미확정 코드만 있는 배열 | 의미를 추측하지 않고 `UNCLEAR`. 미확정 코드는 메타데이터에 보관 |
| 알려진 코드와 미확정 코드가 섞인 배열 | 알려진 코드만 행동에 매핑하고 미확정 코드는 메타데이터에 따로 보관. OFFICIAL_CHANNEL_CHECK는 공식 확인으로 매핑하고 SOMETHING_NEW는 의미를 부여하지 않음 |
| 여러 알려진 행동 | 중복 제거한 전체 행동 집합을 유지. 첫 요소 하나로 축소하지 않음 |

나머지 외부 코드 목록은 아직 미정이다. `ASK_QUESTION`, `REFUSE`, `END_CONTACT_AND_VERIFY`, `COMPLY` 같은 기존 enum 값이 FastAPI의 외부 계약이라고 가정하지 않고 명시적 매핑표를 합의한다. `ai` 패키지가 `UserAction`을 import하지 않도록 매핑은 `training`의 전용 변환 코드에서 담당한다.

현재 `action_code`에는 문자열 하나만 저장하고 `TurnInput`도 행동 하나만 받는다. 3차에서는 전체 매핑 행동 집합을 규칙에 전달할 수 있도록 도메인 입력을 확장하고, `action_meta_json`에 알려진 외부 코드·내부 코드·미확정 외부 코드·분석 출처를 구조화해 보관하는 방안을 제안한다. 미확정 코드도 4장의 타입·문자·길이·개수 검사를 통과한 값만 보관하고 종료·평가 행동으로 해석하지 않는다. 임의의 모델 설명·분석 원문은 저장하지 않는다.

기존 `action_code` 호환용 대표값은 매핑된 행동이 하나면 그 값, 여러 행동의 결합 정책이 확정되지 않았으면 `UNCLEAR`로 두는 방안을 제안한다. 전체 행동을 보존하므로 이후 평가에서 손실 없이 사용할 수 있다. 종료 판단을 이 대표값에만 의존하지 않도록 서비스·도메인·리포트 소비자를 함께 점검한다. 충돌하는 행동 조합의 우선순위는 구현 전 확정한다.

**대표값 정책 변경·TurnInput의 행동 집합 전환·TrainingSession의 조기 종료 판정 변경은 3차의 같은 변경에서 함께 적용한다.** 현재 조기 종료는 단일 `END_CONTACT_AND_VERIFY` 비교에 의존하므로 대표값만 먼저 `UNCLEAR`로 바꾸어 배포하지 않는다. 복수 행동에 조기 종료 행동이 포함되거나 거절 등 다른 행동과 함께 나타나도 합의한 조기 종료 규칙이 유지되는지 회귀 테스트한다.

텍스트의 “확인하겠습니다”는 행동 의사 표현이다. 실제 공식 채널 버튼 클릭·페이지 열기 같은 이벤트와 구분해 `TEXT_ANALYSIS` 등 출처를 보관한다. AI 분석으로 실제 화면 행동이 수행됐다고 기록하지 않는다.

### shouldFinish의 처리

- boolean 값은 응답 계약으로 받아도 **그 값만으로 종료하지 않는다.**
- 1차는 결과 DTO로 반환만 한다. 3차 초기 정책도 서버가 확정한 기존 종료 조건을 우선하고, `shouldFinish`는 종료 제안으로만 취급한다.
- 공식 채널 확인 행동 하나와 `shouldFinish=true`가 왔다고 `END_CONTACT_AND_VERIFY`로 바꾸지 않는다.
- 종료는 서버의 최대 턴·확정된 조기 종료 규칙·사용자 직접 종료에 따라 결정한다. 복수 행동에 따른 조기 종료는 매핑·결합 규칙을 합의한 뒤 적용한다.
- AI의 종료 제안만으로 REPORT 단계나 COMPLETED 상태를 DB에 쓰지 않는다.

### 핵심 장면과 가드레일

새 응답에는 `suggestKeyScene`이 없다. 전환 초기에는 조기 핵심 장면 제안을 사용하지 않고, 서버가 현재의 “12턴까지 핵심 장면, 1회만 표시, 최대 15턴” 규칙을 유지하는 방안을 제안한다. `mockPage` 존재나 특정 대사 표현을 핵심 장면 플래그로 간주하지 않는다. 기존 테스트가 다루는 조기 제안 동작은 새 계약 전환 시 무엇을 유지·제거할지 명시한다.

새 응답에는 `guardrailPassed`도 없다. FastAPI는 출력 검증을 통과한 경우에만 `success=true`를 반환하고, 실패하면 사용 불가로 응답하는 계약을 제안한다. Spring의 형식 검사가 콘텐츠 안전성 검사를 대신하지 않는다. 기존 DTO로 변환하기 위해 무조건 `guardrailPassed=true`를 넣는 임시 연결은 하지 않는다. 새 결과를 서버의 출력 적용 판단으로 연결하는 변환 계층을 만든다.

핵심 장면을 이미 보여줬는지 등 지속 상태는 현재 네 필드만으로 완전히 전달되지 않는다. 이력 일부만으로 일관성을 보장한다고 보지 않는다. FastAPI가 이런 상태까지 필요로 하면 명시적 진행 맥락 필드를 계약에 추가하고 양쪽 DTO를 함께 수정한다. 1차에 합의되지 않은 필드를 몰래 추가하지 않는다.

## 7. 3차 훈련·DB 연결과 관련 코드

| 코드 | 현재 역할 | 처리할 작업 |
|---|---|---|
| [TrainingController](../src/main/java/com/wsid/training/controller/TrainingController.java) | TRN-08 사용자 메시지 접수 | 기존 공개 경로·요청 ID·버전 계약 유지. 모의 페이지 응답 확장은 별도 합의 |
| [TrainingService](../src/main/java/com/wsid/training/service/TrainingService.java) | 검증 → 옛 AI 호출 → 저장 | 시나리오·이력을 읽어 새 요청 구성, 새 결과 변환·대체 흐름 연결 |
| [TrainingSession](../src/main/java/com/wsid/training/domain/TrainingSession.java) | 턴·핵심 장면·종료·만료 규칙 | 복수 행동 입력 수용, 서버 종료·핵심 장면 규칙 유지 |
| [TrainingMessage](../src/main/java/com/wsid/training/domain/TrainingMessage.java) | 사용자·AI 대화와 단일 행동 저장 | 복수 행동 메타데이터 설정/조회와 DB 왕복 지원 |
| [TrainingMessageRepository](../src/main/java/com/wsid/training/repository/TrainingMessageRepository.java) | 대화 이력·요청 ID 조회 | SIMULATION의 USER·AI 이력에서 최근 최대 20개를 선택하는 쿼리 또는 서비스 처리 추가. 길이 초과 과거 메시지 제외 후 시간순 전달, 현재 입력 중복 방지 |
| [TurnInput](../src/main/java/com/wsid/training/domain/TurnInput.java)·[UserAction](../src/main/java/com/wsid/training/domain/UserAction.java) | 단일 행동을 규칙에 전달 | 새 행동 집합과 의미 확정. 외부 코드 매핑은 training에 유지 |
| [TurnResponse](../src/main/java/com/wsid/training/dto/TurnResponse.java)·[MessageDto](../src/main/java/com/wsid/training/dto/MessageDto.java) | 서버 적용 결과를 프론트에 반환 | 기존 finished·keyScene은 서버 판정 결과 사용. AI 결과를 그대로 반환하지 않음 |
| [SessionStateResponse](../src/main/java/com/wsid/training/dto/SessionStateResponse.java) | 이어하기 상태 복원 | 모의 페이지가 연결되면 허용된 페이지 상태도 복원 |
| [AI-03 구현](ai-03-scenario.md) | 시나리오 개요 생성 클라이언트 | TRN-06·07 저장·시작 연결과 실제 역할극 맥락 확정이 선행 |
| 모의 페이지·리포트 코드 | 현재 대부분 미구현 | 실제 이벤트와 텍스트 분석을 구분해 평가에 사용 |

### 요청 한 건의 처리

```text
1. 로그인·소유권·ACTIVE·SIMULATION·만료·requestId·stateVersion 확인
2. 저장 시나리오와 이번 입력 이전 이력 중 최근 최대 20개 선택, 위반 메시지 제외
3. 트랜잭션 밖에서 AI-04 호출
4. 실패 시 검토된 대체 응답 선택
5. 저장 트랜잭션에서 상태·버전·중복·만료 재확인
6. 현재 사용자 입력·복수 행동·적용 대사·턴 수·상태를 함께 저장
7. 서버가 확정한 메시지·종료 여부를 프론트에 반환
```

AI 호출 중 다른 요청이 상태를 바꿨다면 대사·행동도 저장하지 않고 기존 버전 충돌 정책으로 처리한다. DB 롤백이 이미 수행한 LLM 호출까지 취소하는 것은 아니다.

현재 `findByTrainingIdAndPhaseOrderBySequenceNoAsc`는 해당 단계의 이력을 전부 조회하므로 20개 제한을 보장하지 않는다. 3차에서 SIMULATION·USER/AI 조건으로 sequenceNo 내림차순 최신 20개를 선택하는 쿼리 또는 동등한 서비스 처리를 추가한다. 선택한 이력에서 계약 위반 메시지를 제외한 뒤 sequenceNo 오름차순으로 되돌려 전송한다. 제외분을 더 오래된 대화로 다시 채우지 않고, 이력 개수로 turnCount를 재계산하지 않는다.

현재 `scenarioJson`은 저장 공간만 있고 생성·조회 연결이 없다. **시나리오가 없는 기존 세션에 빈 객체를 보내 새 AI-04로 강제 전환하지 않는다.** 전환 배포 전에 검토된 기본 시나리오 복원 또는 해당 세션의 재시작 안내 정책을 정한다. 기존 방식의 AI 호출을 운영에서 무기한 조용히 병행하지 않는다.

`action_meta_json`은 기존 컬럼을 사용할 수 있지만 엔티티 메서드와 의미 규약은 추가해야 한다. `turn_evaluation_json`에 임의로 턴별 평가를 넣지 않는다. 실제 스키마 변경이 필요하면 새 Flyway 마이그레이션을 추가하고 적용된 V1·V2는 수정하지 않는다.

실패 시에는 행동을 `UNCLEAR`로 처리하고 검토된 대사·서버 강제 장면·최대 턴 규칙으로 이어간다. 현재 `askAi()`는 원인 예외까지 로깅할 수 있으므로 새 경로에서는 trainingId·requestId 등 메타데이터만 기록하도록 바꾼다. 사용자 메시지·시나리오·대사·예외 원문을 로그에 남기지 않는다.

## 8. FastAPI 개발 디렉토리에서 할 일

| 제안 구성 | 책임 |
|---|---|
| `schemas/simulation.py` | camelCase 필드·문자열/배열/boolean 타입·길이·mockPage null 정책 |
| `routers/simulation.py` | 목표 경로·내부 키 확인·요청 ID 추적·성공 봉투 |
| `services/simulation_reply.py` | 시나리오·이력·현재 입력을 결합, 동일 가상 인물과 상황 유지 |
| 행동 분석 모듈 | 합의한 외부 행동 코드만 생성. 실제 이벤트와 텍스트 의사 표현 구분 |
| 출력 검증 모듈 | 민감정보·재사용 가능한 사기 절차·실제 링크·페르소나 이탈 검사 |
| 공통 오류 처리 | 가드레일 차단·공급자 장애·시간 초과의 실패 봉투 |

`success`·`shouldFinish`는 문자열이 아닌 boolean으로 응답한다. `success`는 서버가 처리 결과로 설정하고 LLM이 응답 봉투를 결정하게 하지 않는다. `shouldFinish`는 LLM 분석을 서버가 검증한 종료 제안이다.

**null이어도 `mockPage` 필드를 응답에서 생략하지 않는다.** 이 응답에는 `response_model_exclude_none=True` 같은 null 필드 생략 설정을 적용하지 않는다. FastAPI 테스트에서 실제 JSON에 `"mockPage": null`이 포함되는지 확인한다. Spring은 필드 누락과 명시적 null을 구분한다.

사용자 입력과 이력은 프롬프트 지시로 실행하지 않는다. 시나리오에 없는 사실을 사용자 사실로 만들지 않고, 가상 역할의 설정과 실제 사용자가 밝힌 사실을 구분한다. 명세의 reply 예시는 응답 형식을 보여주므로, 실제 대사의 말투·역할은 시나리오 설정과 일치하는지 별도 평가한다.

민감정보나 실제 공격에 재사용할 수 있는 구체적 수법·링크·계좌·인증정보 수집을 출력하지 않는다. 검사 실패는 검증되지 않은 대사를 성공으로 내려주지 않는다. 원문을 예외 메시지·로그에 남기지 않는다. 실제 공급자와 모델 선정·평가 세트 검증은 AI 저장소 작업이다.

## 9. 착수 전 결정할 항목

| 결정 | 권장안/선행 조건 |
|---|---|
| 시나리오 상세 스키마 | 1차 최소 개요를 정하고, 실제 페르소나·핵심 장면·허용 행동 맥락은 AI-03·카탈로그와 함께 확정 |
| 메시지·길이·입력 예산 | user/assistant, 현재 입력 제외, 최근 20개·각 1,000자·reply 1,000자 제안값 확정. 과거 길이 초과 메시지는 서비스에서 제외한 뒤 DTO 생성 |
| 턴 수 | DB의 현재 `simulationTurnCount`를 전달. 선택된 messages의 개수로 계산하지 않으며 이번 처리의 증가분은 Spring이 적용 |
| 행동 목록·복수 행동 | AI-04 명세 초안의 OFFICIAL_CHANNEL_CHECK를 FastAPI와 대조. 알려진/미확정 혼합 처리·충돌 조합 합의. 대표값·집합 입력·조기 종료 규칙은 같은 변경으로 전환 |
| 종료 제안 | shouldFinish만으로 종료하지 않음. 서버 종료 조건 유지 |
| 핵심 장면 | 새 응답에 제안 필드가 없어 초기에는 서버 기한·1회 규칙 사용. 조기 제안 유지 여부와 진행 맥락 확장 합의 |
| 가드레일 | FastAPI 성공은 출력 검증 통과를 의미하도록 계약. 실제 피해 감지의 별도 처리도 확정 |
| mockPage | 1차 필드 필수·null만 지원. FastAPI에서 null 필드 생략 금지. 이후 객체 필드·템플릿 허용 목록·사용자 안내·링크 생성 책임 확정 |
| 시간 제한 | 기존 훈련용 응답 제한 20초 사용. 전체 턴 예산·FastAPI 처리 예산·프록시 제한 정렬 |
| 오류 봉투 | 503·AI_SERVICE_UNAVAILABLE 제안, 내부 인증·요청 오류 상태 확정 |
| 전환·기존 세션 | 새 메서드 병행 개발 후 서비스 일괄 전환. 시나리오 없는 기존 세션 처리·옛 코드 제거 시점 결정 |
| DB·평가 | action_meta_json 규약과 복수 행동 소비자, 원문 삭제 시 메타데이터 보관 정책 확정 |

1차 클라이언트 구현은 시작할 수 있지만, 페르소나 맥락·가드레일·기존 세션 처리·행동 적용 정책이 정해지기 전에는 실제 훈련 서비스로 전환하지 않는다.

## 10. 검증 계획과 완료 기준

| 범위 | 검증할 항목 |
|---|---|
| 요청 DTO | 시나리오·역할·길이·입력·턴·ID, 방어적 복사, 원문 비노출 |
| fake | 결정적 대사·공식 채널 행동 분류·빈 행동·종료 제안·실패 흐름, 원문 재출력 방지 |
| HTTP 계약 | 정확한 새 경로·헤더·JSON, requestId 본문 제외, 성공 봉투 해제 |
| 응답 타입 | 문자열 success/shouldFinish·숫자 reply·비배열 행동·비문자열 요소·필수 필드 누락·초과 길이 거부 |
| mockPage | null 허용, 누락·null 아닌 값은 1차 미지원 처리 |
| 실패 | HTTP 오류·실패 봉투·HTML·시간 초과, 원인 예외/원문 비노출, 자동 재시도 없음 |
| 훈련 변환 | 외부 코드 매핑·미확정 코드만 있는 경우·알려진/미확정 혼합·복수 행동·미확정 메타데이터 보관·의사 표현/실제 이벤트 구분 |
| 서버 규칙 | shouldFinish만으로 종료하지 않음, 공식 확인만으로 연락 종료 간주하지 않음, 복수 행동에도 합의한 조기 종료 유지, 핵심 장면 1회·12턴 기한·15턴 종료 |
| DB·서비스 | AI 호출 중 트랜잭션 부재, 20개 초과 시 최신 선택·오름차순 복원, 과거 1,000자 초과 메시지 제외 후 사용자 요청 지속, 현재 입력 비중복·turnCount 유지, 행동·메시지·상태 원자성, 소유권·버전·중복·만료·동시 요청 |
| 내용 평가 | 페르소나 유지·가상/실제 사실 구분·프롬프트 공격·민감정보·재사용 가능한 수법 차단 |
| 회귀 | AI-01·02·03, 기존 공개 응답·이어하기·훈련 규칙의 영향 확인 |

1차는 기존 `MockRestServiceServer` 패턴으로 계약을 검사하고, 시간 제한은 실제 localhost HTTP 전송으로 확인한다. 3차에서는 `./gradlew dbTest`로 PostgreSQL 저장·충돌·복원을 확인한다.

- `TrainingSessionTest`와 `DiscoveryRulesTest`는 fake AI를 호출하지 않는 도메인 테스트다. TurnInput의 시그니처 변경과 `SUGGEST_KEY_SCENE`·`suggestKeyScene=true`를 직접 넣는 조기 핵심 장면 테스트를 전환 범위로 지정한다. 도메인에서 조기 장면 입력을 유지할지 제거할지에 맞춰 의미를 검토한다.
- `DiscoveryDatabaseTest`는 `@MockitoBean AiClient`를 사용하며, 현재 `suggestTrainingTurn`의 Mockito 응답만 `FakeAiClient`에 위임한다. 1차에서 메서드를 추가해도 기존 서비스 호출은 유지되므로 이 테스트를 전환할 필요는 없다. 3차 서비스 전환 시 `when(ai.replySimulation(any()))`의 응답 설정을 추가해 새 fake 결과를 반환하도록 바꾼다. 이 설정을 빼면 Mockito가 null을 반환하므로 AI 장애와 무관한 테스트 실패가 생길 수 있다.
- FastAPI 테스트에서는 null인 mockPage가 직렬화 후에도 필드로 남는지 확인한다.

**1차 완료:** 새 클라이언트·DTO·fake·계약 테스트가 동작하고 `./gradlew test`가 통과한다. 기존 훈련 호출은 유지된다.

**전체 완료:** 실제 FastAPI·LLM 검증, 시나리오 저장과 맥락 전달, 새 TRN-08 연결, 복수 행동·서버 규칙·DB 검증, 필요 시 모의 페이지 연결까지 완료된다. mock 계약 통과를 LLM 안전성·페르소나 품질 검증으로 대신하지 않는다.

2026-11-16에 1차 클라이언트와 신규 테스트 83개를 구현하고 기본 테스트 전체 192개 통과를 확인했다. FastAPI·LLM·훈련 서비스·DB 연결은 후속 작업이다. 상세 내용은 구현 보고서에 기록했다.
