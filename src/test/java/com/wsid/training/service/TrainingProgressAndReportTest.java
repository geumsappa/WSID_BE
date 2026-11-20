package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.TrainingAiGateway;
import com.wsid.common.error.*;
import com.wsid.report.domain.ReportEvaluation;
import com.wsid.report.service.ReportService;
import com.wsid.training.domain.*;
import com.wsid.training.dto.TrainingRequests;
import com.wsid.training.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.*;
import tools.jackson.databind.ObjectMapper;

class TrainingProgressAndReportTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void submissionIsSentToAiAndNextSceneIsStoredWithoutReopeningPage() {
        var fixture = new Fixture();
        when(fixture.session.getScenarioJson()).thenReturn("{\"currentScene\":1,\"keyScenes\":[{},{}]}");
        when(fixture.ai.request(eq("/simulation/reply"), any())).thenAnswer(call -> {
            Map<?, ?> body = call.getArgument(1);
            assertEquals(1, body.get("currentScene"));
            assertTrue(body.get("messages").toString().contains("본인 확인 정보 제출 완료"));
            assertFalse(body.toString().contains("secret-raw-value"));
            return json.readTree("{\"reply\":\"접수됐습니다. 다음 안내입니다.\",\"recognizedActions\":[],\"shouldFinish\":false,\"mockPage\":{},\"nextScene\":2}");
        });
        var result = fixture.workflow.message(1L, new TrainingRequests.Message(UUID.randomUUID(), 0,
                TrainingRequests.MessageKind.TEXT, "완료했어요", null));
        assertNull(result.mockPage());
        var savedScenario = ArgumentCaptor.forClass(String.class);
        verify(fixture.session).recordScenarioProgress(savedScenario.capture());
        assertEquals(2, json.readTree(savedScenario.getValue()).path("currentScene").asInt());
    }

    @Test void validPasswordAdviceCanCompleteAndStoreTheReport() {
        var fixture = new Fixture();
        when(fixture.ai.request(eq("/training/evaluate"), any())).thenReturn(json.readTree("""
            {"overallFeedback":"비밀번호는 다른 사람에게 알려주지 마세요.","strengths":[],
             "improvements":["인증번호는 공식 채널에서만 사용하세요."],"turnFeedback":[],
             "mockPageEvaluation":{"openedPage":true,"attemptedMockLogin":true,"checkedOfficialChannel":false},
             "recommendations":["요청을 받은 뒤 공식 경로에서 확인하세요."]}
            """));
        when(fixture.reports.recordEvaluation(eq(fixture.session), any(), any())).thenReturn(7L);
        var result = fixture.workflow.finish(1L, new TrainingRequests.Finish(UUID.randomUUID(), 0, "USER_FINISH"));
        assertEquals(7L, result.reportId());
        verify(fixture.session).complete(any());
        var report = ArgumentCaptor.forClass(ReportEvaluation.class);
        verify(fixture.reports).recordEvaluation(eq(fixture.session), report.capture(), any());
        assertTrue(report.getValue().mockPageEvaluation().attemptedMockLogin());
    }

    @Test void failedEvaluationDoesNotCompleteOrLoseTheTraining() {
        var fixture = new Fixture();
        when(fixture.ai.request(eq("/training/evaluate"), any())).thenThrow(new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE));
        assertThrows(ApiException.class, () -> fixture.workflow.finish(1L, new TrainingRequests.Finish(UUID.randomUUID(), 0, "USER_FINISH")));
        verify(fixture.session, never()).complete(any());
        verifyNoInteractions(fixture.reports);
    }

    private class Fixture {
        final TrainingSession session = mock(TrainingSession.class);
        final TrainingAiGateway ai = mock(TrainingAiGateway.class);
        final ReportService reports = mock(ReportService.class);
        final TrainingWorkflow workflow;
        Fixture() {
            var sessions = mock(TrainingSessionRepository.class);
            var messages = mock(TrainingMessageRepository.class);
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            when(session.getId()).thenReturn(1L);
            when(session.getStateVersion()).thenReturn(0);
            when(session.getPhase()).thenReturn(TrainingPhase.SIMULATION);
            when(session.getStatus()).thenReturn(TrainingStatus.ACTIVE);
            when(sessions.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(session));
            when(sessions.lockOwned(1L, 2L)).thenReturn(Optional.of(session));
            var event = TrainingMessage.action(1L, 2, "MOCK_LOGIN_ATTEMPT", "{}", UUID.randomUUID(), Instant.now());
            when(messages.findByTrainingIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(event));
            var properties = new TrainingProperties(4, 15, Duration.ofMinutes(30), 3, 5, 2, List.of("activity"));
            workflow = new TrainingWorkflow(() -> 2L, sessions, messages, reports, ai, json, properties, manager);
        }
    }
}
