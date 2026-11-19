# SOL-07 완료 상담 목록·다시 보기 구현 계획서

작성일: 2026-11-19 · 브랜치: `OHC` · 상태: 계획 (구현 전)

프론트 요청: "내 상담 기록" 화면에서 완료한 상담 목록을 보고, 하나를 눌러 대화를 다시 보는 기능이에요.

## 0. 먼저 정리: 요청과 현재 코드 비교

| 요청 | 현재 코드 | 할 일 |
|---|---|---|
| 1. 목록 API `GET /api/solutions/sessions` | 없음 | **새로 만듦** |
| 1. API ID `SOL-06` | `SOL-06`은 이미 사진 입력(`POST /sessions/{id}/images`)에 씀 | **`SOL-07`(초안 ID)로 부름** |
| 2. `title` = 첫 사용자 글을 가린 앞 40자 | 원문을 저장하지 않음. 첫 사용자 메시지는 고정 문구(`피해 설명을 제출했습니다.` 등) | **제목 만드는 방법을 정해야 함** (2장) |
| 3. SOL-05 종료 시 `COMPLETED` + `completedAt` | **이미 저장함** (`SolutionSessionUpdatesImpl.completeActive`가 `completed_at = now`) | 목록 정렬·응답에 그대로 사용 |
| 4. SOL-02로 완료 상담 조회 | **이미 됨**. `COMPLETED`도 소유자면 200, 메시지 기록 반환, 다른 사람·비회원은 404 | 응답에 시각 필드 추가, 카드 503 문제 확인 (5장) |

## 1. API

```
GET /api/solutions/sessions?size=10&cursor=<nextCursor>
Cookie: SESSION=...           (회원 로그인 필요)
```

| 파라미터 | 필수 | 규칙 |
|---|---|---|
| `size` | X | 기본 10, 1~50. 범위 밖·숫자 아님 → 400 `INVALID_REQUEST` |
| `cursor` | X | 직전 응답의 `nextCursor` 그대로. 형식 오류 → 400 `INVALID_REQUEST`. 서명이 없어 형식이 유효한 값의 변조는 판별할 수 없음 |

```json
{
  "success": true,
  "data": {
    "items": [
      {"solutionId": 238, "status": "COMPLETED", "title": "송금·연락 지속 상담",
       "createdAt": "2026-11-19T05:12:03Z", "completedAt": "2026-11-19T05:20:41Z"}
    ],
    "nextCursor": "MjAyNi0xMS0xOVQwNToyMDo0MVp8MjM4",
    "hasNext": true
  }
}
```

| 상황 | 응답 |
|---|---|
| 비로그인(비회원 세션 포함) | 401 `LOGIN_REQUIRED` |
| 완료 상담 없음 | 200, `items: []`, `nextCursor: null`, `hasNext: false` |

- **조회 대상:** `user_id = 로그인 회원`이고 `status = 'COMPLETED'`인 상담만 보여 줘요.
  - 진행 중 상담과 비회원 상담은 넣지 않아요.
  - 로그인 전에 비회원으로 한 상담도 넣지 않아요. 지금 로그인할 때 비회원 상담을 회원으로 옮기는 기능이 없어요.
- **정렬:** `completed_at DESC, id DESC`로 정렬해요. 완료 시각이 같으면 id로 순서를 고정해요.
- **페이지 처리:** 리포트 목록(`ReportService.saved`)과 같은 방식이에요.
  - `size + 1`개를 읽어서 다음 페이지가 있는지 판단해요.
  - 커서는 "마지막 항목의 `completedAt` + `id`"를 Base64URL로 인코딩해요.
  - 다음 페이지는 `(completed_at, id) < (커서 값)` 조건으로 읽어요. 오프셋 방식이 아니라서 중간에 상담이 새로 완료돼도 항목이 겹치거나 빠지지 않아요.
- **응답에 넣지 않는 것:** 소유자 ID, 사실값, 카드, 메시지, `stateVersion`
- **캐시:** `Cache-Control: no-store` (SOL-02와 같아요)

## 2. 제목(title): 팀 결정 필요

현재 원칙은 **"피해 설명 원문은 DB와 로그에 저장하지 않는다"**예요. SOL-01·03·06 모두 원문 대신 고정 문구만 저장해요. 그래서 지금 데이터로는 "첫 사용자 글의 앞 40자"를 만들 수 없어요.

| 방안 | 방법 | 장점 | 단점 |
|---|---|---|---|
| **A. 사실 기반 제목 (추천)** | 저장된 사실값으로 서버가 만들어요. 예: 송금 `YES`·연락 `YES` → "송금·연락 지속 상담", 사실 없음 → "피해 대응 상담" | 원문 저장 0, DB 변경 없음, 기존 상담에도 바로 적용 | 상담끼리 제목이 비슷할 수 있어요 |
| B. 가린 제목 문구 저장 | SOL-01·03 첫 TEXT 입력 때 `mask_sensitive` 수준으로 가린 앞 40자를 새 컬럼 `title`에 저장 | 사용자 글과 비슷한 제목 | **원문 일부를 저장**해서 현재 원칙과 충돌해요. 이름·지명·회사명은 가림 규칙에 안 걸려요. 사진 입력은 OCR 원문이 계좌까지 그대로라 위험이 더 커요. 기존 상담은 제목이 없어요 |
| C. AI 요약 제목 저장 | AI-06 호출 때 Python이 개인정보 없는 짧은 제목을 함께 돌려주고 저장 | 자연스러운 제목 | Python 계약을 바꿔야 해요. AI 출력도 검사해야 해요(AI-07 검증기 재사용). 장애 시 대체 제목이 필요해요 |

**추천은 A예요.**
- 이유:
  - 원칙을 지킬 수 있어요.
  - 마이그레이션이 필요 없어요.
  - 사진 입력이나 직접 선택으로 시작한 상담에도 똑같이 제목이 생겨요.
- 제목 규칙(초안):
  - `YES`인 사실을 우선순위대로 최대 2개 이어 붙여요. 우선순위는 송금 > 정보 제공 > 앱 설치 > 연락 지속이에요.
  - 예시: "송금·정보 제공 상담", "앱 설치 상담"
  - `YES`인 사실이 하나도 없으면 "피해 대응 상담"이에요.
  - `fraud_type_code`가 채워지면 유형 이름을 앞에 붙일 수 있어요. 지금은 항상 null이에요.
- B나 C로 바꾸더라도 목록 API 모양은 같아서 프론트에 영향이 없어요.

## 3. 대화 다시 보기 (SOL-02): 실제로 보이는 범위

SOL-02는 이미 완료 상담도 반환해요. 다만 **저장된 메시지만 보여 줄 수 있어요.**

| 대화 항목 | 저장 내용 | 다시 보기에서 보이는 것 |
|---|---|---|
| 시스템 질문 | 질문 문장 | ✅ 그대로 |
| 사용자 선택 답변 | 선택값(`YES`/`NO`/`UNKNOWN`) | ✅ 그대로 |
| 사용자 설명(SOL-01·03 TEXT) | `피해 설명을 제출했습니다.` | ⚠️ 고정 문구만 |
| 사진(SOL-06) | `사진(송금 내역)을 제출했습니다.` | ⚠️ 고정 문구만 |
| 자유 질문(QUESTION) | `질문을 접수했습니다.` | ⚠️ 고정 문구만 |
| AI 답변·추가 질문(assistant) | 저장 안 함. 인용 근거 코드만 저장 | ❌ 안 보여요 |
| 사실 수정(SOL-04) | `사실 수정: …` | ✅ 그대로 |
| 마지막 사실·카드 | 세션에 저장 | ✅ 그대로 |

사용자 글과 AI 답변까지 다시 보여 주려면 원문이나 AI 답변을 저장해야 해요. 이건 2장 B와 같은 원칙 문제라서 이번 범위에서는 빼고, 팀에서 정하도록 남겨 둘게요.

## 4. 구현 단계

1. **DB (V6, 새 마이그레이션)**
   - 인덱스만 추가해요: `CREATE INDEX ix_solution_sessions_user_completed ON solution_sessions (user_id, completed_at DESC, id DESC) WHERE status = 'COMPLETED'`
   - 컬럼은 추가하지 않아요(방안 A 기준).
   - 커밋 직전에 develop에서 마이그레이션 번호가 겹치지 않는지 확인해요.
2. **Repository:** `SolutionSessionRepository.findCompletedPage(userId, first, beforeCompletedAt, beforeId, Pageable)`
   - 목록에 필요한 컬럼만 읽어요: id, status, facts_json, created_at, completed_at
3. **도메인:** `SolutionTitles.of(SolutionFacts)`
   - 2장의 제목 규칙을 순수 함수로 만들어요.
4. **서비스:** `SolutionListService.completed(userId, size, cursor)`
   - 크기 확인, 커서 해석, `size + 1`개 읽기, 다음 커서 만들기를 해요.
   - 커서 처리는 리포트와 같은 방식이에요. 공용으로 빼는 대신 `SolutionCursor` record를 따로 둬요.
5. **컨트롤러:** `GET /api/solutions/sessions`
   - 회원 확인은 `LoginCurrentUser.requireUserId(authentication)`로 해요. 비회원은 401이에요.
   - `size`와 `cursor`는 문자열로 받아 직접 확인해요. SOL-06의 버전 파싱과 같은 방식이에요.
6. **보안:** `SolutionSecurityConfig`에 `GET /api/solutions/sessions`를 `permitAll`로 추가해요.
   - 로그인 검사는 컨트롤러에서 해요.
   - 보안 체인에서 막으면 오류 형식이 SOL 공통 JSON과 달라져서 이렇게 해요.
   - GET이라 CSRF는 필요 없어요.
7. **SOL-02 응답:** `createdAt`, `completedAt` 필드를 추가해요. 진행 중이면 `completedAt`은 null이에요. 필드 추가만이라 하위 호환돼요.
8. **문서:** `docs/sol-07-session-list.md` 보고서를 쓰고, `docs/api.md`에 SOL-07 행을 추가하고, SOL-02 응답 필드를 갱신해요.
   - `docs/WSID-API-Spec.md`는 수정하지 않아요.

## 5. 함께 확인할 문제

- **완료 상담 상세가 503이 될 수 있어요.** SOL-02는 저장된 카드 코드로 현재 검토 카드를 다시 찾는데, 카드가 없으면 503 `RESPONSE_CONTENT_UNAVAILABLE`을 내요. 지금 운영 카드 목록이 비어 있어서, 카드가 붙은 상담을 다시 보면 503이에요.
  - **이번 범위:** 그대로 둬요. 카드 목록이 생기면 해결돼요.
  - **대안:** 완료 상담에서는 없는 카드를 빼고 보여 줘요. 이건 SOL-02 결정 2를 바꾸는 일이라 팀 확인이 필요해요.
- **비회원 상담이 사라지는 문제:** 비회원으로 상담하고 나중에 가입하면 그 상담은 목록에 없어요. 옮기는 기능은 범위 밖이에요.
- **보관 기간:** 완료 상담과 메시지를 언제까지 보관할지 정책이 없어요. 범위 밖이에요.

## 6. 테스트

| 테스트 | 확인하는 것 |
|---|---|
| `SolutionTitlesTest` | 사실 조합별 제목, `YES` 없음 → 기본 제목, 최대 2개, 원문 미사용 |
| `SolutionCursorTest` | 인코딩·디코딩 왕복, 깨진 Base64·형식 오류·음수 id → 400 |
| `SolutionListServiceTest` | size 경계(0, 1, 50, 51), `size + 1` 판단, 마지막 페이지 `nextCursor = null` |
| `SolutionListDatabaseTest` (dbTest) | 회원 A의 COMPLETED만 조회(ACTIVE·회원 B·비회원 제외), 정렬·같은 시각 id 순서, 커서로 이어 읽을 때 겹침·누락 없음, 인덱스 사용 |
| `SolutionWebSecurityTest` | 비로그인·비회원 세션 401, 회원 200, 잘못된 size·cursor 400, `no-store`, 기존 `GET /sessions/{id}` 경로와 충돌 없음 |
| SOL-02 기존 테스트 + 추가 | 완료 상담 200과 메시지 기록, 다른 회원·비회원 404, `createdAt`·`completedAt` 값 |

명령: 단위 `./gradlew test`, DB·통합은 WSID 전용 컨테이너(55432·56379)로 `dbTest integrationTest`를 돌려요.

## 7. 정해 주셔야 할 것

1. **제목 방안:** A(사실 기반, 추천), B(가린 문구 저장), C(AI 요약) 중 무엇으로 할지
2. **API ID:** `SOL-07`로 해도 되는지 (`SOL-06`은 사진 입력)
3. **완료 상담에서 없는 카드:** 지금처럼 503으로 둘지, 빼고 보여 줄지
4. **size 상한:** 50으로 둘지 (리포트 목록은 100)
