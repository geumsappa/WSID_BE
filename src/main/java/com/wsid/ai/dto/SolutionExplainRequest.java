package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** AI-07 요청. 사실과 필요한 승인 문단만 전달하며 상담 엔티티를 참조하지 않아요. */
public record SolutionExplainRequest(
        Map<String, String> facts, List<VerifiedReference> verifiedReferences, @JsonIgnore UUID requestId) {
    public static final int MAX_REFERENCES = 10;

    public SolutionExplainRequest {
        if (facts == null || facts.isEmpty() || verifiedReferences == null
                || verifiedReferences.size() > MAX_REFERENCES || requestId == null) {
            throw new IllegalArgumentException("대응 설명 요청 형식이 올바르지 않아요.");
        }
        facts.forEach((key, value) -> {
            if (key == null || !SolutionExtractRequest.FACT_KEYS.contains(key)
                    || value == null || !SolutionExtractRequest.FACT_VALUES.contains(value)) {
                throw new IllegalArgumentException("설명할 사실의 키나 값이 올바르지 않아요.");
            }
        });
        var codes = new HashSet<String>();
        for (VerifiedReference reference : verifiedReferences) {
            if (reference == null || !codes.add(reference.code())) {
                throw new IllegalArgumentException("설명 근거가 없거나 코드가 중복돼요.");
            }
        }
        facts = Map.copyOf(facts);
        verifiedReferences = List.copyOf(verifiedReferences);
    }

    @Override
    public String toString() {
        return "SolutionExplainRequest[factCount=" + facts.size() + ", referenceCount=" + verifiedReferences.size()
                + ", requestId=" + requestId + "]";
    }
}
