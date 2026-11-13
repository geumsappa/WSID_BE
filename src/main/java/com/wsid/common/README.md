# common — 모든 기능이 같이 쓰는 것

특정 기능에 속하지 않는 코드만 둬요. 한 기능에서만 쓰는 코드는 그 기능 패키지에 둬요.

## 명세서 매핑

| 명세서 항목 | 구분 | 상태 | 관련 코드 |
|---|---|---|---|
| 훈련 소유권 확인 | 비기능 | 개발 계정 기준 구현 | `auth/CurrentUser` + 훈련 repository의 `findByIdAndUserId` |
| 리포트·비회원 상담 소유권 확인 | 비기능 | 미구현 | 각 기능 서비스에서 적용 예정 |
| 중복 처리와 상태 충돌 방지 | 비기능 | 구현 | `ErrorCode.DUPLICATE_REQUEST`, `SESSION_VERSION_CONFLICT` |
| 훈련 세션 만료 | 비기능 | 판정·턴 거절 구현, 만료 상태 정리는 미구현 | `ErrorCode.SESSION_EXPIRED` |

## 파일

| 파일 | 역할 |
|---|---|
| `error/ErrorCode` | 오류 코드, HTTP 상태, 사용자에게 보여줄 문구 |
| `error/ApiException` | service에서 던지는 예외 |
| `error/ErrorResponse` | 현재 처리하는 예외의 응답 형식 `{ "code": "...", "message": "..." }` |
| `error/GlobalExceptionHandler` | 예외를 오류 응답으로 변환 |
| `config/SecurityConfig` | 경로별 접근 권한, CSRF |
| `domain/MessageSender`, `InputKind` | training과 solution의 메시지 테이블이 같이 쓰는 enum |

## 접근 정책과 현재 보안 설정

목표 정책은 훈련·리포트·내 정보·세션 확인·로그아웃은 로그인 필수, 피해 대응 상담·GUIDE는 비회원 허용이에요. 상담에는 별도 소유권 확인이 필요해요. 세션 로그인은 아직 구현하지 않았고 local은 개발 계정을 사용해요.

`SecurityConfig`의 현재 `/api/training/**`, `/api/solution/**`, `/api/content/**` 규칙은 이전 경로와 개발 편의를 위한 설정이에요. 새 `/api/trainings/**`, `/api/reports/**`, `/api/solutions/**`, `/api/guides/**`, AUTH 세부 경로에 맞춰 권한을 정렬하고 쿠키 기반 인증의 CSRF 정책을 구현해야 해요.

현재 `GlobalExceptionHandler`는 API 예외·Bean Validation·낙관적 잠금 충돌을 처리해요. JSON 파싱 오류도 INVALID_REQUEST로 처리하고, 요청·순서 유니크 제약 위반은 409로 구분해요. 보안 계층 오류 등은 공통 형식이 보장되지 않으므로 보완해야 해요.

## 오류를 추가하는 방법

1. `ErrorCode`에 항목을 추가해요. 이름은 프론트가 분기에 쓰니 바꾸지 않아요.
2. service에서 `throw new ApiException(ErrorCode.XXX)`.
3. 프론트에 코드 이름과 화면 동작을 알려줘요.

## 오류 코드와 화면 동작

| 코드 | 상태 | 프론트가 할 일 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 입력 확인 안내 |
| `LOGIN_REQUIRED` | 401 | 로그인 화면으로 |
| `SESSION_NOT_FOUND` | 404 | 홈으로 |
| `SESSION_VERSION_CONFLICT` | 409 | 세션 상태를 다시 불러와 화면 갱신 |
| `DUPLICATE_REQUEST` | 409 | 세션 상태를 다시 불러와 화면 갱신 |
| `SESSION_FINISHED` | 409 | 결과 화면으로 |
| `SESSION_EXPIRED` | 410 | 새 훈련 시작 안내 |
