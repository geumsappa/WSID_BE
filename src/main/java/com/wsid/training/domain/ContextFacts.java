package com.wsid.training.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** 사용자 사실만 저장해요. 허용 키 밖의 값과 중첩·비문자열 값은 사용하지 않아요. */
public record ContextFacts(Map<String, String> values) {
    public static final Set<String> ALLOWED = Set.of("activity", "role", "channel", "item", "counterpart");
    private static final ObjectMapper JSON = new ObjectMapper();
    public ContextFacts { values = Map.copyOf(values); }
    public static ContextFacts empty() { return new ContextFacts(Map.of()); }
    public static ContextFacts fromJson(String json) {
        if (json == null || json.isBlank()) return empty();
        var node = JSON.readTree(json);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("사실 JSON이 올바르지 않아요.");
        Map<String, Object> raw = new LinkedHashMap<>();
        node.properties().forEach(e -> { if (e.getValue().isTextual()) raw.put(e.getKey(), e.getValue().asText()); });
        return empty().merge(raw);
    }
    public ContextFacts merge(Map<String, ?> proposed) {
        Map<String, String> merged = new LinkedHashMap<>(values);
        if (proposed != null) proposed.forEach((key, value) -> {
            if (ALLOWED.contains(key) && value instanceof String text) {
                String trimmed = text.strip();
                if (!trimmed.isEmpty() && trimmed.length() <= 100) merged.put(key, trimmed);
            }
        });
        return new ContextFacts(merged);
    }
    public boolean hasAll(List<String> keys) { return keys.stream().allMatch(values::containsKey); }
    public String toJson() { return JSON.writeValueAsString(values); }
}
