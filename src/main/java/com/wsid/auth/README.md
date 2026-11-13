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
| 훈련 소유권 확인 | 비기능 | 상 | 개발 계정 기준 구현 | `CurrentUser` + 훈련 repository |
| 리포트 소유권 확인 | 비기능 | 상 | 미구현 | 로그인 구현 후 report 서비스에서 적용 |

## 목표 API

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| AUTH-01 | POST | `/api/auth/signup` | 불필요 | User.signUp만; API·서비스 없음 |
| AUTH-02 | POST | `/api/auth/email/resend` | 불필요 | 미구현; 인증 코드 저장소도 아직 없음 |
| AUTH-03 | POST | `/api/auth/email/verify` | 불필요 | User.verifyEmail만; 코드 확인·API 없음 |
| AUTH-04 | POST | `/api/auth/login` | 불필요 | 세션 방식 계획 일치; 로그인 구현 없음 |
| AUTH-05 | GET | `/api/auth/session` | 필요 | 목표 API 문서화; 실제 세션 확인 API는 미구현 |
| AUTH-06 | POST | `/api/auth/logout` | 필요 | 미구현; 문서에는 세션 폐기 계획 |
| AUTH-07 | GET | `/api/users/me` | 필요 | 목표 API 문서화; 실제 회원정보 API는 미구현 |

로그인 성공 시 서버 로그인 세션을 만들고 쿠키를 발급해요. AUTH-05는 기존 세션 유효 여부만 확인하며, 유효하면 200, 없거나 만료되면 401을 반환해요. AUTH-07은 로그인한 회원 정보를 반환해요. 로그아웃하면 서버 세션과 세션 쿠키를 폐기해요. 모두 아직 미구현이에요.

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
5. AUTH-05 세션 확인과 AUTH-07 회원정보 조회를 만들어요. `common/config/SecurityConfig`에서 새 API 경로와 세션 쿠키 인증에 맞춰 접근 권한·CSRF 정책을 정해요. 훈련·리포트는 로그인 필수, 상담·가이드는 비회원 허용이에요. 상담은 별도로 해당 건의 소유권을 확인해요.

## 정할 것

- 인증 코드 유효기간과 재발송 제한
- 비회원 상담의 소유자 식별·저장 구조 (`solution/README.md` 참고)
- 로그인 세션을 DB에 둘지 (서버 재시작과 여러 대 운영)
- `users.gender`는 명세서에 쓰임새가 없어요. 필요 없으면 받지 않는 게 좋아요.

## 주의

- 비밀번호 원문과 인증 코드는 로그에 남기지 않아요.
- 가입 여부를 알려주는 응답은 계정 수집에 쓰일 수 있어요. 문구와 요청 횟수 제한을 같이 정해요.
