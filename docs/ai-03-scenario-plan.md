# AI-03 개인화 훈련 시나리오 생성 구현 계획서

작성일: 2026-11-15 · 갱신일: 2026-11-16 · 상태: 1차 Spring 클라이언트 구현 완료, 2·3차 후속 계획

실제 구현 코드·결정 사항·상호작용·검증 결과는 [AI-03 구현·연결 안내](ai-03-scenario.md)를 참고한다. 아래 2장의 현황은 계획 수립 당시 기준이다. 1차에서는 현재 계약의 `SECONDHAND_TRADE`만 지원하고, AI-03 전용 클라이언트와 `scenario-read-timeout` 설정을 구현했다. 기본 응답 제한은 20초다.

## 1. 목표와 구현 범위

상황 파악에서 확인한 사실과 검토된 사례를 바탕으로, 가상 훈련의 제목·설명과 모의 페이지 템플릿 코드를 생성한다.

`POST /internal/ai/scenarios/generate`는 **FastAPI가 제공하고 Spring이 호출하는 내부 API**다. 이 저장소에는 Spring 코드만 있으므로 다음 순서로 진행한다.

| 단계 | 작업 | 완료 결과 |
|---|---|---|
| 1차: 이 저장소 | 요청·응답 DTO, `AiClient` 메서드, fake/HTTP 어댑터, 계약 테스트 | FastAPI가 준비되면 바로 호출할 수 있는 Spring 클라이언트 |
| 2차: AI 저장소 | 내부 인증, 검토 사례 선택, LLM 생성, 출력 검증, 실패 응답 | 실제 AI-03 엔드포인트 |
| 3차: 훈련 연결 | TRN-06 생성·저장, TRN-07 시작 분리, 재개·동시 요청 처리 | 사용자 흐름에서 개인화 시나리오 생성·사용 |

1차와 2차는 계약 확정 후 독립적으로 개발할 수 있다. **1차 완료만으로 개인화 훈련 기능이 완성되는 것은 아니다.** Spring에 같은 내부 경로의 컨트롤러를 추가할 필요는 없다.

## 2. 현재 코드에서 확인한 사항

| 현재 상태 | 구현에 미치는 영향 |
|---|---|
| `AiClient`에 상황 파악·OCR·훈련 응답 메서드가 있음 | 같은 구조로 `generateScenario`를 추가한다. |
| `HttpAiClient`가 내부 키와 연결 2초·응답 20초 타임아웃을 적용함 | 내부 인증 방식은 재사용한다. 20초는 훈련 턴 기준 설정이므로 AI-03 생성에도 적합한지 별도로 결정한다. |
| `AiEnvelope<T>`가 성공·실패 봉투를 표현함 | 응답 봉투는 재사용하되 필수 필드와 JSON 타입을 별도로 검사한다. |
| `TrainingService.submitDiscovery()`가 종료 판정 후 `startSimulation()`을 호출함 | TRN-06·07 연결 시 자동 시작 흐름과 기존 테스트를 함께 수정해야 한다. |
| `TrainingSession`에 `scenarioJson`, `mockPageTemplateCode` 컬럼 매핑이 있음 | 저장 공간은 있지만 결과를 설정·조회하는 도메인 동작은 아직 없다. |
| 명세는 `SECONDHAND_TRADE`, 기존 서비스·YAML은 `USED_GOODS_TRADE`를 사용함 | 유형 코드 표준 또는 명시적인 경계 매핑을 확정해야 한다. |
| `used-goods-seller.yml`은 `reviewed: false`이고 출처·확인일이 없음 | 운영용 검토 사례로 바로 사용할 수 없다. |
| 검토 콘텐츠 로더와 모의 페이지 템플릿 카탈로그가 없음 | 임의의 사례나 템플릿 코드를 그대로 채택하면 안 된다. |
| `TrainingTurnRequest`에 시나리오 맥락이 없음 | 생성·저장 후 AI-04로 전달하는 연결도 별도로 필요하다. |

관련 코드: [AI 클라이언트](../src/main/java/com/wsid/ai/AiClient.java), [훈련 서비스](../src/main/java/com/wsid/training/service/TrainingService.java), [훈련 엔티티](../src/main/java/com/wsid/training/domain/TrainingSession.java), [사례 초안](../src/main/resources/content/scenarios/used-goods-seller.yml).

## 3. API 계약과 확정할 항목

### 요청

```http
POST /internal/ai/scenarios/generate
Content-Type: application/json
X-Internal-Api-Key: <환경 설정값>
X-Request-Id: <현재 생성 요청의 UUID>
```

```json
{
  "facts": {
    "activity": "중고거래"
  },
  "fraudTypeCode": "SECONDHAND_TRADE"
}
```

- `facts`는 객체이며, 기존 `ContextFacts`와 동일한 허용 키를 사용한다: `activity`, `role`, `channel`, `item`, `counterpart`.
- 값은 공백을 제거한 1~100자 문자열만 전달한다. 임의 키·중첩 객체·원문 대화 전체는 보내지 않는다.
- API 자체는 최소 `activity`를 받는 현재 예시를 지원한다. `role`이 없는 직접 호출에서는 판매자·구매자로 임의 확정하지 않고 중립적인 설명을 만든다. 다만 이 프로젝트의 훈련 서비스는 `activity`와 `role`을 확보해 `READY`가 된 경우에만 AI-03을 호출한다. `DEFAULT_SCENARIO`에서는 호출하지 않는다.
- `fraudTypeCode`는 지원 목록에 있는 코드만 허용한다. 지원하지 않는 유형을 다른 유형으로 조용히 바꾸지 않는다.
- `facts`는 신뢰할 수 없는 입력 데이터로 취급한다. 키와 길이 검증만으로 개인정보나 프롬프트 공격을 차단했다고 보지 않는다.
- `X-Request-Id`는 AI-01과 같은 방식으로 Spring과 FastAPI 사이의 요청 추적에 사용한다. 새 논리적 생성 요청에는 새 UUID를 부여하고, 같은 요청을 재시도할 때는 같은 UUID를 유지한다. 현재 어댑터에는 자동 재시도를 추가하지 않는다.
- 요청 ID는 JSON 본문에 넣지 않는다. Java DTO에는 `@JsonIgnore UUID requestId`로 보관하고 HTTP 헤더로만 전달한다. FastAPI는 헤더를 받아 검증하고 처리 결과와 함께 추적한다. 요청 ID 자체가 중복 실행이나 중복 저장을 방지하지는 않는다.

### 성공 응답

```json
{
  "success": true,
  "data": {
    "scenario": {
      "title": "중고거래 안전 확인 훈련",
      "description": "중고거래 과정에서 안전한 확인 방법을 연습하는 가상 상황입니다."
    },
    "mockPageTemplateCode": "BANK_NOTICE"
  }
}
```

| 필드 | 검사 방안 |
|---|---|
| `success` | JSON boolean `true` 필수. 누락·문자열 `"true"`는 실패 처리 |
| `data`, `data.scenario` | JSON 객체 필수 |
| `scenario.title` | 공백이 아닌 문자열. 최대 100자 제안 |
| `scenario.description` | 공백이 아닌 문자열. 최대 1,000자 제안 |
| `mockPageTemplateCode` | 문자열 필수. 1차 어댑터는 형식만 검사한다(대문자·숫자·밑줄, DB 컬럼 `VARCHAR(50)`에 맞춰 1~50자: `[A-Z0-9_]{1,50}`). 카탈로그 등록 여부와 유형별 허용 여부는 FastAPI(2차)가 보장하고, Spring은 3차 저장 전에 확인한다. |

길이 제한은 **구현 제안값**으로 AI 담당자와 확정한다. `BANK_NOTICE`는 명세 예시이며, 모든 중고거래 상황의 기본값으로 강제하지 않는다. 알 수 없는 추가 필드는 사용하지 않고, 필요한 필드가 없거나 타입이 틀리면 결과를 채택하지 않는다.

현재 응답의 제목·설명은 시나리오 개요다. 첫 대사·핵심 장면·평가 기준까지 생성하는 계약은 아니다. 초기 훈련 연결에서는 이 항목들을 검토된 콘텐츠에서 가져오고, AI-03 결과와 함께 사용하는 방식을 정한다.

### 실패 응답

```json
{
  "success": false,
  "error": {
    "code": "AI_SERVICE_UNAVAILABLE",
    "message": "훈련 시나리오를 생성하지 못했어요."
  }
}
```

- 생성 실패·공급자 장애·출력 검증 실패는 `503 + AI_SERVICE_UNAVAILABLE`로 통일하는 방안을 제안한다. 최종 HTTP 상태와 오류 봉투는 AI 저장소와 확정한다.
- 내부 인증 실패는 401, 잘못된 요청은 400 또는 FastAPI 검증 기본값인 422 중 하나로 계약을 확정한다.
- Spring 어댑터는 HTTP 오류, `success=false`, 잘못된 JSON, 필수 필드 누락, 타임아웃을 `AiUnavailableException`으로 변환한다. HTTP 200에 실패 봉투가 와도 성공으로 취급하지 않는다.
- 응답 본문이나 사용자 사실이 섞일 수 있는 HTTP 예외 원문을 원인 예외·로그에 보관하지 않는다.

## 4. 1차: Spring 호출 클라이언트 구현

| 파일 | 변경 계획 |
|---|---|
| `ai/dto/ScenarioGenerateRequest.java` | `Map<String, String> facts`, `fraudTypeCode`, `@JsonIgnore UUID requestId` 요청 record. `ContextFacts`를 import하지 않고 입력을 직접 검사·정규화·방어적 복사하며, 사실 값이 노출되지 않는 `toString` 적용 |
| `ai/dto/ScenarioGenerateResult.java` | `scenario(title, description)`, `mockPageTemplateCode` 결과 record. 시나리오 원문을 `toString`에 출력하지 않음 |
| `ai/AiClient.java` | `ScenarioGenerateResult generateScenario(ScenarioGenerateRequest request)` 추가 |
| `ai/FakeAiClient.java` | 외부 호출 없이 개발용 결정적 시나리오 반환 |
| `ai/HttpAiClient.java` | 목표 경로 POST, 내부 키·`X-Request-Id` 헤더와 JSON 전송, 봉투·필드 타입 검사, 오류 변환 |
| `ai/AiProperties.java`, `ai/AiClientConfig.java`, `application.yml` | AI-03 전용 시간 제한이 필요하다고 결정되면 별도 설정과 생성용 `RestClient` 구성 추가 |
| `src/test/java/com/wsid/ai/` | fake 동작과 HTTP 요청·응답·실패 계약 테스트 추가 |
| `docs/api.md`, `ai/README.md` | 실제 완료 범위에 맞춰 상태·연결 안내 갱신 |

구현 순서:

1. 코드 값·길이·오류 계약을 정하고 합성 입력의 성공·실패 예시를 고정한다.
2. DTO와 인터페이스를 추가한다. 호출자는 `ContextFacts.values()`로 Map을 넘기고, AI 요청 DTO는 훈련 패키지에 의존하지 않고 계약 키·값을 직접 검사한다. 훈련과 AI 계약의 허용 키가 같은지 테스트로 확인한다.
3. fake에서 활동에 맞는 설명을 반환한다. 사용자 입력을 문장에 무조건 삽입하지 않고, 지원하는 표현만 반영한다. 검토 콘텐츠나 실제 LLM 품질을 증명하는 결과로 취급하지 않는다.
4. 응답을 먼저 `JsonNode`로 받아 `success`가 boolean `true`인지, `data`·`data.scenario`가 객체인지, `data.scenario.title`·`data.scenario.description`·`data.mockPageTemplateCode`가 문자열인지 확인한 뒤 `treeToValue`로 변환한다(AI-01 `suggestDiscovery`와 같은 방식). 길이·공백·템플릿 코드 형식도 검사한다. `AiEnvelope<ScenarioGenerateResult>`로 바로 역직렬화해 타입 검사를 대신하지 않는다.
5. 기존 내부 키 방식과 AI-01의 요청 ID 전달 방식을 적용한다. 시간 제한은 8장의 결정 결과에 따라 공유하거나 AI-03 전용으로 분리한다. 자동 재시도는 초기 구현에 넣지 않고 상위 호출자가 대체 흐름을 선택하게 한다.
6. 계약 테스트와 기존 테스트를 실행한다.

### ContextFacts 전달과 요청 검증

훈련 서비스가 소유한 `ContextFacts`에서 Map만 꺼내 전달한다. AI 요청 DTO는 `ContextFacts`를 import하거나 `merge()`를 호출하지 않는다. 운영 코드의 의존 방향은 `training → ai`로 유지한다.

```java
// 3차 훈련 연결 시 TrainingService에서 사용할 호출 예시
ContextFacts facts = session.getContextFacts();
var request = new ScenarioGenerateRequest(facts.values(), fraudTypeCode, requestId);
ScenarioGenerateResult result = aiClient.generateScenario(request);
```

`ScenarioGenerateRequest`의 생성자에서 다음을 검사한다.

- Map이 null이거나 비어 있으면 `IllegalArgumentException`으로 거부한다.
- DTO 내부에 `ALLOWED_KEYS = Set.of("activity", "role", "channel", "item", "counterpart")`를 정의한다. null 키와 목록에 없는 키는 명시적으로 거부한다.
- 값이 null이거나, `strip()` 후 비어 있거나 100자를 초과하면 거부한다. 통과한 값은 앞뒤 공백을 제거한 값으로 새 Map에 담아 전송한다.
- 필수 사실과 유형 코드의 유효성은 3장의 계약을 적용한다. 유형 코드의 null·공백 여부도 검사한다.
- 요청 ID가 null이면 거부한다. 호출자가 현재 논리적 생성 요청의 UUID를 전달하며, HTTP 전송 시도마다 새 UUID를 만들지 않는다.
- 새 Map을 `Map.copyOf()`로 보관해 호출자가 원본 Map을 변경해도 요청 데이터가 바뀌지 않도록 한다.
- `toString()`에는 사실 값 대신 허용된 키 목록만 출력한다.

`ContextFacts.merge()`는 AI가 제안한 잘못된 사실을 **걸러내고 나머지를 누적**하는 역할이다. 반면 AI-03 요청 DTO는 계약에 어긋난 입력을 **예외로 거부**한다. 이 입력 오류를 FastAPI 장애인 `AiUnavailableException`으로 바꾸거나 조용히 필드를 삭제해 전송하지 않는다.

허용 키는 두 패키지에 각각 선언하되 테스트에서 아래와 같이 일치 여부를 확인한다. 테스트 코드에서 두 패키지를 import하는 것은 운영 코드의 역방향 의존성을 만들지 않는다.

테스트 메서드 내부의 비교문:

```text
assertEquals(ContextFacts.ALLOWED, ScenarioGenerateRequest.ALLOWED_KEYS);
```

이 테스트를 기존 `test` 작업에 포함해 한쪽 목록만 바뀌는 변경을 검출한다. 값의 길이 경계·공백 제거·잘못된 입력 거부·방어적 복사·`toString` 원문 비노출도 DTO 테스트에서 확인한다.

### 요청 추적과 생성 시간 제한

- `HttpAiClient`는 `request.requestId().toString()`을 `X-Request-Id` 헤더에 넣는다. FastAPI의 요청 처리와 Spring의 호출 결과를 같은 ID로 연결하고, 사실·생성 원문은 추적 로그에 넣지 않는다.
- AI-03이 기존 응답 제한 20초 안에 완료되어야 하는지, 별도 대기 시간을 허용할지는 공급자 처리 시간과 화면의 허용 대기 시간을 기준으로 결정한다. 이 계획서에서 더 긴 시간을 임의로 확정하지 않는다.
- 별도 제한이 필요하면 `wsid.ai.scenario-read-timeout` 같은 전용 설정과 AI-03용 `RestClient`를 구성한다. 기존 AI-01·02·04의 공용 시간 제한을 일괄 늘리거나 공유 클라이언트의 설정을 호출 중에 바꾸지 않는다. 설정 이름과 연결 제한 공유 여부는 8장에서 확정한다.
- 생성용 클라이언트를 분리해도 내부 키·요청 ID 전달과 테스트용 빌더 주입을 유지한다. FastAPI 처리 예산은 Spring 대기 시간 안에서 끝나도록 정하고, 프론트·프록시의 시간 제한도 함께 확인한다.

1차에서는 훈련 서비스 호출·DB 저장·공개 API·상황 파악 자동 시작 동작을 변경하지 않는다. 어댑터의 형식 검증과 실제 콘텐츠 적합성 검증은 구분하며, 운영 사용 전에 2차의 검토 콘텐츠·템플릿 검증을 갖춰야 한다.

## 5. 2차: FastAPI 생성 파이프라인

아래 파일은 AI 저장소에서 사용할 **제안 구조**다. 이 Spring 저장소에 Python 서버를 추가하는 계획은 아니다.

| 구성 | 역할 |
|---|---|
| `schemas/scenario.py` | 요청·응답의 타입, 길이, 허용 코드 정의. `success`는 문자열이 아닌 boolean으로 정의 |
| `routers/scenarios.py` | 내부 인증과 `X-Request-Id` 확인 후 서비스 호출, 요청 추적, 성공·실패 봉투 반환 |
| `services/scenario_generator.py` | 사례 선택 → 생성 → 검증 조율 |
| `services/reviewed_scenario_catalog.py` | 검토 완료·출처·확인일·버전이 있는 사례만 로딩 |
| `services/scenario_validator.py` | 스키마·유형 일치·허용 템플릿·금지 콘텐츠 검증 |
| LLM 어댑터·프롬프트 | 공급자 호출과 시간 제한, 사실·사례 범위 안의 개요 생성 |

### FastAPI 구현 시 반영할 응답 타입 규칙

사용자 요청에 따라 아래 규칙은 **나중에 FastAPI를 개발하는 디렉토리의 응답 스키마·라우터·공통 오류 처리 코드에 반영한다.** 현재는 이 계획서에 기록하며, 실제 AI 저장소 경로가 정해지면 그 구조에 맞춰 적용한다.

- `success`는 LLM 출력에서 가져오지 않고 FastAPI 서버가 처리 결과에 따라 설정한다.
- 성공 응답은 Python의 `True`, 실패 응답은 `False`를 사용해 JSON boolean `true`·`false`로 반환한다. 문자열 `"true"`·`"false"`를 넣지 않는다.
- 응답 스키마에서 타입을 명시하고, 라우터와 공통 오류 처리에서도 동일한 규칙을 적용한다.
- FastAPI 테스트에서 실제 반환 JSON의 `success`가 문자열이 아닌 boolean인지 확인한다.
- Spring의 원본 JSON 타입 검사는 수신 검증으로 별도 유지한다. FastAPI에서 올바른 타입을 생성하는 책임을 대신하지 않는다.

처리 순서:

1. 내부 키, `X-Request-Id`, 요청 스키마를 확인한다.
2. 개인정보 처리 기준에 따라 사실을 검사·최소화하고, 필요한 필수 사실이 충족되는지 판단한다.
3. `fraudTypeCode`와 사실에 맞는 검토 사례를 서버 카탈로그에서 선택한다. 요청에 사례 본문이 없으므로 FastAPI 측에서 사례를 확보해야 한다.
4. 검토 사례가 없으면 임의 생성하지 않고 사용 불가로 응답한다. 초기에는 별도 검색 인프라 없이 작은 버전 관리 카탈로그로 시작한다.
5. 시스템 지시와 사용자 사실을 분리해 LLM에 전달한다. 사용자 사실 속 명령은 수행하지 않는다.
6. 제목·설명만 구조화해 생성하고, 사례에 허용된 템플릿 코드와 조합한다. 템플릿 코드는 서버가 최종 검증한다.
7. 형식과 의미를 검증한 결과만 반환한다. 실패 시 미검증 결과를 내려주지 않는다.

생성 내용은 안전 확인을 연습하는 가상 상황으로 제한한다. 실제 계좌·전화번호·접속 가능한 유도 링크, 인증정보 수집, 실행 가능한 사기 절차·공격 코드·정교한 설득 대본은 포함하지 않는다. 개인화는 활동·역할·물품 등 필요한 맥락에 한정하고, 불명확한 사실을 만들어내지 않는다.

프롬프트 지시와 문자열 검사만으로 위 조건이 보장되지는 않는다. 합성 평가 세트와 검토자의 내용 점검을 통과해야 운영에 사용한다. 선택한 사례 ID·버전, 프롬프트 버전, 검증 결과는 서버에서 추적하되 사용자 사실과 생성 원문은 로그에 남기지 않는다. 내부 추적을 위해 공개 응답 필드를 즉시 늘릴 필요는 없다.

## 6. 3차: 훈련 흐름 연결

목표 흐름은 다음과 같다.

```text
AI-01에서 여러 답변의 사실을 누적
  ├─ ASKING: 추가 질문 후 사실 누적 계속
  ├─ READY: 필수 사실 확보 → TRN-06에서 AI-03 호출 → 검증 후 저장
  └─ DEFAULT_SCENARIO: 질문 한도에도 정보 부족 → TRN-06에서 검토된 기본 시나리오 저장
저장 완료
  → TRN-07: 저장된 시나리오 확인 → 첫 대사 저장 → SIMULATION 진입
  → AI-04: 저장된 사실·시나리오·필요한 대화 맥락으로 훈련 진행
```

- 기존의 여러 번 질문하고 사실을 누적하는 방식을 유지한다. 현재 설정은 최소 답변 3회·추가 질문 최대 5회·필수 사실 `activity`, `role`이다. 질문 한도 전에는 필수 사실이 부족하다고 즉시 기본 시나리오로 넘어가지 않는다.
- TRN-06의 호출 분기는 고정한다. `READY`이면 사실과 유형별 사례 조건을 확인해 AI-03을 호출하고, `DEFAULT_SCENARIO`이면 AI-03을 호출하지 않고 검토된 기본 시나리오를 선택한다. 기본 시나리오의 가상 역할을 사용자가 밝힌 사실로 저장하지 않는다.
- `submitDiscovery()`는 마지막 답변·사실·완료 상태를 저장한 뒤 반환하도록 변경한다. 현재의 `startSimulation()` 자동 호출은 TRN-06·07 전환과 함께 제거한다.
- 첫 단계에서는 `DISCOVERY + READY/DEFAULT_SCENARIO`와 시나리오 저장 여부로 준비 상태를 구분할 수 있다. 별도 진행 단계나 생성 중 상태가 필요한지는 프론트 계약과 함께 결정한다.
- TRN-06은 로그인·소유권·만료·단계·버전을 검사한 뒤 필요한 사실을 읽는다. 유형 선택은 서버의 검토된 매핑을 사용하며, 기존 고정 유형 코드를 그대로 모든 사실에 적용하지 않는다.
- AI 호출은 DB 트랜잭션 밖에서 수행한다. 저장 직전 소유권·만료·단계·버전을 다시 검사하고 시나리오와 템플릿 코드를 같은 트랜잭션에서 저장한다.
- 생성 완료 후 재요청은 저장된 결과를 반환하는 방향을 제안한다. 동시 생성 요청은 버전 충돌로 한 결과만 저장되게 하며, 중복 LLM 호출까지 방지하는 작업 잠금은 초기 범위에서 제외한다.
- TRN-06의 생성 요청 ID를 AI-03의 `X-Request-Id`로 전달한다. TRN-06의 요청 ID 필드와 재전송 처리는 공개 계약에서 확정한다. 현재 메시지용 `client_request_id`나 추적 헤더만으로 시나리오 생성의 멱등성까지 처리됐다고 보지 않는다.
- TRN-07은 시나리오가 저장된 경우에만 시작한다. 반복 요청으로 첫 대사나 턴 수가 중복 반영되지 않도록 한다.
- `scenario_json`, `mock_page_template_code`, `fraud_type_code`는 기존 컬럼을 활용한다. 출처 추적 정보를 DB에 보존할 경우 JSON 메타데이터와 별도 컬럼 중 하나를 정하고, 스키마 변경이 필요하면 새 Flyway 마이그레이션을 추가한다.
- 재개 응답에 필요한 제목·설명·준비 상태를 추가하고 AI-04 요청에도 시나리오 맥락을 연결한다. `title`·`description`을 상대의 첫 대사로 사용하지 않는다.
- AI 장애 또는 필수 사실 부족 시에는 검토 완료된 기본 시나리오가 있을 때만 대체한다. 대체 콘텐츠도 없으면 생성 불가를 안내하고, 미검토 YAML을 성공 결과로 사용하지 않는다.

## 7. 검증 계획

| 범위 | 주요 검증 | 확인 가능한 결과 |
|---|---|---|
| DTO·fake | 훈련·AI 허용 키 목록 일치, 잘못된 입력의 명시적 거부, 공백 제거·길이 경계·방어적 복사, 누락·잘못된 코드, 동일 입력의 결정성, 맥락에 맞는 설명, `toString` 원문 비노출 | 개발용 동작·입력 경계 |
| Spring HTTP 계약 | 정확한 경로·메서드·내부 키·`X-Request-Id`, JSON 본문에서 requestId 제외, 같은 요청 ID 재전달, 정상 봉투, 추가 필드 | 합의한 메시지 형식과 요청 추적 |
| Spring 실패 처리 | `success=false`, data/scenario 누락, 빈 문자열·길이 초과, 잘못된 타입·JSON·코드, HTTP 오류·타임아웃 | 사용 불가 변환과 응답 본문 비노출 |
| 시간 제한 분리 시 | 짧은 테스트용 제한으로 AI-03 응답 지연 실패 확인, 기존 AI API의 제한 유지 확인 | 생성용 제한 적용과 기존 호출 영향 확인 |
| FastAPI | 내부 인증, 지원 유형, 검토 사례 없음, 템플릿 불일치, 공급자 실패, 출력 검증 실패 | 서버의 입력·출력·실패 정책 |
| 콘텐츠 평가 | 역할 없는 사실, 구매자/판매자 차이, 사실 속 지시문, 개인정보, 실행 가능한 수법 생성 유도 | 개인화 적합성과 가상 훈련 제한 |
| 훈련 연결 | 여러 답변의 사실 누적 유지, READY에서 AI-03 호출, DEFAULT_SCENARIO에서 AI-03 미호출, 생성·저장·재개·시작, 검토된 대체 결과, 타인 접근·만료·상태 변경·중복·동시 요청 | 사용자 흐름과 DB 일관성 |

1차는 기존 AI-01의 `MockRestServiceServer` 방식으로 계약을 검증한다. 실제 FastAPI와 연결할 때는 합성 입력으로 양 서버의 HTTP 왕복을 별도 검증한다. mock 테스트 통과와 실제 LLM 품질 검증은 별개다.

Spring 검증 명령은 `./gradlew test`이며, DB 연결 단계에서는 별도 PostgreSQL 테스트 환경에서 `./gradlew dbTest`도 실행한다. 3차에서는 현재 상황 파악 완료 즉시 `SIMULATION`으로 넘어간다고 가정하는 `DiscoveryDatabaseTest` 등도 새 흐름에 맞춰 변경한다.

## 8. 착수 전 결정과 완료 기준

| 결정할 항목 | 권장안 |
|---|---|
| 유형 코드 | 명세의 `SECONDHAND_TRADE`를 목표로 기존 코드·콘텐츠·기존 데이터의 영향 확인 후 통일하거나 경계 매핑 명시 |
| 템플릿 코드 | 허용 코드와 유형별 매핑을 서버 카탈로그로 관리. `BANK_NOTICE`의 실제 제공 여부 확인 |
| 검토 사례 | 초안 YAML을 내용 검토하고 출처·확인일·버전 추가. 미검토 상태는 운영 선택 대상에서 제외 |
| 스키마·오류 | 제목 100자·설명 1,000자 제안값, 필수 사실, 503 오류 봉투를 AI 담당자와 확정 |
| 생성과 훈련의 연결 | 1차는 호출 어댑터까지, 3차에서 TRN-06·07과 프론트 호출 순서를 함께 전환 |
| 요청 ID | AI-01과 같이 `X-Request-Id` 사용. 같은 논리적 생성 요청의 재시도에는 같은 UUID 유지. TRN-06의 ID 전달·중복 처리 계약 확정 |
| LLM | 공급자·모델 선정 후 구조화 출력과 개인정보 처리 조건 확인 |
| AI-03 시간 예산·클라이언트 분리 | 1차 결정: 전용 `RestClient`와 `wsid.ai.scenario-read-timeout` 구현, 기본 20초·연결 제한은 기존 설정 공유. 실제 생성 처리 시간을 확인해 운영 제한을 확정하고 FastAPI·프론트·프록시의 예산을 함께 정렬 |

**1차 완료 기준:** DTO·인터페이스·fake/HTTP 어댑터가 계약대로 동작하고 기존 테스트가 통과하며, 실제 FastAPI·훈련 연결의 미완료 상태가 문서에 표시되어 있다.

**기능 전체 완료 기준:** 검토 사례 기반 생성, 실제 FastAPI 연결, 시나리오 저장·복원·훈련 시작, 장애 시 검토된 대체 흐름, 콘텐츠 평가와 동시 요청 검증까지 완료되어 있다.

1차 DTO·fake/HTTP 클라이언트와 설정을 구현했으며, 2026-11-16 기본 테스트 109개가 모두 통과했다. 실제 FastAPI·LLM·TRN-06·07·DB 저장 연결은 후속 작업이다. 상세한 구현 결과와 검증 범위는 구현·연결 안내에 기록했다.
