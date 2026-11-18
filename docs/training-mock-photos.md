# 훈련 중 가상 사진 제공

사진 요청은 실제 파일을 받지 않는다. 신분증·통장·일반 사진은 FE가 직접 그린 고정 가상 이미지로 표시하고, Spring과 외부 AI에는 제공 행동만 전달한다. 파일 선택, 촬영, 업로드, base64, 이미지 URL을 쓰지 않는다. 상황 파악 단계의 기존 OCR 업로드와는 별도 기능이다.

## 선택지와 메시지

AI 대사에 자료 제공 요청이 있으면 TRN-07 `availableOptions`, TRN-08 `reply.options`, TRN-03 `availableOptions`에 다음 선택지가 포함된다. 신분증과 통장을 같이 요구하면 각각의 버튼을 제공한다. 거절과 공식 채널 확인 선택지는 유지한다.

```json
{
  "code": "SEND_MOCK_ID_CARD",
  "label": "가상 신분증 보내기",
  "mockAttachment": {"type": "ID_CARD", "title": "훈련용 가상 신분증 사진"}
}
```

지원 코드: `SEND_MOCK_ID_CARD` / `ID_CARD`, `SEND_MOCK_BANKBOOK` / `BANKBOOK`, `SEND_MOCK_PHOTO` / `PHOTO`.

TRN-08 요청은 기존 선택지 계약을 사용한다. 세션 쿠키와 CSRF 헤더도 기존과 같다.

```json
{
  "requestId": "프론트에서 생성한 UUID",
  "stateVersion": 3,
  "inputKind": "CHOICE",
  "selectedCode": "SEND_MOCK_ID_CARD"
}
```

현재 턴에서 서버가 제시하지 않은 자료 코드, 오래된 상태, 중복 요청은 기존 검증으로 거부한다. 가상 자료도 응답 실패 시 전송 표시를 제거한다.

## AI와 저장

Spring은 `userInput: "[가상 신분증 사진 제공]"` 같은 고정 사건 문구만 AI-04에 보낸다. AI는 이를 정보 제공 행동으로 판정한다. Spring도 선택 기록을 기준으로 `RISKY_RESPONSE`, `SENSITIVE_INFO_SHARED`, `SEND_MOCK_ID_CARD`를 보장한다.

사진을 보내겠다는 말만 했을 때는 사진 제공으로 기록하지 않는다. 외부 AI는 파일이 없다고 실제 파일 업로드를 다시 요구하지 않는다.

TRN-03의 사용자 메시지에는 `mockAttachment`가 포함되어 새로고침 시 같은 가상 이미지가 복원된다. DB에는 고정 사건 문구와 선택 코드만 저장한다.

종료 시 선택 코드는 AI-05 `actions`에 전달된다. AI 평가와 Spring 보고서 검증 모두 사진 제공 요청에 응한 위험 행동을 개선점에 반영한다. 가상 모드에서도 같은 행동이 리포트에 남는다.

## 확인 방법

1. 새 시나리오에서 신분증 또는 사진 제공 요청을 받는다.
2. 해당 가상 자료 보내기 버튼을 누른다. 파일 선택 창 없이 훈련용 이미지가 나온다.
3. 네트워크의 `/messages` 요청에 `selectedCode`만 있고 파일·이미지 값이 없는지 확인한다.
4. 대화가 이어지는지, 새로고침 후 가상 이미지가 복원되는지 확인한다.
5. 훈련을 종료하고 리포트의 개선점에 사진 제공 행동이 있는지 확인한다.
