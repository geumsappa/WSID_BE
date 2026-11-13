# content — 검토된 콘텐츠

팀이 검토한 시나리오, 대응 카드, 사기 유형, 기관 정보를 읽어 다른 기능과 화면에 제공해요.
DB 테이블이 없고, `src/main/resources/content`의 YAML 파일이 원본이에요.

## 명세서 매핑

| 명세서 항목 | ID | 우선순위 | 상태 |
|---|---|---|---|
| 사기 유형 목록 | SOL-001 | 중 | 미구현 |
| 유형별 특징과 예방 수칙 | SOL-002 | 중 | 미구현 |
| 상황별 대응 방법 직접 조회 | SOL-004 | 상 | 미구현 |
| 기관별 역할 안내 | SOL-005 | 중 | 미구현 |
| 공식 전화·사이트 연결 | SOL-006 | 상 | 미구현 |
| 공식 출처·링크 검토 | 비기능 | 상 | YAML 형식 초안 |

## 목표 공개 API

모두 로그인 없이 조회해요. 내부 패키지 이름은 `content`, 외부 URL은 `/api/guides`예요. 기존 `SecurityConfig`는 새 guides 경로를 공개하도록 정렬해야 해요.

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| GUIDE-01 | GET | `/api/guides/fraud-types` | 불필요 | content 문서 계획; 로더·API 없음, 현재 보안 규칙에 공개 경로 등록 필요 |
| GUIDE-02 | GET | `/api/guides/fraud-types/{code}` | 불필요 | content 문서 계획; 로더·API 없음 |
| GUIDE-03 | GET | `/api/guides/response-cards` | 불필요 | 카드 형식 README만; 적용 조건의 요청·응답 정의 필요 |
| GUIDE-04 | GET | `/api/guides/institutions` | 불필요 | content 문서 계획; API 없음 |

엑셀 SOL-001/002는 GUIDE-01/02, 직접 카드 조회 SOL-004는 GUIDE-03, 기관 안내·연결 SOL-005/006은 GUIDE-04 및 카드의 연락처 응답에 대응해요. 필터·조건 입력·응답 DTO는 별도로 정의해요.

## 파일 위치

```
src/main/resources/content
├─ scenarios/         훈련 시나리오 (training이 사용)
└─ response-cards/    대응 카드 (solution이 사용). 형식은 그 폴더의 README 참고
```

사기 유형(`fraud-types/`)과 기관(`agencies/`) 폴더는 만들 때 추가해요.

## 개발 순서

1. **카탈로그 로더**: 서버 시작 때 YAML을 읽어 메모리에 두는 `ContentCatalog`를 만들어요.
   형식이 틀리거나 필수 항목(출처, 확인일)이 빠지면 **서버가 켜지지 않게** 해요. 잘못된 안내가 나가는 것보다 나아요.
   YAML을 읽으려면 `build.gradle`에 YAML 파서를 추가해요.
2. **training 연결**: `training/service/ReviewedTexts`의 상수를 시나리오 YAML에서 읽도록 바꾸고 그 클래스를 지워요.
3. **solution 연결**: 카드 코드로 카드 내용을 돌려주는 조회 메서드를 제공해요.
4. **조회 API**: 유형 목록·상세, 상황별 대응, 기관 안내.

## 규칙

- 기관명, 연락처, 공식 링크는 여기서만 나와요.
- 출처와 확인일이 없는 항목, `reviewed: false`인 항목은 화면에 내보내지 않아요.
- 콘텐츠는 PR로 검토해요. 코드 리뷰와 별도로 내용을 확인할 담당자를 정해요.

## 정할 것

- YAML 편집 담당자와 갱신 주기
- 유형 코드 값 (`fraud_type_code`)
