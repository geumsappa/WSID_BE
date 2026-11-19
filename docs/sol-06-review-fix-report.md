# SOL-06 사진 입력 검토·수정 보고서

작성일: 2026-11-19
브랜치: `OHC` (검토 대상 `ae07eb1` 위에 수정 커밋)
관련 문서: [SOL-06 사진 입력](sol-06-session-images.md), [AI-02 OCR](ai-02-ocr.md)

## 배경

피해 대응 상담에서 사용자가 문자·카톡·송금 내역 사진을 올리면, 사진 내용을 판단해 상담 사실을 갱신하고 **사진 내용에 맞는 대응 안내**를 써 주는 것이 목표예요.

| 커밋 | 내용 |
|---|---|
| `d4b60f3` | SOL-06 기본 구현: 파일 검증, OCR 글자를 SOL-03 텍스트처럼 사실 추출에 반영, 훈련 TRN-05 제거 |
| `ae07eb1` | FastAPI 새 응답(`imageType`, `summary`) 수신, 사진은 민감정보가 있어도 허용, 사실 저장 뒤 대응 안내(assist) 추가 |
| 이번 수정 | `ae07eb1` 검토에서 찾은 3가지 수정 |

## 검토에서 찾은 문제와 수정

### 1. 대응 안내가 사진 내용을 모르고 쓰였어요 (목적과 어긋남)

- **문제:** `SolutionImageAdviceService`가 고정 질문("확인된 피해 사실을 기준으로 지금 해야 할 대응 방법을 안내해 주세요.")과 사실 4개만 보냈어요. 택배 사칭 문자를 올리든 중고거래 송금 내역을 올리든 사실이 같으면 같은 안내가 나와요.
- **수정:** 질문에 **사진 종류와 요약**을 넣어요. 원문은 계속 보내지 않아요.

  ```
  사진 종류: 문자 메시지
  사진 내용: 택배 주소 불일치를 이유로 링크 접속을 요구하는 문자
  이 사진과 확인된 피해 사실을 기준으로 지금 해야 할 대응 방법을 안내해 주세요.
  ```

- **안전장치:** 요약은 FastAPI가 링크·연락처·민감정보를 걸러 낸 300자 이내 문장이에요. Spring에서도 한 번 더 확인해서, 계좌·전화번호 같은 숫자(`SensitiveDescriptionGuard` 기준)나 링크가 보이면 요약을 빼고 종류만 보내요. 금액(`500,000원`)과 날짜는 그대로 둬요.
- **구현:** `SolutionImageService.send`가 OCR 결과(종류·요약)를 보관했다가 `optionalAdvice(facts, imageType, summary, requestId)`로 넘겨요.

### 2. 요약이 300자를 넘으면 사진 입력 전체가 실패했어요

- **문제:** `OcrExtractResult`가 300자를 넘는 요약에 예외를 던졌고, `HttpAiClient`가 이를 AI 장애로 바꿔 503 `AI_SERVICE_UNAVAILABLE`이 났어요. 글자를 잘 읽었는데도 부가 정보 하나 때문에 사용자는 실패를 봐요.
- **수정:** 300자를 넘는 요약은 `null`로 버리고 계속 진행해요. FastAPI도 분류에 실패하면 `OTHER`/`null`로 대신하는 방식이라 같은 기준이에요.

### 3. 최악의 경우 한 요청이 70초 넘게 걸렸어요

- **문제:** OCR 30초 + 사실 추출 20초 + 안내 20초였어요.
- **수정:** 대응 안내는 실패해도 사실·카드는 응답되는 부가 기능이라 기본 대기를 **10초**(`AI_IMAGE_ADVICE_TIMEOUT`)로 줄였어요. 최악이 약 60초예요.

## 바뀐 파일

| 파일 | 변경 |
|---|---|
| `ai/dto/OcrExtractResult.java` | 300자 초과 요약 → null |
| `solution/service/SolutionImageAdviceService.java` | 질문에 종류·요약 포함, 안전하지 않은 요약 제외, 기본 대기 10초 |
| `solution/service/SolutionImageService.java` | OCR 결과를 안내 단계로 전달 |
| `application.yml` | `image-advice-timeout` 기본값 10초 |
| 테스트 3개 | 아래 참고 |
| `docs/sol-06-session-images.md`, `docs/ai-02-ocr.md` | 위 변경 반영 |

## 테스트

`./gradlew test` (DB 없이): **1024개 모두 통과**.

| 테스트 | 추가·변경한 확인 |
|---|---|
| `SolutionImageAdviceServiceTest` | 질문에 종류·요약 포함, 원문 없음. 숫자·링크가 섞인 요약은 제외, 금액·날짜 요약은 유지, 요약 없음·종류 없음 처리 |
| `SolutionImageServiceTest` | OCR이 실제로 실행된 결과의 종류·요약이 안내 요청으로 전달됨 |
| `HttpAiClientOcrTest` | 301자 요약을 받아도 실패하지 않고 `summary=null` |

DB 테스트(`dbTest`)와 통합 테스트는 PostgreSQL·Redis가 필요해 이 환경에서는 돌리지 않았어요. `ae07eb1` 작성 세션에서는 DB 50개, 통합 33개 통과로 보고됐어요. 이번 수정은 DB 저장 경로를 바꾸지 않았어요.

## 아직 남은 것

| 항목 | 상태 | 영향 |
|---|---|---|
| WSID_AI `feature/ocr-paddle` (PaddleOCR, 분류·요약, 민감정보 사진 허용) | **원격에 없음** (작업 세션 로컬, 미커밋) | 이 Spring만 develop에 머지되면 민감정보가 있는 사진이 대부분 `OCR_FAILED`가 되고 분류는 항상 `OTHER` |
| FastAPI Gemini 모델 설정 | 503/504·400 응답 | 실제 분류·요약·안내가 나오지 않음. 사실 추출 등 다른 AI 기능도 영향 |
| 승인 근거 파일(`ApprovedReferenceCatalog`) | 비어 있음 | 대응 안내(`assistant`)가 항상 null |
| 정책·명세 | 미반영 | "사진은 민감정보가 있어도 받는다", SOL-06 추가, TRN-05 제거를 노션에 반영 |
| 실제 FastAPI 연결 확인 | 안 함 | WSID_AI 푸시 후 `AI_MODE=http`로 확인 |

## 머지 순서

1. WSID_AI `feature/ocr-paddle` 커밋·푸시·PR
2. **WSID_AI PR을 먼저(또는 동시에) develop에 머지**
3. 이 WSID_BE `OHC` PR 머지
