package com.wsid.training.domain;

import tools.jackson.databind.ObjectMapper;

/** Shared JSONB state for discovery and simulation; missing legacy fields use defaults. */
public record FlowState(boolean keySceneShown, DiscoveryStatus discoveryStatus, boolean finishSuggested) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public FlowState(boolean keySceneShown) { this(keySceneShown, DiscoveryStatus.ASKING, false); }
    public FlowState(boolean keySceneShown, DiscoveryStatus status) { this(keySceneShown, status, false); }
    public FlowState(boolean keySceneShown, boolean finishSuggested) { this(keySceneShown, DiscoveryStatus.ASKING, finishSuggested); }
    public FlowState { if (discoveryStatus == null) discoveryStatus = DiscoveryStatus.ASKING; }
    public static FlowState initial() { return new FlowState(false); }
    public FlowState withKeySceneShown() { return new FlowState(true, discoveryStatus, finishSuggested); }
    public FlowState withDiscoveryStatus(DiscoveryStatus status) { return new FlowState(keySceneShown, status, finishSuggested); }
    public FlowState withSimulation(boolean keyScene, boolean finish) { return new FlowState(keyScene, discoveryStatus, finish); }
    public String toJson() { return JSON.writeValueAsString(this); }
    public static FlowState fromJson(String json) {
        if (json == null) return initial();
        var node = JSON.readTree(json);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("진행 상태 JSON이 올바르지 않아요.");
        var key = node.get("keySceneShown");
        var status = node.get("discoveryStatus");
        var finish = node.get("finishSuggested");
        if (key != null && !key.isBoolean() && !key.isNull()) throw new IllegalArgumentException("핵심 장면 상태가 올바르지 않아요.");
        if (status != null && !status.isTextual() && !status.isNull()) throw new IllegalArgumentException("상황 파악 상태가 올바르지 않아요.");
        if (finish != null && !finish.isBoolean() && !finish.isNull()) throw new IllegalArgumentException("종료 제안 상태가 올바르지 않아요.");
        return new FlowState(key != null && key.asBoolean(), status == null || status.isNull()
                ? DiscoveryStatus.ASKING : DiscoveryStatus.valueOf(status.asText()), finish != null && finish.asBoolean());
    }
}
