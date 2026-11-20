package com.wsid.training.domain;

/**
 * 한 턴을 판단하는 데 필요한 입력. AI 제안에서 규칙에 필요한 값만 뽑아 넘겨요.
 *
 * @param userAction      사용자 행동 분류
 * @param suggestKeyScene AI가 지금 핵심 장면을 꺼내자고 제안했는지
 * @param replyUsable     AI 대사를 그대로 내보내도 되는지 (가드레일 통과, 빈 값 아님)
 */
public record TurnInput(UserAction userAction, boolean suggestKeyScene, boolean replyUsable) {
}
