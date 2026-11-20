package com.wsid.ai;

import com.wsid.ai.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Defaults to the common AI mode. Optional override only affects assist. */
@Component
public class SolutionAssistGateway {
    private final AiClient delegate;
    public SolutionAssistGateway(AiClient common, AiProperties p,
            @Value("${wsid.ai.assist-mode:shared}") String mode,
            @Value("${wsid.ai.assist-base-url:${wsid.ai.base-url}}") String url,
            @Value("${wsid.ai.assist-api-key:${wsid.ai.api-key}}") String key) {
        delegate = switch (mode) {
            case "shared" -> common;
            case "fake" -> new FakeAiClient();
            case "http" -> new HttpAiClient(new AiProperties("http", url, key, p.connectTimeout(),
                    p.readTimeout(), p.scenarioReadTimeout(), p.evaluationReadTimeout(), p.assistReadTimeout()));
            default -> throw new IllegalArgumentException("assist-mode must be shared, http or fake");
        };
    }
    public SolutionAssistPlanResult plan(SolutionAssistPlanRequest request) {
        return SolutionAssistValidator.validate(request, delegate.planSolutionAssist(request));
    }
    public SolutionAssistAnswerResult answer(SolutionAssistAnswerRequest request) {
        return SolutionAssistValidator.validate(request, delegate.answerSolutionAssist(request));
    }
}
