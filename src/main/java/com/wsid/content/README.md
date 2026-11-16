# content — 검토된 콘텐츠

팀이 검토한 시나리오, 대응 카드, 사기 유형, 기관 정보를 읽어 다른 기능과 화면에 제공해요.
DB 테이블이 없고, `src/main/resources/content`의 YAML 파일이 원본이에요.

## 명세서 매핑

| 명세서 항목 | ID | 우선순위 | 상태 |
|---|---|---|---|
| 사기 유형 목록 | SOL-001 | 중 | GUIDE-01 로더·카탈로그·공개 GET 구현. 실제 승인 데이터 등록 전 |
| 유형별 특징과 예방 수칙 | SOL-002 | 중 | 미구현 |
| 상황별 대응 방법 직접 조회 | SOL-004 | 상 | 미구현 |
| 기관별 역할 안내 | SOL-005 | 중 | 미구현 |
| 공식 전화·사이트 연결 | SOL-006 | 상 | 미구현 |
| 공식 출처·링크 검토 | 비기능 | 상 | YAML 형식 초안 |

## 목표 공개 API

목표 GUIDE API는 로그인 없이 조회해요. 내부 패키지 이름은 `content`, 외부 URL은 `/api/guides`예요. 현재 `SecurityConfig`에서 공개한 것은 GUIDE-01의 GET뿐이며 나머지는 각 API 구현 시 추가해야 해요.

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| GUIDE-01 | GET | `/api/guides/fraud-types` | 불필요 | YAML 로더·승인 필터/정렬·메모리 카탈로그·성공 봉투·익명 GET 구현. 기본 자료는 false 초안이므로 현재 빈 배열 |
| GUIDE-02 | GET | `/api/guides/fraud-types/{code}` | 불필요 | content 문서 계획; 로더·API 없음 |
| GUIDE-03 | GET | `/api/guides/response-cards` | 불필요 | 카드 형식 README만; 적용 조건의 요청·응답 정의 필요 |
| GUIDE-04 | GET | `/api/guides/institutions` | 불필요 | content 문서 계획; API 없음 |

엑셀 SOL-001/002는 GUIDE-01/02, 직접 카드 조회 SOL-004는 GUIDE-03, 기관 안내·연결 SOL-005/006은 GUIDE-04 및 카드의 연락처 응답에 대응해요. 필터·조건 입력·응답 DTO는 별도로 정의해요.

## 파일 위치

```
src/main/resources/content
├─ fraud-types/       유형 목록. 현재 미검토 초안만 존재. 공개 절차는 그 폴더의 README 참고
├─ scenarios/         훈련 시나리오 (training이 사용)
└─ response-cards/    대응 카드 (solution이 사용). 형식은 그 폴더의 README 참고
```

기관(`agencies/`) 폴더는 만들 때 추가해요.

## 개발 순서

1. **카탈로그 로더**: 유형은 `FraudTypeYamlLoader`·`FraudTypeCatalog`를 구현했고 SnakeYAML 직접 의존성을 추가했어요. 기동 때 타입·출처/확인일·중복을 검사하고 승인된 목록만 메모리에 둬요. 카드·기관·시나리오의 로더는 별도 스키마로 확장해야 해요.
2. **training 연결**: `training/service/ReviewedTexts`의 상수를 시나리오 YAML에서 읽도록 바꾸고 그 클래스를 지워요.
3. **solution 연결**: 카드 코드로 카드 내용을 돌려주는 조회 메서드를 제공해요.
4. **조회 API**: 유형 목록은 구현했어요. 상세·상황별 대응·기관 안내는 후속 작업이에요.

## GUIDE-01 개발·콘텐츠 관리

[구현 보고서](../../../../../../docs/guide-01-fraud-types.md)에 파일별 처리·테스트·후속 연결을 정리했어요. 기본 `secondhand-trade.yml`은 example.invalid와 초안 확인일을 사용하는 reviewed=false 문서라 현재 응답은 `success:true, data:[]`예요. 빈 목록은 오류가 아니라 정상이에요. 담당자가 실제 출처·확인일·내용을 검토하고 `reviewed: true`로 바꿔 배포해야 공개돼요. 순서와 작성 규칙은 [fraud-types README](../../../../resources/content/fraud-types/README.md)를 따라요. 로더는 `example.invalid` 같은 예시 출처를 막지 않으니 PR에서 source를 꼭 확인해요.

- `wsid.content.fraud-types-location`으로 클래스패스 디렉토리를 지정해요. 기본은 `classpath*:/content/fraud-types/`이고 해당 폴더의 .yml/.yaml만 읽어요. 파일이 없거나 잘못되면 기동 실패, 유효한 false 자료만 있으면 기동·빈 목록을 허용해요.
- 문자열/숫자/boolean 타입을 검사해요. 특히 checked_at은 `"2026-11-16"`처럼 따옴표로 작성해요. name/description은 strip한 값으로 저장하고 code·관리 정보는 임의 보정하지 않아요.
- 합성 자료는 `src/test/resources/test-content/fraud-types/`에 둬요. 테스트별 설정으로 경로를 바꾸고 `src/test/resources/content/fraud-types/`에는 넣지 않아요. 작은 테스트 컨텍스트에는 ContentProperties를 명시적으로 등록해요.
- 로더·위치 설정·유효한 기본 초안을 같은 커밋/배포에 넣어야 기존 전체 컨텍스트가 파일 누락으로 실패하지 않아요.
- 기존 훈련의 USED_GOODS_TRADE는 변경하지 않았어요. 목록 등록이 해당 유형의 훈련 지원이나 AI-07 근거 승인을 뜻하지 않아요.

## AI-07 연결 시 할 일

AI-07 Spring 설명 클라이언트는 구현했지만 콘텐츠 로더·승인 문단 검색은 아직 없어요. [AI-07 구현 보고서](../../../../../../docs/ai-07-solution-explain.md)의 8~10장에 디렉토리별 작업을 정리했어요.

- 카드 ID와 별도로 승인 문단의 code·버전·출처·확인일·검토/철회 상태를 관리해요. 같은 코드의 내용을 임의로 덮어쓰지 않고 재현 가능한 버전을 유지해요.
- 전화번호·URL 없는 설명용 문단을 별도로 승인해 VerifiedReference의 code/title/excerpt로 보내요. 연락처·공식 링크는 카드·카탈로그에서만 제공해요. 문장을 자동으로 일부 삭제해 승인된 문단으로 간주하지 않아요.
- 공개 요청의 코드·URL·reviewed 값을 신뢰하지 않고 카탈로그에서 현재 유효한 문단을 선택해요. 승인 문단이 없으면 AI-07을 생략해요.
- FastAPI 인덱스와 버전·승인/철회 정보를 맞추고, AI 호출 중 근거가 철회/변경되면 결과를 표시·저장하지 않도록 상담 서비스와 연결해요.

## 콘텐츠 제공 규칙

- 기관명, 연락처, 공식 링크는 여기서만 나와요.
- 출처와 확인일이 없는 항목, `reviewed: false`인 항목은 화면에 내보내지 않아요.
- 콘텐츠는 PR로 검토해요. 코드 리뷰와 별도로 내용을 확인할 담당자를 정해요.

## 정할 것

- YAML 편집 담당자와 갱신 주기
- 유형 코드 값 (`fraud_type_code`)
