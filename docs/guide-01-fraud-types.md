# GUIDE-01 사기 유형 목록 조회 구현 보고서와 연동 안내

작성일: 2026-11-16 · 갱신: 2026-11-17 · 상태: API·로더·보안·테스트·실서버 확인 완료, 실제 승인 콘텐츠 등록 전

개발 기준은 [개발·테스트 계획서](guide-01-fraud-types-plan.md)와 팀 공유 `WSID-API-Spec.md`의 GUIDE-01 절(2026-11-16 제공본)이다. 공유 명세는 수정하지 않았다. 목록 API·유형 카탈로그만 구현했고 GUIDE-02~04·훈련/상담 연결·전체 인증 정책은 변경하지 않았다.

## 1. 구현 결과와 현재 응답

로그인 없이 `GET /api/guides/fraud-types`로 검토 완료 유형의 code·name·description을 조회한다. FastAPI·LLM·repository·DB 테이블을 사용하지 않는다. 콘텐츠는 기동 때 읽어 검증하고 이후에는 메모리에서 조회한다.

기본 데이터는 [secondhand-trade.yml](../src/main/resources/content/fraud-types/secondhand-trade.yml) 한 건이며 **reviewed=false인 미검토 초안**이다. source의 example.invalid와 checked_at은 초안용 형식 예시다. 실제 승인 출처·확인일로 갱신하고 콘텐츠 담당자가 검토하기 전에는 공개하지 않는다. 따라서 현재 기본 설정의 정상 응답은 다음과 같다.

```json
{
  "success": true,
  "data": []
}
```

**빈 목록은 정상 동작이다.** 기능 오류가 아니라 공개할 승인 자료가 아직 없다는 뜻이다. 콘텐츠 담당자가 내용·출처·확인일을 검토하고 `reviewed: true`로 바꿔 배포(재시작)하면 그때 목록에 나타난다. 공개 순서와 작성 규칙은 해당 폴더의 [README](../src/main/resources/content/fraud-types/README.md)에 정리했다.

API 구현 완료와 실제 승인 데이터 제공을 구분한다. 테스트에서 보이는 두 유형은 test-content 경로의 합성 자료이며 운영 카탈로그에 포함되지 않는다.

## 2. 처리 흐름

```text
기동
  application.yml / ContentProperties의 유형 리소스 루트
    → FraudTypeCatalogConfig가 해당 폴더의 *.yml·*.yaml 조회
    → FraudTypeYamlLoader: 스트림 파싱·실제 타입·필수/추가 필드 검사
    → FraudType: 문자열 길이·코드·관리 값 검증
    → FraudTypeCatalog: 전체 코드 중복 검사 → 승인 필터·정렬·불변 목록

요청
  익명 GET → SecurityConfig → GuideController
    → FraudTypeQueryService → 카탈로그 메모리 조회
    → FraudTypeSummaryResponse 목록 → ApiResponse(success=true, data=배열)
```

DTO는 공개 데이터 전송 형식이다. DB에 접근하지 않는다. 소유자 조회·AI 호출·원격 출처 확인·요청 중 YAML 재파싱도 하지 않는다. 전체 프로젝트는 기존 JPA/Flyway 환경 때문에 DB가 필요할 수 있으나 이 조회 기능 자체에는 DB 의존성이 없다.

## 3. YAML 형식과 검증

| 필드 | 구현한 검사 |
|---|---|
| code | 실제 문자열, `[A-Z0-9_]{1,50}` |
| name | 실제 문자열, strip 후 공백 불가·100자 이하. 정리한 값을 저장 |
| description | 실제 문자열, strip 후 공백 불가·1,000자 이하. 정리한 값을 저장 |
| display_order | 실제 Integer, 0 이상. Java int 범위를 넘거나 문자열·소수·boolean이면 거부 |
| reviewed | 실제 Boolean. 문자열 true/false·null·누락 거부. 작성 시 true/false 표기 사용 |
| source | 실제 문자열을 URI로 읽어 HTTPS·호스트 존재 검사 |
| checked_at | 실제 문자열, YYYY-MM-DD 패턴과 LocalDate 유효 날짜 검사 |

위 7개 필드는 전부 필수이며 추가 필드는 거부한다. reviewed=false 파일도 같은 형식 검사를 받는다. 문자 수는 Java String.length 기준이다. code·source·날짜를 임의 정규화하지 않는다.

SnakeYAML 2.6을 Boot BOM에서 관리하며 build.gradle에 직접 의존성을 선언했다. SafeConstructor와 중복 키 거부 LoaderOptions를 사용한다. 파싱된 Map의 키와 각 값의 실제 자료형을 먼저 확인하므로 DTO 바인딩의 자동 변환에 기대지 않는다. YAML 자체의 표준 자료형 해석과 필드 검사는 별개이며, 문자열 필드는 따옴표로 작성하는 것이 안전하다.

특히 `checked_at: 2026-11-16`처럼 따옴표를 빼면 SnakeYAML의 날짜 타입으로 해석되어 문자열 검사에서 거부된다. 반드시 `checked_at: "2026-11-16"`로 작성한다. 배열/빈/다중 문서·잘못된 YAML·중복 키·지원하지 않는 객체 태그도 거부한다.

source의 형식 검사·reviewed 플래그는 실제 공식 출처나 관리자 승인을 증명하지 않는다. 출처 확인·내용 검토·승인 권한은 콘텐츠 PR 절차에서 관리한다. URI를 읽기 위해 외부 HTTP/DNS 조회를 하지 않는다.

## 4. 리소스 위치·기동 실패·격리

설정은 `wsid.content.fraud-types-location`이고 기본값은 `classpath*:/content/fraud-types/`다. ContentProperties에 바인딩 기본값을 두었고 application.yml에도 같은 기본 위치를 명시했다. 클래스패스 디렉토리 형식만 지원한다. classpath:/ 또는 classpath*:/ 접두사와 영문/숫자/밑줄/하이픈 경로 및 마지막 /를 요구한다. 빈 값·파일/HTTP 위치·상위 경로·글로브는 거부한다.

설정 루트 뒤에 `*.yml`, `*.yaml`을 각각 붙인다. 하위 디렉토리·형제 디렉토리를 재귀 검색하지 않는다. Resource의 InputStream으로 읽고 닫으며 getFile에 의존하지 않아 JAR 내부에서도 읽을 수 있다.

| 상태 | 실제 처리 |
|---|---|
| 리소스 0개·읽기 실패·잘못된 형식 | 기동 실패 |
| 같은 파일의 중복 키·어느 파일이든 code 중복 | 기동 실패. 미검토끼리/승인·미검토 사이 중복도 거부 |
| 유효한 문서가 전부 미검토 | 기동 성공·목록 빈 배열 |
| 승인·미검토 혼합 | 승인만 display_order 오름차순·같으면 code 오름차순 |

카탈로그 Bean은 비지연 초기화한다. 잘못된 파일을 무시하거나 실패를 정상 빈 목록으로 바꾸지 않는다. YAML 파서 예외의 원문은 cause에 보관하지 않고 실패 리소스 설명만 고정 오류에 붙인다. 이는 기동 오류이므로 공개 API의 GUIDE_NOT_FOUND로 바꾸지 않는다.

main에는 기본 미검토 초안, test에는 `src/test/resources/test-content/fraud-types/`만 사용한다. test의 valid·invalid·duplicate 폴더는 테스트별 설정으로 선택한다. 공용 테스트 application 설정은 추가하지 않았다. valid의 하위 폴더에도 의도적인 중복 자료를 둬 비재귀 스캔을 확인했다. 기본 main 코드와 같은 code의 승인 합성 자료도 test-content에만 두어 기본 스캔에 섞이지 않음을 확인했다.

로더·기동 설정·기본 초안 YAML은 함께 커밋/배포해야 한다. 파일 없이 로더만 적용하면 앱과 기존 전체 컨텍스트 테스트가 기동 실패할 수 있다. 실제 dbTest는 이번에 실행하지 않았지만 기본 위치의 카탈로그 기동은 DB 없는 경량 컨텍스트에서 검증했다.

## 5. HTTP·성공 봉투·보안

success는 실제 boolean true이고 data 자체가 배열이다. data.items 같은 중간 필드는 없다. 공개 DTO는 code·name·description만 반환한다. source·checked_at·reviewed·순서 값·파일 정보는 보내지 않는다. 정상 빈 목록도 200이며 null·204·404로 바꾸지 않는다.

새 `common/api/ApiResponse`는 성공 응답용이다. false나 null data를 생성자로 거부하고 ok 메서드로 만든다. GUIDE-01 컨트롤러에서 명시적으로 적용하며 전역 응답 변환을 추가하지 않았다. 기존 TrainingController·ErrorResponse·GlobalExceptionHandler의 응답 계약은 유지한다.

SecurityConfig는 기존 규칙 앞에 **GET /api/guides/fraud-types만 permitAll**을 추가했다. /api/guides/** 전체를 열지 않는다. 보안 테스트는 실제 SecurityFilterChain·MVC·카탈로그·서비스를 올려 쿠키/인증 없는 GET을 확인한다. 보호된 GET은 4xx와 컨트롤러 미진입으로, 미지원 POST는 401 또는 403으로 차단을 확인해 특정 인증 오류 상태 하나에 고정하지 않았다. 기존 로그인·CSRF 정책은 구현/개편하지 않았다.

## 6. 파일별 상호작용과 이후 변경 시 참고

| 파일 | 역할 | 이후 개발 시 참고 |
|---|---|---|
| [FraudType.java](../src/main/java/com/wsid/content/model/FraudType.java) | 검증된 불변 유형·관리 정보 | GUIDE-02 필드를 추가하면 모델·YAML 검사·테스트 동시 확장. 공개 DTO에 전체 모델을 노출하지 않음 |
| [FraudTypeYamlLoader.java](../src/main/java/com/wsid/content/loader/FraudTypeYamlLoader.java) | 스트림 파싱·자료형/필드 검사 | 알 수 없는 필드를 현재 거부하므로 YAML만 먼저 확장하면 기동 실패. 코드와 스키마 함께 변경 |
| [FraudTypeCatalog.java](../src/main/java/com/wsid/content/catalog/FraudTypeCatalog.java) | 중복 검사·승인 필터·정렬·불변 스냅샷 | 요청 중 파일/DB 조회를 추가하지 않음. 재로딩은 현재 미지원 |
| [ContentProperties.java](../src/main/java/com/wsid/content/config/ContentProperties.java), [application.yml](../src/main/resources/application.yml) | 리소스 루트 바인딩·검증 | 기본값은 양쪽 동일하게 유지. 앱의 ConfigurationPropertiesScan으로 등록되지만 작은 테스트는 EnableConfigurationProperties 필요 |
| [FraudTypeCatalogConfig.java](../src/main/java/com/wsid/content/config/FraudTypeCatalogConfig.java) | 설정 루트 스캔·기동 카탈로그 생성 | .yml/.yaml 모두 읽고 비재귀 유지. JAR 스트림 읽기 유지 |
| [FraudTypeQueryService.java](../src/main/java/com/wsid/content/service/FraudTypeQueryService.java), [FraudTypeSummaryResponse.java](../src/main/java/com/wsid/content/dto/FraudTypeSummaryResponse.java) | 공개 3개 필드만 매핑 | 승인 상태·출처를 원본 직렬화로 누출하지 않음. DB/AI 의존 없음 |
| [GuideController.java](../src/main/java/com/wsid/content/controller/GuideController.java) | 공개 GET·성공 봉투 | GUIDE-02~04는 별도 계약/메서드로 확장. 목록 배열 형식을 변경하지 않음 |
| [ApiResponse.java](../src/main/java/com/wsid/common/api/ApiResponse.java) | 제네릭 성공 봉투 | 기존 API·오류에 자동 적용되지 않음. 공통 응답 전환은 별도 작업 |
| [SecurityConfig.java](../src/main/java/com/wsid/common/config/SecurityConfig.java) | 정확한 GET 익명 허용 | 상세/카드/기관을 구현하면 해당 GET을 따로 등록하고 보호 경로 회귀 확인 |
| [secondhand-trade.yml](../src/main/resources/content/fraud-types/secondhand-trade.yml) | 미검토 기본 초안 | 실제 출처·확인일·내용 검토 후 PR 승인. 개발 편의로 true 전환 금지 |
| [build.gradle](../build.gradle) | 직접 SnakeYAML 의존성 | Boot BOM 버전 사용. 의존성 갱신 시 엄격한 타입·중복 키·날짜 테스트 확인 |

## 7. 연결 기능별 남은 작업

- **콘텐츠 승인:** 현재 공개 유형은 0개이며 정상이다. 담당자가 내용·출처·확인일을 검토하고 `reviewed: true`로 바꿔야 사용자에게 유형이 보인다. 콘텐츠 변경은 배포/재시작으로 적용한다. 절차는 [fraud-types README](../src/main/resources/content/fraud-types/README.md)를 따른다.
- **승인 시 출처 확인 (주의):** 로더는 HTTPS·호스트 형식만 검사하고 `example.invalid` 같은 예시 주소를 막지 않는다. 초안의 source를 바꾸지 않고 reviewed만 true로 바꿔도 기동·공개된다. source는 공개 응답에 나가지 않지만 승인 근거가 남지 않으므로 PR 검토에서 확인한다. 자동으로 막으려면 승인 문서의 `.invalid`·`example.*` 호스트를 거부하는 검사를 후속으로 추가할 수 있다.
- **프론트:** data 배열·빈 상태를 처리하고 code와 표시 이름을 구분한다. 설명은 일반 텍스트로 표시한다.
- **GUIDE-02:** 같은 유형 code와 승인 기준을 공유하되 상세 examples·warningSignals·preventionTips·references 스키마를 모델/로더/DTO에 함께 추가한다. 현재 추가 YAML 필드는 기동 오류다.
- **GUIDE-03/04:** 카드·기관은 별도 스키마/승인 모델로 구현한다. 유형 로더가 카드·기관 폴더를 읽게 하지 않는다.
- **훈련·AI-03:** 현재 훈련 코드 USED_GOODS_TRADE와 카탈로그의 SECONDHAND_TRADE는 다르다. 이 작업에서 매핑·DB 이관·시나리오 지원을 추가하지 않았다. 유형 목록 등록은 그 유형의 훈련 가능 여부를 보장하지 않는다.
- **상담·AI-07:** 유형 설명/출처는 승인 근거 문단 DTO와 다르다. description/source를 바로 verifiedReferences로 보내지 않는다. 카드/근거의 버전·승인·검색은 별도 개발한다.
- **회원·공통 응답:** 전체 로그인/CSRF 및 다른 API의 응답 봉투 전환은 아직 별도 과제다. 이 API의 익명 접근 성공을 전체 인증 구현 완료로 표시하지 않는다.

## 8. 테스트·패키징 결과

Java 21에서 `./gradlew test bootJar --no-daemon`으로 최종 확인했다. **전체 710건 통과, 실패·오류·건너뜀 0건**이다. 신규 GUIDE-01 104건과 기존 606건을 포함한다.

| 테스트 | 실행 수 | 검증 |
|---|---:|---|
| [FraudTypeYamlLoaderTest](../src/test/java/com/wsid/content/FraudTypeYamlLoaderTest.java) | 75 | YAML/필드/타입·누락/추가·중복·길이·URI/날짜·미검토 오류, 스트림 닫기·I/O 실패·실제 임시 JAR 엔트리 읽기 |
| [FraudTypeCatalogTest](../src/test/java/com/wsid/content/FraudTypeCatalogTest.java) | 5 | 승인 필터·정렬·중복·불변 스냅샷·모델 검증 |
| [FraudTypeCatalogConfigurationTest](../src/test/java/com/wsid/content/FraudTypeCatalogConfigurationTest.java) | 17 | 설정 기본/재정의·잘못된 루트·파일 없음/손상/중복 기동 실패·main/test/하위 폴더 격리 |
| [FraudTypeQueryServiceTest](../src/test/java/com/wsid/content/FraudTypeQueryServiceTest.java) | 2 | 공개 필드·순서·불변/빈 목록 |
| [GuideControllerTest](../src/test/java/com/wsid/content/GuideControllerTest.java) | 3 | 성공 봉투·정확한 공개 JSON·한글·빈 배열·미지원 메서드·봉투 값 |
| [GuideSecurityTest](../src/test/java/com/wsid/content/GuideSecurityTest.java) | 2 | 실제 필터/카탈로그/서비스를 통한 익명 GET·정렬·기본 빈 목록·다른 경로/POST 차단 |

초기 실행에서 스트림 테스트의 예외 선언과 Spring 리소스 설명 문자열의 기대값을 수정했다. 최종 전체 실행은 위 결과로 통과했다. 단순 DTO 검사뿐 아니라 실제 Resource 스트림·기동 실패·보안 필터를 포함해 확인했다.

추가로 생성된 `build/libs/wsid-backend-0.0.1-SNAPSHOT.jar`를 확인했다.

- `BOOT-INF/classes/content/fraud-types/secondhand-trade.yml` 포함
- test-content 테스트 자료 미포함
- `BOOT-INF/lib/snakeyaml-2.6.jar` 포함
- 그 JAR에서 추출한 런타임 클래스/라이브러리와 원본 JAR 리소스 URL로 로더 실행: getFile 없이 읽기 성공, 승인 목록 0개

JAR 검증용 코드는 임시 디렉토리에서 실행했고 제품 코드에 넣지 않았다. 별도로 Spring Boot 실행기(PropertiesLauncher)로 압축을 풀지 않은 JAR 안에서 카탈로그를 생성해, 리소스가 `jar:nested:…!BOOT-INF/classes/!/content/fraud-types/secondhand-trade.yml`로 직접 읽히고 test-content 리소스가 0개임을 재확인했다.

### 실서버 smoke 테스트 (2026-11-17)

Docker로 임시 PostgreSQL 17 컨테이너(`POSTGRES_DB/USER/PASSWORD=wsid`)를 띄우고 같은 JAR을 `java -jar`로 실행했다. 다른 프로젝트 컨테이너가 5432·8080을 사용 중이어서 DB는 55432, 서버는 18080 포트를 사용했다(`DB_URL`, `--server.port`로 지정). 빈 DB에서 Flyway가 V1·V2를 적용해 테이블 6개를 만들었고 서버는 약 3초 만에 기동했다.

| 요청 (쿠키·인증 없음) | 결과 |
|---|---|
| `GET /api/guides/fraud-types` | 200, `Content-Type: application/json`, `{"success":true,"data":[]}` |
| `POST /api/guides/fraud-types` | 403 |
| `GET /api/guides/fraud-types/SECONDHAND_TRADE` (GUIDE-02 미공개) | 403 |
| `GET /api/reports` (기존 보호 경로) | 403 |

빈 data는 기본 초안이 reviewed=false이기 때문이며 1장의 기대 결과와 같다. 확인 후 서버와 임시 컨테이너를 삭제했고 저장소에 Docker 설정 파일은 추가하지 않았다. dbTest는 GUIDE-01이 DB를 사용하지 않아 실행하지 않았다. 실제 콘텐츠의 진위·승인은 이 자동 테스트의 검증 범위가 아니다.

변경 문서 7개의 링크·JSON·공백 검사를 통과했고 IntelliJ 문서 오류는 0건이다. Java 소스/테스트 16개도 IDE 검사를 요청했으나 현재 프로젝트의 content roots 밖 또는 제외된 파일로 인식되어 분석되지 않았다. Java 검증 근거는 실제 Gradle 컴파일·710건 테스트·JAR 로드 결과이며 IDE Java 검사 통과로 표시하지 않는다. IDE 설정은 변경하지 않았다.
