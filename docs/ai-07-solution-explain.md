# AI-07 피해 대응 근거 설명 구현 보고서와 연동 개발 안내

작성일: 2026-11-16 · 상태: 1차 Spring 클라이언트 구현·검증 완료, FastAPI·콘텐츠·상담 미연결

개발 기준은 [AI-07 구현·테스트 계획서](ai-07-solution-explain-plan.md)와 팀 공유 `WSID-API-Spec.md`의 AI-07·SOL-01~04 절(2026-11-16 제공본)이다. 명세에 없던 근거 요소 code/title/excerpt·길이·인용 규칙·출력 제한은 이번 Spring의 초기 계약이다. 실제 FastAPI 개발 시 양쪽을 대조해 합의해야 한다. 공유 명세 파일은 이번 작업에서 변경하지 않았다.

## 1. 구현 결과와 범위

`AiClient.explainSolution(SolutionExplainRequest)`를 추가했다. 확인된 사실과 승인 근거의 전송 형식을 검사하고, fake 또는 FastAPI HTTP 호출 결과에서 타입·인용 코드·명시적 금지 출력을 확인한 뒤 `SolutionExplainResult`를 반환한다.

**클라이언트만 구현했다. 실제 상담 서비스 호출·근거 검색·승인 카탈로그·카드 선택·공개 API·DB 저장은 연결하지 않았다.** FastAPI·LLM·RAG 코드는 이 저장소에 없다. 기존 훈련/상담 엔티티, 설정, DB 마이그레이션은 변경하지 않았다.

| 구현한 것 | 후속 작업인 것 |
|---|---|
| facts/근거/UUID DTO와 방어적 복사 | 문단 수집·승인·유효 버전·철회 확인 |
| fake 고정 결과와 근거 제목의 실패 표식 | 실제 RAG 검색/선별·근거 충실성·기관명 판별 |
| 내부 헤더·엄격한 JsonNode 타입 검사 | 상담 소유권·긴급 규칙·호출 생략·동시 변경 처리 |
| 요청 코드 대조·URL/전화/링크 등 패턴 검사 | 모델의 모든 우회·의미 오류 방지 및 표시 전 검증 |
| 계약·실제 HTTP·타임아웃 테스트 | 실제 FastAPI·상담·DB·프론트 통합 검증 |

## 2. 호출 흐름과 DTO의 역할

```text
후속 상담 서비스 (현재 없음)
  ├─ 현재 사실을 Map으로 구성
  ├─ 검토된 카드/긴급 여부 결정
  └─ 승인된 설명용 문단을 선택
       ↓ SolutionExplainRequest
  AiClient.explainSolution
       ├─ FakeAiClient → 고정 문구 + 요청의 첫 코드 / 빈 근거 안내
       └─ HttpAiClient → FastAPI → JsonNode 타입 검사 → 결과 DTO
       ↓
  SolutionExplanationValidator → 요청 코드 대조 + 명시적 출력 패턴 검사
       ↓ SolutionExplainResult 또는 AiUnavailableException
후속 상담 서비스 → 승인·버전·의미·상태 재확인 → 저장/표시 또는 설명 생략
```

DTO는 메모리의 전송·검증 객체이며 DB에 접근하지 않는다. `ai` 패키지는 `solution` 엔티티나 repository를 import하지 않는다. 호출자가 도메인 사실을 Map으로 꺼내 전달하고 결과를 받아 도메인·저장 정책에 적용해야 한다.

AI-06은 피해 설명에서 사실을 추출한다. Spring 규칙은 카드와 긴급 여부를 결정한다. AI-07은 그 사실과 승인된 근거에 대한 보조 설명을 만든다. AI-07이 사실을 수정하거나 새 카드·기관·연락처를 결정하는 경로는 구현하지 않았다.

## 3. 요청 계약

`POST /internal/ai/solutions/explain`, `Content-Type: application/json`으로 보낸다. 다음은 합성 예시이며 실제 승인 콘텐츠가 아니다.

```json
{
  "facts": {
    "transferredMoney": "YES"
  },
  "verifiedReferences": [
    {
      "code": "REF_EXAMPLE_V1_P1",
      "title": "대응 단계 확인 예시",
      "excerpt": "검토된 대응 카드의 순서와 준비 항목을 확인합니다."
    }
  ]
}
```

| 항목 | 구현한 검사 |
|---|---|
| facts | null·빈 Map 거부, 부분 Map 및 UNKNOWN 허용 |
| 키·값 | AI-06 `SolutionExtractRequest.FACT_KEYS/FACT_VALUES` 재사용. 4개 키, 정확한 YES/NO/UNKNOWN 문자열만 허용 |
| verifiedReferences | null 거부, 빈 배열 허용, 최대 10개, null 요소·중복 code 거부 |
| code | `[A-Z0-9_]{1,100}` |
| title | 필수, 공백 불가, 최대 200자 |
| excerpt | 필수, 공백 불가, 최대 2,000자 |
| requestId | Java DTO에서 UUID 필수. JSON 본문에서 제외하고 X-Request-Id 헤더로 전달 |
| 내부 인증 | 기존 X-Internal-Api-Key 헤더 사용 |

문자 길이는 Java String.length 기준이다. 값의 앞뒤 공백을 제거하거나 문단을 임의로 자르지 않는다. Map·List는 방어적으로 복사하고 불변으로 반환한다. 잘못된 입력은 항목을 버리지 않고 IllegalArgumentException으로 거부한다.

`VerifiedReference`는 승인된 데이터라는 증명서가 아니다. DTO는 출처 조회·승인·버전 확인·개인정보 마스킹·입력 문단의 연락처 제거를 하지 않는다. title/excerpt에 전화번호나 링크가 있어도 입력 형식만 맞으면 전송할 수 있다. 실제 호출 서비스가 **연락처·URL 없는 승인된 설명용 문단**을 카탈로그에서 골라 구성해야 한다. 원문을 정규식으로 잘라 자동 승인하지 않고 설명용 문단을 별도로 검토한다. 원본 연락처·출처 링크는 카드·카탈로그에 유지한다.

요청은 최대 약 2만 자의 문단과 제목·사실을 포함할 수 있다. 토큰 예산·실제 RAG 지연·모델 입력 제한은 FastAPI와 별도로 측정하고 합의한다.

## 4. 응답 처리와 코드 일치 검사

```json
{
  "success": true,
  "data": {
    "explanation": "검토된 대응 카드의 순서와 준비 항목을 함께 확인해 주세요.",
    "referenceCodes": ["REF_EXAMPLE_V1_P1"]
  }
}
```

HTTP 응답은 다음 순서로 처리한다.

1. JsonNode로 루트 객체, 실제 boolean true인 success, 객체 data를 확인한다. 문자열 "true"·숫자 1은 거부한다.
2. explanation이 실제 문자열이고 referenceCodes가 배열이며 각 요소가 실제 문자열인지 확인한다. 자동 문자열 변환에 기대지 않는다.
3. treeToValue로 결과 DTO를 만든다. explanation은 공백 불가·최대 2,000자이고, 코드는 최대 10개·허용 형식·중복 없음이어야 한다.
4. 공통 검증기에서 요청 코드 집합과 대조하고 출력 패턴을 검사한다. fake도 같은 검증기를 호출한다.

| 요청·응답 조합 | 처리 |
|---|---|
| 요청 근거 있음, 코드 일부/전체 인용 | 요청에 있는 코드만 반환했다면 허용. 요청 순서와 같을 필요는 없음 |
| 요청 근거 있음, 반환 코드 빈 배열 | 거부 |
| 요청 근거 없음, 반환 코드 빈 배열 | 계약상 허용 |
| 요청 근거 없음, 반환 코드 존재 | 거부 |
| 알려진 코드와 요청에 없는 코드 혼합 | 응답 전체 거부. 모르는 코드만 버리지 않음 |
| 코드 배열 누락·null·중복·잘못된 타입 | 거부 |

`referenceCodes: []`도 필드를 생략하면 안 된다. 결과 DTO에 없는 추가 data 필드는 무시하며 공개 응답으로 전달하지 않는다. 설명 문장은 반환받은 그대로 보존한다. 실제 코드가 요청에 있다는 사실은 설명의 모든 문장이 그 근거에서 도출됐다는 증명이 아니다.

명세의 빈 근거·빈 코드와 “은행과 공식 신고 채널” 문장도 타입·패턴 기준으로 통과할 수 있다. 3차에서는 빈 근거면 AI 호출을 생략하므로 그 예시를 근거 검증 완료 사례나 사용자 표시 기준으로 쓰지 않는다.

## 5. 출력 패턴 검사 범위와 한계

`SolutionExplanationValidator`는 설명을 검사할 때만 NFKC 정규화하고 Unicode Cf 서식 문자를 제거한다. 전각 URL·전화번호와 일부 보이지 않는 문자 삽입을 검사하기 위한 것이며 원본 결과를 수정하지 않는다. 다음과 같은 명시적 패턴을 발견하면 전체 응답을 AiUnavailableException으로 거부한다.

- URI 스킴(http/https/ftp/tel/mailto/data/javascript 등), www, 일반 영문 도메인·이메일 형태
- HTML 태그/주석·자동 링크, Markdown 인라인/이미지/참조형 링크 및 정의. 태그는 `<` 바로 뒤에 이름이 올 때, 자동 링크는 공백 없는 `<…:…>`·`<…@…>`일 때, Markdown 링크는 `](`·`][`처럼 붙어 있을 때만 판단한다
- 0으로 시작하는 흔한 전화번호, 구분자가 있는 번호, 대표번호·국제번호 형태와 일부 짧은 안내 번호(112·119 등). 구분자가 있는 번호와 짧은 안내 번호는 바로 뒤에 개·건·명·원·만·분 등 수량·금액·시간 단위가 오면 제외한다. `112번`의 `번`은 신고 번호 안내일 수 있어 단위로 보지 않는다

일반 단계 번호·금액·날짜·문장부호·비교식 등은 정상 사례로 검증했다. 1차 작성 직후 검토에서 `[주의] (중요)`, `[1단계] [2단계]`, `119개`, `10 000원`, `3 < 5 / 2 > 1` 같은 정상 문장이 링크·전화번호·HTML로 거부되는 오탐을 발견해 위 기준으로 좁혔고, 이 문장들을 회귀 테스트에 추가했다. `112 신고`처럼 안내 번호를 쓴 문장은 정책상 계속 거부한다(연락처는 카드에서만 제공). `Mr.Kim`처럼 영문 약어 뒤에 마침표가 붙은 표기는 도메인으로 판단되는 알려진 오탐이다. 패턴은 모든 국가의 번호·언어·우회 표현을 완전히 판별하지 않는다. 정규식의 오탐/미탐 가능성도 있다. 새 패턴을 추가할 때는 차단 사례와 정상 숫자·문장 경계 테스트를 함께 추가한다.

기관명 의미 판별은 구현하지 않았다. 일반 범주인 은행·경찰·공식 신고 채널은 허용하고 구체적 기관명은 설명에서 사용하지 않는 초기 제안이지만, Spring 패턴 검사가 그 구분을 보장하지는 않는다. 근거 밖의 행동 추가·UNKNOWN을 확정하는 문장·정확한 인용 여부도 이 검증기로 판정하지 않는다. FastAPI의 출력 검증·실제 모델 평가와 후속 표시 전 검증이 필요하다.

연락처·URL은 승인된 값이어도 생성 설명에 넣지 않는 초기 정책이다. 실제 전화·기관·링크는 Spring 카드의 별도 필드로 제공한다. 프론트는 설명을 일반 텍스트로 표시하고 HTML/Markdown 링크·자동 링크 기능을 켜지 않는다. 실제 화면은 이번에 구현하지 않았다.

## 6. fake·예외·설정·로그

fake는 근거가 있으면 고정 보조 문구와 첫 근거 코드 하나를 반환한다. 근거가 없으면 “확인된 근거가 없어 추가 설명을 제공하지 않습니다.”와 빈 배열을 반환한다. 입력 사실·제목·문단을 결과 문구에 복사하지 않는다.

어느 근거든 title에 `[AI_FAIL]`이 있으면 원문 없는 실패를 발생시킨다. excerpt의 표식은 실패를 일으키지 않는다. 따라서 표식 실패 재현은 근거가 있는 요청에서만 가능하다. 빈 근거의 실패는 HTTP 목업/예외 스텁으로 검증하고, 3차 서비스에서는 빈 근거일 때 미호출을 확인한다. fake에는 실제 검색·모델 추론·기관명 의미 판별이 없다.

| 항목 | 현재 동작 |
|---|---|
| null/형식이 잘못된 요청 | IllegalArgumentException |
| HTTP 오류·타임아웃·JSON/응답 계약 위반·코드 불일치·금지 출력 | AiUnavailableException |
| 예외 원인 | 본문이 섞일 수 있는 cause·suppressed를 추가하지 않고 고정 문구만 제공 |
| 타임아웃 | 공통 restClient의 connectTimeout/readTimeout 사용. 기본 연결 2초·응답 20초 |
| AI-03/05 제한 | 각각의 시나리오/평가 전용 제한 유지. AI-07 때문에 설정·생성자 변경 없음 |
| 재시도 | 자동 재시도 없음. 호출자가 같은 요청을 다시 보내면 같은 UUID 헤더 유지 |
| toString | DTO가 길이·개수·UUID만 표시. 사실 값·근거 코드·제목·문단·생성 설명 제외 |

요청 ID만으로 FastAPI 멱등 처리나 DB 중복 저장 방지가 구현되지는 않는다. 시도 구분·호출 취소·상담 전체 시간 예산·전용 RAG 제한은 후속 서비스/운영 설계 항목이다.

클라이언트에 본문 로그를 추가하지 않았다. HTTP/프록시/APM·FastAPI·LLM SDK의 로깅과 공급자 보관 설정은 별도로 확인해야 한다. 추적은 UUID·시간·개수·고정 실패 분류만 사용하고 사용자 피해 설명·개인정보·근거 원문은 로그/DB에 저장하지 않는다.

## 7. 구현 파일과 직접 상호작용

| 파일 | 현재 역할 | 이후 개발 시 지킬 점 |
|---|---|---|
| [AiClient.java](../src/main/java/com/wsid/ai/AiClient.java) | explainSolution 인터페이스 | 호출자는 인터페이스만 사용. 긴급 결정·승인·저장은 호출자 책임 |
| [VerifiedReference.java](../src/main/java/com/wsid/ai/dto/VerifiedReference.java) | code/title/excerpt 검증 | 승인·유효 버전을 확인한 뒤 구성. 형식 통과를 승인 증명으로 취급하지 않음 |
| [SolutionExplainRequest.java](../src/main/java/com/wsid/ai/dto/SolutionExplainRequest.java) | 사실·근거 목록·UUID 검사와 복사 | 도메인 객체 import 대신 Map 사용. 한도·키 추가는 FastAPI와 동시 변경 |
| [SolutionExtractRequest.java](../src/main/java/com/wsid/ai/dto/SolutionExtractRequest.java) | AI-06 사실 키·값 상수 제공 | 상수 변경은 AI-06·07 양쪽에 영향. 두 계약 테스트 함께 확인 |
| [SolutionExplainResult.java](../src/main/java/com/wsid/ai/dto/SolutionExplainResult.java) | 결과 필수 값·길이·코드 형식/중복 검사 | DTO 단독 생성은 요청 대조·금지 출력 검사까지 수행하지 않음 |
| [SolutionExplanationValidator.java](../src/main/java/com/wsid/ai/SolutionExplanationValidator.java) | 요청 코드와 결과 대조·패턴 검사 | fake/HTTP가 공통 호출. 의미 검증을 한 것으로 오해하지 않음 |
| [HttpAiClient.java](../src/main/java/com/wsid/ai/HttpAiClient.java) | 경로·키·ID·타입 검사·변환·검증·실패 | DTO 계약 변경 시 JsonNode 사전 검사·HTTP 테스트 함께 변경 |
| [FakeAiClient.java](../src/main/java/com/wsid/ai/FakeAiClient.java) | 고정 설명·실패 재현 | 실제 근거 검색·품질 평가 대신 사용하지 않음 |
| [AiClientConfig.java](../src/main/java/com/wsid/ai/AiClientConfig.java), [AiProperties.java](../src/main/java/com/wsid/ai/AiProperties.java), [application.yml](../src/main/resources/application.yml) | 기존 모드·키·URL·제한 사용 | 이번 수정 없음. 기본 fake는 외부 호출 없음. 전용 제한 필요 시 설정과 전송 테스트 함께 추가 |
| [AiUnavailableException.java](../src/main/java/com/wsid/ai/AiUnavailableException.java) | 사용 불가 공통 예외 | 후속 호출자가 설명 생략으로 전환. 오류 문구·cause에 원문 추가 금지 |

## 8. 콘텐츠 디렉토리에서 이후 할 일

현재 [content](../src/main/java/com/wsid/content/README.md)와 [response-cards](../src/main/resources/content/response-cards/README.md)는 구조 계획만 있고 실제 카드·승인 문단 로더는 없다. 아래 이름은 새 개발 제안이다.

| 개발 위치 | 필요한 처리 |
|---|---|
| `content/domain/` | 카드 ID와 별도의 문단 코드·버전·출처·확인일·검토/철회 상태 모델 |
| `content/service/`·콘텐츠 로더 | 서버가 관리하는 승인 목록 조회. 잘못되거나 미검토인 문단을 모델 요청에 넣지 않음 |
| `resources/content/` 설명용 근거 영역 | 연락처·URL 없는 설명용 문단을 별도로 승인. 원본 출처·연락처는 카드/관리 정보에 유지 |
| 근거 검색/선별 계층 | 현재 사실·적용 카드에 관련된 승인 문단만 선택. 검색 점수만으로 승인 여부를 대신하지 않음 |
| 코드/버전 관리 | 요청 당시 문단을 재현할 수 있게 관리. 같은 코드의 내용을 임의로 덮어쓰지 않음 |

공개 요청의 code·URL·reviewed 플래그를 신뢰해 VerifiedReference를 만들지 않는다. Spring이 카탈로그에서 직접 고른 문단만 사용한다. 설명용 문단이 없거나 철회됐으면 AI-07을 생략한다. AI 저장소의 인덱스와 승인 카탈로그 버전·철회 상태를 동기화해야 한다.

## 9. 상담·DB·공개 API에서 이후 할 일

현재 `solution/service/`·`controller/`의 상담 흐름은 없으며 아래 서비스/규칙 이름은 제안이다.

| 기존 파일/개발 위치 | 필요한 처리 |
|---|---|
| [SolutionSession.java](../src/main/java/com/wsid/solution/domain/SolutionSession.java) | factsJson을 도메인 사실로 읽고 카드·긴급 규칙 적용. AI-07이 사실/카드를 수정하게 하지 않음 |
| `solution/domain/ResponseRules`·상담 서비스 | 긴급 카드가 있으면 AI-07 미호출. 승인 근거 없음·사실 전부 UNKNOWN이면 설명 생략/추가 질문. 호출은 트랜잭션 밖에서 수행 |
| [SolutionSessionRepository.java](../src/main/java/com/wsid/solution/repository/SolutionSessionRepository.java) | 현재 회원 소유권 조회만 존재. 비회원 식별·소유권·만료/복원 구조 별도 구현 |
| [SolutionMessage.java](../src/main/java/com/wsid/solution/domain/SolutionMessage.java) | referenceCodesJson은 현재 필드만 존재. getter·설정 메서드·코드 종류/버전 검증 필요. safeContent에 미검증 설명이나 개인정보 저장 금지 |
| [SolutionMessageRepository.java](../src/main/java/com/wsid/solution/repository/SolutionMessageRepository.java), [V2 제약](../src/main/resources/db/migration/V2__add_constraints.sql) | 상담별 요청 ID/순서 유일성·중복 조회 활용. 동시 저장 충돌·재시도 정책을 서비스에서 처리 |
| `solution/controller/`·공개 응답 DTO | SOL 응답의 explanation·출처·생성 상태 제공 여부를 프론트와 확정. 내부 봉투·원문·오류를 그대로 노출하지 않음 |

권장 처리 순서는 다음과 같다.

1. 짧은 읽기 트랜잭션에서 소유권·현재 사실·카드·긴급 여부·상담/근거 버전을 확인한다.
2. 긴급이거나 근거가 없으면 AI를 호출하지 않고 검토된 카드/질문을 제공한다.
3. 필요한 사실과 승인 문단만 만들어 트랜잭션 밖에서 explainSolution을 호출한다.
4. 호출 실패 시 기존 사실·카드를 유지하고 설명만 생략하거나 고정 문구로 대체한다.
5. 저장 시 소유권·완료 상태·상담 버전·근거 승인/철회·중복 요청을 다시 확인한다. 동시 수정·종료·근거 철회로 오래된 결과가 됐다면 저장하지 않는다.
6. 근거 충실성·기관 표현·개인정보·출력 제한을 확인한 결과만 표시한다. 안전한 생성 설명의 보관 여부, 근거 버전·코드 저장 형식, 복원 시 철회된 설명 처리 정책을 결정한다.

현재 user_id NOT NULL·회원 외래키 구조는 비회원 상담을 지원하지 않는다. 상담 ID만으로 타인 데이터에 접근하게 만들지 않고 비회원 소유권 구조를 선행 구현해야 한다. AI-07 클라이언트는 저장·버전·소유권을 처리하지 않는다.

## 10. FastAPI 개발 디렉토리별 참고사항

별도 AI 저장소에 적용할 제안 경로다. 현재 Python 파일이나 RAG 인덱스를 구현한 것은 아니다.

| 제안 위치 | 대응 Spring 파일 | 할 일 |
|---|---|---|
| `schemas/solution_explain.py` | VerifiedReference·요청/결과 DTO | code/title/excerpt, camelCase, 한도·부분 facts·엄격한 enum·실제 boolean·필수 배열을 맞춤 |
| `routers/solutions.py` | HttpAiClient | POST 경로·내부 키·X-Request-Id·성공/실패 봉투. 본문에 requestId를 기대하지 않음 |
| `services/solution_explainer.py` | 설명 생성 책임 | 요청 문단 안에서만 선별/재정렬·설명. UNKNOWN 확정·새 행동/기관/연락처 추가 금지. 일반 범주와 구체적 기관명 정책 적용 |
| `services/solution_explanation_validator.py` | SolutionExplanationValidator + 의미 검증 | 요청 코드만 인용, 근거가 있으면 최소 1개, 없으면 빈 코드. 출처 밖 주장·구체적 기관·연락처/URL·우회 표기를 검증 |
| 승인 수집/검색 계층 | Spring 승인 카탈로그 | 검토 상태·문단 버전·철회 동기화. 요청 밖의 새 근거를 결과에 추가하지 않음 |
| 공통 오류·로그·LLM 설정 | 내부 인증·안전한 예외 | 고정 오류·본문 로그 금지·공급자 보관 정책. 요청 ID 기반 추적 |
| `tests/test_solution_explain.py` | Java 계약/검증기/전송 테스트 | 같은 합성 JSON으로 타입·빈 배열·부분 인용·실패를 비교하고 모델 의미 평가를 별도로 수행 |

FastAPI 직렬화에서 success를 문자열로 보내거나 referenceCodes 빈 배열을 생략하면 Spring이 거부한다. `exclude_none/defaults` 설정도 확인한다. description 원문은 요청에 없으며 facts와 승인 문단만으로 처리한다. 근거 내부 지시문은 데이터로 취급한다.

실패 상태는 팀 합의가 필요하다. 현재 Spring은 HTTP 200 + success:false와 4xx/5xx 모두 사용 불가로 처리한다. 초안만으로 실패 상태가 확정됐다고 보지 않는다. 데이터/코드/출력 계약을 바꾸면 DTO뿐 아니라 JsonNode 검사·공통 검증기·fake·테스트를 함께 수정한다.

## 11. 테스트 결과와 남은 검증

Java 21에서 `./gradlew test --no-daemon` 실행 결과 **전체 606건 통과, 실패·오류·건너뜀 0건**이다(`clean test --rerun-tasks`로 캐시 없이 실행). AI-07 신규 161건과 기존 445건을 포함한다.

| 테스트 | 실행 수 | 검증 내용 |
|---|---:|---|
| [SolutionExplainRequestTest](../src/test/java/com/wsid/ai/SolutionExplainRequestTest.java) | 23 | 부분/UNKNOWN facts·허용 값·null, 빈 근거·중복·10/11개, 코드/제목/문단 경계, 불변 복사·원문 없는 toString |
| [SolutionExplainResultTest](../src/test/java/com/wsid/ai/SolutionExplainResultTest.java) | 14 | 필수 설명·2,000자 경계, 코드 형식/중복/누락/개수·빈 배열, 불변 복사·원문 제외 |
| [SolutionExplanationValidatorTest](../src/test/java/com/wsid/ai/SolutionExplanationValidatorTest.java) | 56 | 부분/전체 인용, 요청 밖 코드 혼합·빈 근거·무인용, 연락처/URL/HTML/Markdown·전각/서식 문자, 정상 숫자/문장 경계, 오탐 회귀 문장(대괄호·단위 붙은 숫자·띄운 금액·비교식) |
| [FakeAiClientSolutionExplainTest](../src/test/java/com/wsid/ai/FakeAiClientSolutionExplainTest.java) | 5 | 결정적 결과·첫 코드·빈 근거 고정 안내, 원문 미복사·제목 실패 표식·null |
| [HttpAiClientSolutionExplainTest](../src/test/java/com/wsid/ai/HttpAiClientSolutionExplainTest.java) | 58 | 정확한 직렬화/헤더·UUID 본문 제외, 타입/누락/null/초과·코드 대조·금지 출력, HTTP 오류·JSON·타임아웃·안전한 예외·자동 재시도 없음 |
| [HttpAiClientSolutionExplainTransportTest](../src/test/java/com/wsid/ai/HttpAiClientSolutionExplainTransportTest.java) | 5 | localhost 실제 UTF-8/헤더/본문, 공통 제한·AI-03/05 제한 분리, 실제 503와 재시도 없음, fake 외부 호출 없음 |

HTTP 계약 테스트는 MockRestServiceServer를 사용했고 실제 전송·타임아웃·503은 localhost JDK HttpServer로 검증했다. 목업은 팀 초안과 이번 초기 계약을 재현한 것이며 실제 FastAPI의 응답을 검증한 결과가 아니다. 별도 Postman 목업/시뮬레이션 환경은 만들지 않았다.

일반 test는 db 태그를 제외한다. DB·서비스 변경이 없어 dbTest는 실행하지 않았다. 실제 FastAPI·LLM/RAG·카탈로그·상담·프론트 통합 테스트도 하지 않았다. 기존 코드의 deprecated 경고는 남아 있으며 새 JsonNode 타입 검사는 isString을 사용했다.

변경 문서 7개의 로컬 링크·JSON 예시·공백 검사를 통과했고 IntelliJ 문서 오류는 0건이다. Java 소스/테스트 13개도 IntelliJ 검사를 요청했으나 현재 프로젝트에서 content roots 밖 또는 제외된 파일로 인식되어 분석되지 않았다. Java 코드의 검증 근거는 실제 Gradle 컴파일·테스트 결과이며 IntelliJ Java 검사 통과로 표시하지 않는다. 이 작업에서 IDE 프로젝트 설정을 변경하지 않았다.

2차에는 부정/UNKNOWN·근거 밖 주장·구체적 기관·연락처/링크 우회·지시문 공격·실제 인용/근거 충실성을 모델로 평가한다. 3차에는 긴급·빈 근거 미호출, 카드/사실 유지, 소유권·동시 변경·근거 철회·저장·복원·일반 텍스트 표시를 검증한다. 테스트 606건 통과를 이 후속 기능의 완료로 해석하지 않는다.
