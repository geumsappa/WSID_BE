package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** AI-06 요청. 호출자는 필요한 설명만 전달하고 원문을 저장하거나 로그에 남기지 않아요. */
public record SolutionExtractRequest(
        String description, Map<String, String> knownFacts, @JsonIgnore UUID requestId) {

    public static final int MAX_DESCRIPTION_LENGTH = 1_000;
    public static final Set<String> FACT_KEYS =
            Set.of("transferredMoney", "contactContinues", "sharedInformation", "installedApp");
    public static final Set<String> FACT_VALUES = Set.of("YES", "NO", "UNKNOWN");

    public SolutionExtractRequest {
        if (description == null || description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH
                || knownFacts == null || requestId == null) {
            throw new IllegalArgumentException("피해 사실 추출 요청 형식이 올바르지 않아요.");
        }
        knownFacts.forEach((key, value) -> {
            if (key == null || !FACT_KEYS.contains(key) || value == null || !FACT_VALUES.contains(value)) {
                throw new IllegalArgumentException("확인된 사실의 키나 값이 올바르지 않아요.");
            }
        });
        knownFacts = Map.copyOf(knownFacts);
    }

    @Override
    public String toString() {
        return "SolutionExtractRequest[descriptionLength=" + description.length()
                + ", factCount=" + knownFacts.size() + ", requestId=" + requestId + "]";
    }
}
