# 사기 유형 목록 (GUIDE-01)

`GET /api/guides/fraud-types`가 보여 주는 사기 유형이에요. 유형 하나를 YAML 파일 하나로 두고, 서버가 켜질 때 이 폴더의 `.yml`·`.yaml`만 읽어요. 이 README는 읽지 않아요.

## 지금 목록이 비어 있는 이유

현재 응답은 `{"success":true,"data":[]}`이고, **정상이에요.** 오류가 아니에요.

`secondhand-trade.yml`이 `reviewed: false`인 미검토 초안이라 공개 목록에서 빠져요. 담당자가 내용을 검토하고 `reviewed: true`로 바꿔 배포하면 그때 목록에 나와요. 개발·시연 편의로 `true`로 바꾸지 않아요.

## 유형을 공개하는 순서

1. **내용 확인:** `name`·`description`이 사용자에게 보여도 되는 문장인지 담당자가 확인해요.
2. **출처 교체:** `source`를 실제로 확인한 공식 출처의 HTTPS 주소로 바꿔요. 초안의 `example.invalid`는 형식 예시예요. **서버는 `example.invalid`를 막지 않으니** 꼭 사람이 바꿔야 해요.
3. **확인일 갱신:** `checked_at`을 출처를 확인한 날짜로 바꾸고 따옴표로 감싸요. 예: `"2026-11-17"`
4. **승인:** `reviewed: true`로 바꿔요.
5. **PR 검토:** 코드 리뷰와 별도로 내용 담당자가 승인해요.
6. **배포 또는 재시작:** 서버는 켜질 때만 읽어요. 파일을 바꿔도 실행 중인 서버에는 반영되지 않아요.

## 항목

7개 모두 필수예요. 없는 항목이나 아래에 없는 항목이 있으면 서버가 켜지지 않아요.

| 항목 | 규칙 | 공개 응답에 나옴 |
|---|---|---|
| `code` | 대문자·숫자·밑줄 1~50자. 폴더 안에서 중복 불가 | 예 |
| `name` | 1~100자 | 예 |
| `description` | 1~1,000자 | 예 |
| `display_order` | 0 이상 정수. 작은 값이 먼저, 같으면 `code` 순 | 아니요 |
| `reviewed` | `true` 또는 `false` (따옴표 없이) | 아니요 |
| `source` | 호스트가 있는 `https://` 주소 | 아니요 |
| `checked_at` | `"YYYY-MM-DD"` (따옴표 필수) | 아니요 |

## 자주 하는 실수

| 실수 | 결과 |
|---|---|
| `checked_at: 2026-11-17` (따옴표 없음) | YAML이 날짜 타입으로 읽어 서버 기동 실패 |
| `reviewed: "true"` (따옴표 있음) | 문자열로 읽어 서버 기동 실패 |
| 다른 파일과 같은 `code` | 서버 기동 실패 (미검토 파일끼리도 마찬가지) |
| 이 폴더의 YAML을 모두 삭제 | 서버 기동 실패. 공개할 유형이 없으면 `reviewed: false` 파일을 남겨 둬요 |
| 테스트용 자료를 여기에 추가 | 운영 목록에 섞여요. 테스트 자료는 `src/test/resources/test-content/fraud-types/`에 둬요 |

## 참고

- 훈련에서 쓰는 `USED_GOODS_TRADE`와 이 목록의 `SECONDHAND_TRADE`는 아직 연결되지 않았어요. 목록에 올렸다고 그 유형의 훈련이 되는 건 아니에요.
- 코드·테스트 설명: `docs/guide-01-fraud-types.md`, 패키지 설명: `src/main/java/com/wsid/content/README.md`
