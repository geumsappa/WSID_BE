package com.wsid.training.service;
import static org.junit.jupiter.api.Assertions.*;
import com.wsid.common.error.ApiException;
import org.junit.jupiter.api.Test;
class TrainingGeneratedTextTest {
    @Test void adviceAboutPasswordsIsNotAnActualPassword() {
        assertDoesNotThrow(() -> TrainingWorkflow.safeGenerated("비밀번호는 다른 사람에게 알려주지 마세요.", 1000));
        assertDoesNotThrow(() -> TrainingWorkflow.safeGenerated("인증번호는 공식 채널에서만 사용하세요.", 1000));
    }
    @Test void actualValuesAndLinksRemainBlocked() {
        for (String text : new String[]{"비밀번호: TestPass123!", "010-1234-5678", "4111111111111111", "https://example.org"})
            assertThrows(ApiException.class, () -> TrainingWorkflow.safeGenerated(text, 1000));
    }
    @Test void discoveryReceiptCannotHideUnsafeProseOrMalformedData() {
        var json = new tools.jackson.databind.ObjectMapper();
        String receipt = "eJwABC12." + "a".repeat(20) + "123456789012" + "b".repeat(32);
        var facts = json.createObjectNode().put("preparedScenario", receipt).put("detail", "노트북 판매");
        assertDoesNotThrow(() -> TrainingWorkflow.validateDiscoveryFacts(facts));
        assertEquals(receipt, facts.path("preparedScenario").asString());
        for (String unsafe : new String[]{"010-1234-5678", "https://example.org", "비밀번호: TestPass123!"}) {
            facts.put("detail", unsafe);
            assertThrows(ApiException.class, () -> TrainingWorkflow.validateDiscoveryFacts(facts));
        }
        facts.remove("detail");
        for (String invalid : new String[]{"", "010-1234-5678", "https://example.org", "eJw.not-a-signature"}) {
            facts.put("preparedScenario", invalid);
            assertThrows(ApiException.class, () -> TrainingWorkflow.validateDiscoveryFacts(facts));
        }
        facts.set("preparedScenario", json.createObjectNode().put("secret", "010-1234-5678"));
        assertThrows(ApiException.class, () -> TrainingWorkflow.validateDiscoveryFacts(facts));
    }
    @Test void completeDiscoveryFactsStillHaveASizeLimitIncludingReceipt() {
        var facts = new tools.jackson.databind.ObjectMapper().createObjectNode();
        facts.put("preparedScenario", "a".repeat(14000) + "." + "b".repeat(64));
        assertThrows(ApiException.class, () -> TrainingWorkflow.validateDiscoveryFacts(facts));
        facts.put("preparedScenario", "a".repeat(12000) + "." + "b".repeat(64));
        facts.put("detail", "가".repeat(5000));
        assertThrows(ApiException.class, () -> TrainingWorkflow.validateDiscoveryFacts(facts));
    }
}
