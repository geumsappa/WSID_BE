package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import com.wsid.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class TrainingScenarioSourcesTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode scenario() {
        return (ObjectNode) json.readTree("""
          {"title":"가상 훈련", "description":"공식 경로로 확인하는 훈련", "generationEvidence":{
           "retrievedBeforeGeneration":[{"caseId":"RC-001","sourceUrl":"https://www.korea.kr/news/policyNewsView.do?newsId=148910622"}],
           "usedForGeneration":[{"caseId":"RC-001","source":{"title":"출처 자료", "publisher":"발행기관", "publishedDate":"2025-05-22",
           "url":"https://www.korea.kr/news/policyNewsView.do?newsId=148910622", "license":{"code":"KOGL-1","scope":"TEXT_ONLY",
           "evidenceUrl":"https://www.korea.kr/news/policyNewsView.do?newsId=148910622"}}}]}}
          """);
    }
    @Test void verifiedMetadataDatesAndUrlsAreNotMistakenForPhishingDialogue() {
        assertDoesNotThrow(() -> TrainingWorkflow.validateScenarioContent(scenario()));
    }
    @Test void dialogueStillCannotContainRealLinksOrCredentials() {
        var value = scenario();
        value.put("openingMessage", "여기로 접속 https://example.com");
        assertThrows(ApiException.class, () -> TrainingWorkflow.validateScenarioContent(value));
    }
    @Test void metadataCannotBypassSourceValidation() {
        var value = scenario();
        ((ObjectNode) value.path("generationEvidence").path("usedForGeneration").get(0).path("source").path("license")).put("code", "KOGL-4");
        assertThrows(ApiException.class, () -> TrainingWorkflow.validateScenarioContent(value));
    }
}
