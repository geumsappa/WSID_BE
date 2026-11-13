# ai — AI 서비스 호출

AI 서비스(FastAPI)를 부르는 유일한 통로예요. 다른 패키지는 `AiClient` 인터페이스만 알아요.

## 명세서 매핑

명세서 비고의 I1~I6은 AI 서비스의 내부 API 번호로 보고 정리했어요. AI repo와 맞춰 확정해요.

| 내부 API | 하는 일 | 쓰는 기능 | 상태 |
|---|---|---|---|
| I1 | 일상 대화: 후속 질문, 사실·위험 요소 추출, 판단 지점 선정, OCR | SIM-001, 002, 004 | AI-01 fake/HTTP 어댑터 구현; 위험 요소·판단 지점·OCR은 후속 작업 |
| I2 | 개인화 시나리오 생성 | SIM-005 | 미구현 |
| I3 | 가상 훈련 한 턴: 다음 대사, 행동 판정, 핵심 장면 제안, 가드레일 | SIM-007, 008, 011 | fake/HTTP 어댑터 구현, 목표 경로·맥락 연결은 미완료 |
| I4 | 리포트 평가 | SIM-010 | 미구현 |
| I5 | 피해 사실 추출 | SOL-003 | 미구현 |
| I6 | 공식 근거 문단 검색 | SOL-004 | 미구현 |
| - | AI 호출 실패 시 대체 흐름 | 비기능 | 구현 (`AiUnavailableException`) |

## 목표 내부 API

I1~I6은 엑셀의 기능 구분이고 AI-01~07은 API ID예요. I1은 AI-01(상황 파악)과 AI-02(OCR), I2~I6은 각각 AI-03~07에 대응해요. 내부 호출은 사용자 로그인 여부와 별개인 서버 간 인증을 사용해요.

AI-01 개발 흐름과 요청·응답 초안은 [상황 파악 다음 질문 개발 문서](../../../../../../docs/ai-01-discovery.md)를 참고해요.

| API ID | 메서드 | 경로 | 인증 | 현재 구현 |
|---|---|---|---|---|
| AI-01 | POST | `/internal/ai/discovery/next` | 내부 | fake 및 HTTP 어댑터·응답 계약 테스트 구현; 실제 FastAPI 통합 검증은 미완료 |
| AI-02 | POST | `/internal/ai/ocr/extract` | 내부 | 미구현; 문서에서는 I1에 포함 |
| AI-03 | POST | `/internal/ai/scenarios/generate` | 내부 | 미구현; 고정 유형·상수 문구로 대체 |
| AI-04 | POST | `/internal/ai/simulation/reply` | 내부 | 어댑터 구현; 실제 호출은 /internal/training/turn으로 불일치, 맥락 필드 TODO |
| AI-05 | POST | `/internal/ai/training/evaluate` | 내부 | 미구현; report 문서 계획 |
| AI-06 | POST | `/internal/ai/solutions/extract` | 내부 | 미구현; AiClient TODO |
| AI-07 | POST | `/internal/ai/solutions/explain` | 내부 | 미구현; 문서는 I6 근거 검색으로 설명 |

현재 HTTP 어댑터는 `/internal/training/turn`을 호출해 목표 AI-04 경로와 달라요. 실제 AI 서비스와의 통합 검증은 완료되지 않았어요. AI-01은 확인된 사실을 매번 누적·전달해요. 개인화 시나리오 생성 연결은 후속 작업이에요. 훈련 응답 요청에도 사실·시나리오·필요한 최근 대화를 넣어야 하나 현재 DTO에는 TODO예요.

## 파일

| 파일 | 역할 |
|---|---|
| `AiClient` | 인터페이스 |
| `FakeAiClient` | 가짜 구현. `wsid.ai.mode=fake`(기본값)일 때 사용 |
| `HttpAiClient` | 실제 호출. 연결 2초, 응답 20초 타임아웃 |
| `AiClientConfig`, `AiProperties` | 설정으로 구현 선택 |
| `dto/` | AI 서비스와 주고받는 형식. AI repo와의 계약이에요 |

## 내부 API를 하나 추가하는 방법

1. `dto/`에 요청·응답 record를 만들어요. 응답은 AI가 틀린 값을 보내도 흐름이 깨지지 않게 받아요(예: 허용 목록 밖의 코드는 기본값으로).
2. `AiClient`에 메서드를 추가해요.
3. `FakeAiClient`에 항상 같은 답을 돌려주는 구현을 넣어요. 이걸로 화면과 서비스 개발을 먼저 진행해요.
4. `HttpAiClient`에 실제 호출을 넣고, 실패는 `AiUnavailableException`으로 바꿔 던져요.
5. 호출하는 service에서 예외를 잡아 대체 흐름으로 이어가요.

## 규칙

- **AI는 제안만 해요.** 다음 단계, 종료, 긴급 안내, 기관명·링크는 AI 응답으로 정하지 않아요.
- **AI 호출은 트랜잭션 밖에서 해요.**
- 목표 계약에서 AI 서비스는 상태를 저장하지 않아요. 필요한 맥락은 요청마다 보내도록 구현해야 해요. 현재 맥락 DTO는 미완성이에요.
- 요청·응답 본문은 로그에 남기지 않아요.

## 정할 것

- 필드 이름 표기(camelCase 또는 snake_case)를 AI repo와 맞춰요. 지금 dto는 camelCase예요.
- 외부 LLM 공급자의 요청 한도에 걸렸을 때 재시도를 어느 쪽에서 할지
