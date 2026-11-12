package com.wsid.training.domain;

/**
 * training_sessions.flow_state_json에 저장하는 진행 상태.
 * 지금은 "핵심 장면을 이미 보여줬는지" 하나뿐이라 JSON 문자열을 직접 다뤄요.
 * 항목이 늘어나면 JSON 라이브러리로 변환하도록 이 클래스만 바꾸면 돼요.
 */
public record FlowState(boolean keySceneShown) {

    public static FlowState initial() {
        return new FlowState(false);
    }

    public FlowState withKeySceneShown() {
        return new FlowState(true);
    }

    public String toJson() {
        return "{\"keySceneShown\":" + keySceneShown + "}";
    }

    /** DB가 돌려주는 JSONB는 {"keySceneShown": true}처럼 공백이 들어가므로 공백을 빼고 읽어요. */
    public static FlowState fromJson(String json) {
        if (json == null) {
            return initial();
        }
        String compact = json.replaceAll("\\s", "");
        return new FlowState(compact.contains("\"keySceneShown\":true"));
    }
}
