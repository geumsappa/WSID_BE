# 상담 assist Spring 시간 제한 수정 보고서

작성일: 2026-11-18 · 상태: **Spring 내부 AI 클라이언트 변경 완료, 상담 API 연동 전**

관련 계획: [자유 질문·답변 기능 수정 계획서](solution-assist-question-plan.md)

> **2026-11-18 갱신:** 이 보고서 이후 상담 서비스 연동과 전체 처리 기한(`assist-total-timeout`, 기본 40초)이 구현됐다. 아래 "전체 기한 미구현"·"상담 API 연동 전" 내용은 작성 당시 기준이며, 현재 상태는 [구현 보고서](solution-assist-question.md)를 따른다.

## 1. 변경 목적과 범위

Python assist의 PLAN·ANSWER 처리 시간에 맞춰 Spring에서 assist 호출의 응답 제한을 다른 AI 호출과 별도로 조정할 수 있도록 했다. 이번 Spring 변경은 **내부 AI 클라이언트의 시간 설정**이다. 프론트 질문 API, 상담 서비스, DB 저장, 승인 근거 확인까지 구현한 것은 아니다.

기존 작업 트리에는 `AiClient`의 PLAN·ANSWER 메서드와 DTO, 응답 검증기, 가짜 AI 및 HTTP 호출 코드가 이미 있었다. 이번에는 그 코드가 사용하는 HTTP 클라이언트를 assist 전용 설정으로 분리했다. 이 보고서는 기존 작업 트리의 클라이언트 전체를 이번에 새로 구현했다고 주장하지 않는다.

## 2. 실제 변경

| 파일 | 변경 내용 |
| --- | --- |
| [AiProperties.java](../src/main/java/com/wsid/ai/AiProperties.java) | `assistReadTimeout`을 추가했다. 값이 없으면 공통 `readTimeout`을 사용하고 0 또는 음수는 거부한다. 기존 생성자도 유지한다. |
| [HttpAiClient.java](../src/main/java/com/wsid/ai/HttpAiClient.java) | PLAN·ANSWER가 공통 클라이언트 대신 assist 전용 `RestClient`를 사용하게 했다. 기존 테스트용 빌더 생성자는 그대로 사용할 수 있다. |
| [application.yml](../src/main/resources/application.yml) | `wsid.ai.assist-read-timeout`을 추가했다. 환경 변수 `AI_ASSIST_READ_TIMEOUT`으로 지정하고 기본값은 20초다. |
| [HttpAiClientSolutionAssistTransportTest.java](../src/test/java/com/wsid/ai/HttpAiClientSolutionAssistTransportTest.java) | assist의 별도 시간 제한, 공통값 대체, 0·음수 거부를 확인하는 테스트를 추가했다. |

## 3. Python과 상호작용

```text
Spring AiClient.planSolutionAssist / answerSolutionAssist
 → HttpAiClient의 assist 전용 RestClient
 → POST /internal/ai/solutions/assist
 → Python에서 phase에 맞춰 PLAN 또는 ANSWER 처리
```

Spring은 PLAN과 ANSWER 각각에 같은 assist read timeout을 적용한다. Python 작업 트리의 단계별 예산 기본값은 PLAN 15초, ANSWER 15초다. 각각의 기본값은 환경별 설정에 따라 달라질 수 있으므로 배포 시 양쪽 값을 함께 확인한다. Python이 타임아웃 등 HTTP 오류를 반환하거나 Spring 호출이 실패하면 내부 클라이언트는 `AiUnavailableException`을 발생시킨다.

**주의:** Spring의 20초는 각 HTTP 호출의 응답 제한이다. PLAN과 ANSWER를 이어서 호출하는 전체 과정이 20초 안에 끝난다는 뜻이 아니다. 계획서의 전체 40초 제한은 아직 구현하지 않았다. 연결 전 실제 지연을 측정하고 전체 deadline 및 API 오류 변환을 구현해야 한다.

Python은 모델이 선택한 사실 키에 따라 고정 추가 질문 문구를 생성하도록 별도로 변경됐다. 외부 응답 필드인 `factKey`, `content`, `options`는 유지되므로 이번 Spring 수정에 새 응답 필드가 추가되지는 않았다. Spring의 기존 검증은 응답 형식·요청 사실과의 일치·금지 표현을 계속 검사한다. 향후 사용자에게 보여줄 문구는 계획에 따라 Spring의 고정 질문을 사용할지 확인해야 한다.

## 4. 검증 결과

Java 21 환경에서 `./gradlew test`를 실행해 **935건 통과, 실패·오류·건너뜀 0건**을 확인했다. 전체에는 기존 테스트와 작업 트리에 있던 assist 테스트가 함께 포함된다. 이 중 `HttpAiClientSolutionAssistTransportTest`는 4건이며, 실제 로컬 HTTP 서버 대역으로 전송과 시간 제한을 확인한다.

 추가로 2026-11-18에 Python 소스를 임시 디렉터리에 복사하고 가짜 임베딩 인덱스를 생성한 뒤, 로컬 FastAPI 서버와 Spring `HttpAiClient`를 실제 HTTP로 연결했다. PLAN 추가 질문 → 후속 PLAN 후보 → ANSWER 인용의 계약 테스트 1건이 통과했다. Python 서버 단독 HTTP 확인에서는 세 정상 요청이 200, 잘못된 키가 401, 잘못된 요청이 422였다. 가짜 모델과 테스트용 근거를 사용했으며 미검토 후보는 운영 승인 자료로 취급하지 않았다.

로컬 서버를 별도로 시작한 후 `LIVE_AI_URL`과 `LIVE_AI_KEY`를 설정하고 `./gradlew liveAiTest`를 실행한다. 이 태그는 기본 `./gradlew test`에서 제외되며, 두 환경 변수가 없으면 테스트는 건너뛴다. 실제 Gemini 연결·답변 품질, PostgreSQL·Redis DB/통합 테스트, 운영 승인 근거의 적합성과 실제 모델 응답 시간은 검증하지 않았다.

## 5. 이후 개발 시 참고

- **질문 입력과 프론트:** 후속 계획은 별도 `/questions` 대신 기존 SOL-03 `/messages`에 purpose를 추가하는 안으로 변경됐다. 사용자 질문, 추가 선택지, AI 답변을 주고받을 공개 계약을 프론트와 확정해 구현한다.
- **상담 서비스:** 소유권·CSRF·상태 버전 확인, 트랜잭션 밖 AI 호출, 저장 전 재검증과 CAS, 대기 질문의 생성·해제 및 SOL-02~05 연동이 필요하다.
- **근거:** 후보 code·version만으로는 승인되지 않는다. Spring 승인 근거 카탈로그와 검토된 자료가 있어야 실제 근거 답변을 생성할 수 있다.
- **원문과 피해 유형:** 추가 질문 사이 원문을 유지할지와 사기 유형을 검색·근거 검증에 어떻게 전달할지는 팀 결정 후 계약에 반영한다. 질문 원문을 로그에 남기지 않는다.
- **시간 제한:** Spring의 단계별 read timeout은 추가됐지만 전체 요청 기한은 미구현이다. Python 각 단계 시간과 Spring read·전체 기한을 함께 측정하고 설정한다.
- **검증:** Spring과 실제 Python을 연결한 계약 테스트, 승인 자료 및 Gemini의 답변 품질 테스트를 별도로 수행한다.
