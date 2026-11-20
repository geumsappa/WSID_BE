# GUIDE-02 사기 유형 상세 조회 개발·테스트 계획서

작성일: 2026-11-17 · 상태: 구현 전 계획

명세 출처는 GUIDE-02 API 초안과 팀 공유 `WSID-API-Spec.md`의 GUIDE-01·02 절이다. 이미 구현한 [GUIDE-01 보고서](guide-01-fraud-types.md)와 실제 모델·로더·카탈로그·보안·오류·테스트 코드를 대조했다. 이 문서는 계획서와 목차만 작성하며 구현 코드를 변경하지 않는다. 배열 요소·한도·404 및 출처 승인 범위는 아래 초기 계약을 제안하며 구현 전에 팀과 확인한다.

## 1. 목표와 범위

`GET /api/guides/fraud-types/{code}`는 승인된 유형의 기본 설명·사례·위험 신호·예방 수칙·공식 참고 출처를 로그인 없이 제공한다. GUIDE-01과 같은 기동 시 YAML 검증 및 메모리 카탈로그를 사용한다. DB·FastAPI·LLM·요청 중 외부 출처 조회는 추가하지 않는다.

| 포함할 작업 | 이번에 하지 않을 작업 |
|---|---|
| 상세 모델·YAML 필드 확장·출처 형식 검증 | 관리자 편집 API·실시간 재로딩·웹 수집 |
| 승인 유형의 code 인덱스·단건 조회 | 대응 카드·기관 API·훈련/상담 연결 |
| 상세 DTO·GET·익명 접근·GUIDE_NOT_FOUND | 전체 오류 봉투·로그인/CSRF 일괄 변경 |
| 기존 7필드 YAML·목록 API 호환 | 기존 훈련 유형 코드/저장 데이터 자동 이관 |
| 로더·서비스·MVC·보안·패키징 회귀 테스트 | 실제 출처 검토를 대신하는 AI URL 판정 |

기존 목록은 같은 code/name/description만 반환한다. 상세 API 때문에 목록의 data 배열·정렬·승인 필터가 바뀌면 안 된다.

## 2. 현재 상태와 변경 지점

| 실제 파일 | 현재 상태 | 필요한 변경 |
|---|---|---|
| [FraudType.java](../src/main/java/com/wsid/content/model/FraudType.java) | 기본 값·관리 정보 7개 record 컴포넌트 | 상세 4개 목록 추가, 불변 복사·길이/중복 검사. 기존 7인자 생성자는 빈 상세 목록으로 위임해 호환 유지 |
| [FraudTypeYamlLoader.java](../src/main/java/com/wsid/content/loader/FraudTypeYamlLoader.java) | 키 집합이 정확히 기존 7개여야 함 | 기존 필수 키와 새 허용 키를 분리. 자료형·참고 출처 객체도 엄격하게 검사 |
| [FraudTypeCatalog.java](../src/main/java/com/wsid/content/catalog/FraudTypeCatalog.java) | 승인 목록만 필터·정렬해 보관, 단건 조회 없음 | 승인 목록으로만 불변 code 인덱스 구성·Optional 단건 조회 추가 |
| [FraudTypeQueryService.java](../src/main/java/com/wsid/content/service/FraudTypeQueryService.java) | 공개 목록 매핑만 존재 | code 검사·승인 단건 조회·상세 DTO 매핑·404 예외 |
| [GuideController.java](../src/main/java/com/wsid/content/controller/GuideController.java) | 목록 GET만 존재 | /fraud-types/{code} 추가, 기존 ApiResponse로 상세 객체 반환 |
| [SecurityConfig.java](../src/main/java/com/wsid/common/config/SecurityConfig.java) | 목록 GET만 명시적으로 공개 | 상세 GET 한 경로 세그먼트만 추가 공개 |
| [ErrorCode.java](../src/main/java/com/wsid/common/error/ErrorCode.java) | GUIDE_NOT_FOUND 없음 | 404 오류 코드·고정 메시지 추가 |
| [GuideSecurityTest.java](../src/test/java/com/wsid/content/GuideSecurityTest.java) | 상세 경로를 가짜 보호 컨트롤러에 매핑하고 차단 검사 | 충돌하는 가짜 상세 매핑 제거·다른 보호 경로로 교체. 실제 상세 API를 통해 익명 200/404 검사 |

새 상세 필드가 들어간 YAML만 먼저 배포하면 기존 엄격한 로더가 기동을 거부한다. 모델·로더·데이터·테스트를 일관된 변경으로 구성해야 한다.

## 3. 공개 요청·응답과 오류 계약

```http
GET /api/guides/fraud-types/SECONDHAND_TRADE
```

요청 본문·쿼리·로그인·소유자 정보는 필요 없다. 아래는 명세 형태를 따른 예시이며 실제 공개 콘텐츠 등록을 뜻하지 않는다.

```json
{
  "success": true,
  "data": {
    "code": "SECONDHAND_TRADE",
    "name": "중고거래 관련 사기",
    "description": "거래 과정에서 발생하는 위험",
    "examples": [],
    "warningSignals": [],
    "preventionTips": [],
    "references": []
  }
}
```

명세는 배열 요소를 정의하지 않았다. 초기 계약은 examples·warningSignals·preventionTips를 문자열 배열, references를 title·url 객체 배열로 제안한다. 이 요소 구조는 구현 전에 프론트와 확인한다. 응답의 상세 4개 배열은 항상 존재하고 빈 배열도 null이나 생략으로 바꾸지 않는다. 기본 관리 정보 reviewed·display_order·source·checked_at는 그대로 노출하지 않는다.

### code와 404 정책

code는 기존 유형 코드 형식 `[A-Z0-9_]{1,50}`을 사용한다. 모델의 코드 검사 함수를 재사용하도록 공개하거나 같은 content 내부의 공통 검사로 분리하고 정규식을 중복 정의하지 않는다. 공백 제거·대문자 변환·USED_GOODS_TRADE 별칭 처리를 하지 않는다.

| 상황 | 제안 응답 |
|---|---|
| 승인된 code | 200 + 상세 객체 |
| 존재하지 않는 code | 404 GUIDE_NOT_FOUND |
| 존재하지만 reviewed=false | 같은 404 GUIDE_NOT_FOUND. 미검토 정보 노출 없음 |
| code로 정상 바인딩됐지만 형식/길이가 잘못됨 | 같은 404 GUIDE_NOT_FOUND |
| 기본 미검토 SECONDHAND_TRADE | 구현 후 익명 GET은 404. 개발 편의로 승인하지 않음 |

잘못된 URI가 보안 필터/웹 서버에서 먼저 거부되는 경우나 경로 세그먼트가 추가된 URL까지 이 서비스의 404로 강제하지 않는다. code 없는 정확한 목록 URL은 기존 GUIDE-01이다.

서비스가 ApiException(ErrorCode.GUIDE_NOT_FOUND)을 던지고 기존 GlobalExceptionHandler로 처리한다. 오류 메시지는 “가이드를 찾을 수 없어요.” 같은 고정 문구를 제안한다. 현재 오류 계약을 유지하면 404 본문은 다음 형식이다.

```json
{
  "code": "GUIDE_NOT_FOUND",
  "message": "가이드를 찾을 수 없어요."
}
```

기존 ApiResponse는 성공 전용이므로 false나 null data로 오류를 만들지 않는다. 성공은 공통 봉투, 오류는 현재 ErrorResponse를 유지하는 초기 방안이다. 실패에도 success/error 봉투가 필요하다면 전 API 영향과 함께 별도 결정하며 이번 상세 API에서 전역 오류 형식을 조용히 바꾸지 않는다.

## 4. YAML 확장과 이전 데이터 호환

기존 7개 필수 필드는 그대로 두고 `examples`, `warning_signals`, `prevention_tips`, `references`를 선택 필드로 추가한다. 공개 JSON의 warningSignals·preventionTips와 YAML의 snake_case를 명시적으로 매핑한다.

다음은 형식 설명용 합성 초안이다. URL은 실제 승인 출처가 아니며 reviewed=false를 유지한다.

```yaml
code: SECONDHAND_TRADE
name: "중고거래 관련 사기"
description: "중고거래 중 발생할 수 있는 위험 유형"
display_order: 10
reviewed: false
source: "https://example.invalid/fraud-types/secondhand-trade"
checked_at: "2026-11-17"
examples:
  - "담당자 검토 전의 합성 사례 설명"
warning_signals:
  - "담당자 검토 전의 합성 위험 신호"
prevention_tips:
  - "담당자 검토 전의 합성 예방 문구"
references:
  - title: "공식 출처 형식 예시"
    url: "https://example.invalid/fraud-types/secondhand-trade"
```

| 항목 | 초기 검사·호환 정책 |
|---|---|
| 기존 필수 7키 | 누락·타입 오류 계속 거부. source·checked_at 검증과 따옴표 규칙 유지 |
| 새 4키 생략 | 이전 7키 YAML을 읽기 위해 해당 값을 빈 목록으로 구성 |
| 새 4키가 명시적으로 null/문자열/객체 | 거부. 생략과 잘못된 자료형을 구분 |
| examples·warning_signals·prevention_tips | 각 최대 10개 문자열, 항목마다 strip 후 공백 불가·500자 이하. 정리한 값 저장 |
| 문자열 배열의 중복 | strip 후 같은 항목이면 거부. 자동 제거·절단하지 않음 |
| references | 최대 10개 객체. 각 객체는 title·url만 허용하고 두 값 필수 |
| reference.title | 실제 문자열, strip 후 공백 불가·200자 이하 |
| reference.url | 실제 문자열, 2,048자 이하·절대 HTTPS·호스트 존재·사용자 인증정보 없음. 자동 보정/외부 조회 없음 |
| references 중복 | 동일한 URL 문자열 중복 거부. 다른 URL이 같은 자료인지는 PR에서 확인 |
| 미검토 문서 | 새 필드도 모두 검증하고 공개 목록/상세에서 제외 |
| 다른 알 수 없는 키 | 루트와 reference 내부 모두 계속 거부 |

문자 수는 Java String.length 기준이다. 자료형 검사 후 모델로 만들고 모든 목록·참고 출처는 불변으로 보관한다. 선택 필드는 생략 시에만 기본 빈 목록을 적용하며 null을 조용히 빈 목록으로 바꾸지 않는다.

references의 공개 요소는 `{ "title": "…", "url": "…" }` 두 필드다. 별도의 reference.reviewed·확인일 필드는 초기안에 추가하지 않는다. 유형의 reviewed/checked_at가 본문과 모든 참고 출처의 검토 상태·확인일을 함께 의미하도록 관리한다. 출처별 다른 승인/확인일이 필요하면 스키마와 운영 정책을 별도로 확장한다.

이전 자료에 상세 배열이 없어도 승인 유형은 빈 배열 4개를 가진 상세 응답을 제공할 수 있다. 초기안은 references도 빈 배열을 허용하며, 기존 필수 source/checked_at를 관리 근거로 유지한다. source를 임의의 제목으로 가공해 references에 자동 복사하지 않는다. 공개 유형에 최소 1개 참고 출처를 강제할지는 별도 운영 결정이며, 강제한다면 기존 승인 YAML의 마이그레이션을 함께 계획해야 한다.

기존 7인자 FraudType 생성자는 새 목록 4개를 빈 목록으로 위임하는 오버로드로 유지하는 방안이다. 기존 테스트 도우미와 호출자 컴파일을 보존한다. 새 전체 생성자에서는 명시적인 null 목록을 거부한다.

## 5. 출처 승인과 URL 처리

references는 외부 사이트에서 자동 수집하거나 AI가 생성해 바로 노출하는 값이 아니다. 콘텐츠 담당자가 실제 공식 사이트와 본문·사례·위험 신호·예방 수칙을 확인하고, root source 및 모든 references URL을 함께 PR에서 검토한다. 하나라도 미검토라면 유형 전체를 reviewed=false로 유지한다. 잘못된 구조는 승인 여부와 관계없이 기동 실패다.

HTTPS·호스트·인증정보 검사는 URI 형태만 확인한다. 공식 기관 여부·내용의 정확성·AI 생성 여부·출처 최신성을 입증하지 않는다. URL의 출처가 AI 초안이더라도 담당자의 실확인 없이 승인할 수 없다. 요청 중 URL에 접속하거나 리다이렉트를 따라가서 유효성을 판정하지 않는다.

현재 GUIDE-01은 example.invalid 등 예시 주소를 자동 차단하지 않는다. 이번 기본안도 형식 검사와 승인 절차를 구분하며, 테스트의 합성 주소를 운영 승인 출처로 사용하지 않는다. 예시 호스트 자동 거부나 공식 도메인 허용 목록이 필요하면 적용 범위·테스트·기존 자료 호환을 정해 별도 검증으로 추가한다.

프론트는 문구를 일반 텍스트로 표시하고 승인된 references.url만 출처 링크로 제공한다. 문구에서 URL을 추출하거나 HTML/Markdown을 실행하지 않는다. 새 창 링크 사용 시 opener 관계를 차단하는 표시 정책도 맞춘다. 실제 프론트 구현은 이번 범위가 아니다.

## 6. 구현 순서와 파일별 작업

아래 신규 이름은 계획이며 아직 구현하지 않았다.

| 순서 | 파일 | 작업 |
|---|---|---|
| 1 | `content/model/FraudTypeReference.java` | 제목·URI 검증과 불변 참고 출처 모델 |
| 2 | `content/model/FraudType.java` | 상세 목록 4개·길이/중복/복사·기존 생성자 호환, 코드 검사 재사용 지점 |
| 3 | `content/loader/FraudTypeYamlLoader.java` | 필수 7키+추가 허용 키 분리, 새 배열/객체 타입 검사, 생략 기본값·알 수 없는 키 거부 |
| 4 | 운영 YAML·테스트 fixture·fraud-types README | 새 필드 작성 규칙 반영. 현재 false 초안은 계속 미검토로 유지 |
| 5 | `content/catalog/FraudTypeCatalog.java` | 승인 유형만 code 인덱스로 만들고 Optional 단건 조회. 기존 정렬 목록 유지 |
| 6 | `content/dto/FraudTypeDetailResponse.java`·`FraudTypeReferenceResponse.java` | 기본 3필드+항상 존재하는 4개 배열, 참고 출처의 title/url 매핑 |
| 7 | `common/error/ErrorCode.java`·`content/service/FraudTypeQueryService.java` | GUIDE_NOT_FOUND 404, code 검사·조회·상세 매핑 |
| 8 | `content/controller/GuideController.java`·`common/config/SecurityConfig.java` | 상세 GET·성공 봉투·정확한 익명 접근 범위 |
| 9 | `src/test/java/com/wsid/content/` 및 공통 오류 테스트 | 아래 계약/회귀/보안/스키마/격리 검증 |
| 10 | `docs/guide-02-fraud-type-detail.md` 및 상태 문서 | 실제 구현·파일별 책임·테스트/패키징·콘텐츠 승인 범위 보고 |

카탈로그는 검증된 승인 목록에서만 조회 인덱스를 만들고 미검토 문서를 인덱스에 넣지 않는다. code 유일성 검사는 기존처럼 전체 자료에 적용한다. 단건 요청에서 파일을 열거나 YAML을 다시 읽지 않는다.

GUIDE-01의 SummaryResponse·data 배열·정렬은 유지한다. 이미 제공한 기본 설명은 상세에서도 같은 모델 값을 쓰며 목록/상세용 파일을 따로 만들어 내용이 어긋나게 하지 않는다.

## 7. 보안·기존 테스트 전환

현재 정확한 목록 GET 공개 규칙을 유지하고 상세 GET 한 세그먼트만 추가 허용한다. 예를 들어 GET `/api/guides/fraud-types/*` 범위이며 **전체 `/api/guides/**`나 모든 HTTP 메서드를 열지 않는다.** 형식이 잘못된 code도 컨트롤러에 바인딩되면 서비스에서 같은 404를 반환한다.

[GuideSecurityTest](../src/test/java/com/wsid/content/GuideSecurityTest.java)의 ProtectedController는 현재 `/api/guides/fraud-types/SECONDHAND_TRADE`를 정확히 매핑한다. 그대로 두면 새 변수 경로보다 가짜 컨트롤러가 먼저 선택될 수 있다. 해당 가짜 상세 매핑을 제거하고 `/api/guides/private-probe` 같은 별도 보호 경로로 교체한다. 실제 GuideController를 통해 승인 상세 200·미승인/없는 코드 404를 확인한다.

보호된 다른 경로는 기존처럼 4xx·컨트롤러 미진입으로 확인하고, 미지원 상세 POST는 인증 오류 하나에 고정하지 않고 401/403 차단 여부를 확인한다. 목록과 상세의 JSON 모양을 같이 검사한다. standalone MVC만으로 보안을 확인했다고 표시하지 않고 실제 SecurityFilterChain을 포함한다.

GUIDE-01 보고서의 상세 경로 403 smoke 결과는 당시 미구현 상태의 기록이다. 과거 기록을 새 결과처럼 바꾸지 않고, GUIDE-02 구현 보고서에서 변경 후 익명 접근/404 결과를 별도로 남긴다.

## 8. 테스트 자료·기동·패키징

기존 위치 설정 `wsid.content.fraud-types-location`, 기본 미검토 YAML, 파일 없음 시 기동 실패·비재귀 `.yml/.yaml` 스캔 정책을 유지한다. 새 합성 자료는 `src/test/resources/test-content/fraud-types/detail-valid/`, `detail-invalid/` 등 다른 루트에 두고 해당 테스트만 위치를 재정의한다. `src/test/resources/content/fraud-types/`에는 추가하지 않는다.

기존 valid fixture의 7키 형식도 유지해 이전 데이터 호환을 검증한다. 새 detailed fixture와 같은 code를 다른 폴더에 둬도 기본/기존 테스트 루트와 섞이지 않아야 한다. 작은 ApplicationContextRunner는 ContentProperties를 EnableConfigurationProperties로 명시 등록한다.

새 필드 YAML과 이를 허용하는 로더는 함께 커밋/배포한다. 새 로더는 기존 7키 YAML을 읽으므로 main 초안을 수정하지 않아도 기동할 수 있다. 반대로 기존 로더에 새 필드만 넣으면 기동 실패한다. 미검토 초안을 승인 상태로 바꿔 200을 만들지 않는다.

기본 자료는 여전히 false이므로 정상 배포의 목록은 빈 배열이고 SECONDHAND_TRADE 상세는 404가 된다. 승인 상세 200은 테스트 자료로 검증하며, 실제 200 제공에는 별도의 공식 콘텐츠 검토·등록이 필요하다.

## 9. 테스트 계획

| 테스트 위치 | 확인할 내용 |
|---|---|
| 모델/참고 출처 테스트 | 각 배열 10/11개·문구 500/501자·제목 200/201자·URL 길이, strip 후 저장·공백/중복/null·불변 복사·HTTPS/호스트/인증정보·기존 7인자 생성자 |
| 기존 `FraudTypeYamlLoaderTest` 확장 | 기존 7키 허용, 새 필드 생략과 명시 null 구분, 배열/요소/참고 객체의 실제 타입·필수/추가 키·중복 URL, false 문서도 검증, 기존 파서/날짜/중복 키 거부 유지 |
| `FraudTypeCatalogTest` 확장 | 승인 단건 조회, 없는/미검토 code 없음, 전체 중복 거부·목록 순서/필드 불변·동일 모델 사용 |
| `FraudTypeQueryServiceTest` 확장 | 상세 필드·참고 매핑, 없는/미검토/잘못된 code의 동일 GUIDE_NOT_FOUND, 코드 보정/별칭 없음, 기존 목록 응답 유지 |
| `GuideControllerTest` 확장 | 200 상세 객체·항상 배열 4개·참고 요소 title/url, 관리 메타데이터 미포함, legacy 데이터 빈 배열, 404 고정 코드/메시지, GUIDE-01 data 배열 유지 |
| 공통 오류 테스트 | GUIDE_NOT_FOUND가 기존 Handler를 통해 404 ErrorResponse로 변환. 다른 오류/훈련 응답 형식 불변 |
| `GuideSecurityTest` 확장 | 가짜 상세 매핑 제거, 실제 익명 상세 200/404, 목록 GET 유지, 다른 보호 경로·미지원 POST 미공개 |
| 설정·격리 테스트 | 구/신 스키마 기동 성공, 잘못된 새 스키마 기동 실패, 기본 false 기동·상세 없음, test-content·하위 폴더 격리 유지 |
| JAR 검증·선택적 실서버 smoke | 실제 JAR 리소스에 새/기존 YAML을 적용해 읽기, test-content 미포함, 기본 익명 목록 200/상세 404. 실서버는 기존 DB 환경과 함께 별도 준비 |

Java 21에서 `./gradlew test bootJar --no-daemon`을 실행해 신규·기존 회귀와 패키징을 확인한다. GUIDE-01의 최근 확인 결과는 전체 710건이지만 이번 계획서 작성에서 다시 실행한 것은 아니다. 구현 후 실제 실행 수와 승인 데이터·서버 검증 범위는 별도 보고서에 기록한다. DB 변경은 없으며 dbTest 실행 여부는 전체 컨텍스트 영향과 이용 가능한 DB 환경을 기준으로 구분해 남긴다.

## 10. 완료 기준과 구현 전 결정 항목

- 승인된 code만 익명 상세 200을 반환하고 없는/미검토/형식 오류 code는 같은 404다.
- 성공 data는 객체이고 상세 배열 4개가 항상 존재하며 목록 API는 기존 배열 형식·순서를 유지한다.
- 기존 YAML·생성자 호출·기동/필터링/격리 정책을 보존하고 새 스키마의 잘못된 값은 기동 때 발견한다.
- 공개 출처는 유형 전체 PR 승인 범위에 포함된다. URI 형식 통과를 공식 출처 검증으로 표시하지 않는다.
- 요청 중 DB·AI·외부 URL·파일 읽기가 없고 다른 guides 경로나 메서드를 공개하지 않는다.
- 실제 테스트·패키징과 파일별 후속 작업을 구현 보고서로 남긴다.

구현 전 확인할 초기안은 문자열 배열·title/url 출처 객체의 구조와 한도, 선택 필드 생략 시 빈 배열 호환, 형식 오류도 404로 묶는 정책, 현재 ErrorResponse 유지, 유형 단위 출처 승인·확인일 관리, 빈 references 허용과 향후 최소 1개 출처 강제 여부다. 별도 AI 생성 URL 탐지·공식 도메인 자동 허용 목록은 현재 구현 범위에 넣지 않는다.
