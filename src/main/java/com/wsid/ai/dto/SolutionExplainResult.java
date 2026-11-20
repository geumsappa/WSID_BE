package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashSet;
import java.util.List;

/** AI-07 설명 제안. 요청 근거와의 대조·출력 검사는 클라이언트에서 추가 수행해요. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SolutionExplainResult(String explanation, List<String> referenceCodes) {
    public static final int MAX_EXPLANATION_LENGTH = 2_000;

    public SolutionExplainResult {
        if (explanation == null || explanation.isBlank() || explanation.length() > MAX_EXPLANATION_LENGTH
                || referenceCodes == null || referenceCodes.size() > SolutionExplainRequest.MAX_REFERENCES) {
            throw new IllegalArgumentException("대응 설명 결과 형식이 올바르지 않아요.");
        }
        var codes = new HashSet<String>();
        for (String code : referenceCodes) {
            if (!VerifiedReference.isValidCode(code) || !codes.add(code)) {
                throw new IllegalArgumentException("설명 근거 코드가 올바르지 않거나 중복돼요.");
            }
        }
        referenceCodes = List.copyOf(referenceCodes);
    }

    @Override
    public String toString() {
        return "SolutionExplainResult[explanationLength=" + explanation.length()
                + ", referenceCount=" + referenceCodes.size() + "]";
    }
}
