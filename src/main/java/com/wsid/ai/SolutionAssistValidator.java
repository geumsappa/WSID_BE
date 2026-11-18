package com.wsid.ai;

import com.wsid.ai.dto.SolutionAssistAnswerRequest;
import com.wsid.ai.dto.SolutionAssistAnswerResult;
import com.wsid.ai.dto.SolutionAssistPlanRequest;
import com.wsid.ai.dto.SolutionAssistPlanResult;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.ai.dto.VerifiedReference;
import java.util.stream.Collectors;

/**
 * assist 결과를 요청과 대조해요. 형식이 맞아도 요청 맥락과 어긋나면 결과 전체를 버려요.
 * 근거 후보의 실제 승인 여부·기관명 의미 검증은 여기서 하지 않아요.
 */
public final class SolutionAssistValidator {
    private SolutionAssistValidator() {}

    public static SolutionAssistPlanResult validate(SolutionAssistPlanRequest request, SolutionAssistPlanResult result) {
        if (request == null) {
            throw new IllegalArgumentException("상담 assist PLAN 요청이 필요해요.");
        }
        if (result == null) {
            throw new AiUnavailableException("상담 assist PLAN 결과가 없어요.");
        }
        // 후속 실행은 첫 PLAN의 질문 의도를 유지해야 해요. 원문이 없어 의도를 바꿀 근거가 없어요.
        if (request.questionIntent() != null && result.questionIntent() != null
                && request.questionIntent() != result.questionIntent()) {
            throw new AiUnavailableException("상담 assist 질문 의도가 요청과 달라요.");
        }
        if (result.status() == SolutionAssistStatus.NEED_USER_INPUT) {
            var clarification = result.clarification();
            if (request.remainingClarifications() == 0
                    || !"UNKNOWN".equals(request.facts().get(clarification.factKey()))
                    || request.askedFactKeys().contains(clarification.factKey())) {
                throw new AiUnavailableException("상담 assist 추가 질문이 허용 범위를 벗어났어요.");
            }
            if (SolutionExplanationValidator.containsForbiddenOutput(clarification.content())) {
                throw new AiUnavailableException("상담 assist 추가 질문에 허용되지 않은 출력이 있어요.");
            }
        }
        return result;
    }

    public static SolutionAssistAnswerResult validate(
            SolutionAssistAnswerRequest request, SolutionAssistAnswerResult result) {
        if (request == null) {
            throw new IllegalArgumentException("상담 assist ANSWER 요청이 필요해요.");
        }
        if (result == null) {
            throw new AiUnavailableException("상담 assist ANSWER 결과가 없어요.");
        }
        if (result.status() == SolutionAssistStatus.ANSWERED) {
            var allowed = request.verifiedReferences().stream().map(VerifiedReference::code).collect(Collectors.toSet());
            if (!allowed.containsAll(result.referenceCodes())) {
                throw new AiUnavailableException("상담 assist 답변 근거가 요청과 일치하지 않아요.");
            }
            if (SolutionExplanationValidator.containsForbiddenOutput(result.answer())) {
                throw new AiUnavailableException("상담 assist 답변에 허용되지 않은 출력이 있어요.");
            }
        }
        return result;
    }
}
