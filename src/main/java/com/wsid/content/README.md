# content — 검토된 가이드

GUIDE-01~04는 로그인 없이 조회할 수 있어요. 사기 유형, 대응 카드, 기관 정보는 서버 시작 때 `src/main/resources/content/guides.yml`에서 읽어요. 공개 API 경로와 필터는 [API 문서](../../../../../../../docs/api.md)를 참고하세요.

카탈로그에는 `fraudTypes`, `responseCards`, `institutions` 세 목록이 있어요. 각 항목의 `content`에는 해당 API가 반환하는 필드를 넣어요. 목록용 유형 응답은 상세 데이터에서 `code`, `name`, `description`을 골라 반환해요.

| 항목 | 공개에 필요한 내부 필드 | 출처와 확인일 |
| --- | --- | --- |
| 사기 유형 | `reviewed: true` | `content.references`, 항목의 `checkedAt` |
| 대응 카드 | `reviewed: true` | `content.sourceReferences`, `content.reviewedAt` |
| 기관 | `reviewed: true` | 항목의 `source`, `content.reviewedAt` |

날짜는 YAML에서 `"YYYY-MM-DD"` 형태로 적어요. 승인된 항목만 반환하며, 필수 필드 누락이나 코드 중복, 공개 카드가 미등록 기관을 참조하는 경우에는 시작 시 설정 오류로 처리해요. 실제 전화번호와 공식 링크는 팀 검토를 마친 뒤 등록해요. 기본 카탈로그는 비어 있어요.

훈련 시나리오 파일과 피해 대응 카드의 설계 문서는 각각 `src/main/resources/content/scenarios`와 `src/main/resources/content/response-cards`에 있어요.
