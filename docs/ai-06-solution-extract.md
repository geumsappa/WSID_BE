# AI-06 피해 사실 추출 구현 보고서와 연동 개발 안내

작성일: 2026-11-16 · 상태: Spring 클라이언트 구현·검증 완료, 상담 서비스·FastAPI 미연결

단계별 개발 순서·검증 기준·후속 결정 항목은 [구현·테스트 계획서](ai-06-solution-extract-plan.md)에 정리했다. 계획서는 1차 구현 후 보완한 문서다.

명세 출처는 팀 API 전체 명세 `WSID-API-Spec.md`의 AI-06 및 SOL-01·03·04 절(2026-11-16 제공본)이다. 해당 명세는 팀 공유 파일이며 이번 변경에 포함하지 않는다. 아래의 길이 제한·전체 사실 반환·불확실성 일치 규칙은 이번 Spring 어댑터가 채택한 계약이다. FastAPI 개발 시 같은 규칙으로 합의해야 한다.

## 1. 구현 범위와 현재 동작

`AiClient.extractSolutionFacts(SolutionExtractRequest)`를 추가했다. 피해 설명과 이미 확인한 사실을 FastAPI에 보내고, 응답 타입·허용 값·미확정 항목의 일관성을 검사해 `SolutionExtractResult`로 반환한다. 기본 fake 모드에서는 외부 호출 없이 개발용 결과를 반환한다.

**클라이언트만 구현했다. 현재 SOL API에서 자동으로 호출되거나 DB에 저장되는 기능은 없다.** 상담 서비스·컨트롤러·사실 병합·사용자 수정·질문 선정·대응 카드 선택·비회원 소유권은 후속 작업이다. 기존 훈련 서비스, 설정, DB 마이그레이션은 변경하지 않았다.

단계별 책임은 다음과 같다.

| 단계 | 책임 | 현재 상태 |
|---|---|---|
| 1차 Spring AI 어댑터 | 요청 DTO, fake/HTTP 호출, 응답 구조 검증, 실패 변환 | 완료 |
| 2차 FastAPI | 설명의 의미 분석, 추정 방지, 개인정보 최소 처리, 계약에 맞춘 출력 | 별도 저장소에서 개발 필요 |
| 3차 Spring 상담 연결 | 소유권·중복·버전 확인, 사실 병합, 사용자 선택, 검토된 카드 선택·저장 | 미구현 |

## 2. 데이터가 이동하는 경로

```text
현재 구현한 구간
호출자 → SolutionExtractRequest → AiClient
                               ├─ FakeAiClient → 고정 개발용 결과
                               └─ HttpAiClient → FastAPI → JsonNode 검사
                                                        → SolutionExtractResult

후속 연결 구간
SOL 서비스 → 필요한 설명 + 현재 facts의 Map → AI-06
          ← 구조화된 제안 결과
          → 사용자 확정값·동시 수정과 대조 → 도메인 사실 갱신
          → ResponseRules → 검토된 카드 조회 → DB 저장·공개 응답
```

요청·응답 DTO는 메모리에서 데이터를 검증하고 전송하는 객체다. DTO 자체가 DB를 조회하거나 저장하지 않는다. `ai` 패키지는 `solution` 패키지의 엔티티나 repository를 import하지 않는다. 후속 상담 서비스가 도메인 사실을 `Map<String, String>`으로 꺼내 요청을 만들고, 응답을 도메인 규칙에 전달한다.

## 3. 요청 계약

경로는 `POST /internal/ai/solutions/extract`이고 JSON 필드 이름은 camelCase다.

```json
{
  "description": "송금 후 연락이 끊겼어요.",
  "knownFacts": {}
}
```

| 값 | 검사·처리 |
|---|---|
| `description` | 필수 문자열, 공백만 있는 값 거부, 최대 1,000자. Java `String.length()` 기준 |
| `knownFacts` | 필수 Map. 첫 요청의 빈 Map과 일부 키만 있는 Map 허용 |
| 사실 키 | `transferredMoney`, `contactContinues`, `sharedInformation`, `installedApp`만 허용 |
| 사실 값 | 정확히 `YES`, `NO`, `UNKNOWN` 문자열만 허용. 소문자·주변 공백·null 거부 |
| `requestId` | Java 생성 시 필수 UUID. `@JsonIgnore`로 JSON에서 제외하고 `X-Request-Id` 헤더로 전송 |
| 내부 인증 | 기존 `X-Internal-Api-Key` 헤더 사용 |

잘못된 요청은 조용히 항목을 버리지 않고 `IllegalArgumentException`으로 거부한다. `knownFacts`를 방어적으로 복사해 이후 원본 변경의 영향을 받지 않는다. 설명의 의미가 바뀌지 않도록 DTO가 공백 제거·문장 요약을 하지 않는다.

**DTO에는 개인정보 마스킹 기능이 없다.** 설명은 전달받은 문자열 그대로 HTTP 본문에 들어간다. 실제 서비스 연결 전에 호출자가 불필요한 계좌번호·전화번호·주민번호 등을 제거하고 필요한 상황만 전달하도록 처리해야 한다. 요청 길이 제한은 개인정보 처리나 모델의 입력 안전성 검사를 대신하지 않는다.

요청 ID는 추적 식별자이며 원문·사용자 식별자를 섞어 만들지 않는다. 자동 재시도는 없다. 후속 서비스에서 같은 논리 요청을 재시도하면 동일 ID로 연결하고, 시도 구분이 필요하면 별도 시도 번호를 원문 없이 기록한다. 헤더를 붙였다고 FastAPI나 DB의 중복 방지가 구현되는 것은 아니다.

## 4. 응답 계약과 엄격한 검증

```json
{
  "success": true,
  "data": {
    "facts": {
      "transferredMoney": "YES",
      "contactContinues": "NO",
      "sharedInformation": "UNKNOWN",
      "installedApp": "UNKNOWN"
    },
    "uncertainFields": ["sharedInformation", "installedApp"]
  }
}
```

`HttpAiClient`는 응답을 DTO로 바로 변환하지 않는다. 먼저 `JsonNode`로 다음을 확인한다.

1. 루트가 객체이고 `success`가 실제 JSON boolean `true`인지 확인한다. 문자열 `"true"`와 숫자 `1`은 거부한다.
2. `data`·`facts`가 객체이고 `uncertainFields`가 배열인지 확인한다.
3. `facts`는 허용된 4개 키를 모두 포함하고 다른 키가 없어야 한다. 각 값은 실제 JSON 문자열이어야 한다. boolean·숫자·null을 문자열로 변환해 받아들이지 않는다.
4. `uncertainFields`의 각 요소가 실제 문자열인지 확인한다.
5. `treeToValue`로 변환하면서 결과 DTO의 값·일관성 검사를 수행한다.

`SolutionExtractResult`는 추가로 다음을 보장한다.

- 사실 값은 `YES`·`NO`·`UNKNOWN`만 허용한다.
- `uncertainFields`는 중복 없이 **값이 UNKNOWN인 사실 키의 집합과 정확히 같아야 한다.** 배열 순서는 의미를 가지지 않는다.
- 전부 확정된 경우에도 `uncertainFields: []`를 반드시 보낸다. 필드 생략과 null은 실패다.
- 결과 Map과 List는 방어적 복사 후 불변으로 보관한다.
- `data`의 추가 필드는 무시한다. DTO는 설명·근거 원문 필드를 제공하지 않는다. `facts` 안의 추가 키는 거부한다.

응답은 전체 4개 항목이고 요청 `knownFacts`는 부분 Map일 수 있다는 차이에 주의한다. 응답은 여전히 제안이며 기존 사실에 자동 병합되지 않는다. 올바른 형식의 `YES`라도 문장에서 확인되지 않은 추정일 수 있다. Spring 어댑터가 문장의 진위를 판정하지는 않는다.

이번 새 타입 검사는 Jackson 3의 `isString()`을 사용한다. 기존 AI-01~05의 deprecated API 사용을 일괄 수정하지 않았다.

## 5. 실패·설정·원문 보호

| 상황 | 현재 처리 |
|---|---|
| null 요청·DTO 계약 위반 | `IllegalArgumentException`; 호출자의 입력 구성 문제 |
| HTTP 오류, 연결/읽기 제한 초과, 잘못된 JSON, 응답 계약 위반 | `AiUnavailableException` |
| HTTP 200 + `success:false` | 사용 불가 예외 |
| 설명에서 확인할 사실이 없음 | 정상 응답 가능. 확인되지 않은 사실은 `UNKNOWN` |
| 기존 사실과 AI 결과가 충돌 | 어댑터에서 덮어쓰지 않음. 후속 도메인·서비스에서 처리 |

원인 예외에 HTTP 본문이 포함될 수 있어 `AiUnavailableException`에 cause를 연결하지 않는다. 오류 문구는 고정된 설명만 제공한다. DTO의 `toString()`도 설명·사실 값 대신 길이·개수·요청 ID만 표시한다. HTTP 요청/응답 원문을 기록하는 코드는 추가하지 않았다.

AI-06은 기존 공통 `restClient`를 사용한다. 연결 제한은 `connectTimeout`(기본 2초), 응답 제한은 `readTimeout`(기본 20초)이다. AI-03의 `scenarioReadTimeout`, AI-05의 `evaluationReadTimeout`과 별개다. 새 설정이나 생성자 변경은 없다. 실제 지연 측정 후 별도 제한이 필요하면 AI-06 전용 설정·클라이언트와 분리 테스트를 함께 추가한다.

원문 보호는 DTO의 `toString()`만으로 끝나지 않는다. 후속 FastAPI·LLM SDK·프록시·APM의 본문 수집 및 예외 로그 설정도 확인해야 한다. 원문·추출 근거는 DB와 로그에 저장하지 않고, 운영 추적에는 요청 ID·성공/실패 분류·처리 시간·항목 개수만 사용한다. 이 운영 설정과 실제 개인정보 제거 기능은 이번 구현에 포함하지 않았다.

## 6. FakeAiClient가 하는 일

`description.strip()`으로 앞뒤 공백을 제거한 뒤 개발용 문장 전체가 일치하는 경우만 고정 결과를 만든다. 예시 문장은 `송금 후 연락이 끊겼어요.`, `송금하지 않았어요.`, `송금하려다 멈췄어요.`, 개인정보 전달/미전달, 앱 설치/미설치, 연락 지속 문장이다. `[AI_FAIL]`을 포함하면 실패를 재현한다.

예시 이외의 설명에서는 `knownFacts`를 유지하고 없는 항목을 `UNKNOWN`으로 채운다. `송금`이라는 단어가 들어갔다는 이유만으로 YES를 만들지 않는다. 정확한 개발용 문장이 기존 사실과 다르면 해당 필드의 다른 값을 제안할 수 있다. 이것도 DB 수정이 아니며, 사용자 확정값과의 충돌 정책은 후속 서비스가 적용해야 한다.

fake의 부정·불확실 문장 테스트는 고정 규칙의 회귀 검사다. 실제 LLM의 추출 정확도·개인정보 보호·프롬프트 공격 방어를 검증한 결과로 해석하면 안 된다.

## 7. 현재 파일별 상호작용

아래 링크는 현재 존재하는 파일이다.

| 파일 | 현재 역할 | 나중에 수정할 때 참고할 점 |
|---|---|---|
| [AiClient.java](../src/main/java/com/wsid/ai/AiClient.java) | `extractSolutionFacts` 인터페이스 | 상담 서비스는 이 인터페이스만 호출. 반환값은 사실 제안이며 카드·저장 결과가 아님 |
| [SolutionExtractRequest.java](../src/main/java/com/wsid/ai/dto/SolutionExtractRequest.java) | 설명·부분 knownFacts·요청 ID 검증 | `solution` 객체를 import하지 말고 Map 사용. 항목/길이 변경 시 FastAPI와 동시 변경 |
| [SolutionExtractResult.java](../src/main/java/com/wsid/ai/dto/SolutionExtractResult.java) | 4개 사실·미확정 집합 검증 | UNKNOWN과 uncertainFields 일치 유지. 도메인의 병합 정책을 이 DTO에 넣지 않음 |
| [HttpAiClient.java](../src/main/java/com/wsid/ai/HttpAiClient.java) | 경로·헤더·JsonNode 타입 검사·변환·실패 처리 | 계약이 바뀌면 DTO뿐 아니라 사전 타입 검사·HTTP 계약 테스트도 함께 변경 |
| [FakeAiClient.java](../src/main/java/com/wsid/ai/FakeAiClient.java) | 외부 AI 없는 개발·실패 재현 | 자유 문장 키워드 추정기로 확장하지 말고 필요한 합성 예시를 명시 |
| [AiClientConfig.java](../src/main/java/com/wsid/ai/AiClientConfig.java) | 기존 fake/http 모드 선택 | 수정 없이 새 인터페이스 구현을 사용. 기본 fake는 실제 AI 호출을 하지 않음 |
| [AiProperties.java](../src/main/java/com/wsid/ai/AiProperties.java), [application.yml](../src/main/resources/application.yml) | 기존 내부 키·URL·공통 타임아웃 | AI-06 전용 설정은 아직 없음. 설정 변경 시 다른 공통 API의 제한도 함께 영향받음 |
| [AiUnavailableException.java](../src/main/java/com/wsid/ai/AiUnavailableException.java) | 호출 불가 공통 예외 | 호출 서비스가 직접 선택 흐름으로 전환. 예외에 원문·cause를 다시 붙이지 않음 |

## 8. 후속 Spring 상담 디렉토리에서 할 일

현재 엔티티·repository는 있지만 SOL 서비스·컨트롤러는 없다. 아래의 `SolutionFacts`, `ResponseRules`, 서비스 이름은 **신규 개발 제안**이며 구현된 클래스가 아니다.

| 기존 파일/개발 위치 | 필요한 처리 |
|---|---|
| [solution/domain/SolutionSession.java](../src/main/java/com/wsid/solution/domain/SolutionSession.java) | `factsJson` 기본값은 `{}`. 사실 값 객체·변경 메서드·완료 정책·카드/질문 갱신 규칙 추가. 응답 전체를 무조건 대입하지 않음 |
| `solution/domain/SolutionFacts`, `ResponseRules` (제안) | 허용 사실·사용자 확정/AI 제안 출처·충돌 규칙 정의. 검토된 조건으로 카드·긴급 여부·다음 질문 결정 |
| [solution/domain/SolutionMessage.java](../src/main/java/com/wsid/solution/domain/SolutionMessage.java) | `safeContent`에는 개인정보 제거 후 내용만 저장. `of`는 현재 마스킹을 하지 않으므로 호출자 처리 필요. selectedCode 설정·검증 경로도 추가 필요 |
| [solution/repository/SolutionSessionRepository.java](../src/main/java/com/wsid/solution/repository/SolutionSessionRepository.java) | 현재 `findByIdAndUserId`는 회원 소유권만 처리. 비회원 식별·소유권 조회는 별도 설계 |
| [solution/repository/SolutionMessageRepository.java](../src/main/java/com/wsid/solution/repository/SolutionMessageRepository.java) | 요청 UUID 중복 조회와 순서 조회 존재. 조회만으로 중복 쓰기를 막을 수 없으므로 트랜잭션·유일성 보장 함께 설계 |
| `solution/service/` (제안) | 소유권·상태 확인, 입력 최소 처리, AI 호출, 충돌 해결, 도메인 규칙 적용, 구조화된 사실 저장 |
| `solution/controller/` (제안) | SOL-01~05 요청 검증·공개 응답. 내부 AI 응답/오류를 그대로 프론트에 노출하지 않음 |
| [content/README.md](../src/main/java/com/wsid/content/README.md), [response-cards/README.md](../src/main/resources/content/response-cards/README.md) | 검토된 카드 로더·조회 구현. 기관·전화·링크는 여기서 조회하고 AI가 생성한 값을 사용하지 않음 |

### 8.1 여러 번 정보를 받는 방식

각 AI 호출은 상태를 기억하지 않는 계약이다. SOL-01의 최초 설명 또는 SOL-03의 새 자유 입력을 처리할 때 현재 사실을 `knownFacts`로 함께 보낸다. DB에 누적한 구조화된 사실로 이어가며 과거 설명 원문 전체를 다시 보낼 필요는 없다.

SOL-03의 선택지 입력과 SOL-04의 직접 사실 수정은 이미 명시적인 사용자 답이다. AI로 다시 추출할 필요 없이 도메인 검증 후 적용한다. SOL-03 선택 요청의 `factKey`가 현재 질문의 항목과 같고, `selectedCode`가 해당 질문의 허용 선택지에 속하는지도 확인한다. 공개 API 초안의 CHOICE와 내부 `InputKind.OPTION`은 별도 값이므로 컨트롤러에서 명시적으로 매핑해야 한다.

### 8.2 사실 병합 정책 — 후속 구현 기준

- 입력에서 언급되지 않은 항목은 기존 확정값을 유지한다. AI의 `UNKNOWN`으로 기존 YES/NO를 지우지 않는다.
- 사용자가 선택하거나 직접 수정한 값은 AI 제안으로 조용히 덮어쓰지 않는다.
- 기존 값과 다른 YES/NO가 제안되면 재확인 질문을 만들고 확정 전에는 기존 상태를 유지한다. 사용자가 직접 수정한 경우에만 명시적인 수정 규칙을 적용한다.
- 현재 저장 구조에 확정 출처·수정 시각·질문 맥락이 없으므로 이를 보관할 도메인/저장 형식을 먼저 정한다. `knownFacts` Map만으로 출처를 구분할 수 없다.
- AI 실패 시 사실을 전부 UNKNOWN으로 초기화하지 않는다. 기존 상태를 유지하며 미확정 항목을 직접 선택하게 한다.

이 정책은 이번 어댑터의 자동 처리 기능이 아니다. 후속 도메인 테스트와 서비스 연결에서 구현해야 한다. `SolutionSession` 주석에는 한글 키 예시가 남아 있다. 실제 DB에 옛 형식이 저장됐는지 확인하고, 새 canonical 키·값으로 읽는 호환 처리나 마이그레이션 필요 여부를 결정한다. 이번에 저장 형식을 변경하지 않았다.

### 8.3 트랜잭션·소유권·카드 선택

짧은 읽기 트랜잭션에서 소유권·상담 상태·현재 사실·버전을 확인하고, 트랜잭션 밖에서 AI를 호출한다. 저장 트랜잭션에서는 소유권·완료 상태·버전·요청 중복을 다시 확인한다. 호출 중 사용자가 사실을 수정했다면 오래된 결과를 그대로 저장하지 않는다. 충돌 반환·재구성 등 정책을 정하고 `@Version` 검사를 사용한다.

현재 `solution_sessions.user_id`는 NOT NULL이고 회원 외래키를 사용한다. 로그인 불필요 목표 정책만으로 비회원이 지원되는 것은 아니다. 비회원 소유자 식별·복원·만료·DB 구조를 별도 설계해야 한다. 상담 ID만으로 타인의 사실에 접근하게 해서는 안 된다.

대응 카드와 긴급 여부는 Spring의 검토된 규칙으로 결정한다. 이미 확인한 사실만으로 긴급 안내가 필요하면 LLM 완료를 기다리지 않도록 흐름을 구성한다. AI-06은 카드·기관·연락처를 반환하지 않는다. 후속 AI-07 설명 생성과도 역할이 다르다.

## 9. FastAPI 개발 디렉토리에 반영할 계약

이 저장소에는 FastAPI 코드가 없다. 아래 Python 경로는 AI 저장소에서 사용할 **역할별 제안 경로**다. 기존 AI 프로젝트 구조가 있으면 같은 책임을 해당 위치에 적용한다.

| 제안 위치 | Spring 대응 코드 | 반드시 처리할 내용 |
|---|---|---|
| `schemas/solution_extract.py` | 요청·결과 DTO | description 최대 길이, knownFacts 빈/부분 Map 허용, 응답 전체 4개 필드, 정확한 문자열 enum과 필수 배열 정의 |
| `routers/solutions.py` | HttpAiClient | `/internal/ai/solutions/extract`, 내부 키 검증, X-Request-Id 전달, 성공/실패 봉투 |
| `services/solution_extractor.py` | 의미 분석 책임 | 설명·knownFacts만 사용. 추측·외부인 사례·가정·미래 계획을 이미 일어난 사실로 바꾸지 않음. 모르면 UNKNOWN |
| `services/solution_output_validator.py` | JsonNode·결과 DTO 검사와 대응 | 사실 키·값·미확정 집합 검증. 잘못된 모델 출력을 정상 성공 봉투로 보내지 않음 |
| 공통 인증·오류·로깅 설정 | 내부 키·AiUnavailableException | 요청 ID로 추적, 본문/모델 원문/추출 근거 로그 금지, 고정 오류 코드. 공급자 데이터 보관·APM 설정 확인 |
| `tests/test_solution_extract.py` | Spring 계약 테스트 | 필수 필드·엄격한 타입·상태 코드와 의미 분석 사례를 별도 검증 |

구현 전에 합의할 상세 항목은 다음과 같다.

1. `success`는 문자열이 아닌 실제 boolean으로 직렬화한다. `"true"`를 허용하는 입력/출력 강제 변환 설정을 피한다.
2. facts 값은 boolean이 아니라 `YES`·`NO`·`UNKNOWN` 문자열이다. 4개 필드를 매번 반환한다. 확실한 부정과 확인되지 않음을 구분한다.
3. `uncertainFields`는 UNKNOWN인 키와 정확히 일치하며 중복이 없어야 한다. 모두 확정되어도 `[]`를 생략하지 않는다. `exclude_defaults` 등 직렬화 옵션으로 필수 필드를 빼지 않는다.
4. 요청 1,000자 제한은 이번 Spring 기준이다. Python의 코드 포인트 길이와 Java UTF-16 길이는 이모지 등에서 다를 수 있으므로 양쪽 기준·경계 사례를 맞춘다.
5. 기존 사실 유지와 명시적 정정 제안 방식, 부정·시점·발화 주체의 해석 기준을 맞춘다. 모델이 요청의 knownFacts와 충돌해도 DB를 직접 갱신하지 않는다.
6. 설명에 포함된 지시문은 사실 추출용 데이터로 취급한다. 원문·추출 근거를 응답에 복사하지 않고 지정 필드만 출력한다.
7. 실패 HTTP 상태는 아직 합의가 필요하다. Spring은 현재 503 등 HTTP 오류와 HTTP 200 + `success:false`를 모두 사용 불가로 처리한다. description 입력 오류와 모델 실패를 AI 서비스에서 구분한다.

계약을 바꾸면 `SolutionExtractRequest`·`SolutionExtractResult`, `HttpAiClient`의 사전 검사, fake 및 계약 테스트를 함께 수정한다. FastAPI의 단순 스키마 직렬화 성공만으로 사실 추정 금지를 보장할 수 없으므로 실제 모델 평가도 별도로 필요하다.

## 10. 구현·테스트 순서와 검증 결과

이번 구현은 요청/결과 DTO → 인터페이스 → fake → 공통 HTTP 클라이언트 → DTO·fake·HTTP 계약·실제 전송 테스트 순서로 진행했다.

| 테스트 파일 | 실행 수 | 확인한 내용 |
|---|---:|---|
| [SolutionExtractDtoTest](../src/test/java/com/wsid/ai/SolutionExtractDtoTest.java) | 29 | 1,000/1,001자, 빈/부분 knownFacts, 허용 키·값, null, 불변 복사, 전체 4개 필드, 미확정 집합·중복, toString 원문 제외 |
| [FakeAiClientSolutionExtractTest](../src/test/java/com/wsid/ai/FakeAiClientSolutionExtractTest.java) | 16 | 명세 예시·결정적 결과, 긍정/부정 고정 문장, 불확실/제3자/지시문에서 추정하지 않는 개발 규칙, 기존 사실 유지, 실패 |
| [HttpAiClientSolutionExtractTest](../src/test/java/com/wsid/ai/HttpAiClientSolutionExtractTest.java) | 77 | 경로·메서드·키·UUID, 본문의 요청 ID 제외, 실제 boolean/문자열 타입, 필드 누락/추가·집합 불일치, HTTP 오류·HTML·깨진 JSON·타임아웃·자동 재시도 없음 |
| [HttpAiClientSolutionExtractTransportTest](../src/test/java/com/wsid/ai/HttpAiClientSolutionExtractTransportTest.java) | 4 | localhost 실제 HTTP·UTF-8·헤더, 공통 응답 제한 사용, AI-05 전용 제한과 분리, fake 외부 호출 없음 |

Java 21에서 `./gradlew test --no-daemon` 실행 결과 **전체 445건 통과, 실패·오류·건너뜀 0건**이다. AI-06 신규 126건과 기존 319건을 포함한다. DB 통합 테스트는 이 명령의 범위가 아니며 이번 작업에서는 실행하지 않았다. 실제 FastAPI·LLM·상담 API의 통합 검증은 아직 하지 않았다.

실제 FastAPI 연결 시에는 다음을 추가로 검증한다.

- 같은 합성 요청을 양쪽 계약 테스트에 사용하고 정상·미확정·실패 응답을 비교한다.
- 송금 완료/미완료/계획/취소, 정보 전달 부정, 다른 사람의 피해, 시점이 달라진 연락 상태, 모호한 문장을 구분한다.
- 프롬프트 지시문이 사실을 바꾸지 않는지, 근거 없는 확정을 UNKNOWN으로 유지하는지 평가한다.
- 개인정보가 섞인 합성 설명의 최소 처리와 로그·APM·오류·응답의 원문 미포함을 확인한다.

3차에서는 여러 요청의 사실 누적, 사용자 확정값 보호, 직접 수정, 오래된 질문 선택 거부, AI 실패 후 직접 선택, 긴급 카드 우선, 동시 수정·중복 요청·완료 상태·회원/비회원 소유권을 도메인·서비스·DB·공개 API 테스트로 검증한다. 클라이언트 테스트 통과를 이 기능들의 구현 완료로 간주하지 않는다.
