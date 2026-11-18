package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class TrainingScenarioValidationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsCatalogSourceLinksAndPublicationDatesWithoutChangingStoredReferences() {
        ObjectNode scenario = scenario();
        var reference = scenario.putArray("basedOnCases").addObject()
                .put("caseId", "case-1").put("title", "Public safety notice")
                .put("sourceDate", "2025-10-15").put("sourceUrl", "https://example.org/notices/1");
        String original = scenario.toString();
        assertDoesNotThrow(() -> TrainingWorkflow.validateScenarioContent(scenario));
        assertEquals(original, scenario.toString());
        assertEquals("https://example.org/notices/1", reference.path("sourceUrl").asString());
    }

    @Test
    void stillRejectsLiveLinksInDialogueAndOtherScenarioContent() {
        for (String field : new String[]{"openingMessage", "background", "description", "unexpectedField"}) {
            var scenario = scenario().put(field, "Visit https://example.org");
            assertThrows(ApiException.class, () -> TrainingWorkflow.validateScenarioContent(scenario));
        }
    }

    @Test
    void stillRejectsPersonalDataInNestedScenes() {
        var scenario = scenario();
        scenario.putArray("keyScenes").addObject().put("goal", "010-1234-5678");
        assertThrows(ApiException.class, () -> TrainingWorkflow.validateScenarioContent(scenario));
    }

    @Test
    void referenceMetadataCannotBypassTotalResponseSizeLimit() {
        var scenario = scenario();
        scenario.putArray("basedOnCases").addObject().put("title", "x".repeat(16001));
        assertThrows(ApiException.class, () -> TrainingWorkflow.validateScenarioContent(scenario));
    }

    private ObjectNode scenario() {
        return json.createObjectNode().put("title", "Practice")
                .put("description", "A fictional trade")
                .put("openingMessage", "Please check the trade in the official app.");
    }
}
