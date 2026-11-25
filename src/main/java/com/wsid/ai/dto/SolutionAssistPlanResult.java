package com.wsid.ai.dto;

import java.util.HashSet;
import java.util.List;

/**
 * PLAN 결과. 상태별 필수/금지 필드만 형식으로 검사해요. 요청 사실·이미 물은 항목·질문 의도 일치는
 * {@code SolutionAssistValidator}가 요청과 대조해요.
 */
public record SolutionAssistPlanResult(
        SolutionAssistStatus status, SolutionAssistIntent questionIntent, SolutionAssistClarification clarification,
        List<SolutionCandidateReference> candidateReferences, SolutionAssistReason reasonCode, String guidance) {

    public SolutionAssistPlanResult(SolutionAssistStatus status, SolutionAssistIntent questionIntent,
            SolutionAssistClarification clarification, List<SolutionCandidateReference> candidateReferences,
            SolutionAssistReason reasonCode) {
        this(status, questionIntent, clarification, candidateReferences, reasonCode, null);
    }

    public SolutionAssistPlanResult {
        if (status == null || candidateReferences == null
                || candidateReferences.size() > SolutionExplainRequest.MAX_REFERENCES) {
            throw new IllegalArgumentException("상담 assist PLAN 결과 형식이 올바르지 않아요.");
        }
        boolean valid = switch (status) {
            case NEED_USER_INPUT -> questionIntent != null && clarification != null
                    && candidateReferences.isEmpty() && reasonCode == null;
            case EVIDENCE_READY -> questionIntent != null && clarification == null
                    && !candidateReferences.isEmpty() && reasonCode == null;
            case UNABLE_TO_ANSWER -> clarification == null && candidateReferences.isEmpty() && reasonCode != null;
            case ANSWERED -> false;
        };
        var codes = new HashSet<String>();
        for (SolutionCandidateReference reference : candidateReferences) {
            if (reference == null || !codes.add(reference.code())) {
                valid = false;
            }
        }
        if (!valid || (guidance != null && (status != SolutionAssistStatus.UNABLE_TO_ANSWER
                || reasonCode == SolutionAssistReason.LIMIT_REACHED || guidance.isBlank() || guidance.length() > 240))) {
            throw new IllegalArgumentException("상담 assist PLAN 상태와 필드가 맞지 않아요.");
        }
        candidateReferences = List.copyOf(candidateReferences);
    }

    @Override
    public String toString() {
        return "SolutionAssistPlanResult[status=" + status + ", questionIntent=" + questionIntent
                + ", clarification=" + clarification + ", candidateCount=" + candidateReferences.size()
                + ", reasonCode=" + reasonCode + "]";
    }
}
