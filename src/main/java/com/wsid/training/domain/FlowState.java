package com.wsid.training.domain;

import tools.jackson.databind.ObjectMapper;

/** JSONB 진행 상태. 기존 데이터의 누락 필드에는 기본값을 적용해요. */
public record FlowState(boolean keySceneShown, DiscoveryStatus discoveryStatus) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public FlowState(boolean keySceneShown) { this(keySceneShown, DiscoveryStatus.ASKING); }
    public FlowState {
        if (discoveryStatus == null) discoveryStatus = DiscoveryStatus.ASKING;
    }
    public static FlowState initial() { return new FlowState(false); }
    public FlowState withKeySceneShown() { return new FlowState(true, discoveryStatus); }
    public FlowState withDiscoveryStatus(DiscoveryStatus status) { return new FlowState(keySceneShown, status); }
    public String toJson() { return JSON.writeValueAsString(this); }
    public static FlowState fromJson(String json) {
        if (json == null) return initial();
        var node = JSON.readTree(json);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("진행 상태 JSON이 올바르지 않아요.");
        var key = node.get("keySceneShown");
        var status = node.get("discoveryStatus");
        if (key != null && !key.isBoolean() && !key.isNull()) throw new IllegalArgumentException("핵심 장면 상태가 올바르지 않아요.");
        if (status != null && !status.isTextual() && !status.isNull()) throw new IllegalArgumentException("상황 파악 상태가 올바르지 않아요.");
        return new FlowState(key != null && key.asBoolean(), status == null || status.isNull()
                ? DiscoveryStatus.ASKING : DiscoveryStatus.valueOf(status.asText()));
    }
}
