package com.wsid.training.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wsid.ai.TrainingAiGateway;
import com.wsid.auth.CurrentUser;
import com.wsid.common.error.ApiException;
import com.wsid.report.domain.ReportEvaluation;
import com.wsid.report.service.ReportService;
import com.wsid.training.domain.*;
import com.wsid.training.dto.TrainingRequests;
import com.wsid.training.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

class TrainingMockPhotoTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void onlyActualPhotoRequestsOfferVirtualMaterials() {
        assertEquals(List.of(MockPhoto.ID_CARD), MockPhoto.offered("신분증 사진을 보내 주세요."));
        assertEquals(List.of(MockPhoto.BANKBOOK), MockPhoto.offered("통장 사본을 첨부해 주세요."));
        assertEquals(List.of(MockPhoto.PHOTO), MockPhoto.offered("화면 캡처를 보내 주세요."));
        assertEquals(2, MockPhoto.offered("신분증과 통장 사진을 보내 주세요.").size());
        assertTrue(MockPhoto.offered("사진을 보내지 마세요.").isEmpty());
        assertTrue(MockPhoto.offered("사진 확인했습니다.").isEmpty());
        assertTrue(MockPhoto.offered("계좌번호를 알려 주세요.").isEmpty());
        // 가족 사칭: 상품권 사진 요구 → 상품권 사진 보내기
        assertEquals(List.of(MockPhoto.GIFT_CARD), MockPhoto.offered("엄마 편의점에서 상품권 사서 뒷면 사진 찍어서 보내 줘"));
        // 사기범이 직접 사진을 보내는 말이나 사진 소품 표시는 사용자에게 달라는 말이 아니에요
        assertTrue(MockPhoto.offered("실물 사진 보내 드릴게요\n[사진:상품|전자레인지|김민수]").isEmpty());
        assertTrue(MockPhoto.offered("송장 사진 보냈어요 확인해 주세요 [사진:송장|한결택배|전자레인지]").isEmpty());
    }

    @Test
    void submissionSendsOnlyEventAndStoresAuthoritativeActionForReport() {
        var fixture = new Fixture(true);
        when(fixture.ai.request(eq("/simulation/reply"), any())).thenAnswer(call -> {
            Map<?, ?> payload = call.getArgument(1);
            assertEquals("[가상 신분증 사진 제공]", payload.get("userInput"));
            assertFalse(payload.containsKey("image"));
            return json.readTree("{\"reply\":\"확인했습니다.\",\"recognizedActions\":[\"SAFE_RESPONSE\"],\"shouldFinish\":false,\"mockPage\":null}");
        });
        var response = fixture.workflow.message(1L, fixture.choice());
        assertTrue(response.recognizedActions().contains("SEND_MOCK_ID_CARD"));
        assertTrue(response.recognizedActions().contains("RISKY_RESPONSE"));
        assertTrue(response.recognizedActions().contains("SENSITIVE_INFO_SHARED"));
        assertFalse(response.recognizedActions().contains("SAFE_RESPONSE"));
        var saved = ArgumentCaptor.forClass(TrainingMessage.class);
        verify(fixture.messages, times(2)).save(saved.capture());
        assertEquals("SEND_MOCK_ID_CARD", saved.getAllValues().getFirst().getActionCode());
        assertEquals("[가상 신분증 사진 제공]", saved.getAllValues().getFirst().getContent());
    }

    @Test
    void cannotSubmitMaterialThatWasNotOffered() {
        var fixture = new Fixture(false);
        assertThrows(ApiException.class, () -> fixture.workflow.message(1L, fixture.choice()));
        verifyNoInteractions(fixture.ai);
        verify(fixture.messages, never()).save(any());
    }

    @Test
    void reportContainsPhotoRiskEvenIfModelOmitsItAndWhenRunningFakeMode() {
        var base = new ReportEvaluation("훈련 완료", List.of(), List.of(), List.of(),
                new ReportEvaluation.MockPageEvaluation(false, false, false), List.of());
        for (ReportEvaluation evaluation : Arrays.asList(base, null)) {
            var report = TrainingWorkflow.photoFeedback(evaluation, List.of("SEND_MOCK_ID_CARD"), false);
            assertTrue(report.improvements().stream().anyMatch(text -> text.contains("신분증 사진 제공 요청에 응했어요")));
        }
    }

    private class Fixture {
        final TrainingSessionRepository sessions = mock(TrainingSessionRepository.class);
        final TrainingMessageRepository messages = mock(TrainingMessageRepository.class);
        final TrainingAiGateway ai = mock(TrainingAiGateway.class);
        final TrainingWorkflow workflow;
        Fixture(boolean offered) {
            var session = mock(TrainingSession.class);
            when(session.getId()).thenReturn(1L);
            when(session.getStateVersion()).thenReturn(0);
            when(session.getPhase()).thenReturn(TrainingPhase.SIMULATION);
            when(session.getStatus()).thenReturn(TrainingStatus.ACTIVE);
            when(sessions.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(session));
            when(sessions.lockOwned(1L, 2L)).thenReturn(Optional.of(session));
            var last = TrainingMessage.fromAi(1L, TrainingPhase.SIMULATION, 1,
                    offered ? "신분증 사진을 보내 주세요." : "안녕하세요.", Instant.now());
            last.recordOptions(json.writeValueAsString(TrainingWorkflow.optionsFor(last.getContent())));
            when(messages.findByTrainingIdOrderBySequenceNoAsc(1L)).thenReturn(List.of(last));
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            var properties = new TrainingProperties(4, 15, Duration.ofMinutes(30), 3, 5, 2, List.of("activity"));
            workflow = new TrainingWorkflow(() -> 2L, sessions, messages, mock(ReportService.class), ai, json, properties, manager);
        }
        TrainingRequests.Message choice() {
            return new TrainingRequests.Message(UUID.randomUUID(), 0, TrainingRequests.MessageKind.CHOICE, null, "SEND_MOCK_ID_CARD");
        }
    }
}
