# SOL-03 자유 질문 확장 및 FastAPI 연결 수정 계획서

작성일: 2026-11-18 · 상태: **구현 완료(2026-11-18) / 공개 계약은 팀 명세 반영 필요** · 결과는 [구현 보고서](solution-assist-question.md)

> **구현 결과와 이 계획의 차이(2026-11-18 확인).** 본문은 작성 당시 계획이며, 실제 동작은 구현 보고서를 따른다.
>
> | 항목 | 이 계획 | 실제 구현 |
> | --- | --- | --- |
> | 7장 설정 | 선택적 분리안(미구현), `assist-mode` 기본 fake | 구현됨. `assist-mode` 기본값은 **shared**(AI_MODE를 따름), URL·키는 공통 값으로 대체 |
> | 전체 기한 | 40초 구현 목표 | 구현됨(`assist-total-timeout`, 기본 40초). 초과 시 503·저장 롤백 |
> | 대기 상태 JSON | `factKey: "installedApp"` | `fact: "INSTALLED_APP"`(enum 이름). 다섯 키 외에는 거부 |
> | SOL-03 FACT_UPDATE·SOL-04·SOL-05 무효화 | 각 서비스에서 처리 | 서비스는 그대로 두고 `SolutionSessionUpdatesImpl`의 CAS·종료 쿼리가 `pending_assist_json`을 함께 비움 |
> | 컨트롤러·메시지 저장소 | 변경 대상 | 변경 없음(기존 messages 진입점·쿼리 재사용) |
> | 승인 카탈로그 | code/version·출처·철회·적용 조건 | code/version 일치, reviewed=true, https 출처, 확인일만 검사. **철회·적용 조건·해시는 미구현** |
> | 대기 질문과 다른 questionId | 거부 | 400 INVALID_REQUEST |
> | 새로고침 복원 | 대기 질문 복원 | 대기 질문만 복원. 마지막 AI 답변은 복원하지 않음 |

## 1. 적용 방향과 현재 상태

기존 `POST /api/solutions/sessions/{id}/messages`에 `purpose`를 추가한다. 별도 `/questions` API는 만들지 않는다. 이 문서는 이전 별도 질문 API 제안을 대체하며, [기존 LangGraph 연결 계획](solution-langgraph-integration-plan.md)의 SOL-03 확장 방향을 구체화한다.

작성 당시에는 Java·Python·DB 구현 변경을 포함하지 않았다. 아래 표의 "현재 코드" 열은 작성 당시 상태이며, 수정 목표는 2026-11-18 구현됐다(차이는 맨 위 표 참고).

| 항목 | 현재 코드 | 수정 목표 |
| --- | --- | --- |
| SOL-03 TEXT | AI-06 사실 추출 | purpose에 따라 사실 추출 또는 질문 답변 |
| SOL-03 CHOICE | 기존 고정 질문의 선택 반영 | 기존 선택과 assist 추가 질문 선택 구분 |
| Spring assist | DTO·검증기·HTTP/fake 클라이언트·전용 read timeout 있음 | 상담 서비스에서 호출 |
| Python OHC `app.main` | `/assist`·`/extract`·`/explain`·`/answer`와 훈련·OCR 라우터를 한 서버에 등록 | 대상 서버별 Spring 계약 대조 후 연결 |
| Spring AI 모드 | `AI_MODE` 하나로 전체 구현 선택 | 전체 API의 계약·준비 상태 확인 후 공통 HTTP 또는 선택적 분리 결정 |
| 승인 근거 | Spring 승인 카탈로그 미구현 | 승인 code/version의 원문만 ANSWER에 전달 |

현재 Spring `AiClient.planSolutionAssist`, `answerSolutionAssist`를 재사용한다. 로컬 Python의 `/assist`는 첫 PLAN→후속 PLAN→ANSWER를 실제 로컬 HTTP로 호출하는 계약 테스트가 통과한 상태다. 가짜 모델 검증이며 사용자 화면·DB 흐름 및 Gemini 품질이 완성됐다는 뜻은 아니다.

## 2. Spring 공개 요청 계약

주소: `POST /api/solutions/sessions/{id}/messages`. 회원·비회원 소유권, 쿠키와 CSRF, JSON Content-Type, 기존 16KiB 본문 제한을 유지한다.

| purpose | inputKind | 추가 필드 | 처리 |
| --- | --- | --- | --- |
| 생략 또는 FACT_UPDATE | TEXT | content | 기존 AI-06 사실 추출 |
| 생략 또는 FACT_UPDATE | CHOICE | factKey, selectedCode | 기존 고정 질문 선택 |
| QUESTION | TEXT | content | 새 질문의 PLAN 실행 |
| CLARIFICATION_ANSWER | CHOICE | questionId, selectedCode | 대기 질문 선택 반영 후 PLAN 재실행 |

purpose 생략은 기존 동작과 호환된다. purpose는 실제 문자열과 허용 코드만 받으며 null·빈 값·알 수 없는 값과 표에 없는 조합은 거부한다. 기존 DTO는 추가 키를 엄격히 거부하므로 Spring 배포 전에 프론트가 purpose를 보내면 400이 된다. 프론트는 Spring 배포 후 새 필드를 사용한다.

모든 요청은 표준 UUID `requestId`와 0 이상 정수 `stateVersion`을 포함한다. 기존 버전 상한·JSON 타입·중복 키·후행 JSON 검사를 유지한다. 질문 content는 공백만 불가, 1,000자 이하, 기존 민감정보 검사 적용이다. 질문 원문은 DB·로그·예외 원인에 남기지 않는다. 선택값은 YES/NO/UNKNOWN이다.

새 자유 질문 예시:

```json
{"requestId":"550e8400-e29b-41d4-a716-446655440000","stateVersion":3,"purpose":"QUESTION","inputKind":"TEXT","content":"링크를 눌렀는데 어떻게 해야 하나요?"}
```

추가 질문 선택 예시:

```json
{"requestId":"550e8400-e29b-41d4-a716-446655440001","stateVersion":4,"purpose":"CLARIFICATION_ANSWER","inputKind":"CHOICE","questionId":"550e8400-e29b-41d4-a716-446655440002","selectedCode":"YES"}
```

추가 질문 답변의 factKey는 클라이언트가 보내지 않는다. Spring이 저장한 questionId의 항목을 사용한다. 다른 상담·이미 해제된 질문·과거 질문의 ID는 거부한다. FACT_UPDATE는 기존 `/extract` 요청 형식과 사실 병합·카드 계산 규칙을 유지한다.

## 3. 공개 응답과 프론트 연동

HTTP 200, `{"success":true,"data":{...}}`로 응답한다. 기존 facts·responseCards·nextQuestion·stateVersion에 assistant를 추가하는 안이다. 기존 경로 응답에서 assistant는 null, assist 처리 결과에는 아래 객체를 제공한다. nullable 필드도 명시적으로 내린다.

```json
{
  "success": true,
  "data": {
    "facts": {"transferredMoney":"UNKNOWN","sharedInformation":"UNKNOWN","installedApp":"UNKNOWN","contactContinues":"UNKNOWN"},
    "responseCards": [],
    "nextQuestion": null,
    "stateVersion": 4,
    "assistant": {
      "status": "NEED_USER_INPUT",
      "answer": null,
      "referenceCodes": [],
      "question": {
        "questionId":"550e8400-e29b-41d4-a716-446655440002",
        "factKey":"installedApp",
        "content":"상대방이 알려준 앱을 설치한 적이 있나요?",
        "options":["YES","NO","UNKNOWN"]
      },
      "reasonCode": null
    }
  }
}
```

| assistant.status | answer | question | referenceCodes | reasonCode |
| --- | --- | --- | --- | --- |
| NEED_USER_INPUT | null | Spring 발급 ID와 고정 질문 | [] | null |
| ANSWERED | 검증한 AI 답변 | null | 실제 인용 코드 1개 이상 | null |
| UNABLE_TO_ANSWER | 서버의 고정 안내 | null | [] | OUT_OF_SCOPE / NO_EVIDENCE / LIMIT_REACHED |

UNABLE_TO_ANSWER의 고정 안내는 공개 응답 정책이다. Python 내부 응답의 answer는 이 상태에서 null이다. **현재 Python OHC 흐름은 LIMIT_REACHED를 반환하지 않는다.** 더 물을 수 없으면 현재 정보와 근거로 진행하거나 NO_EVIDENCE를 반환한다. 코드 타입에 LIMIT_REACHED가 남아 있어도 런타임 반환을 기대하지 않는다. Spring이 별도 질문 정책에 따라 LIMIT_REACHED 안내를 만드는 것은 공개 계약으로 구분한다. 임의의 모델 문구를 실패 안내로 사용하지 않는다.

대기 중에는 `nextQuestion=null`로 내려 assist 질문만 보여준다. 기존 고정 질문은 없애지 않는다. 대기가 끝나면 최신 사실 및 UNKNOWN 건너뛰기 이력으로 기존 nextQuestion을 다시 계산한다. SOL-02도 같은 규칙을 사용한다. 프론트는 반환된 stateVersion과 facts·카드·질문을 한 번에 반영하고, 409이면 재조회 후 사용자에게 다시 선택하게 한다. 자동 재전송하지 않는다.

## 4. FastAPI 연결 계약

### 4.1 기능별 주소와 담당 경계

| Spring 입력 | FastAPI 주소 | 역할 |
| --- | --- | --- |
| FACT_UPDATE + TEXT | `/internal/ai/solutions/extract` | description과 knownFacts로 사실 추출 |
| QUESTION / CLARIFICATION_ANSWER | `/internal/ai/solutions/assist` | PLAN으로 추가 정보·근거 판단, ANSWER로 답변 생성 |
| 기존 CHOICE | 호출 없음 | Spring이 사실 및 카드 계산 |

연결 대상은 Python **OHC 브랜치의 `app.main:app`**이다. 로컬 OHC 커밋 `136272c`에서 `/assist`·`/extract`·`/explain`·`/answer`를 한 서버에 등록하고 훈련·OCR 라우터도 함께 등록한 것을 확인했다. 기존 `feature/rag-ai07` 작업 디렉터리의 `app.py`와 구분한다. 실행 예시는 `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`이다. FACT_UPDATE는 기존 `/extract`를 사용하며 assist로 우회시키지 않는다. 각 라우터의 존재만으로 Spring의 응답 봉투·타입·인증 계약과 일치한다고 간주하지 않는다.

주원 브랜치의 `/answer`는 이 문서에서 선택한 `/assist`와 별도 계약이다. 팀에 `/assist` 채택안을 공유하고 한 경로로 확정한다. 같은 역할이라는 설명만으로 두 요청·응답이 호환된다고 가정하거나 실패 시 다른 경로로 자동 전환하지 않는다.

공통 HTTP 헤더:

```text
Content-Type: application/json
X-Internal-Api-Key: 양쪽에서 같은 내부 키
X-Request-Id: Spring이 생성한 UUID
```

각 Python 호출에 별도 추적 UUID를 사용하고 공개 requestId와 매핑한다. 인증 키와 사용자 원문을 로그에 남기지 않는다. 프론트에는 내부 키·Python URL을 전달하지 않는다. 응답 success는 실제 boolean true여야 하고, 원본 JsonNode에서 타입을 검사한 뒤 상태별 DTO·요청 맥락 검증을 한다.

### 4.2 첫 PLAN

```json
{
  "phase":"PLAN",
  "question":"링크를 눌렀는데 어떻게 해야 하나요?",
  "questionIntent":null,
  "facts":{"transferredMoney":"UNKNOWN","sharedInformation":"UNKNOWN","installedApp":"UNKNOWN","contactContinues":"UNKNOWN"},
  "askedFactKeys":[],
  "remainingClarifications":3
}
```

PLAN 결과 예시:

```json
{"success":true,"data":{"status":"NEED_USER_INPUT","questionIntent":"DEVICE_SAFETY","clarification":{"factKey":"installedApp","content":"상대방이 알려준 앱을 설치한 적이 있나요?","options":["YES","NO","UNKNOWN"]},"candidateReferences":[],"reasonCode":null}}
```

Spring은 현재 UNKNOWN인 허용 키·질문 이력·횟수를 검사한다. 사용자가 볼 질문은 `SolutionFact`의 고정 문구로 만들고 ID는 Spring이 발급한다. AI가 새 사실·카드·기관·연락처를 만들도록 하지 않는다.

### 4.3 선택 이후 후속 PLAN

사용자 선택을 메모리상의 facts에 반영하고 카드 재계산을 준비한다. 저장된 questionIntent는 유지하고 다음 요청을 구성한다.

```json
{
  "phase":"PLAN",
  "question":null,
  "questionIntent":"DEVICE_SAFETY",
  "facts":{"transferredMoney":"UNKNOWN","sharedInformation":"UNKNOWN","installedApp":"YES","contactContinues":"UNKNOWN"},
  "askedFactKeys":["installedApp"],
  "remainingClarifications":2
}
```

Python v1 운영 문서는 후속 question=null을 사용한다. 실제 DTO는 question과 questionIntent를 동시에 허용한다. 원문을 유지하려면 보관 정책과 운영 계약을 합의하고 해당 경로도 테스트한다. 의도 코드는 TRANSFER_RESPONSE, DEVICE_SAFETY, INFO_LEAK_RESPONSE, CONTACT_RESPONSE, GENERAL이다.

### 4.4 후보 승인과 ANSWER

PLAN의 EVIDENCE_READY는 candidateReferences에 code·문자열 version을 반환한다. Spring이 승인 카탈로그에서 같은 code/version의 원문을 읽는다. 후보 본문을 추정하거나 미검토 자료를 승인 자료로 취급하지 않는다.

```json
{"success":true,"data":{"status":"EVIDENCE_READY","questionIntent":"DEVICE_SAFETY","clarification":null,"candidateReferences":[{"code":"APP_REMOVE_GUIDE","version":"1"}],"reasonCode":null}}
```

아래 코드와 문단은 계약 예시이며 실제 승인 자료가 아니다.

```json
{
  "phase":"ANSWER",
  "question":null,
  "questionIntent":"DEVICE_SAFETY",
  "facts":{"transferredMoney":"UNKNOWN","sharedInformation":"UNKNOWN","installedApp":"YES","contactContinues":"UNKNOWN"},
  "verifiedReferences":[{"code":"APP_REMOVE_GUIDE","title":"앱 관련 검토 안내","excerpt":"검토된 원문 문단"}]
}
```

```json
{"success":true,"data":{"status":"ANSWERED","answer":"제공된 근거의 안내를 확인해 주세요.","referenceCodes":["APP_REMOVE_GUIDE"],"reasonCode":null}}
```

ANSWER에서는 새 검색을 하지 않는다. 실제 문자열·길이·금지 표현과 요청 근거 코드의 부분집합인지 검사한다. 승인 후보가 0개면 ANSWER를 부르지 않고 NO_EVIDENCE 안내를 반환한다. 연결 대상 OHC 커밋 `136272c` 기준 근거는 41개·승인 0개이며 모든 문단에 version 메타데이터가 있다. 이전 feature 브랜치의 35개와 구분한다. 배포 대상 파일을 재확인하고 콘텐츠 검토 일정을 병행한다.

사기 유형(fraudType)은 현 assist 요청 필드에 없다. 중고거래 등 유형별로 근거 적용 조건이 다를 수 있으므로 유형의 확인·검색 필터·Spring 승인 검증 방식을 별도 확정한다. 미확인 유형을 추측해 제도 적용을 단정하지 않는다. 코드 인용 검증만으로 의미적 정확성을 보장하지 않는다.

## 5. 서비스·저장·동시성 설계

`SendSolutionMessageService`는 purpose를 기준으로 기존 사실 처리 또는 새 `SolutionAssistService`에 위임한다. 기존 FACT_UPDATE의 도메인 정책은 유지하며, 사실 변경 시 assist 대기 상태를 무효화하는 연동만 추가한다.

```text
소유권·중복·ACTIVE·버전 확인 및 스냅샷 조회
 → 민감정보 검사
 → 트랜잭션 밖 PLAN
 → 필요하면 승인 근거 조회 및 ANSWER
 → 결과 검증
 → 소유권·ACTIVE·중복·버전 재검증
 → READ COMMITTED 쓰기 트랜잭션에서 CAS + 이력 저장
 → 커밋한 결과 반환
```

저장할 대기 구조 예시:

```json
{"questionId":"550e8400-e29b-41d4-a716-446655440002","factKey":"installedApp","questionIntent":"DEVICE_SAFETY","askedFactKeys":["installedApp"],"remainingClarifications":2}
```

질문을 사용자에게 발급하는 커밋에서 해당 키를 askedFactKeys에 넣고 남은 횟수를 한 번 줄인다. 선택 답변을 받을 때는 추가 차감하지 않는다. 처음 PLAN에는 []·3을 보내고 첫 질문 저장 후 상태는 [해당 키]·2다. 같은 요청의 재전송이 횟수를 다시 차감하지 않도록 requestId와 CAS로 보장한다. UNKNOWN 선택도 이미 물은 키에 남긴다. 0회이면 추가 질문을 발급하지 않으며, 답변할 정보가 있으면 답변 단계는 계속할 수 있다.

`pending_assist_json` 컬럼을 제안한다. facts·카드·질문·대기 상태·버전·요청 이력을 하나의 트랜잭션으로 저장한다. 질문 원문과 AI 답변 원문은 이 JSON에 넣지 않는다. 마이그레이션 번호는 다른 브랜치와 확인 후 결정한다.

모든 정상 처리(추가 질문·최종 답변·고정 답변 불가)는 요청 이력을 남기고 버전을 한 번 증가시키는 안을 제안한다. 중복 requestId는 기존 DUPLICATE_REQUEST로 거부하며 응답 재생은 제공하지 않는다. 새 QUESTION이 대기 중에 오면 409로 거부하는 안이며 해당 오류 코드는 명세에 추가한다. 이미 확정된 사실과 추가 질문 선택이 충돌하면 기존 버전/질문 유효성을 검사하고 조용히 덮어쓰지 않는다.

외부 오류·응답 검증 실패·시간 초과·필수 카드 누락이면 선택 사실도 먼저 저장하지 않는다. 저장 직전 상태가 바뀌면 AI 결과를 폐기한다. 소유권 404, 중복, 완료, 버전, 질문 유효성의 오류 우선순위는 기존 SOL-03 정책을 기준으로 테스트에 고정한다.

요청 이력에는 purpose·안전한 고정 문구·선택값·질문 ID·실제 인용 코드 등 필요한 구조만 기록한다. 원문 비저장 정책이면 새로고침 시 과거 자유 질문/답변을 복원할 수 없음을 프론트에 알린다. purpose를 보관할 메시지 컬럼 또는 별도 요청 테이블은 마이그레이션 설계 단계에 정한다.

## 6. 원문 보관 및 기존 SOL 연동

**원래 질문을 추가 질문 사이에 보관할지는 팀 결정 1순위다.** 현재 정책대로 폐기하면 후속 PLAN/ANSWER는 의도 코드·사실에 의존하므로 세부 질문 맥락이 줄어든다. HTTP 세션/Redis 임시 보관 또는 클라이언트 재전송은 기술적으로 가능하지만 보관 기한·동의·접근·삭제·상담별 격리와 Python 운영 계약을 함께 정해야 한다. 합의 전 영속 DB·로그에 원문을 저장하지 않는다.

| 경로 | 추가 처리 |
| --- | --- |
| SOL-01 | 기존 시작·AI-06 유지. 새 상담은 assist 대기 상태 없음 |
| SOL-02 | 소유권 확인 후 대기 질문 복원, 대기 중 nextQuestion=null, 완료면 두 질문 모두 null |
| SOL-03 FACT_UPDATE | 기존 사실 처리 유지, 사실 변경 커밋 시 이전 assist 대기 무효화 |
| SOL-03 assist | questionId·의도·횟수 저장/검증, 선택 후 카드 재계산 및 PLAN 재실행 |
| SOL-04 | 사실 정정 커밋 시 assist 대기 무효화 |
| SOL-05 | 종료 커밋 시 assist 대기 제거, 종료 이후 갱신 거부 |

사용자 선택으로 사실이 바뀌는 것은 Spring 책임이다. Python 답변 문장으로 카드를 생성하거나 변경하지 않는다. 사실에 맞는 검토 카드가 카탈로그에 없으면 기존처럼 누락시키지 않고 실패 처리한다. 카드 카탈로그도 현재 운영 콘텐츠 연결이 필요한 별도 선행 항목이다.

## 7. 기능별 HTTP 설정과 시간 제한

현재 AI_MODE 하나는 모든 AI 호출을 http/fake로 전환한다. 연결 대상 OHC의 `app.main`은 상담·훈련·OCR 라우터를 함께 제공하므로 **assist 전용 모드 분리는 필수 선행 작업이 아니다.** 먼저 모든 Spring 호출의 주소·헤더·요청·응답·필수 설정을 대조한다. 전체 계약과 준비 상태가 맞으면 기존 AI_MODE=http 및 AI_BASE_URL·AI_API_KEY를 재사용한다. 일부만 준비됐거나 서버/키가 다르면 아래 선택적 분리안을 적용한다. 등록된 API가 있다는 이유만으로 기존 기능까지 정상이라고 표시하지 않는다.

일부 기능만 HTTP로 연결할 때의 설정. **구현됐으며 실제 기본값은 다음과 같다.**

```yaml
wsid:
  ai:
    mode: ${AI_MODE:fake}
    assist-mode: ${AI_ASSIST_MODE:shared}   # shared / fake / http
    assist-base-url: ${AI_ASSIST_BASE_URL:${AI_BASE_URL:http://localhost:8000}}
    assist-api-key: ${AI_ASSIST_API_KEY:${AI_API_KEY:local-dev-key}}
    assist-read-timeout: ${AI_ASSIST_READ_TIMEOUT:20s}
    assist-total-timeout: ${AI_ASSIST_TOTAL_TIMEOUT:40s}
```

분리가 필요하다고 결정한 경우 기존 AiClient 빈은 기존 mode를 유지하고 별도 `SolutionAssistGateway`가 assist-mode에 따라 기존 fake/http 구현을 위임 호출하는 안이다. 이렇게 하면 `AI_MODE=fake`, `AI_ASSIST_MODE=http`로 assist만 실제 서버에 보낼 수 있다. assist URL/키가 기존 AI-06 서버와 다를 수 있으므로 독립 설정을 둔다. 중복 AiClient 빈으로 주입이 모호해지지 않게 한다. 기존 생성자·테스트의 호환도 유지한다.

공통 모드이면 Spring AI_API_KEY, 분리 모드이면 Spring assist 키를 Python AI_API_KEY와 맞춘다. 위 assist-mode/base-url/api-key/total-timeout은 2026-11-18 구현됐다. 기본 shared에서는 기존 AI_MODE·AI_BASE_URL·AI_API_KEY를 그대로 쓴다. 내부 키는 환경별 비밀 설정으로 관리한다.

현재 Python 단계 예산은 PLAN·ANSWER 각각 기본 15초, Spring assist read는 각 20초다. 사용자 요청 전체는 **40초 제한(구현됨)**으로 하고 연결·직렬화·카탈로그·DB 시간을 포함한다. 순차 호출 전 남은 예산을 확인하고 HTTP 및 저장 단계가 남은 시간을 초과하지 않도록 제한한다. read timeout만으로 전체 40초가 보장되지는 않는다. AI 대역으로 실패를 재현하고 실제 Gemini 측정 후 값을 확정한다.

Python 실패 본문은 `{"success":false,"error":{"code":"...","message":"..."}}`이다. message 추가는 현재 Spring 내부 클라이언트의 HTTP 상태 기반 실패 처리에 영향을 주지 않는다. 외부 message나 응답 본문을 사용자·로그·예외 원인에 그대로 복사하지 않는다. Python의 401·422·502·503·504 등 비-2xx, 잘못된 JSON/필드/인용은 내부 AiUnavailableException으로 변환하고 공개 API에는 503 AI_SERVICE_UNAVAILABLE를 반환하는 안이다. 성공 HTTP의 UNABLE_TO_ANSWER는 정상 비즈니스 결과로 200이다. 자동 재시도는 하지 않는다.

## 8. 변경 파일별 작업

| 파일/영역 | 구현할 내용 |
| --- | --- |
| solution/dto/SendSolutionMessageRequest.java | purpose 생략 호환, 조합별 엄격 검사, questionId 추가 |
| solution/controller/SolutionController.java | 기존 messages 진입점 재사용, 새 분기·응답 연결 |
| solution/service/SendSolutionMessageService.java | FACT_UPDATE 유지, assist service 위임 |
| solution/service/SolutionAssistService.java 신규 | 조회→PLAN→승인→ANSWER→CAS 저장 |
| solution/dto/SolutionMessageResponse.java | assistant 상태·답변·추가 질문·reasonCode 추가 |
| solution/domain/SolutionSession.java, repository/SolutionSessionUpdates* | 대기 상태의 엄격한 읽기·CAS 갱신·종료 제거 |
| solution/domain/SolutionFact.java, SolutionFacts.java, ResponseRules.java | 고정 질문·선택 반영·카드 계산 재사용 |
| solution/service/SolutionQueryService.java, SolutionStateResponse.java | 대기 질문 복원 및 기존 질문 숨김 |
| UpdateSolutionFactsService.java, CompleteSolutionService.java | 동일 트랜잭션에서 무효화/제거 |
| SolutionMessage.java, SolutionMessageRepository.java, 마이그레이션 | 원문 없는 purpose/질문/선택 이력과 요청 ID 유일성 |
| ai/AiClientConfig.java, AiProperties.java 및 필요 시 SolutionAssistGateway.java | OHC 서버의 전체 계약 대조 후 공통 설정 재사용 또는 기능별 분리 선택, 전체 기한 전달 |
| ai/HttpAiClient.java 및 assist DTO/Validator | 기존 구현 재사용, 남은 시간·분리 설정 대응 |
| content/ApprovedReferenceCatalog 신규 | 승인 원문 code/version·출처·철회·적용 조건 관리 |
| application.yml | 기능별 설정 추가, 기존 모드 동작 회귀 검증 |
| solution/security/SolutionSecurityConfig.java | 기존 messages 공개 규칙 재사용, CSRF 유지; 새 URL 허용 불필요 |
| docs/WSID-API-Spec.md, 관련 README | 합의된 목적/응답/오류/복원 정책 명세 갱신; 공유 명세 커밋 범위는 팀 기준 적용 |
| 프론트 상담 UI | purpose 전송, 추가 선택지와 답변 표시, 버전 및 409 재조회 |

## 9. 구현 순서와 검증

1. `/assist` 채택, 원문 유지, 프론트 purpose·응답, 질문 충돌 오류·이력 복원, 사기 유형 정책을 팀과 정한다. 콘텐츠·카드 검토 일정도 함께 잡는다.
2. OHC `app.main`의 상담·훈련·OCR 계약을 Spring과 대조한다. 모두 준비되면 기존 공통 HTTP 설정을 재사용하고, 일부만 준비되면 gateway/기능별 설정 분리를 적용한다. 두 경우 모두 FACT_UPDATE·훈련 기능 회귀를 확인한다.
3. 대기 도메인·마이그레이션·CAS·중복 처리 및 SOL-02/04/05 무효화를 구현한다.
4. SOL-03 DTO와 서비스 분기, 승인 카탈로그, 전체 기한과 사용자 응답을 연결한다.
5. 단위/HTTP 대역/DB·Redis 통합 테스트 후 실제 로컬 Python 계약 테스트, 마지막으로 실제 모델·승인 자료 평가를 수행한다.

필수 시나리오: 기존 purpose 생략, FACT_UPDATE TEXT/CHOICE, QUESTION→답변, 추가 질문→YES/NO/UNKNOWN→재실행, 횟수/중복 질문, 승인 근거 0개·폐기/버전 불일치, 인용 밖 코드, 범위 밖, 두 단계 타임아웃, 전체 기한, 소유권·CSRF, duplicate·버전 동시성, AI 대기 중 정정/종료, 새로고침 복원, 원문 로그 비노출. 질문 발급의 횟수 차감과 선택 제출의 무차감도 검증한다.

기존 `liveAiTest`는 내부 HTTP 계약만 검증한다. 새 기능 완료 기준은 SOL-03→서비스→Python→승인→저장→응답의 전체 검증이며, 실제 Gemini 품질은 별도 사람이 검토한다. 작성 시점에는 미구현이었고, 이후 Spring 서비스·저장·복원과 단위/DB/HTTP 통합 테스트가 구현·통과했다. 프론트 연결, `liveAiTest` 재실행, 실제 모델·승인 자료 평가는 남아 있다.
