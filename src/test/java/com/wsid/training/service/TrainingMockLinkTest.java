package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.TrainingAiGateway;
import com.wsid.report.service.ReportService;
import com.wsid.training.domain.*;
import com.wsid.training.dto.TrainingRequests;
import com.wsid.training.repository.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class TrainingMockLinkTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void restoresRecruitmentPageForExistingScenarioWithoutTemplateCode() {
        var session = mock(TrainingSession.class);
        when(session.getScenarioJson()).thenReturn(scenario());
        var page = TrainingWorkflow.availablePage(session);
        assertNotNull(page);
        assertEquals("지원자 본인 확인", page.title());
        assertEquals("기업은행", page.institutionName());
        assertTrue(page.isSimulation());
    }

    @Test
    void initialMessageContainsPageWithoutWaitingForKeySceneDeadline() {
        var fixture = new Fixture(TrainingPhase.SCENARIO_READY);
        var result = fixture.workflow.start(1L, new TrainingRequests.Versioned(UUID.randomUUID(), 0));
        assertNotNull(result.mockPage());
        assertTrue(TrainingWorkflow.containsMockLink(result.firstMessage().content()));
    }

    @Test
    void subsequentLinkStillIncludesPageAfterKeySceneAlreadyShown() {
        var fixture = new Fixture(TrainingPhase.SIMULATION);
        when(fixture.session.isKeySceneShown()).thenReturn(true);
        when(fixture.ai.request(eq("/simulation/reply"), any())).thenReturn(json.readTree(
                "{\"reply\":\"아래 [모의링크]에서 확인해 주세요.\",\"recognizedActions\":[],\"shouldFinish\":false,\"mockPage\":{}}"));
        var result = fixture.workflow.message(1L, new TrainingRequests.Message(UUID.randomUUID(), 0,
                TrainingRequests.MessageKind.TEXT, "다시 안내해 주세요", null));
        assertNotNull(result.mockPage());
    }

    private String scenario() {
        var scenario = json.createObjectNode().put("title", "채용 확인").put("description", "가상 훈련")
                .put("openingMessage", "기업은행 채용 안내입니다. [모의링크]");
        scenario.putObject("persona").put("organization", "기업은행").put("role", "채용 담당자");
        scenario.putArray("keyScenes").addObject().put("request", "링크 클릭");
        return scenario.toString();
    }

    private class Fixture {
        final TrainingSession session = mock(TrainingSession.class);
        final TrainingAiGateway ai = mock(TrainingAiGateway.class);
        final TrainingWorkflow workflow;
        Fixture(TrainingPhase phase) {
            var sessions = mock(TrainingSessionRepository.class);
            var messages = mock(TrainingMessageRepository.class);
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            when(session.getId()).thenReturn(1L);
            when(session.getStateVersion()).thenReturn(0);
            when(session.getStatus()).thenReturn(TrainingStatus.ACTIVE);
            when(session.getPhase()).thenReturn(phase);
            when(session.getScenarioJson()).thenReturn(scenario());
            when(sessions.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(session));
            when(sessions.lockOwned(1L, 2L)).thenReturn(Optional.of(session));
            var properties = new TrainingProperties(4, 15, Duration.ofMinutes(30), 3, 5, 2, List.of("activity"));
            workflow = new TrainingWorkflow(() -> 2L, sessions, messages, mock(ReportService.class), ai, json, properties, manager);
        }
    }
}
