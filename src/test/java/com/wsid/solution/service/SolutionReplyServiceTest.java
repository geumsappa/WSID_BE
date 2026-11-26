package com.wsid.solution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.wsid.ai.TrainingAiGateway;
import com.wsid.content.GuideCatalog;
import com.wsid.solution.domain.SolutionStatus;
import com.wsid.solution.dto.StartSolutionResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SolutionReplyServiceTest {
    private final TrainingAiGateway ai = mock(TrainingAiGateway.class);
    private final GuideCatalog guides = mock(GuideCatalog.class);
    private final SolutionReplyService service = new SolutionReplyService(ai, guides);
    private final StartSolutionResponse started = new StartSolutionResponse(1L, SolutionStatus.ACTIVE, 0,
            Map.of("transferredMoney", "YES"), List.of(new StartSolutionResponse.Card("STOP_PAYMENT", "URGENT", "지급정지 요청", List.of())), null);

    private void aiSays(String message) {
        var node = new ObjectMapper().createObjectNode().put("message", message);
        when(ai.request(eq("/solutions/reply"), any())).thenReturn(node);
    }

    @Test
    void addsAiReplyToStartResponse() {
        aiSays("돈을 보내셨다면 지금은 시간이 가장 중요해요. 먼저 은행에 지급정지를 요청하세요.");
        var result = service.withReply(started, "돈을 보냈어요");
        assertEquals("돈을 보내셨다면 지금은 시간이 가장 중요해요. 먼저 은행에 지급정지를 요청하세요.", result.reply());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "1588-1234로 전화하세요", "1588 1234로 전화하세요", "1588.1234로 전화하세요",
            "15881234로 전화하세요", "1644-1234로 전화하세요", "1800-1234로 전화하세요",
            "010-1234-5678로 전화하세요", "https://example.org에서 접수하세요"})
    void contactsRemainInReviewedCardsInsteadOfGeneratedReply(String message) {
        aiSays(message);
        assertNull(service.withReply(started, null).reply());
    }

    @Test void ordinaryDeadlinesAndAmountsAreNotContactNumbers() {
        String message = "3영업일 안에 서류를 제출하고, 송금액 10000원과 발생 시각을 정리하세요.";
        aiSays(message);
        assertEquals(message, service.withReply(started, null).reply());
    }

    @Test
    void dropsReplyWithPhoneNumberOrWhenAiFails() {
        aiSays("1588-1234로 전화하세요");
        assertNull(service.withReply(started, null).reply());

        when(ai.request(eq("/solutions/reply"), any())).thenThrow(new RuntimeException("down"));
        assertNull(service.withReply(started, null).reply()); // 실패해도 상담 응답은 그대로
    }
}
