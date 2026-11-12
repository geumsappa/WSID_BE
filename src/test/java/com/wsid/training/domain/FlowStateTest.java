package com.wsid.training.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlowStateTest {

    @Test
    @DisplayName("저장한 JSON을 다시 읽으면 같은 상태다")
    void roundTrip() {
        assertEquals(FlowState.initial(), FlowState.fromJson(FlowState.initial().toJson()));
        assertEquals(new FlowState(true), FlowState.fromJson(new FlowState(true).toJson()));
    }

    @Test
    @DisplayName("DB가 돌려주는 공백이 들어간 JSONB 형식도 읽는다")
    void readsPostgresJsonbFormat() {
        assertTrue(FlowState.fromJson("{\"keySceneShown\": true}").keySceneShown());
        assertFalse(FlowState.fromJson("{\"keySceneShown\": false}").keySceneShown());
    }

    @Test
    @DisplayName("빈 JSON이나 값이 없으면 초기 상태로 본다")
    void emptyMeansInitial() {
        assertFalse(FlowState.fromJson("{}").keySceneShown());
        assertFalse(FlowState.fromJson(null).keySceneShown());
    }
}
