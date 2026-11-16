# AUTH 기반 설정

Spring Session Redis와 SMTP 연결 설정을 설명해요. 회원·인증 API 계약은 [API 문서](api.md)를 참고하세요. 로컬에서도 훈련 소유자는 `CurrentUser`가 확인한 로그인 회원이에요.

## 로컬 실행

Docker Desktop을 켜고 PowerShell에서 실행해요.

```powershell
cd C:\work\WSID\WSID_BE
docker compose up -d
docker start wsid-redis
docker exec wsid-redis redis-cli ping
.\gradlew.bat bootRun
```

Redis가 처음이라면 `docker start` 대신 아래 명령을 한 번 실행해요. 기존 컨테이너가 있으면 다시 만들지 않아요.

```powershell
docker run -d --name wsid-redis -p 127.0.0.1:6379:6379 redis:7
```

IntelliJ에서는 Gradle을 동기화한 뒤 백엔드 실행 구성을 재시작해요. 실행 구성의 Environment variables에 아래 설정을 넣을 수 있어요. `.env` 파일은 이 Spring 실행 방식에서 자동으로 읽지 않아요.

## Redis와 세션

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| REDIS_HOST | localhost | Redis 주소 |
| REDIS_PORT | 6379 | Redis 포트 |
| REDIS_PASSWORD | 빈 값 | Redis 인증 비밀번호 |
| SESSION_TIMEOUT | 30m | 세션 무활동 만료. 팀 확정 전 제안값 |
| SESSION_NAMESPACE | wsid:session | Redis 세션 키 접두사 |

Spring Boot 4의 `spring-boot-starter-session-data-redis` 자동 설정을 사용해요. 세션별 설정은 `spring.session.data.redis.*`에 둬요. Redis 연결·명령 타임아웃은 각각 2초예요. 세션은 실제로 생성될 때 Redis에 저장되므로 서버 기동만으로 새 키가 생기지는 않아요.

쿠키 이름은 SESSION, HttpOnly=true, SameSite=Lax, Path=/예요. Secure는 기본 true이며 `application-local.yml`에서만 false로 덮어써요. 기본 프로필이 local이므로 운영 실행에는 `SPRING_PROFILES_ACTIVE=prod`를 명시해야 해요. 로그인 세션의 30분과 훈련 세션의 24시간은 서로 다른 설정이에요.

## 메일

| 환경변수 | 기본값 |
|---|---|
| MAIL_HOST | localhost |
| MAIL_PORT | 587 |
| MAIL_USERNAME / MAIL_PASSWORD | 빈 값 |
| MAIL_SMTP_AUTH | true |
| MAIL_STARTTLS_ENABLED / MAIL_STARTTLS_REQUIRED | true |

위 기본값은 메일 서버가 준비됐다는 의미가 아니에요. 시작 시 SMTP 접속 검사를 하지 않아 메일 미설정 상태에서도 개발 서버가 켜져요. 실제 발송은 SMTP 서버와 자격증명을 설정하고 후속 발송 서비스를 구현한 뒤 가능해요. 연결·읽기·쓰기 타임아웃은 각각 5초예요. 자격증명은 저장소에 커밋하지 않아요.

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest
```

`test`는 외부 서비스 없이 기존 단위 테스트를 실행해요. `integrationTest`는 실행 중인 PostgreSQL과 Redis가 필요하며 전체 Spring 애플리케이션을 임의 포트로 띄워요. 이때 기존 Flyway 마이그레이션과 JPA 스키마 검증도 실행돼요. 공유·운영 DB 대신 로컬 개발 DB를 사용하세요.

통합 테스트는 테스트 소스에만 있는 HTTP 경로로 세션을 생성하고 다음을 검증해요.

- 세션 없는 조회가 쿠키를 만들지 않는지
- SESSION 쿠키 속성, Redis 키 저장, 30분 TTL
- 쿠키를 보낸 다음 요청에서 같은 세션 속성이 복원되는지
- 무효화 후 쿠키 만료·Redis 키 삭제·이전 쿠키로 복원 불가

테스트는 고유한 Redis 키 접두사를 사용하고 자신이 만든 세션만 삭제해요. 실제 메일 발송과 실제 로그인 인증은 검사하지 않아요. 테스트용 경로는 배포 JAR에 포함되지 않아요.
