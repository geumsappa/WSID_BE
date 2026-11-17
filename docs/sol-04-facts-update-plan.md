# SOL-04 피해 사실 수정 구현·테스트 계획서

> **구현 완료(2026-11-17).** 실제 구현·테스트 결과는 [SOL-04 구현 보고서](sol-04-facts-update.md)를 봐요. 11장 결정 1(UNKNOWN 정정 = 답변 완료 + 수정 기록)·2(nextQuestion·전체 카드, 응답 DTO는 `SolutionMessageResponse` 재사용)·3(같은 값 +1, 재전송 409)·4(완료 409, 카드 없음 503)는 추천안대로 구현했어요.

작성일: 2026-11-17 · 상태: **구현 완료, 구현 전 계획과 결정 근거 보존** · 코드 기준: OHC `d66d8b3`(SOL-03 구현 포함)

명세 출처는 팀 공유 API 명세의 SOL-04 절이다. 현재 코드는 [SOL-01 구현 보고서](sol-01-session-start.md), [SOL-02 구현 보고서](sol-02-session-get.md), [SOL-03 구현 보고서](sol-03-session-message.md)와 대조했다. 명세에 없는 선택은 **제안 정책**으로 구분한다. 이번 작업은 계획서와 목차 작성에 한정하며 구현 코드·DB·보안 설정을 변경하지 않는다.

## 1. 목표와 범위

`PATCH /api/solutions/sessions/{id}/facts`로 사용자가 자신의 피해 사실을 직접 정정한다. 기존 값이 YES/NO여도 변경할 수 있고, 요청에서 빠진 사실은 유지한다. 수정된 전체 사실로 검토 카드와 다음 질문을 다시 계산해 저장한다.

**LLM·FastAPI는 호출하지 않는다.** AI-06이 UNKNOWN만 보완하는 SOL-03과 달리 사용자의 직접 정정은 기존 확정값을 바꿀 수 있다. 알려진 값을 보호하는 `mergeExtracted`를 SOL-04의 변경 함수로 재사용하지 않는다.

```text
브라우저(SESSION 쿠키 + X-CSRF-TOKEN)
  → SOL 전용 보안 체인: 회원·비회원 CSRF 검사
  → 컨트롤러: ID → JSON 본문 → DTO 검사
  → 기존 회원/익명 소유자 결정
  → 짧은 READ COMMITTED 트랜잭션
     → 본인 상담·ACTIVE·버전 검사
     → changes 직접 적용 → 카드·질문 재계산
     → CAS 상태 갱신 + 수정 기록·변경된 질문 저장(제안 정책)
  → 200: facts / responseCards / nextQuestion / stateVersion
  → 프론트: 응답만으로 사실·카드·다음 질문 화면 갱신(추가 GET 불필요)
```

요청·응답 DTO, 직접 수정 도메인, 서비스, 기존 CAS 재사용, 보안 경로, 테스트와 구현 보고서를 개발 범위로 잡는다. AI 클라이언트·FastAPI·AUTH 공통 설정·SOL-05 종료 구현·운영 콘텐츠 승인은 별도 작업이다. 수정 기록을 solution_messages에 남기는 제안은 명세의 관련 저장소(solution_sessions)보다 범위가 넓으므로 6장 정책을 먼저 합의한다.

## 2. 현재 코드와 재사용할 부분

경로의 공통 접두사는 `src/main/java/com/wsid/`다.

| 파일 | 현재 동작 | SOL-04 작업 |
|---|---|---|
| `solution/controller/SolutionController.java` | 시작·복원·메시지·비회원 CSRF 구현. parseId, 16KiB JSON 읽기 함수는 private | PATCH 추가. ID 우선 검사와 기존 본문 읽기를 공통 private 함수로 정리해 재사용 |
| `solution/security/SolutionSecurityConfig.java` | 정확한 SOL-01~03 경로만 허용. facts PATCH는 denyAll | 해당 PATCH만 공개. 회원·비회원 CSRF 유지 |
| `solution/security/SolutionOwnerResolver.java` | findExisting으로 기존 소유자만 결정. 활성 회원 우선 | 그대로 재사용. 새 익명 UUID를 발급하는 resolve는 사용하지 않음 |
| `solution/repository/SolutionSessionRepository.java` | findOwned로 ID+회원 또는 익명 소유자 조건 조회 | 그대로 재사용 |
| `solution/domain/SolutionFacts.java` | withChoice는 한 사실 변경, mergeExtracted는 UNKNOWN만 보완 | 여러 사실을 직접 바꾸는 불변 update/withChanges 함수 추가. 입력 Map 방어적 복사 |
| `solution/domain/SolutionQuestion.java` | 첫 UNKNOWN을 선택하되 답을 마친 UNKNOWN 집합은 제외 | 수정 후 전체 사실과 제외 집합으로 재계산 |
| `solution/domain/ResponseRules.java`, `ResponseCardCatalog.java` | 송금 YES·앱 설치 YES 카드, 검토된 현재 카드만 사용 | 동일 규칙으로 추가·제거·정렬. 카드 규칙을 컨트롤러에 복사하지 않음 |
| `solution/content/UnavailableResponseCardCatalog.java` | 운영 카탈로그는 비어 있음 | 카드 필요 조합은 콘텐츠 연결 전까지 503임을 유지 |
| `solution/domain/SolutionSession.java` | readFacts/readCardReferences/readNextQuestion, 불변 prepareUpdate 제공 | 저장 형식·직렬화 재사용. 관리 엔티티를 직접 변경하지 않음 |
| `solution/repository/SolutionSessionUpdatesImpl.java` | 소유자·ACTIVE·버전 CAS, 상태 JSON/버전+1 변경, JPA 캐시 정리 | 재사용. SOL-03과 같은 상담 행에서 경쟁하도록 유지 |
| `solution/domain/SolutionMessage.java` | choice 팩토리는 factKey·선택 값을 저장하며 UUID 필수 | 별도 correction 팩토리 제안. UUID 없는 PATCH 기록과 기존 CHOICE 팩토리를 구별 |
| `solution/repository/SolutionMessageRepository.java` | 최대 순번·답한 UNKNOWN 키·UUID 확인 | UNKNOWN 이력·순번 재사용. PATCH에서 UUID 중복 검사는 하지 않음 |
| `solution/dto/StartSolutionResponse.java`, `SolutionStateResponse.java` | 카드 전체 필드, 질문, 5개 공개 메시지 필드 제공 | 기존 DTO는 변경하지 않음. SOL-04 응답은 SOL-03의 `SolutionMessageResponse`(전체 카드형)를 그대로 재사용 |
| `common/error/ErrorCode.java` | INVALID_REQUEST·SOLUTION_NOT_FOUND·STATE_VERSION_CONFLICT·SOLUTION_FINISHED·RESPONSE_CONTENT_UNAVAILABLE 존재 | 기존 오류 사용. 새 오류 추가를 기본 전제로 두지 않음 |

현재 공통 CORS 설정은 PATCH와 X-CSRF-TOKEN을 이미 허용한다. AUTH의 공통 SecurityConfig나 `/api/auth/csrf` 회원 전용 정책을 이번에 변경할 필요가 없다. 비회원은 기존 `/api/solutions/csrf`를 사용한다.

## 3. 요청 계약과 검사 순서

```json
{
  "stateVersion": 3,
  "changes": {
    "installedApp": "YES"
  }
}
```

| 항목 | 제안 검증 규칙 |
|---|---|
| 루트 | 필수 JSON 객체. 허용 키는 stateVersion·changes 두 개뿐 |
| stateVersion | 필수 실제 JSON 정수. SOL-03과 같은 0~2,147,483,646. 문자열·소수·boolean·null 거부 |
| changes | 필수 JSON 객체. 1~4개 항목. 빈 객체·null·배열 거부 |
| 사실 키 | transferredMoney·sharedInformation·installedApp·contactContinues만 허용. 대소문자·공백 보정 없이 정확히 검사 |
| 사실 값 | 실제 문자열 YES/NO/UNKNOWN만 허용. true/false·숫자·소문자·null 거부 |
| 추가 필드 | requestId·content·userId·소유자 ID·모르는 키 모두 거부. SOL-03 요청 필드를 이 API에 붙이지 않음 |

JSON 타입은 `JsonNode`에서 확인한 뒤 검증된 DTO로 바꾼다. DTO는 평범한 Map 또는 solution enum Map을 불변 복사하고, 잘못된 값 하나가 있어도 전체 요청을 400으로 거부한다. 잘못된 항목만 조용히 버리거나 부분 적용하지 않는다. toString에는 버전·키/개수 정도만 표시하고 수정 전후 값을 덤프하지 않는다.

검사 순서는 **CSRF → ID 형식(404) → JSON/DTO(400 또는 415) → 소유권(404) → 상태/버전(409)**이다. 유효한 CSRF 아래 잘못된 ID+깨진 JSON은 404, 올바른 ID+깨진 JSON은 400이다. 토큰이 없으면 먼저 403이다. 없는 상담의 형식 오류 본문은 400이 될 수 있으므로 "모든 잘못된 요청은 무조건 404"라고 설명하지 않는다.

SOL-03처럼 ID를 검사한 다음 요청 스트림을 읽는다. 기존 제한 읽기·Content-Type 검사·후행 JSON 거부 함수를 PATCH와 messages에서 공유하되 SOL-01의 본문 선택 규칙에는 적용하지 않는다. 최대 16KiB, 빈 본문 400, 비JSON 본문 415를 유지한다. **중복 JSON 키는 SOL-03·04 모두 400으로 거부한다(확정).** 공유 파서에 Jackson `StreamReadFeature.STRICT_DUPLICATE_DETECTION`을 켠다. 지금은 `{"installedApp":"YES","installedApp":"NO"}`의 마지막 값이 조용히 쓰인다. 정상 프론트는 이런 본문을 보내지 않으므로 SOL-03 영향은 사실상 없지만 SOL-03 회귀 테스트를 추가한다.

## 4. 성공 응답과 프론트 동작

HTTP 상태는 **200 OK**, 성공 봉투는 기존 `ApiResponse.ok`다. SOL-04 응답 필드는 facts·responseCards·**nextQuestion**·stateVersion 네 개다. 명세 응답 예시에는 nextQuestion이 없지만, 명세 개요가 "카드·후속질문 재계산"이므로 재계산한 질문을 돌려주는 것이 개요와 맞다. 명세 예시 정정 대상으로 올린다. responseCards는 항상 배열, facts는 항상 네 사실 전체, nextQuestion은 SOL-01·03과 같은 `StartSolutionResponse.Question` 형식이며 더 물을 항목이 없으면 명시적 null이다.

**카드 항목은 SOL-01·02·03과 같은 전체형(`code, priority, title, institutionCodes`)이다(확정).** 요약형이면 프론트가 카드 제목을 그리기 위해 SOL-02를 다시 불러야 하고, 카드 형식을 두 개 다뤄야 한다. 응답 필드가 SOL-03과 같으므로 새 DTO를 만들지 않고 `SolutionMessageResponse`를 재사용한다. 프론트는 SOL-03·04 응답을 같은 화면 갱신 코드로 처리할 수 있다. 명세 예시의 카드 형식 정정 대상이다(프론트에 2026-11-17 공유).

다음 예시는 송금 YES 상태에서 installedApp을 YES로 수정한 결과다. **두 카드가 모두 승인·등록된 환경**을 전제로 한다. 카드의 priority·title·institutionCodes는 규칙이 아니라 등록된 카드 콘텐츠 값이며, 아래 값은 예시를 위한 가정이다.

```json
{
  "success": true,
  "data": {
    "facts": {
      "transferredMoney": "YES",
      "sharedInformation": "NO",
      "installedApp": "YES",
      "contactContinues": "NO"
    },
    "responseCards": [
      {"code": "TRANSFER_RESPONSE", "priority": "URGENT", "title": "송금 관련 우선 대응", "institutionCodes": ["POLICE", "BANK"]},
      {"code": "MALICIOUS_APP_RESPONSE", "priority": "URGENT", "title": "악성 앱 설치 대응", "institutionCodes": ["POLICE"]}
    ],
    "nextQuestion": null,
    "stateVersion": 4
  }
}
```

팀 명세 예시는 위 사실 조합에서 앱 카드만 보여 주지만, 현재 ResponseRules는 송금 카드도 요구한다. SOL-04가 마지막 수정 항목의 카드만 반환해서는 안 된다. **수정 후 전체 사실로 계산한 전체 카드**를 반환하는 안이며 명세 예시 정정 대상이다. 현재 빈 운영 카탈로그에서는 이 성공 예시가 아니라 503 RESPONSE_CONTENT_UNAVAILABLE이 나와야 한다.

예시는 네 사실이 모두 알려져 nextQuestion이 null이다. installedApp만 UNKNOWN에서 YES로 고치고 contactContinues가 UNKNOWN이라면 `{"factKey":"contactContinues", ...}` 질문이 온다.

**nextQuestion을 응답에 넣는 이유:** 사실을 고치면 다음 질문도 바뀐다(예: 앱 설치 질문이 떠 있는 상태에서 앱 설치를 YES로 고치면 다음 질문은 연락 지속 여부). 응답에 질문이 없으면 화면에 이미 답한 옛 질문이 남고, 프론트가 SOL-02를 한 번 더 호출해야 한다. 그 사이 다른 변경이 끼어들면 PATCH 직후가 아닌 더 뒤의 상태를 받게 된다. 질문을 응답에 포함하면 PATCH 응답 한 번으로 사실·카드·질문·버전을 같은 시점의 한 묶음으로 화면에 적용할 수 있다.

SOL-02 GET은 새로고침·409 충돌·응답 유실 때의 복원용으로만 쓴다. 프론트는 PATCH 응답을 받기 전에는 옛 질문으로 답변을 제출하지 않는다.

## 5. 직접 정정·카드·상태 규칙

- 현재 질문의 factKey에 한정하지 않는다. 사용자는 허용된 모든 사실을 정정할 수 있다. SOL-03의 "현재 질문 일치" 검사를 복사하지 않는다.
- 요청에 있는 키는 기존 값과 관계없이 새 값으로 설정한다. YES↔NO, YES/NO→UNKNOWN, UNKNOWN→YES/NO가 가능하다. 없는 키는 유지한다.
- 모든 changes를 먼저 검증·적용한 불변 결과를 만든 뒤 전체 사실로 카드와 질문을 계산한다. 여러 항목을 순차 저장해서 중간 상태를 노출하지 않는다.
- 제안: 같은 값을 다시 보낸 요청도 확인 요청으로 수락하여 버전을 **한 번 +1**한다. 하나의 PATCH에 네 사실을 보내도 버전은 +1이며 메시지 행 수와 무관하다. 빈 changes는 거부한다.
- 카드 추가·제거를 모두 처리한다. 예를 들어 송금 YES→NO이면 송금 카드를 제거하고, 앱 설치가 여전히 YES면 앱 카드는 유지한다. 긴급 카드 우선·같은 우선순위의 규칙 순서를 유지한다.
- 승인 여부는 새 사실 조합이 요구하는 카드에 대해 확인한다. 카드는 수정한 전체 사실로 새로 계산하므로 **저장된 카드 참조(card_codes_json)는 읽지도 검증하지도 않는다**(SOL-03과 같음). 그래서 저장된 카드 값이 손상됐거나 제거될 옛 카드가 카탈로그에 없어도 정정을 막지 않는다. 새로 필요한 카드가 하나라도 없으면 503으로 전체 롤백한다.
- ACTIVE만 수정 가능하고 COMPLETED는 SOL-03과 같은 409 SOLUTION_FINISHED로 거부하는 안이다. 사실이 모두 알려져도 자동 완료하지 않는다.
- 이후 SOL-03 TEXT는 직접 정정한 YES/NO를 기존 병합 정책에 따라 유지한다. UNKNOWN 정정은 확인 사실이 아니므로 이후 TEXT에서 명확한 YES/NO로 보완될 수 있다. UNKNOWN을 영구 잠금하는 기능은 아니다.

## 6. UNKNOWN 정정과 수정 기록 제안

### 추천안: UNKNOWN은 답변 완료로 처리하고 수정 이력을 남김

**UNKNOWN 정정은 "지금은 모른다"라는 명시적 답변으로 취급하며, 같은 상담에서 자동으로 다시 묻지 않는 안**을 추천한다. SOL-03의 UNKNOWN 선택과 같은 의미다. 질문 순서는 transferredMoney → sharedInformation → installedApp → contactContinues이며, 알려진 값이나 UNKNOWN으로 답을 마친 항목을 제외한다. 모두 제외되면 nextQuestion=null, 상태는 ACTIVE다.

이 상태는 PATCH 한 번의 메모리 집합에만 반영하면 안 된다. 현재 SOL-03은 메시지의 `USER + OPTION + selected_code=UNKNOWN + fact_key`를 읽어 제외 집합을 매번 재구성하기 때문이다. 다음 TEXT/CHOICE에서도 의미가 유지되도록 수정 이력을 같은 저장 경계에 남기는 안이다.

1. 요청한 사실별로 USER OPTION 수정 기록을 저장한다. fact_key와 selected_code를 채우고 client_request_id는 null로 둔다. SOL-04에는 클라이언트 UUID 계약이 없다.
2. safe_content는 `사실 수정: ` 접두사와 서버 고정 사실 문구·선택 코드만 조합한다. 요청 본문 문자열이나 사용자 원문을 붙이지 않는다. 같은 값 재확인은 사실 확인·수정 기록으로 표현한다.
3. 기존 choice 팩토리는 UUID를 필수로 받으므로 null을 억지로 넘기지 않고 별도 correction 팩토리를 추가한다. fact_key 허용 제약과 기존 저장 형식은 재사용한다.
4. 한 PATCH의 기록 순서는 JSON 키 순서가 아니라 SolutionFact 선언 순서로 고정한다. 최대 네 USER 기록과, 질문이 바뀌었고 새 질문이 있을 때만 SYSTEM 질문 한 건을 추가한다.
5. 질문 계산 시 기존 answeredUnknownKeys와 이번 changes의 UNKNOWN 키를 합친다. 이력이 저장된 뒤에는 기존 SOL-03 쿼리도 같은 제외 집합을 읽을 수 있다.
6. UNKNOWN 이력을 지우지 않는다. 뒤에 YES/NO로 바꾸면 알려진 사실이어서 질문하지 않으며, 나중에 다시 UNKNOWN으로 바꿔도 답변 완료다. 재확인은 사용자가 SOL-04에서 직접 값을 고치는 방식이다.

이 안은 새 컬럼이나 마이그레이션 없이 V4의 fact_key를 재사용할 수 있다. PostgreSQL의 기존 UNIQUE(solution_id, client_request_id)는 null이 여러 행에 들어가는 것을 허용한다. 순번은 세션 CAS 성공 후 max(sequence_no) 다음부터 배정하고, 상태와 기록을 한 번에 커밋한다.

**명세 대비 추가 합의:** 관련 저장소가 solution_sessions로만 적혀 있으나 추천안은 solution_messages에도 쓴다. SOL-02 복원에서는 현재 OPTION→CHOICE 매핑에 따라 수정 기록도 CHOICE로 표시되고 content 접두사로 구별된다. 별도 공개 입력 종류 EDIT나 변경 상세 필드는 이번에 임의로 추가하지 않는다. 프론트가 구조적으로 수정/응답을 구별해야 하면 공개 메시지 계약을 별도로 확정해야 한다.

### 다른 정책을 택할 때

UNKNOWN 정정이 "다시 질문해 달라"는 뜻이어야 한다면 과거 UNKNOWN 이력을 무조건 제외하는 SOL-03 정책을 함께 바꿔야 한다. 최신 답변/수정 이벤트를 구분해 읽거나 별도 현재 질문 상태를 저장하는 설계가 필요하다. 기존 이력을 삭제하거나 next_question_json에 의미가 다른 목록을 섞어 넣어 임시 해결하지 않는다.

solution_sessions만 쓰기로 엄격하게 제한한다면 UNKNOWN 질문 상태를 지속할 저장 모델과 SOL-03의 읽기 경계 변경을 먼저 설계한다. 이 선택은 새 마이그레이션을 포함할 수 있으며, 위 추천안과 혼합하지 않는다. **구현 전에 11장 결정 1을 확정한다.**

## 7. 소유권·버전·저장 트랜잭션

### 소유권과 인증

기존 findExisting과 findOwned를 사용한다. 새 익명 ID를 발급하지 않으며 소유자 ID를 요청에서 받지 않는다. 로그인 principal이 있으면 활성 회원으로 처리하고 남은 익명 UUID로 우회하지 않는다. 로그인 뒤 이전 익명 상담을 자동 이관하지 않는다. 없는 상담·타인 상담·익명 소유자 없음·잘못된 ID 형식은 같은 404다. 비활성 회원은 기존 401 AUTH 오류를 유지한다.

### CAS와 동시 수정

서비스 진입점은 SOL-03처럼 NOT_SUPPORTED로 호출자의 트랜잭션을 중단하고 내부 TransactionTemplate 쓰기를 **READ COMMITTED**로 명시하는 안이다. 외부 AI 호출은 없으므로 AI 전후 두 단계 조회가 필요하지 않다. 짧은 쓰기 안에서 본인 상담·상태·버전을 읽고 검증한다. 실패 분류를 위한 재조회는 쓰기 트랜잭션이 종료된 뒤 새 읽기 트랜잭션에서 한다.

1. facts·카드 참조·다음 질문을 엄격하게 읽는다. 저장 JSON이 손상됐으면 500으로 처리하고 정정 요청을 데이터 복구 수단으로 삼지 않는다.
2. 사실을 일괄 적용하고 UNKNOWN 이력·카탈로그로 카드·질문·안전한 수정 메시지 후보를 만든다. 카드 조회는 현재 메모리 카탈로그 경계를 사용한다. 외부 HTTP 콘텐츠 조회를 추가하면 트랜잭션 설계를 다시 검토한다.
3. prepareUpdate와 기존 compareAndSet으로 ID·소유자·ACTIVE·기대 버전을 조건으로 상태 JSON·updated_at·state_version+1을 변경한다.
4. 갱신 1행이면 해당 상담 행 잠금이 유지되는 동안 메시지를 저장한다. 관리 엔티티 dirty checking UPDATE와 CAS를 섞지 않는다. CAS 후 캐시 정리와 실제 DB 버전 재조회를 재사용한다.
5. 기록 저장·flush·후속 검증 중 실패하면 상태·카드·질문·모든 메시지를 롤백한다. 부분 성공은 없다.

같은 버전으로 SOL-04 두 건, 또는 SOL-03과 SOL-04가 경쟁하면 한 건만 반영한다. 서로 다른 사실을 수정했어도 자동 병합하지 않는다. READ COMMITTED의 일반적인 행 경쟁에서는 패자가 UPDATE 0행을 받는다. 저장 트랜잭션을 종료한 뒤 본인 상담이 없어졌으면 404, 완료됐으면 SOLUTION_FINISHED, 그 외 버전/잠금 경쟁은 STATE_VERSION_CONFLICT로 분류한다.

SOL-03이 AI 응답을 기다리는 동안 SOL-04가 먼저 성공하면 SOL-03의 저장 전 버전 검사에서 409가 나야 한다. 늦은 AI 응답으로 정정값을 덮어쓰면 안 된다. 기존 전역 낙관적 잠금 처리기가 훈련용 오류를 반환할 수 있으므로 SOL 서비스 경계에서 오류를 변환한다. 관련 없는 DB 제약 오류를 모두 충돌로 숨기지 않는다.

### 재시도

SOL-04에는 requestId가 없다. UUID를 새 필수 필드로 넣거나 SOL-03의 DUPLICATE_REQUEST 처리와 같은 계약이라고 설명하지 않는다. 같은 본문·같은 버전을 재전송하면 첫 성공 후 나머지는 409 STATE_VERSION_CONFLICT다. 본문 비교·200 응답 재생·서버 UUID 생성만으로 멱등 처리를 약속하지 않는다.

응답 유실 시 프론트는 SOL-02를 다시 조회한다. 서버는 requestId가 없어 원래 PATCH의 성공과 다른 수정의 성공을 구별해 줄 수 없다. 화면의 최신 사실을 확인한 뒤 사용자가 정정 내용을 다시 결정해야 하며 최신 버전으로 자동 재제출하지 않는다. 성공한 새 버전으로 동일 값을 다시 확인하는 것은 새로운 PATCH로 버전이 다시 +1된다.

Redis 세션 소유권 확인과 PostgreSQL 커밋은 분산 트랜잭션이 아니다. 요청 시 소유권을 검사하지만 커밋과 정확히 동시에 발생한 로그아웃·만료까지 원자적으로 묶는다고 보장하지 않는다.

## 8. 오류·보안·개인정보

| HTTP·코드 | 조건·처리 |
|---|---|
| 400 INVALID_REQUEST | 빈/잘못된 changes, 타입/키/값/범위 오류. 전체 거부 |
| 401 기존 AUTH 오류 | 비활성·삭제 등 인증 오류. 익명으로 우회하지 않음 |
| 403 CSRF_TOKEN_INVALID | 회원·비회원 토큰 누락/불일치. DB 변경 없음 |
| 404 SOLUTION_NOT_FOUND | ID 형식 오류·없는 상담·타인 상담·익명 소유자 없음 |
| 409 STATE_VERSION_CONFLICT | 오래된 버전·CAS 0행·동시 갱신. 복원 후 재확인 |
| 409 SOLUTION_FINISHED | 완료 상담 수정 거부 제안 |
| 503 RESPONSE_CONTENT_UNAVAILABLE | 수정 후 필요한 검토 카드 누락. 전체 롤백 |

명세에는 200·STATE_VERSION_CONFLICT만 요약돼 있으나 위 오류는 기존 소유권·CSRF·완료·콘텐츠 정책을 일관되게 적용하기 위한 제안이다. LLM을 부르지 않으므로 이 API에 AI_SERVICE_UNAVAILABLE을 연결하지 않는다. 자유입력이 없으므로 SensitiveDescriptionGuard를 거치는 대신 허용 키·값을 엄격하게 제한한다. accountNumber·content 같은 입력은 INVALID_REQUEST로 거부하며 로그에 원문을 남기지 않는다.

성공은 ApiResponse, 오류는 기존 `{code,message}` 형식이다. 응답에 소유자 ID·익명 UUID·내부 카드 버전·원문을 넣지 않는다. 로그는 상담 ID·오류 코드·수정 항목 개수 정도로 제한한다. 요청 본문·이전/새 사실 값 전체·세션 ID·CSRF 토큰을 출력하지 않는다. Spring Security의 no-store를 PATCH 성공·오류와 복원 응답에서 검증한다.

## 9. 파일별 개발 순서와 상호작용

아래 신규 파일명은 제안이다. Java 경로의 공통 접두사는 `src/main/java/com/wsid/`다.

| 순서 | 파일 | 작업·이후 참고점 |
|---|---|---|
| 1 | `solution/dto/UpdateSolutionFactsRequest.java`(신규) | JsonNode 실제 타입·조건부 키·버전/값 검사, 불변 changes. training/ai DTO를 import하지 않음 |
| 2 | `solution/dto/SolutionMessageResponse.java`(기존 재사용) | facts·전체 카드·nextQuestion·버전. SOL-03·04 공통 상태 변경 응답으로 설명만 갱신. 새 응답 DTO 없음 |
| 3 | `solution/domain/SolutionFacts.java` | withChanges 추가. 여러 키를 모두 검증한 뒤 불변 결과 생성. AI 병합 정책은 바꾸지 않음 |
| 4 | `solution/domain/SolutionMessage.java`, `SolutionQuestion.java` | 합의된 correction 팩토리·UNKNOWN 답변 완료 규칙. 기존 choice UUID 필수 계약과 초기 질문 함수 유지 |
| 5 | `solution/service/UpdateSolutionFactsService.java`(신규) | 소유권·상태·버전·일괄 정정·카드·질문·짧은 저장·오류 분류. AiClient 의존성 없음 |
| 6 | 기존 `SolutionSession.prepareUpdate`, `SolutionSessionUpdates`, `SolutionMessageRepository` | CAS·최대 순번·UNKNOWN 이력 재사용. 조회가 추가로 필요할 때만 저장소 메서드 확장 |
| 7 | `solution/controller/SolutionController.java` | PATCH 연결, 제한 JSON 읽기를 공유 함수로 정리. SOL-03의 오류 우선순위와 SOL-01의 본문 선택 계약 유지 |
| 8 | `solution/security/SolutionSecurityConfig.java` | 정확한 facts PATCH만 permitAll. CSRF·나머지 경로 denyAll 유지. AUTH 공통 코드 수정 없음 |
| 9 | `src/test/java/com/wsid/solution/` | DTO·도메인·서비스 신규 테스트, 기존 웹/DB/실제 Redis 테스트 확장, SOL-03과 경쟁 검증 |
| 10 | 구현 후 `docs/sol-04-facts-update.md`, `docs/api.md`, `solution/README.md`, `docs/README.md` | 실제 정책·파일 상호작용·검증 결과·프론트/콘텐츠 후속 작업 보고. 계획서와 보고서를 별도로 유지 |

추천안을 채택하면 새 테이블·컬럼·Flyway 마이그레이션이 필요하지 않다. V1~V4는 수정하지 않는다. UNKNOWN 재질문 등 다른 안으로 바뀌어 새 저장 모델이 필요하면 DB 담당과 다음 마이그레이션 번호를 예약한다. 코드 설명만 고쳐 기존 이력이 변했다고 보지 않는다.

FastAPI 개발 디렉토리를 추가할 필요도 없다. 이 API가 고친 YES/NO는 이후 SOL-03의 knownValues에 포함되고 기존 AI 병합에서 보호된다. 수정한 UNKNOWN은 knownValues에서 제외된다. 실제 FastAPI 계약을 바꾸는 작업과 직접 수정 API 개발을 혼동하지 않는다.

## 10. 테스트 계획과 완료 기준

| 범위 | 확인할 사항 |
|---|---|
| DTO | 정상 1개/4개 수정, 빈/null/배열 changes, 추가·잘못된 키·값, 실제 타입, stateVersion 0/최대 경계·문자열/소수 거부, 방어적 복사, 잘못된 한 항목이 있으면 전체 거부 |
| 사실 도메인 | YES↔NO·YES/NO→UNKNOWN·UNKNOWN→YES/NO, 다른 키 유지, 같은 값 재확인, 원본 불변, 여러 항목 한 번 적용, AI mergeExtracted의 기존 보호 규칙 회귀 |
| 카드·질문 | 송금/앱 카드 추가·제거·둘 다 유지·정렬, 제거될 옛 카드 누락은 정정 가능, 필요한 새 카드 누락 503, UNKNOWN 정정과 이력 지속, 모두 답변 시 null/ACTIVE |
| 서비스 | AiClient 완전 미호출, 소유권/ACTIVE/버전 실패 무저장, 한 PATCH +1, 실제 DB 버전 응답, 관련 없는 DB 오류를 409로 숨기지 않음 |
| 기록·복원 | 정정 prefix·OPTION→CHOICE·선택 코드·factKey·null UUID, 여러 수정 순번, 바뀐 질문만 SYSTEM 저장, 이후 SOL-03에서도 UNKNOWN 건너뛰기 유지, SOL-02 공개 필드 유지 |
| MVC·보안 | 200 봉투/네 응답 필드(nextQuestion 명시적 null 포함)·SOL-03과 같은 전체 카드형·수정 후 바뀐 질문, CSRF→ID→본문→소유권 순서, ID+깨진 JSON, 16KiB/후행 JSON/중복 키(SOL-03 회귀 포함), 회원/비회원/비활성 회원, 다른 세션·로그인 후 과거 익명 상담 404, no-store·허용 경로 |
| PostgreSQL dbTest | 같은 값·4개 값 모두 버전 +1, 상태/카드/질문/기록 원자성, CAS 뒤 메시지 실패 롤백, 결번 max+1, 관리 엔티티/SQL 버전 중복 없음, 기존 V4 제약·null UUID 호환 |
| 동시성 dbTest | PATCH 두 건의 동일 버전 경쟁, PATCH와 SOL-03 동시 경쟁은 성공 1/409 1, 수정 키가 달라도 자동 병합 없음, 실제 READ COMMITTED와 CAS 0행 확인 |
| 지연 AI와 경쟁 | SOL-03 AI 호출을 대기시킨 동안 PATCH 성공 → 늦은 SOL-03은 409·정정값 유지. Spring 서비스와 제어 가능한 AI 대역으로 재현 |
| 실제 Redis integrationTest | CSRF→시작→PATCH→SOL-02→SOL-03 흐름, 수정 질문/카드 복원, 만료 뒤 새 CSRF만 받아도 404, 회원 우선·외부 세션 차단, 기존 AUTH/SOL-01~03 회귀 |
| 개인정보·로그 | 허용 외 자유입력은 저장/로그 없음, 고정 수정 문구만 저장, DTO·오류·로그에 요청 본문/토큰 없음 |

구현 후 실행은 `./gradlew test`, 프로젝트 전용 PostgreSQL의 `./gradlew dbTest`, 전용 Redis·PostgreSQL의 `./gradlew integrationTest`, `./gradlew bootJar` 순서로 한다. 공유 프로젝트가 사용하는 기본 5432/6379에 테스트 데이터를 쓰지 않는다. 실제 DB 테스트를 단순 repository mock으로 대체해 통과했다고 보고하지 않는다.

완료 조건은 직접 정정·전체 카드/질문 재계산·소유권/CSRF·버전 경쟁·전체 롤백·SOL-03 확정값 보호가 합의한 정책대로 동작하는 것이다. 승인 카드가 없는 운영 조합과 실제 프론트 화면 검증은 별도로 남기고, 테스트 카드를 운영 승인 자료로 취급하지 않는다. 이번 계획서 작성 중에는 Gradle·DB·API 테스트를 실행하지 않았다.

## 11. 구현 전 결정할 사항

1. **UNKNOWN·수정 이력:** 답변 완료·자동 재질문 없음 + solution_messages에 수정 기록을 남기는 추천안을 채택할지. 명세의 관련 저장소 확대와 SOL-02 CHOICE 표시를 프론트/DB 담당과 확인한다. 재질문을 원하면 SOL-03과 저장 모델부터 함께 설계한다.
2. **응답 형식:** nextQuestion을 응답에 포함한다(확정, 2026-11-17). 명세 개요("카드·후속질문 재계산")와 맞추고 PATCH 뒤 추가 GET을 없앤다. 명세 응답 예시에 nextQuestion을 추가하도록 프론트·명세 담당에게 알린다. 카드는 SOL-01~03과 같은 전체형으로 확정하고 `SolutionMessageResponse`를 재사용한다. 프론트에는 2026-11-17 공유했다.
3. **확인·재시도:** 동일 값 PATCH도 +1, UUID 없이 같은 버전 재전송은 409, 성공 응답 유실 시 복원 후 수동 재확인 정책을 확정한다.
4. **완료·콘텐츠 실패:** COMPLETED 409·필요한 카드 누락 503을 SOL-03과 일관되게 유지할지. 두 카드가 필요한 명세 예시를 바로잡고 운영 콘텐츠 연결 주체를 확인한다.
5. **문구·화면:** 고정 수정 기록 표현, OPTION→CHOICE 복원 표시, 직접 수정 UI와 수정 후 질문 갱신을 프론트/콘텐츠 담당과 맞춘다. 실제 화면·승인 콘텐츠는 이번 Spring 계획서만으로 완료로 표시하지 않는다.
