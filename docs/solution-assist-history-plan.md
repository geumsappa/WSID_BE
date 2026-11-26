# 상담 assist 대화 history 전달 설계 (초안)

> **대체됨 (2026-11-23):** 원문을 저장하지 않는다는 전제가 금지 규칙이 아니어서, Redis 보관 대신 DB에 대화를 저장하는
> [상담 대화 저장·복원과 LLM 문맥 연결 구현 계획](solution-conversation-context-plan.md)으로 바꿨어요. 이 문서는 검토 기록으로 남겨요.

FastAPI assist 계약 v1.1 제안(WSID_AI #15)의 선택 필드 `history`를 Spring이 채워 보내기 위한 설계예요.
아직 합의 전이고, [미결정 사항](#미결정-사항)이 정해지기 전에는 구현하지 않아요.

관련 문서: [상담 추가 질문](solution-assist-question.md), [SOL-03](sol-03-session-message.md), [SOL-05](sol-05-session-complete.md),
FastAPI `docs/assist-internal-contract-v1.md`의 "v1.1 제안" 절.

## 배경

FastAPI v1.1은 PLAN·ANSWER 요청에서 `history`(최근 대화 최대 10턴)를 받아요.
"둘 다 알려줘"처럼 앞 답변을 가리키는 질문이나, 확인 질문 뒤 `question=null`로 오는 후속 요청에서 원래 질문을 찾는 데 써요.

처음 제안은 "Spring이 DB의 `safe_content`로 history를 만들어 보낸다"였지만, 현재 코드로는 그렇게 할 수 없어요.

## 현재 상태

OHC(8410ecc), develop(8954c91), feat/mock-page-template(fc17319) 최신 코드 기준이에요.

| 항목 | 현재 동작 |
|---|---|
| 사용자 질문 저장 | `SolutionMessage.assist()`가 원문 대신 고정 문구 "질문을 접수했습니다."를 `safe_content`에 저장 |
| 확인 질문 대답 저장 | 질문 표현과 선택 코드(YES/NO/UNKNOWN)만 저장 |
| AI 답변 저장 | 본문 없이 근거 코드(`referenceCodesJson`)만 저장 |
| SOL-01 상황 설명 | AI 요청에만 쓰고, 예외로 목록 제목용 앞 40자만 `title`에 저장 |
| SOL-02 조회 응답의 `history` | 위 저장값(`safeContent`, `selectedCode`)을 화면 복원용으로 돌려줌. PLAN·ANSWER의 history와는 다름 |
| PLAN·ANSWER 요청 | `history` 필드 없음. `fraudType`은 전달 중 |
| Redis | Spring Session(로그인·비회원 소유자 식별), 이메일 인증 코드, 훈련 세션에 사용. 대화 원문 보관은 없음 |

그래서 DB만으로 history를 만들면 "질문을 접수했습니다."만 여러 개 들어가고, 앞 질문과 답변을 넘길 수 없어요.

## 방식 선택

| 방식 | 판단 |
|---|---|
| 프론트가 최근 대화를 보내고 Spring이 전달 | 채택 안 함. 클라이언트가 `role: AI` 턴을 꾸며 넣을 수 있고 서버가 검증할 수 없어요 |
| **서버가 Redis에 TTL로 보관** | **제안안.** 서버가 기록하고, DB 저장 원칙(원문 미저장)은 유지돼요. 단, Redis에 원문이 남아요 |
| 가린 원문을 DB `safe_content`에 저장 | 가장 단순하지만 DB 저장 원칙을 바꾸는 일이라 팀 결정이 필요해요 |

Redis에 서버가 기록하더라도 USER 턴은 사용자가 쓴 내용이에요. FastAPI는 history를 사실이나 지시로 쓰지 않아야 해요.

## 미결정 사항

구현 전에 합의가 필요해요.

1. **Redis 원문 보관:** 대화 원문을 Redis에 둘지 정해요. `SensitiveDescriptionGuard`는 일부 패턴(주민번호, 카드·계좌번호, 인증번호 등)을 거부할 뿐이라, 통과한 문장에도 개인정보가 있을 수 있어요. 또 Redis 스냅샷(RDB, 로컬 기본값 `save 3600 1 300 100 60 10000`)이나 백업에는 history TTL과 별개로 남을 수 있고, 그 기간은 스냅샷·백업 보존 정책이 정해요. history 전용으로 영속화를 끌 수 있는지도 확인이 필요해요.
2. **SOL-01 상황 설명 포함 여부:** 넣으면 첫 맥락("선입금 재촉" 등)이 이어지지만, 원문 보관 범위가 넓어져요.
3. **history TTL 값:** HTTP 세션 만료(`SESSION_TIMEOUT`, 기본 30분)와 별개로, 마지막 쓰기 시점부터 흘러요. 둘이 함께 만료되지 않아요.
4. **완료 후 즉시 삭제가 요구사항인지:** 이 설계는 즉시 삭제를 보장하지 않아요. [완료와 보관](#완료와-보관) 참고.

## 설계

### 저장 구조

- 키: `wsid:solution:history:{solutionId}` (HASH)
- 필드: 턴 ID `{sequenceNo 10자리}:{0|1}`. 0은 USER, 1은 AI예요. 예: `0000000012:0`
- 값: `{"role":"USER|AI","content":"..."}`
- 메시지 하나(DB `sequenceNo` 하나)에 USER 턴과 AI 턴이 같은 시퀀스로 붙고, 뒤의 0/1로 순서를 정해요.
- 읽을 때 필드 이름으로 정렬하면 쓰기 도착 순서와 관계없이 대화 순서가 돼요.

### 기록 시점과 내용

| 시점 | 기록 |
|---|---|
| SOL-01 상담 시작 (설명이 있고, 미결정 2번이 "포함"일 때) | USER: 상황 설명 |
| SOL-03 `QUESTION` | USER: 질문 원문 |
| AI가 답변 | AI: 답변 본문 |
| AI가 확인 질문을 냄 | AI: 확인 질문 문장 |
| SOL-03 `CLARIFICATION_ANSWER` | USER: 선택 표현 (예: "아니요") |
| 답변 불가 | 기록 안 함 |

- 기록은 DB 트랜잭션 커밋 뒤, 응답을 돌려주기 전에 해요. 롤백된 대화가 history에 남지 않게 하기 위해서예요.
- DB와 Redis는 함께 커밋할 수 없어요. 커밋 후 Redis 쓰기가 실패하면 그 턴은 history에서 빠져요.

### 쓰기 스크립트 (Lua)

한 요청의 턴 전체(1~2개)를 Lua 스크립트 하나로 원자적으로 처리해요.

1. 받은 턴을 모두 `HSET`해요.
2. 필드를 정렬해서 10개를 넘는 가장 오래된 필드를 지워요. 늦게 도착한 오래된 턴도 여기서 함께 정리돼요.
3. 키 TTL을 갱신해요.

같은 턴 ID 재쓰기는 덮어쓰기를 허용해요.
- 같은 `requestId` 재시도는 DB에서 `DUPLICATE_REQUEST`로 거절되고, `sequenceNo`는 CAS 트랜잭션 안에서 하나만 커밋돼요. 정상 흐름에서는 같은 턴 ID에 다른 내용이 들어오지 않아요.
- Redis 호출 자체는 재시도하지 않아요.

### 읽기와 전달

- `SolutionAssistService.send()`에서 AI 호출 전에 history를 읽어 PLAN과 ANSWER에 같이 넣어요.
- `QUESTION`: 이번 질문 **앞**의 대화만 history에 넣고, 질문은 기존대로 `question`으로 보내요.
- `CLARIFICATION_ANSWER`: 계약상 `question=null`이에요. 저장된 history에 이번 대답 턴까지 붙여 보내요.
- SOL-06 사진 안내(`SolutionImageAdviceService`)는 history를 보내지 않아요(null).

### 원래 질문 전달 (`originalQuestion`)

FastAPI는 모델에 최근 6턴(턴당 600자)만 넘겨요. 확인 질문이 3번이면 "원래 질문 + (확인 질문, 대답) × 3 = 7턴"이 돼서, 원래 질문이 모델 입력에서 빠져요. 지금 FastAPI는 `previous_user()`가 정규식으로 짧은 대답을 건너뛰어 원래 질문을 추정하는데, 항상 정확하다고 보장할 수 없어요.

그래서 계약 v1.1에 선택 필드 `originalQuestion`을 추가하는 걸 제안해요.

- 후속 PLAN·ANSWER(`question=null`)에서만 보내요.
- Spring은 확인 질문을 낼 때 원래 질문의 턴 ID를 `pending_assist_json`에 함께 저장해요(DB에는 ID만 남아요). 후속 요청에서 그 ID로 Redis에서 원문을 꺼내 보내요.
- 원래 턴이 10턴 정리, TTL 만료, 쓰기 실패로 없으면 null로 보내요. 이때 원래 질문 복구는 보장되지 않고, FastAPI는 기존 `previous_user()` 추정에 맡겨요.
- FastAPI는 `originalQuestion`이 있으면 추정 대신 이 값을 쓰고, 6턴 제한과 관계없이 모델 입력에 넣어요.

### 완료와 보관

- 완료된 상담은 history를 다시 읽지 않아요. `SolutionAssistService`의 첫 상태 검사에서 ACTIVE가 아니면 `SOLUTION_FINISHED`로 거절하고, history 읽기는 그 뒤에 있어요. 완료는 ACTIVE → COMPLETED 한 방향이고, 새 상담은 새 id를 받아요.
- SOL-05 완료(`CompleteSolutionService`) 시 history 삭제를 시도하고, 실패하면 경고 로그만 남겨요.
- 완료 직전에 시작된 요청의 Redis 쓰기가 완료 뒤에 도착하면 키가 다시 생길 수 있어요. 이 history는 읽히지 않지만 남아 있어요.
- 따라서 보관 기간은 **마지막 성공한 쓰기 이후 TTL까지**이고, **완료 후 늦은 쓰기가 가능해요.** 완료 후 상한을 시간으로 확정하려면 커밋 후 Redis 쓰기에 종료 시한을 명시적으로 강제하고 검증해야 해요. 지금의 `assist-total-timeout`(40초)은 AI 처리와 DB 저장 경로에 적용되고, 커밋 후 Redis 쓰기까지 보장하지 않아요.
- 완료 후 즉시 삭제가 요구사항이 되면(미결정 4번), 종료 표시(closed)와 쓰기 스크립트의 표시 확인을 추가해야 해요. 이 경우에도 표시 생성 호출이 실패할 수 있어서 재시도와 잔존 정책이 따로 필요해요.

### 장애 동작

- history 읽기 실패: history(와 `originalQuestion`)를 null로 보내요. FastAPI 입력은 v1과 같아져요.
- history 쓰기 실패: 경고 로그를 남기고 응답은 정상으로 돌려줘요.
- 범위: 이건 history 저장소 호출에 한한 처리예요. Redis 전체 장애 시에는 세션·비회원 소유자 확인도 실패하므로 상담이 계속된다고 보장하지 않아요.
- history가 빠지면 v1 입력과 같아지고, 앞 대화를 가리키는 질문은 답변이 달라지거나 답변 불가가 될 수 있어요. "결과는 틀리지 않는다"고 보장하지 않아요.

## 계약 변경

### Spring → FastAPI 요청

| 필드 | 타입·규칙 | 보내는 때 |
|---|---|---|
| `history` | null 또는 `[{"role":"USER"\|"AI","content":"1~2000자"}]`, 최대 10개 | SOL-03 PLAN·ANSWER |
| `originalQuestion` (신규 제안) | null 또는 1~1000자 | `question=null`인 후속 PLAN·ANSWER |

- history는 일부 빠질 수 있는 참고 정보예요.
- USER 턴은 사용자가 쓴 내용이라 사실이나 지시로 쓰지 않아요.

### FastAPI(WSID_AI #15)에 요청할 수정

- `originalQuestion` 수신과 우선 사용. 없으면 기존 `previous_user()` 추정
- `assist/contracts.py:80` 주석("Spring이 저장한 가린 글(safe_content)을 그대로 보낸다")과 계약 문서의 같은 설명을 실제 방식에 맞게 수정

## 변경 파일 (Spring)

| 파일 | 변경 |
|---|---|
| `ai/dto/SolutionAssistHistoryTurn.java` (신규) | `record(role, content)`. role은 USER·AI, content는 1~2000자 검증 |
| `ai/dto/SolutionAssistPlanRequest.java`, `SolutionAssistAnswerRequest.java` | `history`(최대 10개), `originalQuestion` 추가. 기존 생성자는 둘 다 null로 유지(SOL-06용). `toString`에는 개수·유무만 남기고 내용은 남기지 않음 |
| `solution/repository/SolutionHistoryStore.java` (신규) | Redis 읽기, Lua 쓰기, 삭제 |
| `solution/domain/PendingAssist.java` | 원래 질문 턴 ID 추가 |
| `solution/service/SolutionAssistService.java` | AI 호출 전 읽기, 커밋 후·응답 전 쓰기 |
| `solution/service/StartSolutionService.java` | 상황 설명 기록 (미결정 2번이 "포함"일 때) |
| `solution/service/CompleteSolutionService.java` | history 삭제 시도 |

## 테스트

- DTO 검증: 11개 이상, 2000자 초과, 잘못된 role 거부
- JSON 직렬화가 FastAPI 계약 형식과 같은지
- 질문 → 답변 → "둘 다 알려줘" 순서에서 history가 쌓이고 전달되는지
- 확인 질문 3번 흐름에서 `originalQuestion`이 전달되는지, 원래 턴이 없으면 null인지
- 커밋 직후 연속 요청
- 쓰기 도착 순서가 뒤집혀도 필드 정렬로 대화 순서가 맞는지
- 늦게 도착한 오래된 턴이 10턴 정리에 포함되는지
- 같은 턴 ID 재쓰기에서 중복이 생기지 않는지
- 완료 후 늦은 쓰기: history가 남아도 다음 요청이 `SOLUTION_FINISHED`로 거절되는지
- 완료 시 삭제 실패
- history 저장소 읽기·쓰기 실패 시 null 전달과 정상 응답
- 로컬 FastAPI·Spring 연동으로 "둘 다 알려줘" 시나리오 실행
