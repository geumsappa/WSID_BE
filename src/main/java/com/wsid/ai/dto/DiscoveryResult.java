package com.wsid.ai.dto;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscoveryResult(Map<String, Object> facts, String nextQuestion, Boolean canGenerateScenario) {
    public boolean isValid() {
        return facts != null && canGenerateScenario != null && nextQuestion != null
                && !nextQuestion.isBlank() && nextQuestion.length() <= 200;
    }
}
