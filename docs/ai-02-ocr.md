# AI-02 OCR 추출 개발·연결 설계

## 이 문서의 기준

AI-02(`POST /internal/ai/ocr/extract`)는 사진 속 글자를 읽고 민감정보를 검사하는 **FastAPI 내부 API**예요. 사용자는 Spring에만 사진을 올리고, Spring이 FastAPI를 불러요.

| 구분 | 상태 | 위치 |
|---|---|---|
| FastAPI OCR | 구현 (shoon0123, 11/17 `942b4d4`) | WSID_AI `app/routers/ocr.py`, `app/services/ocr.py` |
| Spring 호출 클라이언트 | 구현 | `AiClient.extractText` (`HttpAiClient`, `FakeAiClient`) |
| 사용하는 공개 API | 피해 대응 **SOL-06** `POST /api/solutions/sessions/{id}/images` | [SOL-06 문서](sol-06-session-images.md) |
| 훈련 TRN-05 | **제거**. 훈련에서는 사진 입력을 쓰지 않아요 | - |
| 실제 FastAPI와 통합 검증 | 미완료 (Tesseract 설치 필요) | - |

관련 문서: [API 기준](api.md), [AI 패키지 README](../src/main/java/com/wsid/ai/README.md)

## 전체 연결

```
프론트 ──SOL-06──▶ Spring ──AI-02──▶ FastAPI ──▶ Tesseract (kor+eng)
                   │                   └ 민감정보 검사 → usable
                   └ 읽은 글자 → SOL-03 텍스트와 같은 흐름 (민감정보 확인 → AI-06 사실 추출 → 카드·질문)
```

| 책임 | Spring | FastAPI |
|---|---|---|
| 로그인·비회원 상담 소유권, CSRF | O | X |
| 파일 장수·크기·형식 1차 검증 | O (SOL-06) | O (같은 기준으로 다시 확인) |
| 글자 인식 | X | O |
| 민감정보 검사, `usable` 판정 | 결과를 따르고 한 번 더 확인 | O |
| 읽은 글자를 상담에 반영 | O | X |
| 원본·글자 보관 | 하지 않음 | 하지 않음 |

## FastAPI 구현 (WSID_AI develop 기준)

| 항목 | 내용 |
|---|---|
| 경로 | `POST /internal/ai/ocr/extract`, 파트 `images: list[UploadFile]` |
| 인증 | `require_internal_key`: `X-Internal-Api-Key` 확인, 없거나 틀리면 401 |
| OCR 엔진 | **Tesseract** 명령어 실행: `tesseract stdin stdout -l kor+eng` (`OCR_EXECUTABLE`, `OCR_LANGUAGES`로 변경) |
| 제한 | 최대 3장, 장당 5MB, PNG·JPEG만(Pillow로 확인), 1600만 픽셀 이하, 전체 15초 |
| 민감정보 | `mask_sensitive`·전화번호·URL이 보이거나 1000자를 넘으면 `{"recognizedText":"","usable":false}` |
| 원본 처리 | 파일 이름·경로는 OCR에 넘기지 않고 바이트만 표준 입력으로 전달 |
| 테스트 | `tests/test_internal_endpoints.py` (OCR 실행 부분은 가짜로 바꿔 확인) |

### 응답

| 상황 | HTTP | 본문 | Spring `extractText` 결과 | SOL-06 응답 |
|---|---|---|---|---|
| 정상 | 200 | `{"success":true,"data":{"recognizedText":"...","usable":true}}` | 결과 반환 | 상담에 반영 |
| 민감정보 | 200 | `{"success":true,"data":{"recognizedText":"","usable":false}}` | 결과 반환 | 400 `SENSITIVE_DATA_DETECTED` |
| 글자 없음 | 422 | `{"success":false,"error":{"code":"OCR_FAILED",...}}` | `OcrExtractResult.failed()` | 422 `OCR_FAILED` |
| 키 오류 | 401 | `UNAUTHORIZED` | `AiUnavailableException` | 503 `AI_SERVICE_UNAVAILABLE` |
| 파일 제한·형식 | 413/422 | `FILE_TOO_LARGE` / `INVALID_REQUEST` | `AiUnavailableException` | Spring이 먼저 막아서 보통 오지 않음 |
| 엔진 없음·시간 초과 | 503 | `AI_SERVICE_UNAVAILABLE` | `AiUnavailableException` | 503 `AI_SERVICE_UNAVAILABLE` |

## Spring 클라이언트 (`HttpAiClient.extractText`)

```java
AiEnvelope<OcrExtractResult> envelope = restClient.post()
        .uri("/internal/ai/ocr/extract")               // baseUrl = AI_BASE_URL, X-Internal-Api-Key는 기본 헤더
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(toMultipart(images))                     // 파트 이름 "images"로 여러 장
        .exchange((request, response) -> response.bodyTo(OCR_RESPONSE)); // 상태 코드와 상관없이 본문 읽기
return unwrap(envelope);
```

| 설계 결정 | 이유 |
|---|---|
| `OCR_FAILED`는 예외가 아니라 `OcrExtractResult.failed()` | 흐린 사진처럼 사용자 입력 문제라서 서버 장애와 구분해 "직접 입력" 안내로 이어가요 |
| `retrieve()`가 아니라 `exchange()` | 422 응답에서도 본문의 `error.code`를 읽어야 해요 |
| `toString`에 바이트·글자를 출력하지 않음 | 디버그 로그에 이미지나 전화번호가 남지 않게 해요 |
| 예외에 원인(cause)을 붙이지 않음 | HTTP 예외 메시지에 응답 본문이 섞일 수 있어요 |
| `MultipartBodyBuilder` 대신 `LinkedMultiValueMap` + `HttpEntity` | `MultipartBodyBuilder`는 reactive 라이브러리가 필요해 테스트 때 `NoClassDefFoundError`가 났어요 |

`FakeAiClient`(`AI_MODE=fake`)는 고정 문구를 `usable=true`로 돌려주고, 파일 이름에 `ocr-fail`이 있으면 실패 결과를 돌려줘요. SOL-06은 AI 서비스에 `image.png`/`image.jpg`로만 보내므로 화면에서 실패 흐름을 보려면 테스트 대역을 써요.

## 실행과 연결

| 설정 | 쓰는 곳 |
|---|---|
| Spring `AI_MODE` | `fake`면 FastAPI를 부르지 않아요. 연결할 때 `http` |
| Spring `AI_BASE_URL`, `AI_API_KEY` | FastAPI 주소와 내부 키 |
| FastAPI `AI_API_KEY` | Spring과 같은 값 |
| FastAPI `OCR_EXECUTABLE`, `OCR_LANGUAGES` | Tesseract 경로(기본 `tesseract`), 언어(기본 `kor+eng`) |

1. FastAPI 서버에 Tesseract와 한국어 언어 데이터를 설치해요(Ubuntu: `apt-get install tesseract-ocr tesseract-ocr-kor`).
2. FastAPI만 먼저 확인해요.

   ```bash
   curl -X POST http://localhost:8000/internal/ai/ocr/extract -H 'X-Internal-Api-Key: local-dev-key' -F 'images=@sample.png'
   ```

3. Spring을 `AI_MODE=http`로 띄워 SOL-06으로 확인해요.

## 테스트

| 테스트 | 확인하는 것 |
|---|---|
| `HttpAiClientOcrTest` (7) | JDK 내장 HTTP 서버를 FastAPI 대신 띄워 multipart 형식·내부 키·성공·민감정보·`OCR_FAILED`·500·HTML·data 없음·로그 노출 확인 |
| SOL-06 테스트 | [SOL-06 문서](sol-06-session-images.md#테스트) 참고 |

## 남은 것

- 실제 FastAPI(Tesseract)와 통합 확인
- 한글 캡처(문자·카톡) 인식률 확인. 낮으면 PaddleOCR 같은 한국어 OCR로 FastAPI `_recognize()`만 교체 검토 (응답 계약은 그대로)
- WSID_AI `README.md`의 "AI-02 이미지 OCR은 아직 이 서버에 없어요"(291줄) 정리, 설명을 훈련용에서 피해 대응용으로 수정
