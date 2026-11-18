package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.TrainingAiGateway;
import com.wsid.auth.CurrentUser;
import com.wsid.report.service.ReportService;
import com.wsid.training.domain.*;
import com.wsid.training.dto.TrainingRequests;
import com.wsid.training.repository.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class TrainingSimulationDisclosureTest {
    @Test
    void cardSubmissionContinuesWithMaskedAiPayloadAndMaskedStoredMessage() {
        var current = mock(CurrentUser.class);
        var sessions = mock(TrainingSessionRepository.class);
        var messages = mock(TrainingMessageRepository.class);
        var ai = mock(TrainingAiGateway.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var session = mock(TrainingSession.class);
        when(current.id()).thenReturn(2L);
        when(session.getId()).thenReturn(1L);
        when(session.getStateVersion()).thenReturn(0);
        when(session.getPhase()).thenReturn(TrainingPhase.SIMULATION);
        when(session.getStatus()).thenReturn(TrainingStatus.ACTIVE);
        when(sessions.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(session));
        when(sessions.lockOwned(1L, 2L)).thenReturn(Optional.of(session));
        var json = new ObjectMapper();
        when(ai.request(eq("/simulation/reply"), any())).thenAnswer(invocation -> {
            Map<?, ?> body = invocation.getArgument(1);
            assertEquals("[카드번호]", body.get("userInput"));
            assertFalse(body.toString().contains("4111111111111111"));
            return json.readTree("{\"reply\":\"확인했습니다\",\"recognizedActions\":[\"SENSITIVE_INFO_SHARED\",\"RISKY_RESPONSE\"],\"shouldFinish\":false,\"mockPage\":null}");
        });
        var properties = new TrainingProperties(4, 15, Duration.ofMinutes(30), 3, 5, 2, List.of("activity"));
        var workflow = new TrainingWorkflow(current, sessions, messages, mock(ReportService.class), ai, json, properties, manager);
        var result = workflow.message(1L, new TrainingRequests.Message(UUID.randomUUID(), 0,
                TrainingRequests.MessageKind.TEXT, "4111111111111111", null));
        assertFalse(result.shouldFinish());
        assertTrue(result.recognizedActions().contains("SENSITIVE_INFO_SHARED"));
        var saved = ArgumentCaptor.forClass(TrainingMessage.class);
        verify(messages, times(2)).save(saved.capture());
        assertEquals("[카드번호]", saved.getAllValues().getFirst().getContent());
        assertTrue(saved.getAllValues().getFirst().getTurnEvaluationJson().contains("SENSITIVE_INFO_SHARED"));
        assertTrue(saved.getAllValues().stream().noneMatch(message -> message.getContent().contains("4111111111111111")));
    }
}
