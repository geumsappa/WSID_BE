package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.TrainingAiGateway;
import com.wsid.common.error.ApiException;
import com.wsid.report.service.ReportService;
import com.wsid.training.domain.*;
import com.wsid.training.dto.TrainingRequests;
import com.wsid.training.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import tools.jackson.databind.ObjectMapper;

class TrainingDiscoveryScenarioQualityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void fiveAnswersDoNotForceReadinessAndAnotherAnswerIsAccepted() {
        var fixture = new Fixture();
        when(fixture.session.getDiscoveryTurnCount()).thenReturn(5);
        when(fixture.ai.request(eq("/discovery/next"), any())).thenReturn(json.readTree(
            "{\"facts\":{},\"nextQuestion\":\"구체적인 상황을 한 가지 알려주시겠어요?\",\"canGenerateScenario\":false}"));
        var response = fixture.workflow.discover(1L, new TrainingRequests.Discovery(UUID.randomUUID(), 0, "모르겠어요"));
        assertFalse(response.canGenerateScenario());
        assertEquals(6, response.discoveryTurnCount());
    }

    @Test void scenarioUsesAndStoresJobCodeInsteadOfFixedTradeCode() {
        var fixture = new Fixture();
        when(fixture.session.getContextFactsJson()).thenReturn("{\"canGenerateScenario\":true,\"fraudTypeCode\":\"JOB_SCAM\"}");
        when(fixture.ai.request(eq("/scenarios/generate"), any())).thenAnswer(call -> {
            Map<?, ?> request = call.getArgument(1);
            assertEquals("JOB_SCAM", request.get("fraudTypeCode"));
            return json.readTree("{\"scenario\":{\"title\":\"채용 확인 훈련\",\"description\":\"가상의 채용 연락\",\"fraudTypeCode\":\"JOB_SCAM\"},\"mockPageTemplateCode\":\"BANK_NOTICE\"}");
        });
        var response = fixture.workflow.scenario(1L, new TrainingRequests.Versioned(UUID.randomUUID(), 0));
        assertEquals("JOB_SCAM", response.fraudTypeCode());
        verify(fixture.session).prepareScenario(eq("JOB_SCAM"), anyString(), eq("BANK_NOTICE"), any(), any());
    }

    @Test void olderFactsCanInferJobCodeWithoutMetadata() {
        assertEquals("JOB_SCAM", TrainingWorkflow.contextualFraudType(json.readTree(
            "{\"extractedFacts\":[{\"category\":\"구직·아르바이트\",\"detail\":\"은행 역량검사\"}]}")));
        assertEquals("SECONDHAND_TRADE", TrainingWorkflow.contextualFraudType(json.readTree("{\"activity\":\"중고거래\"}")));
    }

    @Test void discoverySendsBoundedHistoryAndKeepsAccumulatedFacts() {
        var fixture = new Fixture();
        var history = new ArrayList<TrainingMessage>();
        for (int sequence = 1; sequence <= 40; sequence++) history.add(TrainingMessage.fromAi(1L, TrainingPhase.DISCOVERY, sequence, "지난 질문입니다", Instant.now()));
        when(fixture.messages.findByTrainingIdOrderBySequenceNoAsc(1L)).thenReturn(history);
        when(fixture.session.getContextFactsJson()).thenReturn("{\"extractedFacts\":[{\"detail\":\"은행 면접에 응시함\"}]}");
        when(fixture.ai.request(eq("/discovery/next"), any())).thenAnswer(call -> {
            Map<?, ?> request = call.getArgument(1);
            assertEquals(20, ((List<?>) request.get("conversation")).size());
            assertTrue(request.get("knownFacts").toString().contains("은행 면접"));
            return json.readTree("{\"facts\":{},\"nextQuestion\":\"이후 안내가 있었나요?\",\"canGenerateScenario\":false}");
        });
        fixture.workflow.discover(1L, new TrainingRequests.Discovery(UUID.randomUUID(), 0, "이메일로 받았어요"));
    }

    @Test void unsupportedGeneratedTypeIsRejectedBeforeSaving() {
        var fixture = new Fixture();
        when(fixture.session.getContextFactsJson()).thenReturn("{\"canGenerateScenario\":true,\"fraudTypeCode\":\"JOB_SCAM\"}");
        when(fixture.ai.request(eq("/scenarios/generate"), any())).thenReturn(json.readTree(
            "{\"scenario\":{\"title\":\"훈련\",\"description\":\"가상 상황\",\"fraudTypeCode\":\"UNKNOWN\"}}"));
        assertThrows(ApiException.class, () -> fixture.workflow.scenario(1L, new TrainingRequests.Versioned(UUID.randomUUID(), 0)));
        verify(fixture.session, never()).prepareScenario(any(), any(), any(), any(), any());
    }

    @Test void basicOfferChoicesAreReturnedAndPersistedForResume() {
        var fixture = new Fixture();
        when(fixture.ai.request(eq("/discovery/next"), any())).thenReturn(json.readTree(
            "{\"facts\":{\"basicScenarioOffered\":true},\"nextQuestion\":\"기본 훈련을 진행하시겠어요?\",\"canGenerateScenario\":false}"));
        var response = fixture.workflow.discover(1L, new TrainingRequests.Discovery(UUID.randomUUID(), 0, "없어"));
        assertEquals(List.of("DISCOVERY_MORE", "DISCOVERY_BASIC"), response.reply().options().stream().map(o -> o.code()).toList());
        var saved = org.mockito.ArgumentCaptor.forClass(TrainingMessage.class);
        verify(fixture.messages, times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().get(1).getOptionsJson().contains("DISCOVERY_BASIC"));
        assertFalse(response.canGenerateScenario());
    }

    @Test void basicChoiceIsDistinguishedFromPersonalizedReadiness() {
        var fixture = new Fixture();
        when(fixture.ai.request(eq("/discovery/next"), any())).thenReturn(json.readTree(
            "{\"facts\":{\"scenarioMode\":\"BASIC\",\"basicScenarioOffered\":false},\"nextQuestion\":\"준비 완료\",\"canGenerateScenario\":true}"));
        var response = fixture.workflow.discover(1L, new TrainingRequests.Discovery(UUID.randomUUID(), 0, "네"));
        assertTrue(response.canGenerateScenario());
        assertTrue(response.reply().content().contains("사례 기반 기본 훈련"));
        assertTrue(response.reply().options().isEmpty());
    }

    @Test void signedPreparedPlanIsMetadataNotAnAccountNumberAndIsPreserved() {
        var fixture = new Fixture();
        // A valid-shaped opaque receipt can contain long digit runs in its HMAC.
        String receipt = "eJwABC_123=" + "." + "a".repeat(20) + "123456789012" + "b".repeat(32);
        var facts = json.createObjectNode().put("pipelineVersion", 3).put("preparedScenario", receipt);
        var aiResponse = json.createObjectNode().set("facts", facts);
        aiResponse.put("nextQuestion", "준비 완료").put("canGenerateScenario", true);
        when(fixture.ai.request(eq("/discovery/next"), any())).thenReturn(aiResponse);
        var response = fixture.workflow.discover(1L,
                new TrainingRequests.Discovery(UUID.randomUUID(), 0, "당근 마켓을 이용했어요."));
        assertTrue(response.canGenerateScenario());
        var saved = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.session).recordDiscovery(saved.capture(), any(), any());
        assertEquals(receipt, json.readTree(saved.getValue()).path("preparedScenario").asString());
    }

    @Test void basicSelectionWithDeployedCorpusHashIsSavedWithout503() {
        var fixture = new Fixture();
        // SHA-256 of the committed LF JSON, as checked out on the Linux deployment.
        String digest = "0167efeef657b67278eb1db89e7ac0c62a7917f2492254173574dd253b8e2bbd";
        var facts = json.createObjectNode().put("scenarioMode", "BASIC").put("discoveryReady", true)
                .put("basicScenarioOffered", false);
        facts.putObject("basicScenarioSelection").put("caseId", "RC-001").put("consentTurn", 3)
                .put("selectionReason", "DEFAULT_CATALOG_SELECTION").put("corpusHash", digest);
        var aiResponse = json.createObjectNode().set("facts", facts);
        aiResponse.put("nextQuestion", "사례 기반 기본 훈련을 준비할 수 있어요.").put("canGenerateScenario", true);
        when(fixture.ai.request(eq("/discovery/next"), any())).thenReturn(aiResponse);
        var response = fixture.workflow.discover(1L,
                new TrainingRequests.Discovery(UUID.randomUUID(), 0, "기본 훈련 시작"));
        assertTrue(response.canGenerateScenario());
        assertTrue(response.reply().options().isEmpty());
        var saved = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.session).recordDiscovery(saved.capture(), any(), any());
        assertEquals(digest, json.readTree(saved.getValue()).path("basicScenarioSelection").path("corpusHash").asString());
    }

    private class Fixture {
        final TrainingSession session = mock(TrainingSession.class);
        final TrainingAiGateway ai = mock(TrainingAiGateway.class);
        final TrainingMessageRepository messages = mock(TrainingMessageRepository.class);
        final TrainingWorkflow workflow;
        Fixture() {
            var sessions = mock(TrainingSessionRepository.class);
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            when(session.getId()).thenReturn(1L);
            when(session.getStateVersion()).thenReturn(0);
            when(session.getPhase()).thenReturn(TrainingPhase.DISCOVERY);
            when(session.getStatus()).thenReturn(TrainingStatus.ACTIVE);
            when(sessions.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(session));
            when(sessions.lockOwned(1L, 2L)).thenReturn(Optional.of(session));
            var properties = new TrainingProperties(4, 15, Duration.ofMinutes(30), 3, 5, 2, List.of("activity"));
            workflow = new TrainingWorkflow(() -> 2L, sessions, messages, mock(ReportService.class), ai, json, properties, manager);
        }
    }
}
