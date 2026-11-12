# common — 모든 기능이 같이 쓰는 것

특정 기능에 속하지 않는 코드만 둬요. 한 기능에서만 쓰는 코드는 그 기능 패키지에 둬요.

## 명세서 매핑

| 명세서 항목 | 구분 | 상태 | 관련 코드 |
|---|---|---|---|
| 세션·리포트 소유권 확인 | 비기능 | 구현 | `auth/CurrentUser` + 각 repository의 `findByIdAndUserId` |
| 중복 처리와 상태 충돌 방지 | 비기능 | 구현 | `ErrorCode.DUPLICATE_REQUEST`, `SESSION_VERSION_CONFLICT` |
| 훈련 세션 만료 | 비기능 | 구현 | `ErrorCode.SESSION_EXPIRED` |

## 파일

| 파일 | 역할 |
|---|---|
| `error/ErrorCode` | 오류 코드, HTTP 상태, 사용자에게 보여줄 문구 |
| `error/ApiException` | service에서 던지는 예외 |
| `error/ErrorResponse` | 모든 오류 응답의 형식 `{ "code": "...", "message": "..." }` |
| `error/GlobalExceptionHandler` | 예외를 오류 응답으로 변환 |
| `config/SecurityConfig` | 경로별 접근 권한, CSRF |
| `domain/MessageSender`, `InputKind` | training과 solution의 메시지 테이블이 같이 쓰는 enum |

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
