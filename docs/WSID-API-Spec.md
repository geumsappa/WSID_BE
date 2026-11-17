# WSID API 명세

[Notion API 명세 원본](https://app.notion.com/p/cc952b445f9282b09b31014bfebebe6e) · [API 목록](https://app.notion.com/p/dcf52b445f928377adeb815621a299de)

Notion의 **API 최종** 목록 39개를 담은 공유본입니다. 각 상세 페이지의 요청과 응답 예시, 오류 코드, 구현 메모를 옮겼습니다. 예시는 Notion 설계 기준입니다.

## 팀 확인이 필요한 항목

AUTH-06의 CSRF 토큰 전달 방식은 Notion에서 미정입니다. [AUTH-06의 백엔드 연동 절차](#auth-06-csrf)에 현재 구현을 추가했습니다.

RPT-03의 `TOKEN_EXPIRED`는 Notion 원문에 남아 있는 코드입니다. 서버 세션 만료 시 사용할 리포트 오류 코드는 팀에서 확인이 필요합니다.

## 공통 규칙

- 로그인은 Spring Security 서버 세션을 사용합니다. 세션은 Redis에 저장하고 클라이언트에는 HttpOnly 쿠키를 발급합니다. 인증이 필요한 요청에 쿠키를 포함합니다.
- 긴급 피해 대응(SOL-01~05)은 비회원도 이용할 수 있습니다. 비회원 상담은 익명 서버 세션으로 소유권을 확인합니다.
- 성공 응답의 예시가 `data`만 표시된 경우 전체 JSON 응답은 `{"success":true,"data":{...}}`입니다. 응답 본문이 없는 `204`와 PDF 응답은 각 상세 명세를 따릅니다.
- 상태 변경 요청의 CSRF 방어 방식은 Notion 원본에서 팀 합의 사항으로 남아 있습니다.

## API 찾아보기

| ID | 기능 | 메서드 | 경로 |
| --- | --- | --- | --- |
| [AUTH-01](#auth-01) | 회원가입 | POST | `/api/auth/signup` |
| [AUTH-02](#auth-02) | 이메일 인증번호 재발송 | POST | `/api/auth/email/resend` |
| [AUTH-03](#auth-03) | 이메일 인증 완료 | POST | `/api/auth/email/verify` |
| [AUTH-04](#auth-04) | 로그인 | POST | `/api/auth/login` |
| [AUTH-05](#auth-05) | 로그인 세션 확인 | GET | `/api/auth/session` |
| [AUTH-06](#auth-06) | 로그아웃 | POST | `/api/auth/logout` |
| [AUTH-07](#auth-07) | 내 회원정보 조회 | GET | `/api/users/me` |
| [TRN-01](#trn-01) | 새 훈련 생성 | POST | `/api/trainings` |
| [TRN-02](#trn-02) | 진행 중 훈련 조회 | GET | `/api/trainings/active` |
| [TRN-03](#trn-03) | 훈련 상태·대화 복원 | GET | `/api/trainings/{id}` |
| [TRN-04](#trn-04) | 상황 파악 AI 대화 | POST | `/api/trainings/{id}/discovery/messages` |
| [TRN-05](#trn-05) | 이미지 첨부·OCR | POST | `/api/trainings/{id}/discovery/images` |
| [TRN-06](#trn-06) | 개인화 시나리오 생성 | POST | `/api/trainings/{id}/scenario` |
| [TRN-07](#trn-07) | 가상 훈련 시작 | POST | `/api/trainings/{id}/start` |
| [TRN-08](#trn-08) | AI 훈련 메시지 | POST | `/api/trainings/{id}/messages` |
| [TRN-09](#trn-09) | 모의 페이지 조회 | GET | `/api/trainings/{id}/mock-page` |
| [TRN-10](#trn-10) | 모의 페이지 행동 기록 | POST | `/api/trainings/{id}/actions` |
| [TRN-11](#trn-11) | 훈련 종료 및 리포트 생성 | POST | `/api/trainings/{id}/finish` |
| [RPT-01](#rpt-01) | 훈련 직후 결과 조회 | GET | `/api/trainings/{id}/report` |
| [RPT-02](#rpt-02) | 내 리포트에 저장 | POST | `/api/reports/{id}/save` |
| [RPT-03](#rpt-03) | 저장된 리포트 목록 | GET | `/api/reports` |
| [RPT-04](#rpt-04) | 리포트 상세 조회 | GET | `/api/reports/{id}` |
| [RPT-05](#rpt-05) | 리포트 PDF 다운로드 | GET | `/api/reports/{id}/pdf` |
| [SOL-01](#sol-01) | 피해 대응 상담 시작 | POST | `/api/solutions/sessions` |
| [SOL-02](#sol-02) | 피해 대응 상담 복원 | GET | `/api/solutions/sessions/{id}` |
| [SOL-03](#sol-03) | 피해 대응 상담 메시지 | POST | `/api/solutions/sessions/{id}/messages` |
| [SOL-04](#sol-04) | 피해 사실 수정 | PATCH | `/api/solutions/sessions/{id}/facts` |
| [SOL-05](#sol-05) | 피해 대응 상담 종료 | POST | `/api/solutions/sessions/{id}/complete` |
| [GUIDE-01](#guide-01) | 사기 유형 목록 조회 | GET | `/api/guides/fraud-types` |
| [GUIDE-02](#guide-02) | 사기 유형 상세 조회 | GET | `/api/guides/fraud-types/{code}` |
| [GUIDE-03](#guide-03) | 피해 대응 카드 조회 | GET | `/api/guides/response-cards` |
| [GUIDE-04](#guide-04) | 공식 기관 안내 조회 | GET | `/api/guides/institutions` |
| [AI-01](#ai-01) | 상황 파악 다음 질문 | POST | `/internal/ai/discovery/next` |
| [AI-02](#ai-02) | 이미지 OCR 추출 | POST | `/internal/ai/ocr/extract` |
| [AI-03](#ai-03) | 훈련 시나리오 생성 | POST | `/internal/ai/scenarios/generate` |
| [AI-04](#ai-04) | 시뮬레이션 AI 응답 | POST | `/internal/ai/simulation/reply` |
| [AI-05](#ai-05) | 훈련 최종 평가 | POST | `/internal/ai/training/evaluate` |
| [AI-06](#ai-06) | 피해 사실 추출 | POST | `/internal/ai/solutions/extract` |
| [AI-07](#ai-07) | 피해 대응 근거 설명 | POST | `/internal/ai/solutions/explain` |

## 회원·인증

<a id="auth-01"></a>

### AUTH-01 · 회원가입

[Notion 상세 페이지](https://app.notion.com/p/fa952b445f9282ef8da40107a84304d1)

#### 개요

이메일·비밀번호·성별로 가입 신청하고 인증 메일 발송.

| 속성 | 값 |
| --- | --- |
| Method | `POST` |
| Endpoint | `/api/auth/signup` |
| 인증 | 불필요 |
| 관련 저장소 | users / Redis |

#### Request

```json
{
  "email": "user@example.com",
  "password": "examplePassword!1",
  "gender": "MALE"
}
```

#### Response — 성공 시 data

```json
{
  "userId": 1,
  "email": "user@example.com",
  "status": "PENDING",
  "verificationRequired": true
}
```

전체 JSON 응답은 `{"success":true,"data":{...}}` 형식을 따릅니다. `204`는 응답 본문이 없습니다.

#### 예외 및 응답 코드

201 Created · EMAIL\_ALREADY\_EXISTS

#### 구현 메모

gender는 MALE·FEMALE, 필수 여부 미확정. 비밀번호 해시 저장. 기존 이메일 중복 확인.

> **상태:** 개발 전 설계안. 세부 유효성 검사 및 구현 응답 값은 팀 합의 후 확정합니다.

<a id="auth-02"></a>

### AUTH-02 · 이메일 인증번호 재발송

[Notion 상세 페이지](https://app.notion.com/p/aae52b445f92833996e9012bc64ea14a)

#### 개요

기존 인증 코드를 무효화하고 새 인증번호 발송.

| 속성 | 값 |
| --- | --- |
| Method | `POST` |
| Endpoint | `/api/auth/email/resend` |
| 인증 | 불필요 |
| 관련 저장소 | Redis |

#### Request

```json
{
  "email": "user@example.com"
}
```

#### Response — 성공 시 data

```json
{
  "message": "인증 메일 발송 요청이 처리되었습니다."
}
```

전체 JSON 응답은 `{"success":true,"data":{...}}` 형식을 따릅니다. `204`는 응답 본문이 없습니다.

#### 예외 및 응답 코드

200 OK · RATE\_LIMIT\_EXCEEDED

#### 구현 메모

인증번호 검증값은 Redis TTL 사용. 발송 횟수 제한. 가입 여부를 노출하지 않는 동일한 안내 반환.

> **상태:** 개발 전 설계안. 세부 유효성 검사 및 구현 응답 값은 팀 합의 후 확정합니다.

<a id="auth-03"></a>

### AUTH-03 · 이메일 인증 완료

[Notion 상세 페이지](https://app.notion.com/p/4d052b445f92838cac5d81a7ee55abc7)

#### 개요

사용자 인증번호 검증 후 회원 상태를 활성화.

| 속성 | 값 |
| --- | --- |
| Method | `POST` |
| Endpoint | `/api/auth/email/verify` |
| 인증 | 불필요 |
| 관련 저장소 | users / Redis |

#### Request

```json
{
  "email": "user@example.com",
  "code": "123456"
}
```

#### Response — 성공 시 data

```json
{
  "verified": true,
  "status": "ACTIVE"
}
```

전체 JSON 응답은 `{"success":true,"data":{...}}` 형식을 따릅니다. `204`는 응답 본문이 없습니다.

#### 예외 및 응답 코드

200 OK · VERIFICATION\_CODE\_EXPIRED · INVALID\_VERIFICATION\_CODE

#### 구현 메모

인증 성공 시 email\_verified\_at 설정, status=ACTIVE. 사용된 코드는 즉시 삭제.

> **상태:** 개발 전 설계안. 세부 유효성 검사 및 구현 응답 값은 팀 합의 후 확정합니다.

<a id="auth-04"></a>

### AUTH-04 · 로그인

[Notion 상세 페이지](https://app.notion.com/p/c5c52b445f928213abd2816f24d982c1)

#### 개요

이메일·비밀번호를 검증하고 서버에 로그인 세션을 생성합니다. JWT·Refresh Token은 발급하지 않습니다.

| 속성 | 값 |
| --- | --- |
| Method | `POST` |
| Endpoint | `/api/auth/login` |
| 인증 | 불필요 |
| 관련 저장소 | users / Redis |

#### Request

```json
{
  "email": "user@example.com",
  "password": "examplePassword!1"
}
```

#### Response — 성공 시 data

응답 헤더: `Set-Cookie: SESSION=<세션ID>; HttpOnly; Secure; SameSite=Lax; Path=/` (HTTPS 운영 기준). 세션 ID는 응답 JSON에 포함하지 않습니다.

```json
{
  "user": {
    "id": 1,
    "email": "user@example.com",
    "gender": "MALE"
  }
}
```

전체 JSON 응답은 `{"success":true,"data":{...}}` 형식을 따릅니다. `204`는 응답 본문이 없습니다.

#### 예외 및 응답 코드

200 OK · INVALID\_CREDENTIALS · EMAIL\_NOT\_VERIFIED

#### 구현 메모

Spring Security 인증 성공 시 HTTP 세션 생성, Spring Session Redis에 로그인 세션 저장. 서버는 응답 Set-Cookie로 SESSION 세션 ID 쿠키 발급(HttpOnly, Secure(HTTPS 운영), SameSite=Lax, Path=/; 쿠키명은 설정에 따라 JSESSIONID 가능). 로그인 시 세션 ID 교체로 세션 고정 공격 방지. 이메일 미인증 로그인 제한. React는 쿠키 자동 전송(credentials: 'include')하며 Authorization: Bearer 헤더를 사용하지 않음. 상태 변경 API는 CSRF 보호 필수(토큰 전달 방식은 팀 합의).

> **상태:** 개발 전 설계안. 세부 유효성 검사 및 구현 응답 값은 팀 합의 후 확정합니다.

<a id="auth-05"></a>

### AUTH-05 · 로그인 세션 확인

[Notion 상세 페이지](https://app.notion.com/p/c4552b445f9282988c6f810476161b11)

#### 개요

현재 브라우저의 로그인 세션 쿠키를 확인하여 인증 여부와 로그인 사용자 정보를 반환합니다. 토큰 갱신은 수행하지 않습니다.

| 속성 | 값 |
| --- | --- |
| Method | `GET` |
| Endpoint | `/api/auth/session` |
| 인증 | 세션 쿠키 |
| 관련 저장소 | Redis |

#### Request

요청 본문 없음. 브라우저가 `SESSION`(또는 서버 설정상 `JSESSIONID`) 쿠키를 자동 전송합니다. React 요청 시 `credentials: 'include'`를 사용합니다.

#### Response — 성공 시 data

```json
{
  "authenticated": true,
  "user": {
    "id": 1,
    "email": "user@example.com",
    "gender": "MALE"
  }
}
```

전체 JSON 응답은 `{"success":true,"data":{...}}` 형식을 따릅니다. `204`는 응답 본문이 없습니다.

#### 예외 및 응답 코드

200 OK · 401 Unauthorized (세션 없음·만료)

#### 구현 메모

Spring Security의 현재 인증 세션을 조회합니다. Redis(Spring Session)에 저장된 세션이 없거나 만료됐으면 401을 반환합니다. 로그인 정보 확인용 API이며 세션을 새로 발급하거나 JWT를 갱신하지 않습니다.

> **상태:** 개발 전 설계안. 세부 유효성 검사 및 구현 응답 값은 팀 합의 후 확정합니다.

<a id="auth-06"></a>

### AUTH-06 · 로그아웃

[Notion 상세 페이지](https://app.notion.com/p/a0652b445f9282f6b64c81487999d4f0)

#### 개요

현재 로그인한 사용자의 서버 세션을 무효화하고 세션 쿠키를 만료시킵니다.

| 속성 | 값 |
| --- | --- |
| Method | `POST` |
| Endpoint | `/api/auth/logout` |
| 인증 | 필요 |
| 관련 저장소 | Redis |

#### Request

요청 본문 없음. 세션 쿠키 자동 전송. CSRF 보호에 필요한 토큰을 함께 전송합니다.

<a id="auth-06-csrf"></a>

#### CSRF 토큰 발급 및 전송 (현재 백엔드 구현)

아래 절차는 Notion 명세에서 미정인 토큰 전달 방식을 보완합니다.

1. 로그인한 상태에서 같은 `SESSION` 쿠키로 `GET /api/auth/csrf`를 호출합니다. 요청 본문은 없습니다.
2. `200 OK` 응답의 `data.headerName`과 `data.token`을 읽습니다.

```json
{
  "success": true,
  "data": {
    "headerName": "X-CSRF-TOKEN",
    "token": "<서버가 발급한 토큰>"
  }
}
```

3. 같은 세션에서 `POST /api/auth/logout`을 **본문 없이** 호출합니다. 응답의 `data.headerName`을 요청 헤더 이름으로, `data.token`을 값으로 사용합니다.

```http
POST /api/auth/logout
X-CSRF-TOKEN: <서버가 발급한 토큰>
```

성공하면 `204 No Content`와 함께 서버 세션이 폐기되고 쿠키가 만료됩니다. 세션이 없거나 만료되면 `401`, 유효한 세션에서 토큰이 누락되거나 올바르지 않으면 `403`이 반환됩니다.

#### Response — 성공 시 data

204 No Content (본문 없음)

전체 JSON 응답은 `{"success":true,"data":{...}}` 형식을 따릅니다. `204`는 응답 본문이 없습니다.

#### 예외 및 응답 코드

204 No Content · 401 Unauthorized (로그인 세션 없음·만료)

#### 구현 메모

Spring Security 로그아웃 처리 시 HttpSession.invalidate() 및 SecurityContext 정리. Spring Session Redis의 해당 세션 삭제, SESSION(또는 JSESSIONID) 쿠키 만료. Refresh Token·JWT를 사용하지 않습니다. CSRF 보호 적용.

> **상태:** 개발 전 설계안. 세부 유효성 검사 및 구현 응답 값은 팀 합의 후 확정합니다.

<a id="auth-07"></a>

### AUTH-07 · 내 회원정보 조회

[Notion 상세 페이지](https://app.notion.com/p/b5352b445f928269b68201c2085e661a)

#### 개요

현재 로그인 회원의 프로필을 반환.

| 속성 | 값 |
| --- | --- |
| Method | `GET` |
| Endpoint | `/api/users/me` |
| 인증 | 필요 |
| 관련 저장소 | users |

#### Request

경로/쿼리/본문 없음. 브라우저에서 세션 쿠키가 자동 전송됩니다.

#### Response — 성공 시 data

```json
{
  "id": 1,
  "email": "user@example.com",
  "gender": "MALE",
  "status": "ACTIVE",
  "createdAt": "2026-11-12T10:00:00+09:00"
}
```

전체 JSON 응답은 `{"success":true,"data":{...}}` 형식을 따릅니다. `204`는 응답 본문이 없습니다.

#### 예외 및 응답 코드

200 OK · 401 Unauthorized (세션 없음·만료)

#### 구현 메모

Spring Security Authentication(서버 세션)에서 로그인 사용자 ID 확인. 비밀번호 해시 미반환. 성별은 null 가능(선택수집일 경우). JWT·Bearer 토큰을 사용하지 않습니다.

> **상태:** 개발 전 설계안. 세부 유효성 검사 및 구현 응답 값은 팀 합의 후 확정합니다.

## AI 훈련

<a id="trn-01"></a>

### TRN-01 · 새 훈련 생성

[Notion 상세 페이지](https://app.notion.com/p/25f52b445f9283d88da781e3db4360c1)

#### 개요

새로운 상황 파악 훈련 세션 생성.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_sessions |

#### Request

```json
{}
```

#### Response — 성공 data

```json
{
  "trainingId": 101,
  "phase": "DISCOVERY",
  "status": "ACTIVE",
  "stateVersion": 0,
  "firstMessage": {
    "sender": "AI",
    "content": "최근 어떤 상황을 겪으셨나요?"
  }
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

201 Created

#### 구현 및 검증 포인트

최초 단계 DISCOVERY. 로그인 회원 소유로 생성.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-02"></a>

### TRN-02 · 진행 중 훈련 조회

[Notion 상세 페이지](https://app.notion.com/p/76952b445f92826db8ca01d4050bda23)

#### 개요

회원의 진행 중인 훈련 세션 확인.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/active` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_sessions |

#### Request

본문 없음

#### Response — 성공 data

```json
{
  "trainingId": 101,
  "phase": "SIMULATION",
  "status": "ACTIVE",
  "lastActivityAt": "2026-11-12T11:00:00+09:00"
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK

#### 구현 및 검증 포인트

진행 중 훈련이 없으면 data=null. 다중 진행 허용 여부 확정 필요.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-03"></a>

### TRN-03 · 훈련 상태·대화 복원

[Notion 상세 페이지](https://app.notion.com/p/0d852b445f9283b49d8e81f5bc4dd8bc)

#### 개요

중단된 훈련 화면·대화·선택지 복원.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_sessions / training\_messages |

#### Request

Path: id=trainingId

#### Response — 성공 data

```json
{
  "trainingId": 101,
  "phase": "SIMULATION",
  "status": "ACTIVE",
  "stateVersion": 5,
  "turnCount": 3,
  "messages": [
    {
      "id": 1,
      "sender": "AI",
      "inputKind": "TEXT",
      "content": "확인이 필요합니다."
    }
  ],
  "availableOptions": [],
  "mockPage": null
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · TRAINING\_NOT\_FOUND

#### 구현 및 검증 포인트

훈련 완료 후 원문 삭제 시 대화 복원 불가. 회원 소유권 확인.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-04"></a>

### TRN-04 · 상황 파악 AI 대화

[Notion 상세 페이지](https://app.notion.com/p/3c452b445f9282588cb8017b553c65d1)

#### 개요

사용자 근황을 입력받아 AI가 다음 질문 제공.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/discovery/messages` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_messages / training\_sessions |

#### Request

```json
{
  "requestId": "uuid",
  "stateVersion": 0,
  "content": "최근 중고거래를 했어요."
}
```

#### Response — 성공 data

```json
{
  "reply": {
    "sender": "AI",
    "content": "물건을 사는 쪽이셨나요?"
  },
  "discoveryTurnCount": 1,
  "canGenerateScenario": false,
  "stateVersion": 1
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · SENSITIVE\_DATA\_DETECTED · STATE\_VERSION\_CONFLICT

#### 구현 및 검증 포인트

DISCOVERY 단계에서만 허용. 민감정보 감지. 입력 중복 방지 requestId 사용.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-05"></a>

### TRN-05 · 이미지 첨부·OCR

[Notion 상세 페이지](https://app.notion.com/p/bf052b445f92833583f001a62a1f40ae)

#### 개요

상황 파악용 첨부 이미지에서 문자 추출.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/discovery/images` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | 임시 스토리지 / FastAPI |

#### Request

multipart/form-data: images(File\[\]), requestId(UUID)

#### Response — 성공 data

```json
{
  "recognizedText": "거래 관련 안내 문구",
  "usable": true
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · FILE\_TOO\_LARGE · OCR\_FAILED

#### 구현 및 검증 포인트

PNG/JPEG 최대 3장, 장당 5MB 초안. 개인 정보 검토 후 원본 임시 파일 삭제. OCR 실패 시 직접 입력.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-06"></a>

### TRN-06 · 개인화 시나리오 생성

[Notion 상세 페이지](https://app.notion.com/p/46352b445f928364b342816f1dfddffd)

#### 개요

상황 파악 결과를 기반으로 가상 금융사기 시나리오 생성.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/scenario` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_sessions / FastAPI |

#### Request

```json
{
  "stateVersion": 3,
  "requestId": "uuid"
}
```

#### Response — 성공 data

```json
{
  "trainingId": 101,
  "phase": "SCENARIO_READY",
  "fraudTypeCode": "SECONDHAND_TRADE",
  "scenario": {
    "title": "중고거래 안전 확인 훈련",
    "description": "의심스러운 요청을 받는 상황"
  },
  "mockPageTemplateCode": "BANK_NOTICE",
  "stateVersion": 4
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · INVALID\_TRAINING\_PHASE · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

현실 사례 기반 안전한 가상 시나리오. 모의 페이지 템플릿 코드 포함 가능.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-07"></a>

### TRN-07 · 가상 훈련 시작

[Notion 상세 페이지](https://app.notion.com/p/57b52b445f928208b6858106ac01b8f8)

#### 개요

생성된 시나리오로 사용자-사기상황 AI 훈련 시작.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/start` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_sessions / training\_messages |

#### Request

```json
{
  "stateVersion": 4,
  "requestId": "uuid"
}
```

#### Response — 성공 data

```json
{
  "phase": "SIMULATION",
  "firstMessage": {
    "sender": "AI",
    "content": "거래 전에 확인이 필요합니다."
  },
  "stateVersion": 5
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · INVALID\_TRAINING\_PHASE

#### 구현 및 검증 포인트

기존 훈련 id 유지, SCENARIO\_READY에서만 시작.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-08"></a>

### TRN-08 · AI 훈련 메시지

[Notion 상세 페이지](https://app.notion.com/p/27252b445f928377b84401d20822e707)

#### 개요

사용자 발화/선택지를 전달하고 AI 답변 및 행동 평가 반환.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/messages` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_messages / FastAPI |

#### Request

```json
{
  "requestId": "uuid",
  "stateVersion": 5,
  "inputKind": "TEXT",
  "content": "공식 고객센터를 확인하겠습니다."
}
```

#### Response — 성공 data

```json
{
  "reply": {
    "sender": "AI",
    "content": "공식 채널에서 확인해 보세요.",
    "options": []
  },
  "recognizedActions": [
    "OFFICIAL_CHANNEL_CHECK"
  ],
  "turnCount": 4,
  "shouldFinish": false,
  "mockPage": null,
  "stateVersion": 6
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · STATE\_VERSION\_CONFLICT · AI\_RATE\_LIMIT\_EXCEEDED

#### 구현 및 검증 포인트

CHOICE는 selectedCode 사용. 대화 턴 제한은 초안 최대 15턴. stateVersion 충돌 처리.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-09"></a>

### TRN-09 · 모의 페이지 조회

[Notion 상세 페이지](https://app.notion.com/p/96152b445f928203a5b381dc763dc2fa)

#### 개요

가상 은행·공공·채용 페이지 렌더링 데이터 반환.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/mock-page` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_sessions |

#### Request

Path: id=trainingId

#### Response — 성공 data

```json
{
  "templateCode": "BANK_NOTICE",
  "title": "가상 금융 안내",
  "institutionName": "가상금융센터",
  "screenCode": "LOGIN_CHECK",
  "isSimulation": true
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · TRAINING\_NOT\_FOUND

#### 구현 및 검증 포인트

실제 기관 로그인과 오인되지 않도록 가상임을 명확히 표시. 민감정보 입력·수집 금지.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-10"></a>

### TRN-10 · 모의 페이지 행동 기록

[Notion 상세 페이지](https://app.notion.com/p/b9e52b445f92832e991b01c5fe32bcbf)

#### 개요

가상 화면에서 열기/닫기/로그인시도/공식 채널 확인 이벤트 저장.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/actions` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_messages |

#### Request

```json
{
  "requestId": "uuid",
  "stateVersion": 6,
  "actionCode": "MOCK_PAGE_OPEN",
  "actionMeta": {
    "templateCode": "BANK_NOTICE",
    "screenCode": "LOGIN_CHECK"
  }
}
```

#### Response — 성공 data

```json
{
  "recorded": true,
  "stateVersion": 7
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · STATE\_VERSION\_CONFLICT

#### 구현 및 검증 포인트

MOCK\_PAGE\_OPEN,CLOSE,MOCK\_LOGIN\_ATTEMPT,OFFICIAL\_CHANNEL\_CHECK. 실제 자격증명은 저장하지 않음.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

<a id="trn-11"></a>

### TRN-11 · 훈련 종료 및 리포트 생성

[Notion 상세 페이지](https://app.notion.com/p/a3152b445f9283f7984401e361c2f2e7)

#### 개요

훈련 종료 후 AI 평가 리포트를 생성·저장.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/finish` |
| 인증 | 로그인 세션 쿠키 필수, 소유권 검증 |
| 관련 저장소 | training\_sessions / training\_reports / FastAPI |

#### Request

```json
{
  "requestId": "uuid",
  "stateVersion": 7,
  "reason": "USER_FINISH"
}
```

#### Response — 성공 data

```json
{
  "trainingId": 101,
  "status": "COMPLETED",
  "phase": "RESULT",
  "reportId": 501
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · INVALID\_TRAINING\_PHASE · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

리포트 중복 생성 금지(훈련당 하나). 평가 실패 시 상태 재시도 가능하도록 설계.

> **설계 초안:** 팀 확정 전 엔드포인트·DTO 변경 가능.

## 리포트

<a id="rpt-01"></a>

### RPT-01 · 훈련 직후 결과 조회

[Notion 상세 페이지](https://app.notion.com/p/26a52b445f928387ac6a81f86b3dc4b5)

#### 개요

해당 훈련의 분석 리포트 조회.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/trainings/{id}/report` |
| 인증 | 로그인 세션 쿠키 필수 / 본인 소유권 검증 |
| 관련 저장소 | training\_reports |

#### Request

Path: id=trainingId

#### Response — 성공 data

```json
{
  "reportId": 501,
  "trainingId": 101,
  "title": "중고거래 금융사기 예방 훈련",
  "fraudTypeCode": "SECONDHAND_TRADE",
  "saved": false,
  "evaluation": {
    "overallFeedback": "위험 신호를 인식했습니다.",
    "strengths": [
      "공식 채널 확인"
    ],
    "improvements": [
      "신원 확인"
    ],
    "turnFeedback": [],
    "mockPageEvaluation": {
      "openedPage": true,
      "attemptedMockLogin": false,
      "checkedOfficialChannel": true
    },
    "recommendations": [
      "공식 고객센터 확인"
    ]
  }
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · REPORT\_NOT\_FOUND

#### 구현 및 검증 포인트

훈련 직후 저장 전에도 조회 가능. 원문 전체를 report\_json에 복제하지 않음.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="rpt-02"></a>

### RPT-02 · 내 리포트에 저장

[Notion 상세 페이지](https://app.notion.com/p/9eb52b445f92838192430199cd3088e6)

#### 개요

리포트 보관 여부 지정 및 훈련 원문 삭제.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/reports/{id}/save` |
| 인증 | 로그인 세션 쿠키 필수 / 본인 소유권 검증 |
| 관련 저장소 | training\_reports / training\_messages |

#### Request

Path: id=reportId

#### Response — 성공 data

```json
{
  "reportId": 501,
  "saved": true,
  "savedAt": "2026-11-12T12:00:00+09:00"
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · REPORT\_NOT\_FOUND

#### 구현 및 검증 포인트

saved\_at 기록 및 훈련 메시지 원문 삭제를 한 트랜잭션으로 처리. flow\_state\_json에 원문 복사본이 남지 않도록 정리.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="rpt-03"></a>

### RPT-03 · 저장된 리포트 목록

[Notion 상세 페이지](https://app.notion.com/p/b6252b445f9283d1832401e82d276330)

#### 개요

회원이 저장한 리포트를 최신순 조회.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/reports` |
| 인증 | 로그인 세션 쿠키 필수 / 본인 소유권 검증 |
| 관련 저장소 | training\_reports / training\_sessions |

#### Request

Query: size=10, cursor(선택)

#### Response — 성공 data

```json
{
  "items": [
    {
      "reportId": 501,
      "title": "중고거래 금융사기 예방 훈련",
      "fraudTypeCode": "SECONDHAND_TRADE",
      "completedAt": "2026-11-12T11:55:00+09:00"
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · TOKEN\_EXPIRED

#### 구현 및 검증 포인트

saved\_at IS NOT NULL만 조회. 회원 소유권 검증. 페이지네이션.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="rpt-04"></a>

### RPT-04 · 리포트 상세 조회

[Notion 상세 페이지](https://app.notion.com/p/b8252b445f9282799b0281d6f333ff2a)

#### 개요

저장된 특정 리포트의 종합 평가·턴별 피드백 조회.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/reports/{id}` |
| 인증 | 로그인 세션 쿠키 필수 / 본인 소유권 검증 |
| 관련 저장소 | training\_reports |

#### Request

Path: id=reportId

#### Response — 성공 data

```json
{
  "reportId": 501,
  "title": "중고거래 금융사기 예방 훈련",
  "saved": true,
  "evaluation": {
    "overallFeedback": "공식 채널 확인을 잘 수행했습니다.",
    "turnFeedback": [],
    "mockPageEvaluation": {
      "openedPage": true,
      "attemptedMockLogin": false,
      "checkedOfficialChannel": true
    },
    "recommendations": []
  }
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · REPORT\_NOT\_FOUND

#### 구현 및 검증 포인트

보관(saved\_at NOT NULL)된 본인 소유 리포트만 조회. 훈련 직후는 RPT-01 사용.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="rpt-05"></a>

### RPT-05 · 리포트 PDF 다운로드

[Notion 상세 페이지](https://app.notion.com/p/f2b52b445f92825c807d01405858ae30)

#### 개요

report\_json으로 PDF를 즉석 생성해 다운로드.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/reports/{id}/pdf` |
| 인증 | 로그인 세션 쿠키 필수 / 본인 소유권 검증 |
| 관련 저장소 | training\_reports |

#### Request

Path: id=reportId

#### Response — 성공 data

Content-Type: application/pdf; Content-Disposition: attachment; filename="training-report.pdf"

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK (PDF 바이너리) · REPORT\_NOT\_FOUND

#### 구현 및 검증 포인트

PDF 별도 DB 저장하지 않음. 민감정보 비포함 및 접근 권한 검증.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

## 피해 대응

<a id="sol-01"></a>

### SOL-01 · 피해 대응 상담 시작

[Notion 상세 페이지](https://app.notion.com/p/ca552b445f9283c9af3c010d44c92b87)

#### 개요

사용자 의심 피해 설명을 분석하고 초기 대응 카드와 질문 제공.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/solutions/sessions` |
| 인증 | 로그인 불필요 (회원·비회원 모두 이용 가능) |
| 관련 저장소 | solution\_sessions / solution\_messages / FastAPI |

#### Request

```json
{
  "description": "모르는 사람에게 돈을 보냈고 연락이 끊겼어요."
}
```

#### Response — 성공 data

```json
{
  "solutionId": 301,
  "status": "ACTIVE",
  "stateVersion": 0,
  "facts": {
    "transferredMoney": "YES",
    "sharedInformation": "UNKNOWN",
    "installedApp": "UNKNOWN",
    "contactContinues": "NO"
  },
  "responseCards": [
    {
      "code": "TRANSFER_RESPONSE",
      "priority": "URGENT",
      "title": "송금 관련 우선 대응",
      "institutionCodes": [
        "POLICE",
        "BANK"
      ]
    }
  ],
  "nextQuestion": {
    "factKey": "sharedInformation",
    "content": "개인정보를 전달한 적이 있나요?",
    "options": [
      "YES",
      "NO",
      "UNKNOWN"
    ]
  }
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

201 Created · SENSITIVE\_DATA\_DETECTED

#### 구현 및 검증 포인트

원문은 DB·로그에 저장 금지. 확인 사실은 YES/NO/UNKNOWN. 긴급 대응 우선. 설명 미제공시 선택형 질문으로 시작 가능.

**회원·비회원 공통 정책:** 로그인 없이도 상담을 새로 시작할 수 있습니다. 회원은 로그인 사용자, 비회원은 서버가 발급·관리하는 익명 세션 소유자로 상담을 연결합니다. 비회원 최초 호출 시 서버가 임의 소유자 식별자를 세션에 저장하고 HttpOnly 세션 쿠키(SESSION 또는 설정된 JSESSIONID)를 발급하는 구현을 권장합니다. 클라이언트에서 전달한 회원 ID·소유자 ID는 신뢰하지 않습니다. 브라우저는 쿠키를 포함하여 요청하며 변경 요청에는 CSRF 방어를 적용합니다. 구현 방식과 익명 세션 만료 기간은 팀에서 확정합니다.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="sol-02"></a>

### SOL-02 · 피해 대응 상담 복원

[Notion 상세 페이지](https://app.notion.com/p/cf552b445f9282b5a449013c74703b75)

#### 개요

상담 상태·비식별 사실·안내 카드·마스킹 메시지 조회.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/solutions/sessions/{id}` |
| 인증 | 로그인 불필요 / 회원·비회원 상담 소유권 검증 |
| 관련 저장소 | solution\_sessions / solution\_messages |

#### Request

Path: id=solutionId

#### Response — 성공 data

```json
{
  "solutionId": 301,
  "status": "ACTIVE",
  "stateVersion": 2,
  "facts": {
    "transferredMoney": "YES",
    "sharedInformation": "NO",
    "installedApp": "UNKNOWN",
    "contactContinues": "NO"
  },
  "responseCards": [],
  "nextQuestion": null,
  "messages": []
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · SOLUTION\_NOT\_FOUND

#### 구현 및 검증 포인트

회원은 로그인 세션의 사용자 ID, 비회원은 상담 생성 시 사용한 익명 세션 소유자로 접근 권한을 확인합니다. 상담 ID만 알아서는 조회할 수 없으며, 타인 상담에는 SOLUTION\_NOT\_FOUND(404)로 응답합니다. 비회원 세션 쿠키 분실·만료 시 이전 상담 복원 불가(재시작 안내). 원본 피해 상담 메시지는 복원하지 않음.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="sol-03"></a>

### SOL-03 · 피해 대응 상담 메시지

[Notion 상세 페이지](https://app.notion.com/p/85952b445f9283a2b7718101bbda945f)

#### 개요

자유입력/선택지 반영 후 사실 업데이트·대응 카드·후속 질문 반환.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/solutions/sessions/{id}/messages` |
| 인증 | 로그인 불필요 / 회원·비회원 상담 소유권 검증 |
| 관련 저장소 | solution\_messages / solution\_sessions / FastAPI |

#### Request

```json
{
  "requestId": "uuid",
  "stateVersion": 2,
  "inputKind": "CHOICE",
  "selectedCode": "NO",
  "factKey": "sharedInformation"
}
```

#### Response — 성공 data

```json
{
  "facts": {
    "transferredMoney": "YES",
    "sharedInformation": "NO",
    "installedApp": "UNKNOWN",
    "contactContinues": "NO"
  },
  "responseCards": [],
  "nextQuestion": {
    "factKey": "installedApp",
    "content": "상대 안내로 앱을 설치했나요?",
    "options": [
      "YES",
      "NO",
      "UNKNOWN"
    ]
  },
  "stateVersion": 3
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · STATE\_VERSION\_CONFLICT · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

TEXT라면 content 사용. 사용자 원문 미저장, safe\_content만 DB 저장. stateVersion 및 factKey 검증. 회원·비회원 모두 호출 가능하며, 매 요청에서 로그인 회원 또는 익명 세션의 상담 소유권을 확인합니다(타인 상담은 404). 쿠키 자동 전송 및 CSRF 보호를 적용합니다.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="sol-04"></a>

### SOL-04 · 피해 사실 수정

[Notion 상세 페이지](https://app.notion.com/p/75b52b445f9283ccbe9d813b0f3e2f92)

#### 개요

사용자가 확인된 피해 사실을 정정하고 카드·후속질문 재계산.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/solutions/sessions/{id}/facts` |
| 인증 | 로그인 불필요 / 회원·비회원 상담 소유권 검증 |
| 관련 저장소 | solution\_sessions |

#### Request

```json
{
  "stateVersion": 3,
  "changes": {
    "installedApp": "YES"
  }
}
```

#### Response — 성공 data

```json
{
  "facts": {
    "transferredMoney": "YES",
    "sharedInformation": "NO",
    "installedApp": "YES",
    "contactContinues": "NO"
  },
  "responseCards": [
    {
      "code": "MALICIOUS_APP_RESPONSE",
      "priority": "URGENT"
    }
  ],
  "stateVersion": 4
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · STATE\_VERSION\_CONFLICT

#### 구현 및 검증 포인트

허용된 사실 키만 변경. 상태 버전 증가 및 조합별 대응카드 재계산. 회원·비회원 모두 자신의 상담만 수정할 수 있도록 회원 ID 또는 익명 세션 소유자를 확인합니다(타인 상담은 404). 쿠키 자동 전송 및 CSRF 보호를 적용합니다.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

<a id="sol-05"></a>

### SOL-05 · 피해 대응 상담 종료

[Notion 상세 페이지](https://app.notion.com/p/9e152b445f9282efb28f8120fe60e800)

#### 개요

상담 세션의 상태를 COMPLETED로 변경.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/solutions/sessions/{id}/complete` |
| 인증 | 로그인 불필요 / 회원·비회원 상담 소유권 검증 |
| 관련 저장소 | solution\_sessions |

#### Request

Path: id=solutionId

#### Response — 성공 data

```json
{
  "solutionId": 301,
  "status": "COMPLETED"
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · SOLUTION\_NOT\_FOUND

#### 구현 및 검증 포인트

완료 후 갱신 불가 정책은 팀 결정. 민감정보 원문은 끝까지 저장하지 않음. 회원·비회원 모두 자신의 상담만 종료 가능하도록 회원 ID 또는 익명 세션 소유자를 확인합니다(타인 상담은 404). 쿠키 자동 전송 및 CSRF 보호를 적용합니다.

> **설계 초안:** 개발 전 팀에서 DTO·정책 확정.

## 유형·가이드

<a id="guide-01"></a>

### GUIDE-01 · 사기 유형 목록 조회

[Notion 상세 페이지](https://app.notion.com/p/b2b52b445f92821aa7bd81ee3fe0a345)

#### 개요

등록된 금융사기 유형의 목록을 조회.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/guides/fraud-types` |
| 인증 | 불필요 |
| 데이터 | 검토된 YAML |

#### Request

쿼리/본문 없음

#### Response — 성공 data

```json
[
  {
    "code": "SECONDHAND_TRADE",
    "name": "중고거래 관련 사기",
    "description": "중고거래 중 발생할 수 있는 위험 유형"
  }
]
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK

#### 구현 및 검증 포인트

관리자가 검토한 유형 코드만 제공. DB 테이블 불필요.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="guide-02"></a>

### GUIDE-02 · 사기 유형 상세 조회

[Notion 상세 페이지](https://app.notion.com/p/2a552b445f9283a0839681c650f8da3b)

#### 개요

선택한 사기 유형의 특징·예방 수칙·근거 조회.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/guides/fraud-types/{code}` |
| 인증 | 불필요 |
| 데이터 | 검토된 YAML |

#### Request

Path: code=사기 유형 코드

#### Response — 성공 data

```json
{
  "code": "SECONDHAND_TRADE",
  "name": "중고거래 관련 사기",
  "description": "거래 과정에서 발생하는 위험",
  "examples": [],
  "warningSignals": [],
  "preventionTips": [],
  "references": []
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · GUIDE\_NOT\_FOUND

#### 구현 및 검증 포인트

유형별 증거·공식 출처 관리. 검토되지 않은 AI 생성 URL 사용 금지.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="guide-03"></a>

### GUIDE-03 · 피해 대응 카드 조회

[Notion 상세 페이지](https://app.notion.com/p/51752b445f928336b6a28198706b9547)

#### 개요

사기 상황별 승인된 대응 조치 카드 제공.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/guides/response-cards` |
| 인증 | 불필요 |
| 데이터 | 검토된 YAML |

#### Request

Query: codes=TRANSFER\_RESPONSE,MALICIOUS\_APP\_RESPONSE

#### Response — 성공 data

```json
[
  {
    "code": "TRANSFER_RESPONSE",
    "title": "송금 관련 우선 대응",
    "priority": "URGENT",
    "actions": [],
    "requiredMaterials": [],
    "institutionCodes": [
      "BANK",
      "POLICE"
    ],
    "sourceReferences": [],
    "reviewedAt": "2026-11-12"
  }
]
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK

#### 구현 및 검증 포인트

카드의 행동, 기관, 출처는 검토된 데이터만 이용. 예시는 데이터 스키마 설명용.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="guide-04"></a>

### GUIDE-04 · 공식 기관 안내 조회

[Notion 상세 페이지](https://app.notion.com/p/2a552b445f928347b0db816245176f63)

#### 개요

공식 기관의 연락처·역할·안내 URL 반환.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/api/guides/institutions` |
| 인증 | 불필요 |
| 데이터 | 검토된 YAML |

#### Request

쿼리/본문 없음

#### Response — 성공 data

```json
[
  {
    "code": "POLICE",
    "name": "경찰",
    "role": "피해 신고 접수",
    "phone": null,
    "officialUrl": null,
    "reviewedAt": null
  }
]
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK

#### 구현 및 검증 포인트

정확한 전화번호·주소는 별도 검증 후 게시. AI가 임의 URL 작성 금지.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

## 내부 AI

<a id="ai-01"></a>

### AI-01 · 상황 파악 다음 질문

[Notion 상세 페이지](https://app.notion.com/p/09852b445f92821183e601514fe737e2)

#### 개요

훈련용 최근 상황 대화에서 사실을 추출하고 다음 질문 생성.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/internal/ai/discovery/next` |
| 인증 | 내부 |
| 데이터 | FastAPI / LLM |

#### Request

```json
{
  "conversation": [],
  "knownFacts": {},
  "currentInput": "최근 중고거래를 했어요."
}
```

#### Response — 성공 data

```json
{
  "facts": {
    "activity": "중고거래"
  },
  "nextQuestion": "물건을 사는 쪽이었나요?",
  "canGenerateScenario": false
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

Spring Boot 내부 호출 전용. 출력 JSON 검증. 원문/개인정보 로그 금지.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="ai-02"></a>

### AI-02 · 이미지 OCR 추출

[Notion 상세 페이지](https://app.notion.com/p/d6d52b445f928374ba2f01b4296605f2)

#### 개요

훈련 이미지에서 문자를 추출하고 민감정보 검사.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/internal/ai/ocr/extract` |
| 인증 | 내부 |
| 데이터 | FastAPI / OCR |

#### Request

multipart/form-data: images(File\[\])

#### Response — 성공 data

```json
{
  "recognizedText": "거래 관련 안내",
  "usable": true
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · OCR\_FAILED

#### 구현 및 검증 포인트

원본 임시 보관 제한 및 OCR 실패 처리. 외부 사용자 직접 호출 금지.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="ai-03"></a>

### AI-03 · 훈련 시나리오 생성

[Notion 상세 페이지](https://app.notion.com/p/85752b445f9282c2aec501362df22986)

#### 개요

상황 파악 결과와 검토된 사례를 조합해 훈련 시나리오 생성.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/internal/ai/scenarios/generate` |
| 인증 | 내부 |
| 데이터 | FastAPI / LLM |

#### Request

```json
{
  "facts": {
    "activity": "중고거래"
  },
  "fraudTypeCode": "SECONDHAND_TRADE"
}
```

#### Response — 성공 data

```json
{
  "scenario": {
    "title": "중고거래 안전 확인 훈련",
    "description": "가상 거래 상황"
  },
  "mockPageTemplateCode": "BANK_NOTICE"
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

상황별 개인화. 가상 훈련으로 제한하고 재사용 가능한 사기 수법 구체화 방지.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="ai-04"></a>

### AI-04 · 시뮬레이션 AI 응답

[Notion 상세 페이지](https://app.notion.com/p/8dd52b445f9282f5814201d7b709c467)

#### 개요

사용자 응답에 맞는 다음 역할극 메시지와 행동 분석 생성.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/internal/ai/simulation/reply` |
| 인증 | 내부 |
| 데이터 | FastAPI / LLM |

#### Request

```json
{
  "scenario": {},
  "messages": [],
  "userInput": "공식 채널을 확인하겠습니다.",
  "turnCount": 3
}
```

#### Response — 성공 data

```json
{
  "reply": "공식 고객센터로 확인해 보세요.",
  "recognizedActions": [
    "OFFICIAL_CHANNEL_CHECK"
  ],
  "shouldFinish": false,
  "mockPage": null
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

훈련 페르소나 일관성, 안전 가드레일, 출력 타입 검증. 상태 DB 영속화는 Spring Boot 책임.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="ai-05"></a>

### AI-05 · 훈련 최종 평가

[Notion 상세 페이지](https://app.notion.com/p/5f252b445f928301884d015f1239eda1)

#### 개요

훈련 대화·선택지·모의페이지 행동을 평가하여 구조화된 리포트 생성.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/internal/ai/training/evaluate` |
| 인증 | 내부 |
| 데이터 | FastAPI / LLM |

#### Request

```json
{
  "scenario": {},
  "conversation": [],
  "actions": [
    {
      "actionCode": "MOCK_PAGE_OPEN"
    }
  ]
}
```

#### Response — 성공 data

```json
{
  "overallFeedback": "의심 행동을 잘 식별했습니다.",
  "strengths": [],
  "improvements": [],
  "turnFeedback": [],
  "mockPageEvaluation": {
    "openedPage": true,
    "attemptedMockLogin": false,
    "checkedOfficialChannel": true
  },
  "recommendations": []
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

report\_json으로 저장할 평가만 반환. 전체 원문을 리포트 필드에 복사하지 않음.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="ai-06"></a>

### AI-06 · 피해 사실 추출

[Notion 상세 페이지](https://app.notion.com/p/c0d52b445f9283d3a1bc019572b8c9f4)

#### 개요

피해 설명에서 확정/미확정 사실을 구분해 구조화.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/internal/ai/solutions/extract` |
| 인증 | 내부 |
| 데이터 | FastAPI / LLM |

#### Request

```json
{
  "description": "송금 후 연락이 끊겼어요.",
  "knownFacts": {}
}
```

#### Response — 성공 data

```json
{
  "facts": {
    "transferredMoney": "YES",
    "contactContinues": "NO",
    "sharedInformation": "UNKNOWN",
    "installedApp": "UNKNOWN"
  },
  "uncertainFields": [
    "sharedInformation",
    "installedApp"
  ]
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

사실 추정 금지. 개인정보 최소 처리, 로그 원문 금지. Spring Boot에서 검토된 대응카드 선택.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.

<a id="ai-07"></a>

### AI-07 · 피해 대응 근거 설명

[Notion 상세 페이지](https://app.notion.com/p/b9052b445f9283d6970c018cb2a89b37)

#### 개요

확인된 사실과 승인된 근거를 이용해 대응 단계 설명 생성.

| 속성 | 값 |
| --- | --- |
| Endpoint | `/internal/ai/solutions/explain` |
| 인증 | 내부 |
| 데이터 | FastAPI / RAG |

#### Request

```json
{
  "facts": {
    "transferredMoney": "YES"
  },
  "verifiedReferences": []
}
```

#### Response — 성공 data

```json
{
  "explanation": "은행과 공식 신고 채널을 통한 신속한 확인을 권장합니다.",
  "referenceCodes": []
}
```

응답 기본 구조: `{"success":true,"data":{...}}`

#### 오류/상태 코드

200 OK · AI\_SERVICE\_UNAVAILABLE

#### 구현 및 검증 포인트

출처에 없는 전화번호/링크 생성 금지. 긴급 행동은 규칙형 카드 우선.

> **설계 초안:** 운영 전 데이터/출처 검증과 API 스키마 확정 필요.
