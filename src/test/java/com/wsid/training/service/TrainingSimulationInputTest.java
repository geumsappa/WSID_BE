package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import com.wsid.common.error.ApiException;
import org.junit.jupiter.api.Test;

class TrainingSimulationInputTest {
    @Test
    void acceptsCardSubmissionAndMasksItBeforeAiOrStorage() {
        assertEquals("카드번호는 [카드번호] 입니다", TrainingWorkflow.simulationInput("카드번호는 4111 1111 1111 1111 입니다"));
        assertEquals("[카드번호]", TrainingWorkflow.simulationInput("4111111111111111"));
        assertEquals("[카드번호]", TrainingWorkflow.simulationInput("4111-1111-1111-1111"));
    }

    @Test
    void masksOtherDisclosureTypesWithoutBlockingTheTurn() {
        assertEquals("[주민등록번호]", TrainingWorkflow.simulationInput("900101-1234567"));
        assertEquals("[계좌번호]", TrainingWorkflow.simulationInput("110-123-456789"));
        assertEquals("[전화번호]", TrainingWorkflow.simulationInput("010-1234-5678"));
        assertEquals("[이메일]", TrainingWorkflow.simulationInput("test@example.org"));
        assertEquals("[비밀번호]", TrainingWorkflow.simulationInput("비밀번호는 TestPass123!"));
        assertEquals("[인증정보]", TrainingWorkflow.simulationInput("인증번호: 123456"));
        assertEquals("[민감정보]", TrainingWorkflow.simulationInput("123456"));
    }

    @Test
    void leavesRefusalAndOrdinaryAmountsAlone() {
        assertEquals("카드번호는 알려드릴 수 없어요", TrainingWorkflow.simulationInput("카드번호는 알려드릴 수 없어요"));
        assertEquals("가격은 15000원인가요?", TrainingWorkflow.simulationInput("가격은 15000원인가요?"));
    }

    @Test
    void stillRejectsEmptyOversizedInputAndLiveLinks() {
        for (String text : new String[]{" ", "x".repeat(1001), "https://example.org"}) {
            assertThrows(ApiException.class, () -> TrainingWorkflow.simulationInput(text));
        }
    }
}
