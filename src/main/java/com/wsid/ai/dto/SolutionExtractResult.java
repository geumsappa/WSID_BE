package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** AI-06 제안 결과. 사용자 확정값과의 충돌 해결·카드 선택·저장은 호출자가 수행해요. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SolutionExtractResult(Map<String, String> facts, List<String> uncertainFields, String fraudType) {

    public SolutionExtractResult(Map<String, String> facts, List<String> uncertainFields) {
        this(facts, uncertainFields, null);
    }

    public SolutionExtractResult {
        if (facts == null || !facts.keySet().equals(SolutionExtractRequest.FACT_KEYS)
                || facts.values().stream().anyMatch(value -> value == null || !SolutionExtractRequest.FACT_VALUES.contains(value))
                || uncertainFields == null || uncertainFields.size() > SolutionExtractRequest.FACT_KEYS.size()
                || uncertainFields.stream().anyMatch(key -> key == null || !SolutionExtractRequest.FACT_KEYS.contains(key))) {
            throw new IllegalArgumentException("피해 사실 추출 결과 형식이 올바르지 않아요.");
        }
        Set<String> unknown = facts.entrySet().stream().filter(entry -> "UNKNOWN".equals(entry.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> uncertain = new HashSet<>(uncertainFields);
        if (uncertain.size() != uncertainFields.size() || !uncertain.equals(unknown)) {
            throw new IllegalArgumentException("미확정 항목과 사실 값이 일치하지 않아요.");
        }
        facts = Map.copyOf(facts);
        uncertainFields = List.copyOf(uncertainFields);
        fraudType = SolutionFraudType.orNull(fraudType);
    }

    @Override
    public String toString() {
        return "SolutionExtractResult[factCount=" + facts.size() + ", uncertainCount=" + uncertainFields.size() + ", fraudType=" + fraudType + "]";
    }
}
