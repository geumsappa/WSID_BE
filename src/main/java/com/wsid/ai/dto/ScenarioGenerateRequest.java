package com.wsid.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** AI-03 요청. 사실은 값만 전달하고 훈련 도메인 타입에는 의존하지 않아요. */
public record ScenarioGenerateRequest(
        Map<String, String> facts, String fraudTypeCode, @JsonIgnore UUID requestId) {

    public static final Set<String> ALLOWED_KEYS =
            Set.of("activity", "role", "channel", "item", "counterpart");
    public static final Set<String> SUPPORTED_FRAUD_TYPES = Set.of("SECONDHAND_TRADE");

    public ScenarioGenerateRequest {
        if (facts == null || facts.isEmpty()) {
            throw new IllegalArgumentException("사실이 비어 있어요.");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key == null || !ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("허용되지 않은 사실 키예요.");
            }
            if (value == null || value.isBlank() || value.strip().length() > 100) {
                throw new IllegalArgumentException("사실 값이 올바르지 않아요.");
            }
            normalized.put(key, value.strip());
        });
        if (!normalized.containsKey("activity")) {
            throw new IllegalArgumentException("활동 정보가 필요해요.");
        }
        if (fraudTypeCode == null || !SUPPORTED_FRAUD_TYPES.contains(fraudTypeCode)) {
            throw new IllegalArgumentException("지원하지 않는 유형 코드예요.");
        }
        if (requestId == null) {
            throw new IllegalArgumentException("요청 ID가 필요해요.");
        }
        facts = Map.copyOf(normalized);
    }

    @Override
    public String toString() {
        return "ScenarioGenerateRequest[keys=" + facts.keySet()
                + ", fraudTypeCode=" + fraudTypeCode + ", requestId=" + requestId + "]";
    }
}
