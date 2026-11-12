# auth — 회원과 현재 사용자

회원가입, 이메일 인증, 로그인·로그아웃을 맡고, 다른 기능에 "지금 요청한 사용자가 누구인지"를 알려줘요.

## 명세서 매핑

| 명세서 항목 | ID | 우선순위 | 상태 | 관련 코드 |
|---|---|---|---|---|
| 이메일 회원가입 | AUTH-001 | 상 | 엔티티만 | `User.signUp` |
| 인증 코드 발송·재발송 | AUTH-004 | 상 | 미구현 (테이블 없음) | - |
| 인증 코드 확인 | AUTH-004 | 상 | 엔티티만 | `User.verifyEmail` |
| 이메일 로그인 | AUTH-002 | 상 | 미구현 | `LoginCurrentUser` |
| 로그아웃 | AUTH-003 | 상 | 미구현 | - |
| 세션·리포트 소유권 확인 | 비기능 | 상 | 구현 | `CurrentUser` |

## 테이블

- `users` → `domain/User`
- 인증 코드를 저장할 테이블이 ERD에 없어요. AUTH-004를 만들 때 새 마이그레이션으로 추가해요.

## 파일

| 파일 | 역할 |
|---|---|
| `CurrentUser` | 서비스 계층이 사용자 ID를 얻는 인터페이스 |
| `LocalDevCurrentUser` | `local` 프로필용. 개발 계정을 자동으로 만들어 그 사용자로 동작 |
| `LoginCurrentUser` | 그 외 프로필용. 로그인 세션에서 ID를 꺼낼 자리 (지금은 항상 401) |
| `domain/User`, `UserStatus` | 엔티티. PENDING → 이메일 인증 → ACTIVE |
| `repository/UserRepository` | 이메일로 조회 |

## 개발 순서

1. **AUTH-001 가입**: `controller`, `service`, `dto`를 만들어요. 비밀번호는 `PasswordEncoder`(BCrypt)로 해시해 `User.signUp`에 넘겨요. 이미 가입한 이메일이면 로그인 화면으로 안내하는 응답을 줘요.
2. **AUTH-004 인증 코드**: 테이블을 추가해요(사용자 ID, 코드 해시, 만료 시각, 사용 시각). 재발송하면 이전 코드를 무효화하고, 확인을 마친 코드는 다시 쓸 수 없게 해요. 메일 발송을 위해 `build.gradle`에 `spring-boot-starter-mail`을 추가하고, 발송은 응답을 기다리지 않게 비동기로 보내요.
3. **AUTH-002 로그인**: Spring Security의 세션 로그인으로 만들어요. 인증을 마치지 않은 사용자(`User.canLogin() == false`)는 이메일 인증 화면으로 안내해요. `LoginCurrentUser`가 로그인 세션에서 사용자 ID를 꺼내도록 채워요.
4. **AUTH-003 로그아웃**: 서버에서 로그인 세션을 폐기해요.
5. `common/config/SecurityConfig`에서 로그인이 필요한 경로를 좁히고 CSRF 정책을 정해요.

## 정할 것

- 인증 코드 유효기간과 재발송 제한
- 로그인 없이 쓸 수 있는 범위 (루트 README의 "팀에서 정할 것")
- 로그인 세션을 DB에 둘지 (서버 재시작과 여러 대 운영)
- `users.gender`는 명세서에 쓰임새가 없어요. 필요 없으면 받지 않는 게 좋아요.

## 주의

- 비밀번호 원문과 인증 코드는 로그에 남기지 않아요.
- 가입 여부를 알려주는 응답은 계정 수집에 쓰일 수 있어요. 문구와 요청 횟수 제한을 같이 정해요.
