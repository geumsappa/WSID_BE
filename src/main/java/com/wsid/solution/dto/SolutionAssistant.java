package com.wsid.solution.dto;

import com.wsid.ai.dto.SolutionAssistReason;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.solution.domain.PendingAssist;
import java.util.List;
import java.util.UUID;

public record SolutionAssistant(SolutionAssistStatus status, String answer, List<String> referenceCodes,
        Question question, SolutionAssistReason reasonCode) {
    public SolutionAssistant { referenceCodes = List.copyOf(referenceCodes); }
    public record Question(UUID questionId, String factKey, String content, List<String> options) {}
    /**
     * 네 사실 질문은 서버 고정 문구를, 조건 질문(factKey=null)은 답할 때까지 보관한 검증된 AI 문장을 보여 줘요.
     * 처음 응답과 다시 조회할 때 같은 문장이라 같은 questionId의 YES/NO 뜻이 바뀌지 않아요.
     */
    public static SolutionAssistant pending(PendingAssist p) {
        String content = p.asksCondition() ? p.conditionContent() : p.fact().question();
        return new SolutionAssistant(SolutionAssistStatus.NEED_USER_INPUT, null, List.of(),
                new Question(p.questionId(), p.asksCondition() ? null : p.fact().key(), content,
                        List.of("YES", "NO", "UNKNOWN")), null);
    }
    public static SolutionAssistant unable(SolutionAssistReason reason) {
        return unable(reason, null);
    }

    public static SolutionAssistant unable(SolutionAssistReason reason, String guidance) {
        String text = switch (reason) {
            case OUT_OF_SCOPE -> (guidance == null ? "" : guidance + " ")
                    + "피해가 의심되는 문자나 전화가 있었다면 어떤 연락을 받았는지 알려 주세요.";
            case LIMIT_REACHED -> "지금 확인한 내용으로는 더 구체적으로 안내하기 어려워요. 상황이 달라지면 새 질문으로 알려 주세요.";
            case NO_EVIDENCE -> guidance == null
                    ? "이 상황에 맞는 절차를 확인하기 어려워요. 관련 대응 카드나 공식 기관 상담을 확인해 주세요."
                    : "상황에 맞는 절차는 확인이 더 필요해요. " + guidance;
        };
        return new SolutionAssistant(SolutionAssistStatus.UNABLE_TO_ANSWER, text, List.of(), null, reason);
    }
    @Override public String toString() { return "SolutionAssistant[status=" + status + ", citations=" + referenceCodes.size() + "]"; }
}
