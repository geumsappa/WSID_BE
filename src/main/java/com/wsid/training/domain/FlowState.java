package com.wsid.training.domain;

/**
 * training_sessions.flow_state_json에 저장하는 진행 상태.
 * 핵심 장면 노출과 최종 평가 요청 가능 여부를 기록해요.
 */
public record FlowState(boolean keySceneShown, boolean finishSuggested) {

    public FlowState(boolean keySceneShown) {
        this(keySceneShown, false);
    }

    public static FlowState initial() {
        return new FlowState(false);
    }

    public FlowState withKeySceneShown() {
        return new FlowState(true, finishSuggested);
    }

    public String toJson() {
        return "{\"keySceneShown\":" + keySceneShown + ",\"finishSuggested\":" + finishSuggested + "}";
    }

    /** DB가 돌려주는 JSONB는 {"keySceneShown": true}처럼 공백이 들어가므로 공백을 빼고 읽어요. */
    public static FlowState fromJson(String json) {
        if (json == null) {
            return initial();
        }
        String compact = json.replaceAll("\\s", "");
        return new FlowState(compact.contains("\"keySceneShown\":true"), compact.contains("\"finishSuggested\":true"));
    }
}
