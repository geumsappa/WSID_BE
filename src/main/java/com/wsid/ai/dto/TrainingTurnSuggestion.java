package com.wsid.ai.dto;

/**
 * AI 서비스가 돌려주는 제안. 그대로 믿지 않고 백엔드 규칙을 거쳐요.
 *
 * @param userAction      사용자 행동 분류 코드. 허용 목록 밖의 값이면 UNCLEAR로 처리해요.
 * @param reply           상대 역할의 다음 대사
 * @param suggestKeyScene 지금 핵심 장면을 꺼내자는 제안
 * @param guardrailPassed 생성 대사가 출력 검사를 통과했는지
 * @param realIncident    사용자가 실제 피해를 말한 것으로 보이는지
 */
public record TrainingTurnSuggestion(
        String userAction,
        String reply,
        boolean suggestKeyScene,
        boolean guardrailPassed,
        boolean realIncident) {

    /** AI를 쓸 수 없을 때 쓰는 빈 제안. 백엔드가 검토된 문구로 이어가요. */
    public static TrainingTurnSuggestion unavailable() {
        return new TrainingTurnSuggestion(null, null, false, false, false);
    }

    /** 화면에 그대로 내보내도 되는 대사인지. */
    public boolean replyUsable() {
        return guardrailPassed && reply != null && !reply.isBlank();
    }
}
