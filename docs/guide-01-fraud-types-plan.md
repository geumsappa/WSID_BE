# GUIDE-01 사기 유형 목록 조회 개발·테스트 계획서

작성일: 2026-11-16 · 상태: API·로더·테스트 구현 완료, 실제 승인 콘텐츠 등록 전

명세 출처는 GUIDE-01 API 초안과 팀 공유 `WSID-API-Spec.md`의 GUIDE-01·02 절(2026-11-16 제공본)이다. 기존 content/common 문서와 실제 보안·응답 코드를 대조해 계획을 작성했고, 이후 이 기준으로 API·로더·테스트를 구현했다. 실제 파일별 처리·실행 결과·후속 작업은 [구현 보고서](guide-01-fraud-types.md)를 참고한다. YAML 관리 필드·한도·정렬·기동 실패 정책은 Spring 초기 계약이며 실제 콘텐츠 승인은 별도로 필요하다.

## 1. 목표와 범위

`GET /api/guides/fraud-types`는 검토된 YAML에 등록된 금융사기 유형의 code·name·description 목록을 로그인 없이 반환한다. Spring Boot가 콘텐츠를 직접 읽어 제공한다. FastAPI·LLM·DB 테이블·repository는 필요하지 않다.

| 포함할 작업 | 후속 작업으로 남길 것 |
|---|---|
| 사기 유형 YAML 형식·파싱·검증·승인 필터링 | GUIDE-02 유형 상세·GUIDE-03 카드·GUIDE-04 기관 API |
| 기동 시 불변 목록 구성·안정적인 정렬 | 관리자 편집/업로드 화면·실시간 재로딩 |
| 목록 DTO·성공 봉투·서비스·GET 컨트롤러 | 기존 훈련/상담/오류 응답의 일괄 봉투 전환 |
| 해당 GET의 익명 접근 허용·회귀 테스트 | 전체 로그인/CSRF 정책 재설계 |
| 파서·카탈로그·MVC·보안·패키징 테스트 | AI-03·훈련 유형 선택·AI-07 근거 카탈로그 연결 |

이 기능의 요청 처리에는 DB 조회나 트랜잭션이 없다. 전체 애플리케이션은 기존 JPA·Flyway 설정 때문에 DB가 필요할 수 있으므로 “GUIDE-01에 DB가 불필요”와 “프로젝트 전체가 DB 없이 기동 가능”을 구분한다.

## 2. 현재 코드와 필요한 변경

아래는 계획 수립 당시의 상태다. 이후 구현한 변경은 6장과 구현 보고서에서 확인한다.

| 확인한 파일 | 현재 상태 | 개발 시 처리 |
|---|---|---|
| [content/README.md](../src/main/java/com/wsid/content/README.md) | 카탈로그·공개 API 계획만 존재 | content 안에 유형 전용 로더·카탈로그·조회 계층 추가 |
| [resources/content/](../src/main/resources/content/) | scenarios·response-cards만 있고 fraud-types 없음 | 새 fraud-types 디렉토리 추가. 테스트 합성 데이터는 별도 test-content 경로에 두고 기본 스캔에서 제외 |
| [application.yml](../src/main/resources/application.yml) | wsid.content 설정 없음 | 유형 리소스 위치 설정 추가. 테스트별로 전용 위치를 지정할 수 있게 함 |
| [build.gradle](../build.gradle) | 웹·보안·MVC 테스트 스타터 존재, YAML 직접 의존성 선언 없음 | 직접 사용할 YAML 파서를 명시적 의존성으로 선언 |
| [SecurityConfig.java](../src/main/java/com/wsid/common/config/SecurityConfig.java) | 구 /api/content/**는 공개지만 /api/guides/**는 anyRequest().authenticated()에 걸림 | GET /api/guides/fraud-types만 명시적으로 공개 |
| [TrainingController.java](../src/main/java/com/wsid/training/controller/TrainingController.java) | 성공 DTO를 봉투 없이 반환 | 기존 동작 유지. GUIDE-01에 성공 봉투를 명시적으로 적용 |
| [ErrorResponse.java](../src/main/java/com/wsid/common/error/ErrorResponse.java), [GlobalExceptionHandler.java](../src/main/java/com/wsid/common/error/GlobalExceptionHandler.java) | 오류는 code/message 형식. 공통 성공 봉투 없음 | 이번에 기존 오류 형식을 일괄 변경하지 않음 |
| [TrainingService.java](../src/main/java/com/wsid/training/service/TrainingService.java) | 기본 유형이 USED_GOODS_TRADE | 새 SECONDHAND_TRADE와 다름. 목록 개발에서 자동 치환·DB 이관하지 않음 |

범용 ContentCatalog 전체를 한 번에 구현하지 않고 FraudTypeCatalog부터 시작한다. 승인된 설명용 문단·대응 카드·훈련 시나리오는 서로 다른 스키마이므로 같은 로더로 억지로 읽지 않는다.

## 3. 공개 API 계약

```http
GET /api/guides/fraud-types
```

쿼리·본문·로그인·소유자 식별자를 요구하지 않는다. 페이징·검색·필터는 이번 계약에 추가하지 않는다. 정상 응답은 HTTP 200과 다음 JSON이다.

```json
{
  "success": true,
  "data": [
    {
      "code": "SECONDHAND_TRADE",
      "name": "중고거래 관련 사기",
      "description": "중고거래 중 발생할 수 있는 위험 유형"
    }
  ]
}
```

명세의 공통 봉투 표기 `data:{...}`는 목록 항목을 감싸는 객체를 뜻하는 것으로 해석하지 않는다. GUIDE-01의 성공 data 예시를 따라 **data 자체가 배열**이다. data.items·data.fraudTypes 같은 필드는 추가하지 않는다.

공개 목록이 비어 있으면 `{"success":true,"data":[]}`와 200을 반환한다. null·204·404로 바꾸지 않는다. 순서는 display_order 오름차순, 같은 값이면 code 오름차순으로 고정한다. 파일 탐색 순서에 의존하지 않는다.

응답에는 reviewed·source·checked_at·display_order·파일 경로 등 내부 관리 정보를 포함하지 않는다. 승인 자료의 URI를 요청 처리 중 외부 사이트에서 조회하지 않는다. 응답 DTO는 원본 YAML 객체 대신 공개 3개 필드만 명시적으로 매핑한다.

## 4. YAML 저장 형식 제안

기본 경로는 `src/main/resources/content/fraud-types/`이고 한 파일에 유형 한 건을 저장한다. `.yml`·`.yaml`을 지원한다. 테스트 합성 데이터는 같은 상대 경로에 복사하지 않고 `src/test/resources/test-content/fraud-types/` 아래에 둔다. 다음은 구조 설명용 합성 초안이며 운영 콘텐츠가 아니다. example.invalid는 테스트용 주소이고 승인 출처로 사용하지 않는다.

```yaml
code: SECONDHAND_TRADE
name: "중고거래 관련 사기"
description: "중고거래 중 발생할 수 있는 위험 유형"
display_order: 10
reviewed: false
source: "https://example.invalid/fraud-types/secondhand-trade"
checked_at: "2026-11-16"
```

관리 필드는 기존 content 문서의 검토·출처·확인일 원칙을 유형 카탈로그에 구체화한 제안이다. 운영 데이터는 담당자가 실제 공식 출처와 설명을 확인한 뒤 PR로 승인하고 reviewed를 true로 변경한다. 예시를 개발 편의상 검토 완료로 표시하지 않는다.

| 필드 | 검사 제안 |
|---|---|
| code | 필수 문자열, `[A-Z0-9_]{1,50}`. 파일 전체에서 유일. 주변 공백을 자동 보정하지 않음 |
| name | 필수 문자열. 앞뒤 공백 제거 후 공백 불가·최대 100자. 정리한 값을 저장 |
| description | 필수 문자열. 앞뒤 공백 제거 후 공백 불가·최대 1,000자. 정리한 값을 저장 |
| display_order | 필수 정수, 0 이상. 문자열/소수/boolean을 숫자로 강제 변환하지 않음 |
| reviewed | 필수 실제 boolean. 문자열 "true"·"false", 누락·null 거부. YAML 작성 표기는 true/false 사용 |
| source | 필수 문자열. 절대 HTTPS URI·호스트 존재 여부 검사. 형식 검사는 실제 공식 출처 검토를 대신하지 않음 |
| checked_at | 필수 문자열. YYYY-MM-DD 형태의 유효 날짜. YAML 날짜의 자동 타입 해석을 피하도록 따옴표로 작성 |

문자 길이는 Java String.length 기준이다. 필수 필드와 타입은 reviewed=false인 파일도 동일하게 검사한다. 미검토 파일이라는 이유로 손상된 형식을 무시하지 않는다. 알 수 없는 필드는 초기 스키마에서 오류로 처리해 오타를 발견한다. GUIDE-02 필드를 추가할 때 허용 스키마와 테스트를 함께 확장한다.

유형 code는 DB에 새로 저장하지 않지만 기존 유형 컬럼 VARCHAR(50)과 연결할 가능성을 고려해 50자로 제한한다. code 형식·유일성·reviewed 검증이 “이 내용이 실제로 관리자에게 검토됐다”는 사실까지 입증하지는 않는다. 승인 권한·담당자 확인은 콘텐츠 PR 절차로 관리한다.

## 5. 로더·카탈로그 정책

### 파서와 리소스 읽기

직접 사용하는 `org.yaml:snakeyaml`을 implementation 의존성으로 선언하고 버전은 현재 Spring Boot BOM에 맡기는 방안이다. 로컬의 Boot 4.1.2 BOM은 SnakeYAML 2.6을 관리하는 것을 확인했다. 구현 시 의존성 그래프를 확인해 다른 파서를 중복 추가하지 않는다.

SafeConstructor·LoaderOptions를 사용해 기본 YAML 자료형으로 읽고, 중복 YAML 키를 거부한다. 받은 Map의 문자열 키·각 값의 실제 타입·필드 집합을 먼저 확인한 뒤 도메인 객체로 만든다. DTO 직접 바인딩의 문자열/boolean/숫자 강제 변환에 기대지 않는다. 여러 YAML 문서·배열 루트·빈 문서는 허용하지 않는다.

읽을 위치는 `wsid.content.fraud-types-location` 설정으로 지정한다. 초기 제안은 파일 패턴이 아닌 클래스패스 디렉토리 루트를 값으로 받고, 로더가 그 뒤에 `*.yml`과 `*.yaml`을 각각 붙여 ResourcePatternResolver로 조회하는 방식이다. 기본 설정 예시는 다음과 같다.

```yaml
wsid:
  content:
    fraud-types-location: "classpath*:/content/fraud-types/"
```

`content/config/ContentProperties.java`에서 설정을 바인딩하고 지원하는 클래스패스 루트 형식·빈 값·디렉토리 끝 구분자를 검증한다. 기본 스캔은 `classpath*:/content/fraud-types/*.yml`·`*.yaml`이며 content 전체를 재귀적으로 읽지 않는다. 리소스는 InputStream으로 읽고 닫는다. getFile()이나 실행 디렉토리 상대 경로에 의존하지 않아 패키징된 JAR에서도 동작하게 한다.

테스트 실행 시 main/test 리소스가 모두 클래스패스에 올라가므로 `src/test/resources/content/fraud-types/`에는 합성 데이터를 두지 않는다. 정상·손상·중복 같은 시나리오별 자료는 `src/test/resources/test-content/fraud-types/valid/`, `invalid/`, `duplicate/` 등 별도 위치에 둔다. 콘텐츠 컨텍스트 테스트에서만 `wsid.content.fraud-types-location=classpath*:/test-content/fraud-types/valid/`처럼 필요한 경로를 명시적으로 재정의한다. 로더 단위 테스트는 해당 Resource/InputStream을 직접 전달해 기본 카탈로그 스캔을 거치지 않아도 된다.

테스트 공용 application 설정을 바꿔 모든 테스트가 합성 카탈로그를 읽게 하지 않는다. 기존 [DiscoveryDatabaseTest](../src/test/java/com/wsid/training/DiscoveryDatabaseTest.java)처럼 전체 애플리케이션을 올리는 테스트는 기본 main 리소스를 사용하며, 다른 경로의 합성 데이터가 섞이지 않아야 한다.

### 기동·필터링·실패

애플리케이션 기동 시 로더가 모든 해당 파일을 읽고 검증한다. 검증된 전체 항목의 code 중복을 확인한 뒤 reviewed=true만 정렬해 불변 카탈로그로 구성한다. 요청마다 디스크를 읽거나 YAML을 다시 파싱하지 않는다. 콘텐츠 변경은 PR·배포·재시작으로 반영하며 실시간 재로딩은 제공하지 않는다.

| 상태 | 초기 처리 정책 |
|---|---|
| 해당 경로의 파일이 하나도 없음 | 배포/경로 누락으로 보고 기동 실패 |
| 파일 존재, 정상 문서 전부 reviewed=false | 기동 허용, 공개 목록은 빈 배열 |
| 잘못된 YAML·필수 필드/타입·날짜/URI·알 수 없는 필드 | 기동 실패. 빈 목록으로 삼키지 않음 |
| 같은 파일의 중복 키·여러 파일의 동일 code | reviewed 여부와 무관하게 기동 실패 |
| 정상이며 reviewed=true | 공개 목록에 포함 |
| 정상이며 reviewed=false | 공개 목록에서 제외 |

파일 없음과 “검토 완료 항목 없음”을 구분한다. 운영에서 공개 유형이 반드시 1개 이상이어야 한다면 별도 배포 검증 조건으로 합의한다. 1차 제안은 정상 빈 목록의 API 계약을 유지한다.

잘못된 콘텐츠는 서버 초기화 오류로 다루므로 GUIDE-01의 정상 조회에 별도 CONTENT_NOT_FOUND·GUIDE_NOT_FOUND를 만들지 않는다. YAML 전체를 응답에 노출하지 않고 실패 리소스와 필드 정도만 진단한다. 서버 기동 실패와 HTTP 오류 응답은 서로 다른 범위다.

## 6. 개발 순서와 파일별 책임

아래 로더·설정·카탈로그·API·테스트는 구현했다. 기본 YAML은 실제 승인 전의 false 초안이다.

| 순서 | 위치 | 작업 |
|---|---|---|
| 1 | `resources/content/fraud-types/*.yml` | 형식이 유효한 reviewed: false 초안 1개 이상 준비. 실제 승인 전 false 유지하고 기동 로더와 같은 커밋에 포함 |
| 2 | `build.gradle` | YAML 직접 의존성 추가·BOM 및 런타임 버전 확인 |
| 3 | `content/model/FraudType.java` | 공개 값+관리 정보의 검증된 불변 모델 정의 |
| 4 | `content/loader/FraudTypeYamlLoader.java` | 리소스 스트림 파싱·실제 타입/스키마·중복 키/코드 검증 |
| 5 | `content/config/ContentProperties.java`, `application.yml`, `content/catalog/FraudTypeCatalog.java`, `content/config/FraudTypeCatalogConfig.java` | 리소스 위치 바인딩, 기동 시 지정 위치 로드·승인 필터링·정렬·불변 스냅샷 구성 |
| 6 | `content/dto/FraudTypeSummaryResponse.java`, `content/service/FraudTypeQueryService.java` | 카탈로그에서 공개 3개 필드만 조회·매핑. DB/AI 미사용 |
| 7 | `common/api/ApiResponse.java` | 성공 여부 true와 제네릭 data를 담는 성공 봉투·생성 메서드 추가 |
| 8 | `content/controller/GuideController.java` | GET 경로에서 ApiResponse<List<FraudTypeSummaryResponse>> 반환 |
| 9 | `common/config/SecurityConfig.java` | anyRequest보다 앞에 해당 GET의 permitAll 추가 |
| 10 | `src/test/java/com/wsid/content/` 및 문서 | 아래 테스트 수행·구현 보고서·상태 문서 갱신 |

**기동 로더·위치 설정·기본 경로의 유효한 reviewed: false 초안 YAML은 같은 커밋/머지 단위로 포함한다.** 파일 없음 시 기동 실패 정책 때문에 로더만 먼저 머지하면 기존 앱과 전체 컨텍스트를 사용하는 dbTest도 실패할 수 있다. 코드와 함께 최소 1개 리소스를 배포하고, 승인 전에는 기동 성공·공개 목록 빈 배열을 기대한다. 이때도 출처·확인일 등 필수 형식은 충족해야 하며 승인 여부를 개발 편의상 true로 바꾸지 않는다.

공통 성공 봉투는 아직 없으므로 추가가 필요하다. 전역 ResponseBodyAdvice나 기존 controller 일괄 변경은 하지 않는다. GUIDE-01에서 명시적으로 감싸고 기존 훈련 응답·ErrorResponse를 그대로 유지한다. 이후 전 API를 통일할 때 별도 계약 변경으로 처리한다.

SecurityConfig에서는 GET /api/guides/fraud-types만 우선 공개한다. /api/guides/** 전체나 모든 메서드를 이번 작업으로 열지 않는다. 기존 개발용 공개 경로를 회원 인증 정책에 맞게 정리하는 일과 CSRF 개편은 별도 작업이다.

## 7. 파일 간 상호작용과 후속 개발 참고

```text
기동: wsid.content.fraud-types-location (기본 main의 content/fraud-types/)
          → 지정 위치의 *.yml, *.yaml
          → FraudTypeYamlLoader → 검증된 FraudType
          → reviewed 필터·정렬 → 불변 FraudTypeCatalog

요청: 익명 GET → SecurityConfig → GuideController
          → FraudTypeQueryService → 카탈로그 메모리 조회
          → 공개 DTO 목록 → ApiResponse(success=true, data=배열)
```

- **프론트:** data를 배열로 읽고 빈 배열은 빈 상태로 표시한다. code는 표시 이름과 분리해 유지한다. 설명을 일반 텍스트로 표시하고 내부 검토 메타데이터를 요구하지 않는다.
- **GUIDE-02:** 같은 code/승인 필터를 공유하되 examples·warningSignals·preventionTips·references 스키마는 상세 구현 시 확장한다. 목록의 필드나 반환 배열을 상세 구조로 바꾸지 않는다.
- **training·AI-03:** 목록에 등록됐다고 해당 유형의 훈련 시나리오가 준비됐다는 뜻은 아니다. 현재 USED_GOODS_TRADE와 SECONDHAND_TRADE가 다르고 AI-03도 지원 범위가 제한되어 있다. 후속 선택/시나리오 연결 시 코드 매핑·지원 유형·기존 저장값 이관 필요 여부를 따로 정한다. GUIDE-01에서 기존 데이터를 자동 변경하지 않는다.
- **solution·AI-07:** 유형 목록은 승인된 근거 문단이나 대응 카드 카탈로그가 아니다. 목록의 description/source를 바로 AI-07 근거로 넘기지 않는다. 해당 모델·버전·승인 절차는 각 기능에서 별도로 구현한다.
- **common:** 새 성공 봉투가 기존 오류·훈련 응답을 자동으로 바꾸지 않게 한다. 공개 경로 추가가 다른 보호 경로를 열지 않는지 테스트한다.
- **콘텐츠 담당자:** 공식 출처·확인일·유형 설명을 PR로 검토한다. 데이터 파일 추가만으로 자동 승인하지 않고 reviewed를 명시적으로 관리한다.

## 8. 테스트 계획

| 테스트 제안 | 필수 검증 |
|---|---|
| `FraudTypeYamlLoaderTest` | 정상 한글 값·파일 확장자·InputStream 로드, 필수 값/타입·중복 키·중복 code·잘못된 YAML/루트/여러 문서·알 수 없는 필드·빈 파일 거부 |
| 모델/로더 경계 테스트 | code 50/51자·형식, name 100/101자·description 1,000/1,001자·공백 정리 후 저장, reviewed 문자열/누락/null 거부, 순서 음수/문자열/소수 거부, 날짜/URI 형식 |
| `FraudTypeCatalogTest` | true/false 혼합 필터링, 미검토 문서의 구조 오류도 거부, 모든 code 중복 검사, display_order/code 정렬, 불변 목록, 호출마다 재파싱하지 않음 |
| `FraudTypeCatalogConfigurationTest` | 파일 없음·손상 시 컨텍스트 기동 실패, 유효한 false 문서만 있으면 기동·빈 카탈로그 허용, 소규모 테스트 컨텍스트로 JPA/DB 없이 검증 |
| `ContentPropertiesTest`·리소스 격리 테스트 | 기본 위치·테스트별 위치 재정의·잘못된 설정 검증. 기본 스캔에 test-content의 동일 code/승인 합성 데이터가 섞이지 않고, 전용 위치 테스트도 main 데이터와 합쳐지지 않음 |
| `FraudTypeQueryServiceTest` | 공개 필드 3개만 매핑, 순서 유지, 빈 목록, DB/AI 의존성이 없음 |
| `GuideControllerTest` | 정확한 GET·200·실제 boolean success·data 배열, code/name/description·UTF-8, 내부 메타데이터 미포함, 빈 배열 반환 |
| `GuideSecurityTest` | 실제 SecurityFilterChain을 포함한 경량 웹 컨텍스트에서 쿠키/인증 없는 GET 성공. 기존 보호 경로와 미지원 메서드까지 공개되지 않음 |
| 패키징 검증 | JAR에 fraud-types 리소스가 포함되는지, 파일 시스템 변환 없이 JAR 리소스 스트림 로드가 되는지 확인 |

컨트롤러 standaloneSetup만으로는 익명 접근 허용을 검증할 수 없다. 보안 테스트에서는 실제 필터 체인을 포함한다. 현재 프로젝트의 로그인/보안 오류 응답은 아직 미완성이므로 기존 보호 경로의 미인증 차단을 확인하되 로그인 전체 구현 완료로 표시하지 않는다.

기동/웹 테스트는 필요한 content·security 구성만 올려 DB 없는 검증 범위를 유지한다. 전체 @SpringBootTest에 데이터소스·Flyway가 자동 설정되는데 DB가 없다는 이유로 실패하는 테스트를 만들지 않는다. MockMvc만 통과했다고 실제 배포 JAR 리소스까지 검증했다고 표시하지 않는다.

실제 출처의 진위·관리자 검토 여부는 자동 단위 테스트만으로 입증할 수 없다. 합성 데이터는 `src/test/resources/test-content/fraud-types/`에 두고 각 테스트가 필요한 경로를 선택한다. 기본 경로에는 담당자가 관리하는 YAML만 두며, 승인 문서가 없으면 예시를 true로 바꿔 성공 응답을 만들지 않는다.

## 9. 검증·완료 기준과 결정 항목

구현 후 Java 21에서 `./gradlew test --no-daemon`으로 신규 및 기존 회귀를 확인하고 `./gradlew bootJar`와 JAR 리소스 검증을 수행한다. DB를 변경하지 않으므로 GUIDE-01을 위해 dbTest나 마이그레이션을 추가하지 않는다. 전체 서버 수동 기동 시에는 기존 프로젝트의 DB 환경을 별도로 준비한다. 실서버 확인은 임시 PostgreSQL 컨테이너를 띄우고 JAR을 실행해 익명 GET 200·빈 목록, 다른 경로·메서드 차단을 호출로 확인한다.

완료 기준은 다음과 같다.

- 실제 검토 완료 YAML만 익명 GET의 목록에 나타나고 공개 필드는 3개다.
- 기본 초안만 있는 현재 상태의 정상 응답은 `{"success":true,"data":[]}`이다. 빈 목록을 오류로 보지 않으며, 담당자가 검토 후 `reviewed: true`로 바꿔 배포해야 유형이 나타난다. 공개 절차는 `content/fraud-types/README.md`에 둔다.
- data는 항상 배열이며 순서가 안정적이고 정상 빈 목록도 200이다.
- 잘못된 콘텐츠·배포 리소스 누락은 기동 시 발견되고 오류를 빈 목록으로 숨기지 않는다.
- 기동 로더와 기본 초안 YAML이 함께 배포되고, main/test 리소스 경로가 격리되어 기존 전체 컨텍스트 테스트에 합성 데이터가 섞이지 않는다.
- 메모리 조회만 수행하며 요청 중 DB·FastAPI·외부 출처 조회가 없다.
- 보안·기존 응답·훈련 회귀 및 실제 패키징 리소스 검증을 통과한다.
- 구현 보고서에 변경 파일·상호작용·실행 결과·실제 승인 데이터 범위와 남은 작업을 기록한다.

구현 전에 팀에서 확인할 항목은 YAML 필수 관리 필드·길이/정렬, 승인 담당자·출처/확인일 검토 방식, 운영에서 승인 유형 0개를 허용할지, 공유 성공 봉투 적용 범위, SECONDHAND_TRADE 코드와 기존 훈련 코드 호환 정책이다. GUIDE-02 상세 필드를 지금 함께 확정할지 여부도 분리해 결정한다.

현재 API·로더·보안·기본 미검토 초안 및 테스트를 구현했고, 실행·패키징·실서버 smoke 결과는 구현 보고서 8장에 기록했다. 승인 문서의 예시 출처(`example.invalid`)는 현재 자동으로 거부하지 않으므로 PR 검토로 확인한다. 실제 승인 데이터·GUIDE-02~04·훈련/상담 연결을 완료했다고 표시하지 않는다.
