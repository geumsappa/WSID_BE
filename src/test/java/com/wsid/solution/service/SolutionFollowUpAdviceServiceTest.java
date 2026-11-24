package com.wsid.solution.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.ai.dto.OcrImageType;
import com.wsid.ai.dto.SolutionAssistStatus;
import com.wsid.solution.domain.FactValue;
import com.wsid.solution.domain.SolutionFact;
import com.wsid.solution.domain.SolutionFacts;
import com.wsid.solution.domain.SolutionMessage;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionQuestion;
import com.wsid.solution.domain.SolutionSession;
import com.wsid.solution.domain.SolutionStatus;
import com.wsid.solution.dto.SolutionAssistant;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SolutionFollowUpAdviceServiceTest {
    private final SolutionImageAdviceService advice = mock(SolutionImageAdviceService.class);
    private final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    private final SolutionMessageRepository messages = mock(SolutionMessageRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SolutionOwner owner = SolutionOwner.member(7L);
    private final SolutionSession session;
    private final SolutionFollowUpAdviceService service;
    private final UUID requestId = UUID.randomUUID();
    private static final SolutionAssistant ANSWER =
            new SolutionAssistant(SolutionAssistStatus.ANSWERED, "사진을 보면 보내지 마세요.", List.of("REF_A"), null, null);

    {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        session = SolutionSession.start(owner, SolutionFacts.unknown(), List.of(),
                SolutionQuestion.next(SolutionFacts.unknown()).orElseThrow(), Instant.now());
        ReflectionTestUtils.setField(session, "id", 301L);
        ReflectionTestUtils.setField(session, "stateVersion", 2);
        ReflectionTestUtils.setField(session, "fraudTypeCode", "SECONDHAND_TRADE");
        when(sessions.findOwned(301L, owner)).thenReturn(Optional.of(session));
        when(sessions.findById(301L)).thenReturn(Optional.of(session));
        when(messages.findBySolutionIdOrderBySequenceNoAsc(301L)).thenReturn(List.of(
                SolutionMessage.photo(301L, 3, "사진(메신저 대화)을 제출했습니다.", "[사진 · 메신저 대화] 요약: 상대가 사기라고 인정했어요.",
                        UUID.randomUUID(), Instant.EPOCH)));
        when(messages.lastSequence(301L)).thenReturn(4);
        service = new SolutionFollowUpAdviceService(advice, sessions, messages, manager,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), true);
    }

    @Test void choiceAsksWithAnswerHistoryAndFraudTypeThenStoresAiReply() {
        when(advice.optionalAdvice(any(), anyString(), any(), any(), any())).thenReturn(ANSWER);
        var result = service.afterChoice(301L, owner, 2, SolutionFact.TRANSFERRED_MONEY, FactValue.NO, requestId);
        assertEquals(ANSWER, result);
        var question = ArgumentCaptor.forClass(String.class);
        verify(advice).optionalAdvice(any(), question.capture(), eq("SECONDHAND_TRADE"),
                argThat(h -> h != null && h.size() == 1 && h.get(0).content().startsWith("[사진")), eq(requestId));
        assertTrue(question.getValue().contains(SolutionFact.TRANSFERRED_MONEY.question()));
        assertTrue(question.getValue().contains("\"아니요\""));
        var saved = ArgumentCaptor.forClass(SolutionMessage.class);
        verify(messages).save(saved.capture());
        assertEquals("사진을 보면 보내지 마세요.", saved.getValue().getSafeContent());
        assertEquals(5, saved.getValue().getSequenceNo());
        assertEquals(List.of("REF_A"), saved.getValue().getReferenceCodes());
    }

    @Test void replyGoesBeforeTheNextFixedQuestionThisRequestAdded() {
        when(advice.optionalAdvice(any(), anyString(), any(), any(), any())).thenReturn(ANSWER);
        when(messages.moveSystemMessage(301L, 4, 5)).thenReturn(1); // 마지막 행(4)이 이번에 생긴 다음 질문
        service.afterChoice(301L, owner, 2, SolutionFact.TRANSFERRED_MONEY, FactValue.NO, requestId);
        var saved = ArgumentCaptor.forClass(SolutionMessage.class);
        verify(messages).save(saved.capture());
        assertEquals(4, saved.getValue().getSequenceNo()); // 안내 4, 다음 질문 5
    }

    @Test void choiceQuestionAsksOnlyForWhatThisAnswerChanges() {
        when(advice.optionalAdvice(any(), anyString(), any(), any(), any())).thenReturn(ANSWER);
        service.afterChoice(301L, owner, 2, SolutionFact.CONTACT_CONTINUES, FactValue.YES, requestId);
        verify(advice).optionalAdvice(any(), argThat(q -> q.contains(SolutionFact.CONTACT_CONTINUES.question())
                && q.contains("\"예\"") && q.contains("되풀이하지 말고")), any(), any(), any());
    }

    @Test void photoQuestionNamesTheImageType() {
        when(advice.optionalAdvice(any(), anyString(), any(), any(), any())).thenReturn(ANSWER);
        assertEquals(ANSWER, service.afterPhoto(301L, owner, 2, OcrImageType.MESSENGER_CHAT, requestId));
        verify(advice).optionalAdvice(any(), contains(OcrImageType.MESSENGER_CHAT.label()), any(), any(), any());
    }

    @Test void noAdviceMeansNoAiRow() {
        when(advice.optionalAdvice(any(), anyString(), any(), any(), any())).thenReturn(null);
        assertNull(service.afterChoice(301L, owner, 2, SolutionFact.TRANSFERRED_MONEY, FactValue.YES, requestId));
        verify(messages, never()).save(any());
    }

    @Test void staleOrFinishedSessionGetsNoAdviceAndNoRow() {
        // 다른 요청이 먼저 상태를 바꿨어요(버전 불일치): AI를 부르지도 않아요.
        assertNull(service.afterChoice(301L, owner, 1, SolutionFact.TRANSFERRED_MONEY, FactValue.YES, requestId));
        verify(advice, never()).optionalAdvice(any(), anyString(), any(), any(), any());
        // 안내를 기다리는 사이 상담이 끝났어요: 안내를 버리고 저장하지 않아요.
        when(advice.optionalAdvice(any(), anyString(), any(), any(), any())).thenAnswer(i -> {
            ReflectionTestUtils.setField(session, "status", SolutionStatus.COMPLETED);
            return ANSWER;
        });
        assertNull(service.afterChoice(301L, owner, 2, SolutionFact.TRANSFERRED_MONEY, FactValue.YES, requestId));
        verify(messages, never()).save(any());
    }
}
