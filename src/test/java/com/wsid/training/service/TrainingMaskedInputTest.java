package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** AI가 돌려준 가린 문장(maskedInput)을 저장용으로 쓸 수 있는지 확인해요. */
class TrainingMaskedInputTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void usesMaskedTextFromAi() {
        var node = json.readTree("{\"maskedInput\":\"[이름, 전화번호, 주소 입력함]\"}").path("maskedInput");
        assertEquals("[이름, 전화번호, 주소 입력함]", TrainingWorkflow.maskedInputOf(node));
    }

    @Test
    void masksNumbersThatSlipThrough() {
        var node = json.readTree("{\"maskedInput\":\"박선홍 010-1234-5678\"}").path("maskedInput");
        assertFalse(TrainingWorkflow.maskedInputOf(node).contains("1234"));
    }

    @Test
    void ignoresMissingBlankOrLinkText() {
        assertNull(TrainingWorkflow.maskedInputOf(json.readTree("{}").path("maskedInput")));
        assertNull(TrainingWorkflow.maskedInputOf(json.readTree("{\"maskedInput\":\"  \"}").path("maskedInput")));
        assertNull(TrainingWorkflow.maskedInputOf(json.readTree("{\"maskedInput\":\"https://example.com\"}").path("maskedInput")));
    }
}
