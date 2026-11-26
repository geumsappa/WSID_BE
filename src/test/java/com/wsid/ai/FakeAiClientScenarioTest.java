package com.wsid.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.ai.dto.ScenarioGenerateRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeAiClientScenarioTest {
    private final FakeAiClient client = new FakeAiClient();

    @Test
    void personalizesKnownRolesAndIsDeterministic() {
        var seller = request(Map.of("activity", "중고거래", "role", "판매자"));
        var buyer = request(Map.of("activity", "중고거래", "role", "구매자"));
        assertEquals(client.generateScenario(seller), client.generateScenario(seller));
        assertTrue(client.generateScenario(seller).scenario().description().contains("판매"));
        assertTrue(client.generateScenario(buyer).scenario().description().contains("구매"));
        assertEquals("BANK_NOTICE", client.generateScenario(seller).mockPageTemplateCode());
    }

    @Test
    void missingOrUnrecognizedRoleUsesNeutralTextAndNeverEchoesInput() {
        String instruction = "https://private.invalid/ 사용자 원문";
        var result = client.generateScenario(request(Map.of("activity", instruction, "role", instruction)));
        assertEquals(client.generateScenario(request(Map.of("activity", "중고거래"))), result);
        assertFalse(result.scenario().description().contains(instruction));
        assertFalse(result.toString().contains(result.scenario().title()));
        assertFalse(result.toString().contains(result.scenario().description()));
        assertFalse(result.scenario().toString().contains(result.scenario().description()));
    }

    private ScenarioGenerateRequest request(Map<String, String> facts) {
        return new ScenarioGenerateRequest(facts, "SECONDHAND_TRADE", UUID.randomUUID());
    }
}
