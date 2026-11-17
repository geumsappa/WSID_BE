# auth — 회원과 현재 사용자

회원가입·이메일 인증·로그인·로그아웃과 현재 활성 회원 조회를 구현합니다. 공개 API 계약은 [API 문서](../../../../../../../docs/api.md)를 참고하세요.

- 가입 비밀번호는 BCrypt로 해시하고, 이메일 인증 전 회원은 PENDING입니다.
- 인증 코드는 Redis에 해시·TTL·재발송/검증 제한을 저장하고 SMTP로 발송합니다.
- 로그인 principal은 `Long` 회원 ID이며 `CurrentUser.id()`는 활성 회원인지 다시 확인합니다.
- 로그인 세션은 Spring Session Redis, 쿠키는 SESSION/HttpOnly/SameSite=Lax/Path=/입니다. 운영 기본 Secure=true이며 local HTTP에서만 해제합니다.
- 회원 CSRF 발급은 `GET /api/auth/csrf`입니다. 비회원 상담은 `GET /api/solutions/csrf`를 사용하며 두 API는 동일한 세션 토큰 저장소를 공유합니다.
- 로그인 실패 횟수 제한과 AUTH의 CSRF 거부 오류 JSON 통일은 후속 개선 항목입니다.

`RegistrationIntegrationTest`, `RedisSessionIntegrationTest`, `SolutionIntegrationTest`에서 실제 DB·Redis와 HTTP 요청을 검증합니다. SMTP 단위 테스트는 대역을 사용하며 실제 메일 발송을 검증하지 않습니다.
