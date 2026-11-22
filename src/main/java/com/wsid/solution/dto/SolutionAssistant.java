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
    public static SolutionAssistant pending(PendingAssist p) {
        return new SolutionAssistant(SolutionAssistStatus.NEED_USER_INPUT, null, List.of(),
                new Question(p.questionId(), p.fact().key(), p.fact().question(), List.of("YES", "NO", "UNKNOWN")), null);
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
