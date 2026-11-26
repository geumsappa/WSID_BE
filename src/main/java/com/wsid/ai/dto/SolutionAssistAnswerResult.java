package com.wsid.ai.dto;

import java.util.HashSet;
import java.util.List;

/** ANSWER 결과. 요청 근거와의 대조·금지 출력 검사는 {@code SolutionAssistValidator}가 해요. */
public record SolutionAssistAnswerResult(
        SolutionAssistStatus status, String answer, List<String> referenceCodes, SolutionAssistReason reasonCode,
        String guidance) {
    public static final int MAX_ANSWER_LENGTH = 2_000;

    public SolutionAssistAnswerResult(SolutionAssistStatus status, String answer, List<String> referenceCodes,
            SolutionAssistReason reasonCode) {
        this(status, answer, referenceCodes, reasonCode, null);
    }

    public SolutionAssistAnswerResult {
        if (status == null || referenceCodes == null
                || referenceCodes.size() > SolutionExplainRequest.MAX_REFERENCES) {
            throw new IllegalArgumentException("상담 assist ANSWER 결과 형식이 올바르지 않아요.");
        }
        boolean valid = switch (status) {
            case ANSWERED -> answer != null && !answer.isBlank() && answer.length() <= MAX_ANSWER_LENGTH
                    && !referenceCodes.isEmpty() && reasonCode == null;
            case UNABLE_TO_ANSWER -> answer == null && referenceCodes.isEmpty()
                    && reasonCode != null && reasonCode != SolutionAssistReason.LIMIT_REACHED;
            case NEED_USER_INPUT, EVIDENCE_READY -> false;
        };
        var codes = new HashSet<String>();
        for (String code : referenceCodes) {
            if (!VerifiedReference.isValidCode(code) || !codes.add(code)) {
                valid = false;
            }
        }
        if (!valid || (guidance != null && (status != SolutionAssistStatus.UNABLE_TO_ANSWER
                || guidance.isBlank() || guidance.length() > 240))) {
            throw new IllegalArgumentException("상담 assist ANSWER 상태와 필드가 맞지 않아요.");
        }
        referenceCodes = List.copyOf(referenceCodes);
    }

    @Override
    public String toString() {
        return "SolutionAssistAnswerResult[status=" + status + ", answerLength=" + (answer == null ? 0 : answer.length())
                + ", referenceCount=" + referenceCodes.size() + ", reasonCode=" + reasonCode + "]";
    }
}
