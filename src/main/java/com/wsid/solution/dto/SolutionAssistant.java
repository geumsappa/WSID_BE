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
        String text = switch (reason) {
            case OUT_OF_SCOPE -> "피해 대응과 관련된 질문을 입력해 주세요.";
            case LIMIT_REACHED -> "현재 확인된 정보로는 추가 안내가 어려워요.";
            case NO_EVIDENCE -> "확인된 근거가 부족해 답변을 제공하기 어려워요. 검토된 대응 카드를 확인해 주세요.";
        };
        return new SolutionAssistant(SolutionAssistStatus.UNABLE_TO_ANSWER, text, List.of(), null, reason);
    }
    @Override public String toString() { return "SolutionAssistant[status=" + status + ", citations=" + referenceCodes.size() + "]"; }
}
