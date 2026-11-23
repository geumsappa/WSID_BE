package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** AI가 돌려준 훈련 상태(trainingState)를 다음 턴에 돌려줄 수 있는지 확인해요. */
class TrainingStateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void keepsSmallStateObject() {
        var node = json.readTree("{\"trainingState\":{\"facts\":[\"재배송 기한: 오늘 오후 6시\"],\"lastMove\":\"신뢰\",\"scene\":1,\"trust\":1,\"push\":0,\"requestMade\":false}}")
                .path("trainingState");
        assertEquals("신뢰", TrainingWorkflow.trainingStateOf(node).path("lastMove").asString());
    }

    @Test
    void dropsMissingOrLinkState() {
        assertNull(TrainingWorkflow.trainingStateOf(json.readTree("{}").path("trainingState")));
        assertNull(TrainingWorkflow.trainingStateOf(json.readTree("{\"trainingState\":\"text\"}").path("trainingState")));
        assertNull(TrainingWorkflow.trainingStateOf(json.readTree("{\"trainingState\":{\"facts\":[\"https://example.com\"]}}").path("trainingState")));
    }
}
