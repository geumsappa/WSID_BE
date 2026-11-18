# 상담 LangGraph 도입·추가 질문·근거 답변 연결 및 영향 범위

> **후속 설계 기준:** 공개 SOL-03 확장과 FastAPI 연결의 최신 수정안은 [SOL-03 자유 질문 확장 계획](solution-assist-question-plan.md)을 따른다. 이 문서의 최초 현황·파일 제안은 작성 당시 기록이다. 특히 5.1장의 질문 배치 대신 최신안의 `assistant.question`을 사용하고, assist 질문 대기 중 `nextQuestion`은 null로 제공한다.

작성일: 2026-11-17 · 상태: **설계 제안 · 계약 초안 v1 · 3.1장 내부 계약은 그대로 구현(2026-11-17), 공개 API·DB·상담 서비스는 최신 계획 기준으로 구현(2026-11-18, [구현 보고서](solution-assist-question.md))**

> **v1 변경:** 3.1장에 Python 내부 계약(PLAN/ANSWER 요청·응답 필드), 5.1장에 프론트 공개 계약(SOL-03 요청·응답, SOL-02 복원)을 확정 초안으로 정리했다. 필드 이름은 이 문서 기준(`phase`, `questionIntent`, `candidateReferences`, `questionId`, `assistant`)으로 통일한다. 이전에 구두로 공유한 `stage`·`intentCode`·`clarificationId`·`assist`·`pendingClarification` 이름은 쓰지 않는다. 8장은 합의 상태와 남은 결정이다.

확인 기준: Spring `OHC dcee7ff`, 로컬 AI 저장소 `feature/rag-ai07 26eae35`. 이 문서는 실제 두 저장소를 읽고 변경 방향을 정리한 것이며, Java/Python 구현·공유 API 명세·DB는 변경하지 않는다. 새 필드·경로·이름·제한은 합의 전 제안이다.

## 1. 결론과 현재 상태

LangGraph는 Python에 넣는다. 모델은 사용자 정보/근거가 충분한지 판단하고, 그래프는 그 결과에 따라 검색·질문·답변을 연결한다. Spring은 상담 소유권·사실 확정·긴급 카드·질문 정책·근거 승인·버전·상태 저장을 맡는다.

**Python에 그래프만 추가해서는 서비스가 연결되지 않는다.** SOL-03에 입력 통로는 있지만 지금은 사실 보완용이며, 임의의 질문/추가 답변 구분·안전한 대화 상태·자유 답변 응답 필드가 없다. 기존 AI-06·07을 그대로 유지하고 새 내부 상담 흐름 API와 Spring 호출 경계를 추가하는 안이다.

| 확인한 코드 | 현재 상태 |
|---|---|
| AI `ask.py` | 터미널 input/print. 상황 추출 뒤 한 번 검색하고 같은 refs로 질문에 계속 답함 |
| AI `rag/retrieve.py`, `rag/facts.py` | 검색 질의는 facts·fraud_type로 생성. 질문 인자 없음. YES 사실의 상황 태그가 없으면 빈 결과 |
| AI `rag/qa.py` | question·facts·refs·fraud_type으로 answer 생성. 부족 정보 판단/추가 질문 상태 없음 |
| AI `rag/explain.py` | facts·verified_references로 AI-07 explanation 생성. 질문 답변 함수와 계약이 다름 |
| AI `rag/extract.py` | extract_facts(description)만 있음. knownFacts 인자가 없고 fraudType은 데모 추가 값. FastAPI AI-06 어댑터가 그대로 완성돼 있는 것은 아님 |
| AI `requirements.txt` | LangGraph·FastAPI 의존성 없음. 서버 라우터 없음 |
| AI 근거 파일/인덱스 | references.jsonl 35개 중 reviewed=true 0개. index.build의 reviewed_only 기본값은 false |
| Spring SOL-03 | TEXT→AI-06→UNKNOWN만 보완, CHOICE→현재 factKey 검증. 다음 질문은 고정 순서 |
| Spring AI-07 | 클라이언트·검증기만 있음. 상담 서비스에서 explainSolution 호출 안 함 |
| Spring SOL-03/04 응답 | facts·responseCards·nextQuestion·stateVersion. 자유 답변/질문 식별자 없음 |

초기 구현에서 사용자에게 실제로 보여 줄 답변은 승인 자료가 있어야 한다. 현재 데모가 답변한다는 사실만으로 운영 근거 승인이 완료된 것으로 간주하지 않는다.

## 2. 그래프와 사용자 대기 방식

LangGraph의 StateGraph는 상태·노드·조건부 edge로 흐름을 구성한다. 다음은 이 서비스용 제안이며 프레임워크가 자동으로 제공하는 판단 기능은 아니다. [공식 Graph API 문서](https://docs.langchain.com/oss/python/langgraph/graph-api)

```text
정규화된 질문·최신 facts·이전 추가 질문 상태 입력
  → 현재 질문을 반영해 근거 검색
  → 모델의 구조화된 충분성 판단
     ├─ USER_INFO_MISSING → 추가 질문 제안 반환 → END
     ├─ EVIDENCE_MISSING → 조건을 바꿔 제한 재검색
     │                    → 여전히 부족하면 UNABLE_TO_ANSWER → END
     └─ 충분함 → 후보 근거 반환 → Spring 승인 검증
                  → 검증된 근거만 받아 답변 생성·검사 → END
```

사용자 응답을 기다리는 동안 HTTP 요청을 유지하지 않는다. **1차는 요청마다 END로 끝내고 Spring이 저장한 구조화 상태로 다음 실행을 시작**한다. Python이 사용자별 메모리/전역 변수를 기억한다고 가정하지 않는다.

LangGraph interrupt/checkpointer를 쓰면 Python에도 영속 상태·thread_id 관리가 필요하다. 공식 interrupt는 저장된 checkpoint를 같은 thread_id로 재개하는 방식이다. Spring 저장과 별도 영속 저장소가 생기고 원문이 checkpoint에 남을 수 있으므로 이번 안은 이를 기본으로 쓰지 않는다. 추후 장시간 작업 복구가 필요할 때 소유권·버전·보관 정책과 함께 검토한다. [공식 Interrupts 문서](https://docs.langchain.com/oss/python/langgraph/interrupts)

사용자 정보 부족과 검색 근거 부족은 다른 결과여야 한다(3.1장의 NEED_USER_INPUT과 UNABLE_TO_ANSWER `NO_EVIDENCE`). 근거가 없다는 이유로 사용자를 계속 질문하거나, 사실이 UNKNOWN이라는 이유만으로 답변과 무관한 네 질문을 모두 요구하지 않는다. 상한은 추가 질문 최대 3회(Spring이 `remainingClarifications`로 전달·강제)·재검색 최대 2회(Python 내부)를 초안으로 하고 실제 값은 측정 후 합의한다. 사용자 UNKNOWN 선택·이미 물은 항목(`askedFactKeys`)·상한 도달(`LIMIT_REACHED`)도 종료 조건이다.

## 3. 근거 승인 경계와 새 내부 API

현재 AI-07은 Spring이 넘긴 verifiedReferences 안에서만 설명하고 반환 referenceCodes도 요청 코드의 부분집합이어야 한다. 그래프가 뒤에서 새 문단을 찾아 AI-07 검증을 통과한 것처럼 처리해서는 안 된다.

제안 경로는 `POST /internal/ai/solutions/assist`다. **정식 API 번호가 정해진 것은 아니다.** 기존 AI-06 `/extract`·AI-07 `/explain`의 요청/응답은 유지한다.

한 경로에 다음 두 실행 단계를 구분하는 안을 제안한다.

| phase | 역할·결과 |
|---|---|
| PLAN | question(첫 실행)·questionIntent·facts·askedFactKeys·remainingClarifications를 받아 검색/충분성 판단. NEED_USER_INPUT, EVIDENCE_READY, UNABLE_TO_ANSWER 중 하나 반환(필드는 3.1장) |
| ANSWER | Spring이 승인한 동일 질문 맥락과 verifiedReferences를 받아 답변. ANSWERED 또는 UNABLE_TO_ANSWER. 새로운 검색은 이 단계에서 하지 않음 |

### 3.1 내부 계약 초안 v1 (Spring ↔ Python)

공통: `POST /internal/ai/solutions/assist`, `Content-Type: application/json`, `X-Request-Id: <uuid>`. 응답은 기존 내부 API와 같은 `{"success": true, "data": {...}}`이고 success는 실제 boolean이다. 실패는 5xx로 보내며 Spring은 503 `AI_SERVICE_UNAVAILABLE`로 처리하고 상담 상태를 바꾸지 않는다. Python은 요청 사이 상태를 저장하지 않는다. 필요한 상태는 Spring이 매번 보낸다. 사실 키·값은 AI-06과 같다(`transferredMoney`, `sharedInformation`, `installedApp`, `contactContinues` / `YES`, `NO`, `UNKNOWN`).

**PLAN 요청**

```json
{
  "phase": "PLAN",
  "question": "앱 관련해서 어떻게 해야 하나요?",
  "questionIntent": null,
  "facts": {"transferredMoney": "YES", "sharedInformation": "NO", "installedApp": "UNKNOWN", "contactContinues": "UNKNOWN"},
  "askedFactKeys": [],
  "remainingClarifications": 3
}
```

| 필드 | 규칙 |
|---|---|
| question | 새 질문 원문(최대 1,000자). 추가 질문에 답한 뒤 다시 실행할 때는 null. Spring은 원문을 저장하지 않으므로 후속 실행에는 questionIntent만 전달한다 |
| questionIntent | 첫 실행은 null. 이후에는 첫 PLAN이 돌려준 값을 그대로 보낸다 |
| facts | 현재 확정 사실 4개 전체. 추가 질문의 답은 Spring이 반영한 뒤 보낸다 |
| askedFactKeys | 이 질문 흐름에서 이미 물은 사실 키. 같은 항목을 다시 묻지 않게 한다 |
| remainingClarifications | 남은 추가 질문 횟수. 0이면 NEED_USER_INPUT을 반환할 수 없다 |

**PLAN 응답 data** (상태와 관계없이 모든 키를 보내고, 해당 없는 값은 null 또는 빈 배열)

```json
{
  "status": "NEED_USER_INPUT",
  "questionIntent": "DEVICE_SAFETY",
  "clarification": {"factKey": "installedApp", "content": "상대방이 알려준 앱을 설치한 적이 있나요?", "options": ["YES", "NO", "UNKNOWN"]},
  "candidateReferences": [],
  "reasonCode": null
}
```

| status | 채우는 필드 | Spring 처리 |
|---|---|---|
| NEED_USER_INPUT | questionIntent, clarification | factKey가 4개 키 중 현재 UNKNOWN이고 askedFactKeys에 없는지, options가 YES/NO/UNKNOWN인지 검사. 서버 questionId 발급·저장 |
| EVIDENCE_READY | questionIntent, candidateReferences `[{code, version}]` 1~10개 | 승인 근거 원본과 코드·버전 대조. 통과한 것만 ANSWER로 전달 |
| UNABLE_TO_ANSWER | reasonCode `OUT_OF_SCOPE` / `NO_EVIDENCE` / `LIMIT_REACHED` | 확인 불가 안내. 사실·카드 유지 |

questionIntent는 합의한 코드 목록에서만 고른다. 초안: `TRANSFER_RESPONSE`, `DEVICE_SAFETY`, `INFO_LEAK_RESPONSE`, `CONTACT_RESPONSE`, `GENERAL`. 근거 재검색은 Python 안에서 최대 2회로 제한한다.

**ANSWER 요청**

```json
{
  "phase": "ANSWER",
  "question": "앱 관련해서 어떻게 해야 하나요?",
  "questionIntent": "DEVICE_SAFETY",
  "facts": {"...": "4개 전체"},
  "verifiedReferences": [{"code": "APP_REMOVE_GUIDE", "title": "악성 앱 삭제 방법", "excerpt": "..."}]
}
```

verifiedReferences는 AI-07과 같은 형식이다(code `[A-Z0-9_]{1,100}`, 최대 10개, title 200자·excerpt 2,000자). question은 PLAN과 같은 규칙이다(후속 실행이면 null). ANSWER에서는 새 검색을 하지 않는다.

**ANSWER 응답 data**

```json
{"status": "ANSWERED", "answer": "설치한 앱은 바로 삭제하고 …", "referenceCodes": ["APP_REMOVE_GUIDE"], "reasonCode": null}
```

| status | 채우는 필드 |
|---|---|
| ANSWERED | answer(최대 2,000자), referenceCodes(요청 verifiedReferences 코드의 부분집합, 1개 이상) |
| UNABLE_TO_ANSWER | reasonCode `NO_EVIDENCE` 등 |

**Spring이 거부하는 응답(→ 503, 상태 무변경):** 정해지지 않은 status·questionIntent·reasonCode, 상태별 필수/금지 필드 위반, 실제 JSON 타입 불일치, 허용되지 않은 clarification factKey, 요청 밖 referenceCodes, 길이 초과, 답변 안의 전화번호·계좌번호·승인되지 않은 기관/연락처.

**구현하면서 정한 세부 규칙(Spring 내부 클라이언트 반영, Python과 확인 필요):**

| 항목 | 규칙 |
|---|---|
| 응답 키 | 상태와 관계없이 PLAN은 5개, ANSWER는 4개 키가 모두 있어야 한다. 해당 없으면 null/빈 배열. 없는 키는 거부, 추가 키는 무시 |
| facts | 요청은 항상 네 키를 모두 보낸다 |
| remainingClarifications | 0~3 정수 |
| clarification | content는 1~200자, options는 정확히 `["YES","NO","UNKNOWN"]` 순서 |
| candidateReferences | `{code, version}` 1~10개, 코드 중복 불가. code는 `[A-Z0-9_]{1,100}`, version은 문자열 `[A-Za-z0-9._-]{1,50}` |
| questionIntent | NEED_USER_INPUT·EVIDENCE_READY에서는 필수, UNABLE_TO_ANSWER에서는 null 허용. 후속 실행에서는 요청 값과 같아야 한다 |
| ANSWER | 요청 verifiedReferences는 1개 이상. UNABLE_TO_ANSWER의 reasonCode로 LIMIT_REACHED는 쓰지 않는다 |
| 금지 출력 | 답변과 추가 질문 문장 모두 AI-07과 같은 링크·이메일·HTML·전화 표기 검사 |
| 실패 | HTTP 오류·타임아웃·형식 위반은 모두 `AiUnavailableException`(원인 미보관), 자동 재시도 없음 |

**Python이 지킬 것:** 사용자가 말하지 않은 사실을 추측하거나 UNKNOWN을 NO로 바꾸지 않는다. 카드·기관·연락처를 만들지 않는다. PLAN·ANSWER 각각 한 HTTP 요청 안에서 끝낸다. 사용자 질문·프롬프트·LLM 응답 원문을 로그에 남기지 않는다. 정보 부족/근거 있음/범위 밖 분기를 모델 대역으로 테스트한다.

Spring은 질문을 확인한 뒤 서버 questionId를 발급해 저장한다. 모델이 만든 ID를 상담 소유권/질문 식별의 근거로 사용하지 않는다. 1차 추가 질문은 기존 네 factKey와 YES/NO/UNKNOWN 범위로 제한한다. 모델이 그중 필요한 항목을 고르되 자유 문구의 정책 검증이 확실하지 않으면 승인된 질문 템플릿을 렌더링한다. 새로운 사실/자유 답변 질문이 필요하면 별도 스키마·저장·검증을 설계해야 한다.

EVIDENCE_READY에서는 후보의 코드·버전·본문을 Spring의 승인 원본과 대조한다. 코드 이름에 V1이 들어가거나 Python이 reviewed=true라고 반환했다는 것만으로 신뢰하지 않는다. 현재 Spring에 승인 근거 카탈로그 구현이 없으므로 `ApprovedReferenceCatalog` 같은 경계를 새로 만들고 승인된 manifest/원문·버전·해시를 관리해야 한다. 검증 통과한 원문을 ANSWER의 verifiedReferences로 보낸다.

ANSWER는 기존 qa.answer의 질문 답변 로직을 검증 후 재사용하는 안이다. AI-07 explain은 대응 이유 설명용으로 남겨 둔다. qa와 explain은 공통 출력/인용 검사를 공유할 수 있지만 answer를 explanation으로 이름만 바꿔 기존 계약이 같다고 보지는 않는다.

빈 근거·부족한 근거는 UNABLE_TO_ANSWER를 반환한다. ANSWERED면 승인 근거가 있고 인용 코드가 그 부분집합인지 검사한다. 응답 타입·필수/금지 필드·길이를 상태별로 검증하고, raw JSON에서 실제 boolean/string/array 여부부터 확인한다. 내부 인증 키·X-Request-Id·기한을 사용하고 본문/프롬프트/답변을 로그에 덤프하지 않는다.

## 4. Python 파일별 변경

실제 저장소 루트는 `WSID/AI/WSID_AI`다. 신규 이름은 제안이다.

| 파일 | 변경 내용 |
|---|---|
| `requirements.txt` | 검증한 호환 버전의 LangGraph·FastAPI·서버·스키마 라이브러리 추가. 현재 google-genai 호출을 반드시 LangChain 모델로 전부 바꿀 필요는 없음 |
| `rag/workflow.py`(신규) | StateGraph·조건 분기·PLAN/ANSWER 시작 경로·재검색/추가 질문 제한. 상태별 END 반환 |
| `rag/assess.py`(신규) | 모델이 정보/근거 충분성을 구조화해 반환. 결과를 3.1장 PLAN 응답(status·questionIntent·clarification{factKey,content,options}·candidateReferences{code,version}·reasonCode)으로 변환하고, askedFactKeys·remainingClarifications 제한을 먼저 검사 |
| `rag/retrieve.py` | question(첫 실행) 또는 questionIntent(후속 실행, 원문 없음)로 검색 질의를 만드는 인자 추가. 사실뿐 아니라 현재 질문을 검색에 반영. 기존 데모 호출은 기본 인자로 호환 |
| `rag/facts.py` | query_text에 질문 문맥 결합. YES 태그가 없으면 무조건 빈 결과인 정책도 자유 질문 검색과 분리 검토 |
| `rag/qa.py` | 승인 근거만 받는 답변 노드로 감싸기. 요청 사실/참조/길이 검사, 빈 근거 상태·인용·출력 검증. 단순 답변만으로 충분성 판단을 대신하지 않음 |
| `rag/extract.py` | 사실 추출 코어 재사용. knownFacts 처리 어댑터·정정 보호는 Spring과 계약 맞추기. fraudType 추정을 확인 사실로 자동 확정하지 않음 |
| `rag/explain.py`, `rag/guard.py` | AI-07 계약 유지. 새 QA 출력 검사에 공통 로직 재사용 가능. 정규식 통과를 의미적 근거 충실성 검증으로 간주하지 않음 |
| `rag/index.py` | 운영 인덱스는 검토 자료만 포함하도록 명시 설정/검사. 미검토 포함 데모와 운영 인덱스를 구별하고 인덱스 원본 버전 확인 |
| `app/main.py`, `app/routers/solutions.py`, `app/schemas/assist.py`(신규) | FastAPI 시작점, 내부 키·X-Request-Id·phase별 엄격 스키마(3.1장 필드·상태별 필수/금지 필드·길이). AI-06/07 라우터는 기존 계약으로 별도 제공 |
| `ask.py` | 그래프를 호출하는 데모로 교체. 질문마다 최신 결과 사용. 고정 URGENT 문구를 Spring 운영 카드로 이식하지 않음 |
| `tests/`·`README.md` | 모델/검색 대역으로 분기·반복 상한·출처 검증·계약 테스트. 실제 Gemini 품질 평가와 FastAPI 전송 검증은 별도로 기록 |

현재 Python 오류 메시지 일부는 추출 원본 결과를 포함하고 qa는 cause를 붙인다. API 오류/로그로 그대로 노출되지 않도록 서버 경계에서 정제해야 한다. 데모 print/debug를 그대로 운영 로깅으로 사용하지 않는다.

## 5. Spring 파일별 변경과 공개 API 영향

새 공개 URL을 반드시 추가할 필요는 없다. 기존 SOL-03을 확장할 수 있지만 TEXT 하나를 모든 뜻으로 취급하면 새 질문과 추가 질문의 답변을 구분할 수 없다. 다음 purpose를 추가하는 안이다.

- FACT_UPDATE: 기존 사실 보완/선택 입력. 기존 클라이언트는 이 경로를 기본으로 유지.
- QUESTION: 새 자유 질문 시작. inputKind=TEXT·content 필수. 해당 요청에서 원문을 검사하고 Python PLAN/ANSWER에만 전달하며 저장하지 않음.
- CLARIFICATION_ANSWER: 응답으로 받은 서버 `questionId`에 대한 답. 소유권·버전·questionId·대기 factKey/선택지 확인 후 반영하고 questionIntent로 PLAN 재실행(세부는 5.1장).

아래 표는 작성 당시 제안이다. 2026-11-18 최신 계획 기준으로 구현됐으며, 이 표와 다른 점(응답 형태, 저장 컬럼 이름·범위, 무효화 위치 등)은 8장과 [구현 보고서](solution-assist-question.md)를 따른다.

| 실제 경로(Spring 루트 기준) | 필요한 변경 |
|---|---|
| `src/main/java/com/wsid/ai/dto/` | `SolutionAssistPlanRequest`/`SolutionAssistAnswerRequest`/`SolutionAssistResult`, 상태·reasonCode·questionIntent enum, `Clarification`, `CandidateReference{code, version}` 신규(이름은 제안). phase별 필수 필드·원문 없는 toString. ANSWER의 근거는 기존 `VerifiedReference` 재사용 |
| `ai/AiClient.java`, `HttpAiClient.java`, `FakeAiClient.java` | `planSolutionAssist`·`answerSolutionAssist` 메서드와 `/internal/ai/solutions/assist` 경로, raw JsonNode 엄격 검사(3.1장 거부 조건), 상태별로 고를 수 있는 fake, HTTP 계약 테스트. 기존 메서드 추가에 따른 익명 구현/Mockito 설정도 확인 |
| `ai/AiProperties.java`, `AiClientConfig.java`, `application.yml` | 여러 모델 호출+재검색의 총 기한, 전용 타임아웃/RestClient 제안. AI-06/07 공통 20초를 전체 서비스에서 무작정 늘리지 않음 |
| `solution/dto/SendSolutionMessageRequest.java` | `purpose`(생략 시 FACT_UPDATE)·`questionId`(CLARIFICATION_ANSWER에서만 필수) 추가와 purpose×inputKind 조합 검사. 현재 DTO는 추가 키를 거부하므로 Spring 배포 전에 프론트가 새 키를 붙이면 400임 |
| `solution/service/SendSolutionMessageService.java` | FACT_UPDATE와 QUESTION/CLARIFICATION_ANSWER 분리. 전용 상담 흐름 서비스로 위임. 네 질문을 고정 순서로 묻는 기존 동작을 유지/전환하는 기준 명시 |
| `solution/service/SolutionAssistService.java`(신규 제안) | 상태 읽기→Python PLAN→질문/근거 검증→필요 시 ANSWER→출력 검사→저장 전 소유권/ACTIVE/버전 재검증. 외부 호출 동안 DB 트랜잭션 없음 |
| `solution/domain/SolutionQuestion.java` | AI가 고른 허용 항목을 실제 질문으로 만드는 경계. 기존 초기 질문/기존 클라이언트용 fallback은 별도 유지 |
| `solution/domain/SolutionSession.java`, `SolutionSessionUpdates*` | 추가 질문 대기 상태·안전한 질문 의도·횟수·답변 참조를 같은 CAS로 갱신. 기존 facts만 저장하고 AI 상태는 나중에 따로 커밋하지 않음 |
| `content/` 또는 승인 근거 전용 패키지 | 승인 원본·버전/해시·폐기 근거 관리. 기존 ResponseCardCatalog는 카드용이므로 근거 승인 카탈로그 역할까지 이미 있다고 보지 않음 |
| `solution/dto/SolutionMessageResponse.java` | `assistant {status, questionId, answer, referenceCodes}` 추가(흐름 없으면 null). 기존 facts/cards/nextQuestion/stateVersion 유지. AI 추가 질문 문장은 nextQuestion에 담음 |
| `solution/dto/SolutionStateResponse.java`, `SolutionQueryService.java` | 같은 `assistant`를 복원 응답에 추가해 대기 질문(nextQuestion+questionId)·마지막 검증 답변을 이어 보여줌. 응답만 확장하고 DB는 그대로면 새로고침 시 대화가 끊김. 완료 상담은 assistant=null |
| `solution/service/UpdateSolutionFactsService.java` | 사실 수정 시 이전 답변/근거/대기 질문의 유효성 재판정 또는 무효화. SOL-04도 SolutionMessageResponse를 공유하므로 assistant 상태를 최신으로 응답 |
| `solution/service/CompleteSolutionService.java`, `SolutionSessionUpdatesImpl.java` | 종료가 AI 대기 상태도 정리/비활성화하도록 확장. 늦은 Python 결과는 기존 ACTIVE/버전 검사로 차단 |
| `solution/controller/SolutionController.java`, `SolutionSecurityConfig.java` | 같은 SOL-03 URL 재사용이면 새 보안 공개 규칙 불필요. owner/CSRF/입력 검사는 유지 |
| `src/main/resources/db/migration/` | 새 마이그레이션(번호 합의)으로 `solution_sessions.assistant_state_json` 추가 제안: questionId·questionIntent·status·pendingFactKey·askedFactKeys·remainingClarifications·검증 답변과 referenceCodes/버전·기준 stateVersion. 원문 question·프롬프트·그래프 state는 넣지 않음. 기존 V1~V4를 수정하지 않음 |

### 5.1 공개 계약 초안 v1 (프론트 ↔ Spring)

**SOL-03 요청에 추가하는 필드**

| 필드 | 규칙 |
|---|---|
| purpose | `FACT_UPDATE` / `QUESTION` / `CLARIFICATION_ANSWER`. 생략하면 FACT_UPDATE로 처리해 기존 프론트 호출이 그대로 동작한다 |
| questionId | CLARIFICATION_ANSWER에서만 필수. 서버가 발급해 응답으로 준 값이며 현재 대기 중인 질문과 같아야 한다. 다른 purpose에서는 보내면 400 |

- QUESTION: inputKind=TEXT, content 필수(민감정보 검사 후 PLAN/ANSWER에만 전달, 저장하지 않음).
- CLARIFICATION_ANSWER: inputKind=CHOICE면 factKey가 대기 질문의 factKey와 같아야 하고 selectedCode로 사실을 반영한다. inputKind=TEXT면 AI-06으로 그 사실만 보완하며, 여전히 UNKNOWN이면 같은 질문을 유지한다. 반영 후 questionIntent로 PLAN을 다시 실행한다.

```json
{"requestId": "uuid", "stateVersion": 5, "inputKind": "CHOICE", "factKey": "installedApp", "selectedCode": "YES",
 "purpose": "CLARIFICATION_ANSWER", "questionId": "q-7f3a"}
```

**SOL-03 응답에 추가하는 필드** (기존 facts·responseCards·nextQuestion·stateVersion 유지)

```json
{
  "facts": {"...": "..."},
  "responseCards": [],
  "nextQuestion": {"factKey": "installedApp", "content": "상대방이 알려준 앱을 설치한 적이 있나요?", "options": ["YES", "NO", "UNKNOWN"]},
  "stateVersion": 6,
  "assistant": {"status": "NEED_USER_INPUT", "questionId": "q-7f3a", "answer": null, "referenceCodes": []}
}
```

| assistant.status | 의미·화면 처리 |
|---|---|
| NEED_USER_INPUT | AI 추가 질문 대기. 질문 본문은 **nextQuestion 하나로만** 보낸다(고정 질문과 동시에 두 개를 보여주지 않음). 답은 CLARIFICATION_ANSWER + questionId로 보낸다 |
| ANSWERED | answer와 referenceCodes(승인 근거 코드)를 표시한다 |
| UNABLE_TO_ANSWER | 확인 불가 안내. 카드는 그대로 둔다 |

assistant는 AI 질문 흐름이 없으면 null이다(기존 FACT_UPDATE 화면은 그대로). EVIDENCE_READY는 내부 상태라 공개하지 않는다. SOL-04도 같은 응답 DTO를 쓰므로 정정으로 대기 질문의 사실이 확정되면 대기 상태를 해제하고 assistant를 최신 상태로 돌려준다. SOL-02 복원 응답에도 같은 assistant를 추가해 새로고침 뒤 대기 질문·마지막 검증 답변을 이어 보여준다. 완료(SOL-05)된 상담은 assistant를 null로 정리하고, 늦게 도착한 Python 결과는 기존 ACTIVE·버전 검사로 버린다. FACT_UPDATE 입력이 대기 질문의 사실을 채우면 대기 상태를 해제한다.

초기 도입은 SOL-03의 명시적인 QUESTION 분기부터 연결하는 안을 권장한다. **이 단계만 만들면 SOL-01의 초기 고정 질문까지 AI 판단으로 바뀌는 것은 아니다.** 시작부터 AI가 필요한 항목을 고르게 하려면 StartSolutionService의 질문 생성 경로도 별도 전환하고, AI 장애 시 기존 선택형 fallback과 초기 stateVersion=0 저장 규칙을 유지해야 한다.

## 6. 대화 상태·개인정보·긴급 카드

사용자 원문을 DB/로그에 저장하지 않는 기존 정책은 유지한다. 원래 질문을 후속 요청에서도 그대로 복원하려고 raw question·모델 프롬프트·LangGraph checkpoint를 저장해서는 안 된다.

Spring이 저장할 최소 상태는 5장 마이그레이션 행의 `assistant_state_json` 항목(서버 발급 questionId, 허용된 questionIntent, assistant status, 답변 대기 pendingFactKey, askedFactKeys, remainingClarifications, 검증된 answer·referenceCodes/버전, 기준 stateVersion)이다. 사실 자체는 기존 facts_json에 그대로 두고 중복 저장하지 않는다. AI 질문/답변 문장은 별도 출력 검사를 통과한 것만 저장한다. 그래프의 임의 state 전체를 JSON으로 덤프하지 않는다.

**네 사실과 질문 의도 코드만으로 모든 자유 질문을 정확히 복원할 수는 없다.** 1차는 지원 가능한 질문 의도 범위를 제한하고 범위 밖은 확인 불가로 처리한다. 원문을 보관하지 않으면서 더 넓은 질문을 이어가려면 승인된 비식별 요약/구조화 정책을 먼저 합의해야 한다. 메시지 저장의 고정 접수 문구만으로 원래 질문을 재구성할 수 있다고 설명하지 않는다.

긴급 대응 카드는 기존 Spring 규칙과 검토 콘텐츠가 결정한다. 자유 질문 답변과 추가 질문이 긴급 카드를 가리거나 늦춰서는 안 된다. 초기안에서는 카드/사실 저장을 먼저 반환하고 답변 생성을 별도 실행하는 공개 계약, 또는 검증된 현재 카드가 이미 화면에 있는 상태에서 보조 답변을 생성하는 UX를 팀과 확정해야 한다. 동기 그래프를 길게 기다린 뒤에만 새 긴급 카드를 반환하면 "카드 우선" 요구를 충족했다고 볼 수 없다.

Python 장애·재검색 상한·모델 추정·근거 미승인은 기존 사실을 지우지 않는다. AI 초안이 기존 YES/NO를 조용히 정정하지 않으며 SOL-04 직접 수정 정책을 유지한다. 새 상태 저장도 소유권·requestId 중복·stateVersion·완료 상태 검사와 한 트랜잭션으로 반영한다. 여러 Python 호출이 있었다고 사용자 메시지/버전을 단계별로 중복 저장하지 않는다.

## 7. 프론트·테스트·개발 순서

프론트에는 5.1장대로 `purpose`·`questionId` 제출, `assistant.status`(NEED_USER_INPUT/ANSWERED/UNABLE_TO_ANSWER) 표시, AI 추가 질문은 nextQuestion으로 표시, 기존 카드 우선 표시, 복원 응답의 assistant로 대기 질문/답변 이어 보기, 409 재조회·완료 후 늦은 답변 무시가 필요하다. 새 페이지가 반드시 필요한 것은 아니지만 기존 사실 선택 UI만으로 자유 질문·답변 표시가 자동 지원되는 것은 아니다. SOL-04가 같은 응답(assistant 포함)을 쓰는 점도 함께 확인한다.

권장 순서:

1. 지원할 질문 의도/추가 사실 범위, 원문 미저장 상태, 근거 승인 주체, 긴급 카드 우선 응답 방식을 확정한다.
2. PLAN/ANSWER 내부 계약과 SOL-03 목적/assistant 응답을 예시·스키마로 고정한다. AI-06/07 계약은 별도로 유지한다.
3. Python 그래프·충분성 판단·질문 반영 검색을 fake로 검증하고 FastAPI로 감싼다. 승인 인덱스/근거 카탈로그를 함께 준비한다.
4. Spring DTO/클라이언트·출력/근거/질문 검증, 안전한 상태 저장·조회, SOL-03/04/05 연계와 마이그레이션을 구현한다.
5. 프론트 질문 제출·후속 응답·복원·카드 우선 표시를 연결한다. 초기 SOL-01 질문 전환은 별도 단계로 검증한다.

필수 검증은 사용자 정보 부족/근거 부족/답변 가능 구분, 질문에 따라 달라지는 검색, 재검색/추가 질문 상한, 미검토/내용 불일치 근거 거부, 요청 밖 인용 거부, 악성 지시/개인정보, Python 타임아웃, 같은 UUID 경쟁·수정/종료 중 늦은 결과, 새로고침 복원이다. fake 통과·실제 FastAPI 전송·실제 모델 품질을 각각 구분해 보고한다.

작성 당시에는 설계 문서만 작성했다. 이후 Spring 구현과 테스트 결과는 [구현 보고서](solution-assist-question.md)에 있다.

## 8. 합의 상태와 남은 결정 (v1)

| 대상 | 상태 | 전달·확인할 내용 |
|---|---|---|
| Python 담당 | 3.1 계약 초안 공유 필요 | phase/questionIntent/candidateReferences 이름, 상태별 필드, 재검색·추가 질문 상한, 원문 로그 금지 |
| 프론트 | 5.1 계약 초안 공유 필요 | purpose·questionId 제출, assistant 표시, nextQuestion 하나만 표시, 기존 호출은 변경 없음 |
| 콘텐츠·근거 승인 | 미정 | 근거 코드 체계·버전, 승인 원본 관리 주체, AI-07과 공유 여부 |
| 팀 | 미정 | questionIntent 목록, 답변·질문 최대 길이, PLAN/ANSWER 응답 시간 목표와 타임아웃, 긴급 카드 우선 표시 방식(카드 먼저 응답 후 답변 별도 vs 동기) |

Spring 구현 계획서는 [SOL-03 자유 질문 확장 계획](solution-assist-question-plan.md)으로 작성·구현됐다. 공유 명세(`WSID-API-Spec.md`)는 아직 바꾸지 않았다.

**이 문서와 실제 구현의 차이(2026-11-18 확인)**

| 항목 | 이 문서 | 실제 구현 | 남은 결정 |
|---|---|---|---|
| 응답 형태(5.1장) | `assistant {status, questionId, answer, referenceCodes}`, AI 질문은 nextQuestion | `assistant {status, answer, referenceCodes, question{questionId, factKey, content, options}, reasonCode}`, 대기 중 nextQuestion=null | 없음(최신 계획 채택) |
| 추가 질문 답변(5.1장) | CHOICE+factKey, TEXT는 AI-06 보완 | CHOICE만, factKey 금지(서버가 정함), TEXT는 400 | TEXT 답변 지원 여부 |
| 저장 상태(5·6장) | `assistant_state_json`: 상태·검증 답변·인용 코드/버전·기준 버전 | `pending_assist_json`: 대기 질문 5개 항목만. 인용 코드는 메시지 이력에만 저장 | **새로고침 시 마지막 AI 답변 복원 여부** |
| 승인 근거(3장) | 원본·버전·해시·폐기 관리 | code/version·reviewed·출처·확인일만 검사 | 해시·폐기 관리 주체와 방식 |
| 긴급 카드 우선(6장) | 답변 대기로 카드가 늦어지면 안 됨 | 카드는 AI 전에 계산하지만 응답은 AI 완료 후(최대 40초). AI 실패 시 선택과 새 카드도 저장 안 됨 | **카드 먼저 응답 후 답변 별도 vs 현재 동기 방식** |
