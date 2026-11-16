# AI-06 피해 사실 추출 구현·테스트 계획서

작성일: 2026-11-16 · 상태: 1차 구현 후 계획 문서 보완, 2·3차 미착수

명세 출처는 팀 공유 `WSID-API-Spec.md`의 AI-06 및 SOL-01·03·04 절(2026-11-16 제공본)이다. 이 계획서는 Spring 1차 구현 뒤에 추가했다. 아래에는 1차의 작업 순서·검증 기준과 후속 개발 계획을 구분해 기록한다. 실제 변경 파일·테스트 결과는 [구현 보고서](ai-06-solution-extract.md)를 참고한다.

## 1. 목표와 단계별 범위

`POST /internal/ai/solutions/extract`는 피해 설명과 현재까지 확인한 사실을 받아 송금·연락 지속·정보 제공·앱 설치 여부를 구조화한다. 설명에 없는 사실은 추정하지 않고 `UNKNOWN`으로 남긴다. AI는 대응 카드·기관·전화번호·링크를 결정하거나 DB를 갱신하지 않는다.

| 단계 | 개발 범위 | 완료 기준 | 현재 상태 |
|---|---|---|---|
| 1차 Spring 클라이언트 | 요청·응답 DTO, 인터페이스, fake/HTTP, 타입 검사, 계약·전송 테스트 | 정해진 요청을 보내고 검증된 결과 또는 사용 불가 예외 반환 | 구현·테스트 완료 |
| 2차 FastAPI | 내부 인증, 사실 추출, 출력 검증, 개인정보·로그 처리, 모델 평가 | Spring과 계약 일치 및 추정 방지 검증 | 미구현 |
| 3차 상담 연결 | SOL 서비스·API, 사실 누적·직접 수정, 소유권·동시성, 카드·질문·저장 | 사용자의 확인된 사실로 검토된 안내를 제공·복원 | 미구현 |

1차에서는 기존 훈련 동작, 상담 엔티티, DB 마이그레이션을 변경하지 않는다. 호출 클라이언트 구현과 실제 상담 기능 완성을 구분한다.

## 2. 기존 코드와 연결 조건

| 파일·디렉토리 | 현재 상태와 개발 시 참고사항 |
|---|---|
| [ai/AiClient.java](../src/main/java/com/wsid/ai/AiClient.java) | 다른 기능이 AI를 호출하는 인터페이스. AI-06 메서드 추가 위치 |
| [ai/HttpAiClient.java](../src/main/java/com/wsid/ai/HttpAiClient.java) | 공통/시나리오/평가용 클라이언트 존재. AI-06은 공통 클라이언트와 readTimeout 사용 |
| [ai/AiClientConfig.java](../src/main/java/com/wsid/ai/AiClientConfig.java) | 기존 fake/http 선택 유지. AI-06 때문에 설정 구조를 바꾸지 않음 |
| [solution/domain/SolutionSession.java](../src/main/java/com/wsid/solution/domain/SolutionSession.java) | factsJson·카드·질문·버전 컬럼 존재. 사실 갱신·충돌 처리 없음 |
| [solution/domain/SolutionMessage.java](../src/main/java/com/wsid/solution/domain/SolutionMessage.java) | safeContent 저장 공간은 있지만 개인정보 제거 기능은 없음 |
| [solution/repository/](../src/main/java/com/wsid/solution/repository/) | 회원 소유권 조건 조회·요청 ID 중복 조회 존재. 비회원 소유권·중복 쓰기 보장 추가 필요 |
| [content/README.md](../src/main/java/com/wsid/content/README.md) | 검토된 카드 제공 계획. 실제 로더·카드 선택 규칙은 후속 개발 |

`ai`는 `solution`의 엔티티·도메인 객체를 import하지 않는다. 후속 상담 서비스가 도메인 사실을 Map으로 꺼내 요청 DTO에 전달한다. DTO는 전송·검증 형식이며 DB 접근 기능이 아니다.

## 3. 요청·응답 계약

```http
POST /internal/ai/solutions/extract
Content-Type: application/json
X-Internal-Api-Key: <환경 설정값>
X-Request-Id: <요청 UUID>
```

```json
{
  "description": "송금 후 연락이 끊겼어요.",
  "knownFacts": {}
}
```

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

| 항목 | 1차 적용 기준 | FastAPI와 확인할 점 |
|---|---|---|
| description | 필수, 공백 불가, 최대 1,000자. 원문을 임의 요약·절단하지 않음 | 1,000자 제한은 Spring 채택값. Java UTF-16 길이와 Python 길이 차이 합의 |
| knownFacts | 필수 Map, 빈/부분 Map 허용 | 첫 호출과 후속 호출 모두 동일 계약 사용 |
| 키 | transferredMoney·contactContinues·sharedInformation·installedApp | 새 항목 추가 시 양쪽 계약·테스트 함께 변경 |
| 값 | 정확히 YES·NO·UNKNOWN 문자열 | boolean·숫자·소문자·주변 공백을 자동 변환하지 않음 |
| requestId | Java DTO에 UUID 필수, 본문 제외·헤더 전송 | 추적 ID 전달·로그 정책, 재시도 시 시도 번호 필요 여부 |
| facts 응답 | 4개 항목 전체 필수, 추가 키 거부 | 부분 응답을 허용하지 않는 기준 합의 |
| uncertainFields | 필수 문자열 배열. 중복 없이 UNKNOWN인 키와 정확히 일치 | 전부 확정되어도 빈 배열 생략 금지. 순서는 무관 |
| success | 실제 JSON boolean true | 문자열 "true"를 성공으로 받아들이지 않음 |

길이 제한·전체 반환·미확정 집합 일치·요청 ID는 이번 Spring 기준이다. 초안만으로 양쪽 합의가 끝난 것으로 취급하지 않는다. 해당 항목이 달라지면 DTO·HTTP 검사·fake·테스트를 함께 수정한다.

## 4. 1차 개발 순서

| 순서 | 파일 | 작업과 확인 기준 |
|---|---|---|
| 1 | `ai/dto/SolutionExtractRequest.java` | 설명·허용 키/값·UUID 검사, Map 방어적 복사. 오류 입력을 조용히 버리지 않고 IllegalArgumentException 반환 |
| 2 | `ai/dto/SolutionExtractResult.java` | 전체 4개 사실·UNKNOWN/uncertainFields 일치·중복 검사, Map/List 불변 복사 |
| 3 | `ai/AiClient.java` | extractSolutionFacts 추가. 사실 병합·카드 선택·저장은 호출자 책임으로 명시 |
| 4 | `ai/FakeAiClient.java` | 명세 예시와 합성 고정 문장만 재현. 이외에는 기존 사실/UNKNOWN 유지. AI_FAIL 실패 재현 |
| 5 | `ai/HttpAiClient.java` | 공통 RestClient로 POST, 내부 키·요청 ID, JsonNode 사전 검사 후 treeToValue, 사용 불가 예외 변환 |
| 6 | `src/test/java/com/wsid/ai/` | DTO·fake·HTTP 계약·실제 전송과 타임아웃 테스트 |
| 7 | `docs/` 및 ai/solution README | 구현 범위, 파일별 책임, FastAPI·상담 연결 조건과 검증 결과 기록 |

HTTP 단계에서는 루트·data·facts의 객체 여부, 실제 boolean success, 실제 문자열 사실 값, 배열과 요소 타입을 DTO 변환 전에 검사한다. DTO의 자동 타입 변환에 기대지 않는다. 변환 후에는 값과 미확정 집합의 일관성을 검사한다.

입력 오류는 IllegalArgumentException, 통신·JSON·응답 계약 오류는 AiUnavailableException으로 구분한다. 응답 본문이 포함될 수 있는 cause는 보관하지 않는다. DTO toString에는 원문·사실 값 대신 길이·개수·요청 ID만 표시한다. 자동 재시도는 추가하지 않는다.

## 5. FastAPI 개발 계획

별도 AI 저장소의 기존 구조에 맞춰 아래 책임을 배치한다. Python 경로는 제안이며 이 저장소에 구현된 파일이 아니다.

| 제안 경로 | 작업 |
|---|---|
| `schemas/solution_extract.py` | 엄격한 요청·응답 타입, 허용 키/값, 전체 facts·필수 uncertainFields, 길이·일관성 검사 |
| `routers/solutions.py` | 내부 키 검증, 요청 ID 전달, 경로·성공/실패 봉투·HTTP 오류 계약 |
| `services/solution_extractor.py` | 현재 설명·knownFacts로만 추출. 부정·시점·발화 주체·불확실성 구분, 정보 부족 시 UNKNOWN |
| `services/solution_output_validator.py` | 모델 출력 검증. 계약 위반을 정상 성공으로 보내지 않음 |
| 공통 로그·LLM 설정 | 요청/응답·추출 근거 원문 수집 금지, 공급자·프록시·APM 데이터 보관 설정 확인 |
| `tests/test_solution_extract.py` | Spring과 같은 계약 사례 및 실제 모델의 의미 분석·추정 방지 검증 |

개인정보 최소 처리는 DTO 길이 검사로 해결되지 않는다. 후속 Spring 호출자가 불필요한 개인정보를 제거하고, FastAPI에서도 모델 전송 범위·로그 설정을 검증한다. 원문이나 추출 근거를 결과 필드에 복사하지 않는다.

실패 HTTP 상태는 합의 후 확정한다. 현재 Spring은 HTTP 오류와 HTTP 200 + success:false를 모두 사용 불가로 처리한다. 추출할 사실이 없다는 정상 UNKNOWN 결과와 서비스 실패를 구분한다.

## 6. 상담 연결 계획

1. **사실 도메인·규칙부터 구현한다.** SolutionFacts·ResponseRules는 신규 제안 클래스다. 사실의 값·확정 출처·충돌·직접 수정 규칙을 정하고 검토된 카드·다음 질문을 결정한다.
2. **입력 경로를 나눈다.** SOL-01 최초 설명과 SOL-03 자유 입력은 현재 사실을 knownFacts로 함께 보내 AI-06을 호출한다. SOL-03 선택과 SOL-04 직접 수정은 AI를 다시 거치지 않고 도메인 검증 후 적용한다. 공개 CHOICE와 내부 InputKind.OPTION을 명시적으로 매핑한다.
3. **병합 규칙을 적용한다.** UNKNOWN은 기존 YES/NO를 지우지 않는다. AI 제안이 사용자 확정값과 다르면 재확인하고 확정 전 기존 값을 유지한다. 직접 수정은 명시적인 사용자 변경으로 처리한다. 출처 저장 구조를 먼저 정한다.
4. **호출과 저장을 분리한다.** 소유권·상태·사실·버전을 읽은 뒤 트랜잭션 밖에서 호출한다. 저장 시 소유권·버전·완료 상태·요청 중복을 다시 확인한다. 오래된 결과로 동시 수정을 덮어쓰지 않는다.
5. **실패·긴급 흐름을 연결한다.** AI 실패 시 기존 사실을 유지하고 직접 선택을 제공한다. 이미 확인한 사실에 필요한 긴급 안내는 LLM을 기다리지 않는다. 카드·기관·전화·링크는 검토된 content에서만 가져온다.
6. **저장·API를 연결한다.** 원문·추출 근거는 저장하지 않는다. 필요한 구조화된 사실·선택·카드만 저장하고 safeContent는 개인정보 제거 후 전달한다. SOL-01~05 공개 API와 복원·수정·종료를 구현한다.

현재 user_id NOT NULL·회원 외래키 구조는 비회원 상담을 지원하지 않는다. 로그인 불필요 정책에 맞는 소유자 식별·만료·복원·DB 구조가 선행되어야 한다. 기존 factsJson의 한글 키 예시와 실제 저장 데이터도 확인해 canonical 키·값으로 읽는 호환 처리 또는 마이그레이션 필요 여부를 결정한다.

## 7. 테스트 계획

| 구분·파일 | 필수 사례 |
|---|---|
| `SolutionExtractDtoTest` | 1,000/1,001자, null·공백, 빈/부분 knownFacts, 허용 키/값, UUID, 방어적 복사, 전체 facts, UNKNOWN 집합·중복, 원문 없는 toString |
| `FakeAiClientSolutionExtractTest` | 명세 예시, 결정적 결과, 긍정/부정 고정 문장, 불명확·제3자·지시문에서 확정하지 않음, 기존 사실 유지, 실패 |
| `HttpAiClientSolutionExtractTest` | 경로·POST·키·ID, ID 본문 제외, 문자열 true·숫자/boolean 사실 값·누락/null·추가 키·미확정 불일치 거부, HTTP 오류·깨진 JSON·타임아웃·재시도 없음 |
| `HttpAiClientSolutionExtractTransportTest` | localhost 실제 HTTP·UTF-8·헤더, 공통 readTimeout 사용, 평가 전용 제한과 분리, fake 외부 호출 없음 |
| 2차 FastAPI 계약·모델 테스트 | 양쪽 JSON 일치, 부정·완료/계획·타인 사례·불확실성·프롬프트 지시문, 로그/응답에 원문 없음 |
| 3차 도메인·서비스·DB·API 테스트 | 여러 번 사실 누적, 사용자 확정값 보호·충돌·직접 수정, 오래된 질문 선택 거부, 실패 후 선택, 긴급 카드, 동시 수정·중복·완료·회원/비회원 소유권 |

fake 고정 규칙 검증과 실제 LLM 품질 평가는 별개다. 클라이언트 테스트 통과만으로 모델의 추정 금지나 상담 저장·카드 선택이 검증됐다고 표시하지 않는다.

1차 검증 명령은 Java 21 환경의 `./gradlew test --no-daemon`이다. 기존 테스트의 회귀도 함께 확인한다. DB 테스트는 별도 실행 범위이며 3차 스키마·서비스 개발에서 추가한다. 문서는 로컬 링크·JSON 예시·IntelliJ 문서 오류와 diff 공백 검사를 확인한다. 실제 실행 결과는 구현 보고서에 기록한다.

## 8. 후속 단계 착수 전 결정할 항목

- FastAPI와 요청 길이·문자 길이 계산, 전체 facts 반환, 엄격한 enum·boolean, 필수 빈 배열, 실패 HTTP 상태를 합의한다.
- 기존 facts와 새로운 설명이 충돌할 때 재확인·사용자 수정의 우선순위 및 확정 출처 저장 형식을 정한다.
- 개인정보 제거 책임·허용 전송 범위·본문 로그/공급자 보관 정책을 정한다.
- 비회원 소유권·복원·만료와 DB 변경, 기존 사실 JSON 호환 필요 여부를 정한다.
- 요청 ID·시도 추적·중복 쓰기 보장, 동시 변경 시 오래된 결과 폐기/충돌 응답 정책을 정한다.
- 검토된 카드 카탈로그·긴급 규칙·미확정 항목 질문 순서를 정하고 서비스에 연결한다.
- 실제 지연 측정 후 공통 20초가 적절한지 확인한다. 전용 제한이 필요하면 설정·RestClient·분리 테스트를 함께 추가한다.

1차 완료 기준은 클라이언트·계약 테스트·문서다. 전체 기능 완료 기준은 FastAPI 계약/모델 검증과 상담 도메인·서비스·DB·공개 API 연결까지 통과하는 것이다.
