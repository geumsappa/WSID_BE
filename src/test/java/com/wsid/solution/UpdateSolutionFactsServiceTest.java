package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.SolutionMessageResponse;
import com.wsid.solution.dto.UpdateSolutionFactsRequest;
import com.wsid.solution.repository.*;
import com.wsid.solution.service.UpdateSolutionFactsService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;

class UpdateSolutionFactsServiceTest {
    private final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    private final SolutionMessageRepository messages = mock(SolutionMessageRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ResponseCardCatalog cards = mock(ResponseCardCatalog.class);
    private final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());
    private final AtomicBoolean active = new AtomicBoolean();
    private final List<SolutionMessage> saved = new ArrayList<>();
    private SolutionSession session;
    private UpdateSolutionFactsService service;

    @BeforeEach void setup() {
        service = new UpdateSolutionFactsService(cards, sessions, messages, manager);
        when(manager.getTransaction(any())).thenAnswer(i -> {
            TransactionDefinition def = i.getArgument(0);
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, def.getIsolationLevel());
            active.set(true); return mock(TransactionStatus.class);
        });
        doAnswer(i -> { active.set(false); return null; }).when(manager).commit(any());
        doAnswer(i -> { active.set(false); return null; }).when(manager).rollback(any());
        initial(SolutionDomainTest.facts("YES", "NO", "UNKNOWN", "UNKNOWN"));
        when(sessions.findOwned(301, owner)).thenAnswer(i -> Optional.of(session));
        when(messages.answeredUnknownKeys(301)).thenReturn(List.of());
        when(messages.lastSequence(301)).thenReturn(4);
        when(messages.save(any())).thenAnswer(i -> { assertTrue(active.get()); saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(sessions.compareAndSet(eq(301L), eq(owner), eq(3), any())).thenAnswer(i -> {
            assertTrue(active.get());
            SolutionSession.Update u = i.getArgument(3);
            ReflectionTestUtils.setField(session, "factsJson", u.factsJson());
            ReflectionTestUtils.setField(session, "cardCodesJson", u.cardsJson());
            ReflectionTestUtils.setField(session, "nextQuestionJson", u.questionJson());
            ReflectionTestUtils.setField(session, "stateVersion", 4);
            return 1;
        });
        when(cards.findReviewed(anyString())).thenAnswer(i -> SolutionDomainTest.CATALOG.findReviewed(i.getArgument(0)));
    }

    private void initial(Map<String, String> raw) {
        var facts = SolutionFacts.fromStrings(raw);
        session = SolutionSession.start(owner, facts, List.of(), SolutionQuestion.next(facts).orElse(null), Instant.now());
        ReflectionTestUtils.setField(session, "id", 301L);
        ReflectionTestUtils.setField(session, "stateVersion", 3);
    }
    private SolutionMessageResponse update(String... keyValues) {
        return service.update(301, UpdateSolutionFactsRequest.from(UpdateSolutionFactsRequestTest.body(3, keyValues)), () -> Optional.of(owner));
    }
    private void fails(ErrorCode code, Runnable run) { assertEquals(code, assertThrows(ApiException.class, run::run).errorCode()); }

    @Test void directCorrectionOverwritesKnownValueRecalculatesAllCardsAndQuestion() {
        var response = update("installedApp", "YES");
        assertEquals(4, response.stateVersion());
        assertEquals(Map.of("transferredMoney", "YES", "sharedInformation", "NO", "installedApp", "YES", "contactContinues", "UNKNOWN"),
                response.facts());
        assertEquals(List.of("TRANSFER_RESPONSE", "MALICIOUS_APP_RESPONSE"), response.responseCards().stream().map(c -> c.code()).toList());
        assertNotNull(response.responseCards().getFirst().title(), "SOL-03과 같은 전체 카드형이에요.");
        assertEquals("contactContinues", response.nextQuestion().factKey());
        assertEquals(2, saved.size());
        SolutionMessage correction = saved.getFirst();
        assertEquals(5, correction.getSequenceNo());
        assertEquals(InputKind.OPTION, correction.getInputKind());
        assertEquals("installedApp", correction.getFactKey());
        assertEquals("YES", correction.getSelectedCode());
        assertNull(correction.getClientRequestId());
        assertTrue(correction.getSafeContent().startsWith(SolutionMessage.CORRECTION_PREFIX));
        assertEquals(MessageSender.SYSTEM, saved.getLast().getSender());
        assertEquals(6, saved.getLast().getSequenceNo());
    }

    @Test void knownYesCanBeCorrectedToNoAndRemovesItsCard() {
        var response = update("transferredMoney", "NO");
        assertTrue(response.responseCards().isEmpty());
        assertEquals("NO", response.facts().get("transferredMoney"));
    }

    @Test void unknownCorrectionCountsAsAnsweredAndAllAnsweredGivesNullQuestion() {
        var response = update("installedApp", "UNKNOWN");
        assertEquals("contactContinues", response.nextQuestion().factKey());
        initial(SolutionDomainTest.facts("YES", "NO", "UNKNOWN", "UNKNOWN"));
        when(messages.answeredUnknownKeys(301)).thenReturn(List.of("installedApp"));
        saved.clear();
        var all = update("contactContinues", "UNKNOWN");
        assertNull(all.nextQuestion());
        assertEquals(SolutionStatus.ACTIVE, session.getStatus());
        assertEquals(1, saved.size(), "질문이 없으면 SYSTEM 질문을 추가하지 않아요.");
    }

    @Test void sameValueIsAcceptedOnceWithoutRepeatingUnchangedQuestion() {
        var response = update("sharedInformation", "NO");
        assertEquals(4, response.stateVersion());
        assertEquals("installedApp", response.nextQuestion().factKey());
        assertEquals(1, saved.size());
    }

    @Test void fourChangesOneVersionAndRecordsInFactOrder() {
        var response = update("contactContinues", "NO", "installedApp", "NO", "sharedInformation", "YES", "transferredMoney", "NO");
        assertEquals(4, response.stateVersion());
        assertNull(response.nextQuestion());
        assertEquals(List.of("transferredMoney", "sharedInformation", "installedApp", "contactContinues"),
                saved.stream().map(SolutionMessage::getFactKey).toList());
        assertEquals(List.of(5, 6, 7, 8), saved.stream().map(SolutionMessage::getSequenceNo).toList());
        verify(sessions, times(1)).compareAndSet(anyLong(), any(), anyInt(), any());
    }

    @Test void storedCardReferencesAreNotReadSoCorruptedCardsDoNotBlockCorrection() {
        ReflectionTestUtils.setField(session, "cardCodesJson", "{\"broken\":true}");
        assertEquals(4, update("transferredMoney", "NO").stateVersion());
    }

    @Test void missingRequiredCardIs503WithoutStateChange() {
        when(cards.findReviewed(ResponseRules.MALICIOUS_APP_RESPONSE)).thenReturn(Optional.empty());
        fails(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE, () -> update("installedApp", "YES"));
        verify(sessions, never()).compareAndSet(anyLong(), any(), anyInt(), any());
        assertTrue(saved.isEmpty());
    }

    @Test void ownerStatusAndVersionFailuresChangeNothing() {
        fails(ErrorCode.SOLUTION_NOT_FOUND, () -> service.update(301,
                UpdateSolutionFactsRequest.from(UpdateSolutionFactsRequestTest.body(3, "installedApp", "NO")), Optional::empty));
        verifyNoInteractions(sessions);
        fails(ErrorCode.SOLUTION_NOT_FOUND, () -> service.update(301,
                UpdateSolutionFactsRequest.from(UpdateSolutionFactsRequestTest.body(3, "installedApp", "NO")),
                () -> Optional.of(SolutionOwner.anonymous(UUID.randomUUID()))));
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> service.update(301,
                UpdateSolutionFactsRequest.from(UpdateSolutionFactsRequestTest.body(2, "installedApp", "NO")), () -> Optional.of(owner)));
        ReflectionTestUtils.setField(session, "status", SolutionStatus.COMPLETED);
        fails(ErrorCode.SOLUTION_FINISHED, () -> update("installedApp", "NO"));
        verify(sessions, never()).compareAndSet(anyLong(), any(), anyInt(), any());
        assertTrue(saved.isEmpty());
    }

    @Test void casLossIsClassifiedAfterTransactionEnds() {
        when(sessions.compareAndSet(anyLong(), any(), anyInt(), any())).thenReturn(0);
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> update("installedApp", "NO"));
        assertTrue(saved.isEmpty());
        verify(manager).rollback(any());
    }

    @Test void unrelatedIntegrityErrorsAreNotHiddenAsConflicts() {
        doThrow(new DataIntegrityViolationException("other")).when(messages).flush();
        assertThrows(DataIntegrityViolationException.class, () -> update("installedApp", "NO"));
    }

    @Test void domainCorrectionKeepsOriginalAndOtherFacts() {
        var original = SolutionFacts.fromStrings(SolutionDomainTest.facts("YES", "NO", "UNKNOWN", "NO"));
        var changed = original.withChanges(Map.of(SolutionFact.TRANSFERRED_MONEY, FactValue.UNKNOWN, SolutionFact.INSTALLED_APP, FactValue.YES));
        assertEquals(SolutionDomainTest.facts("UNKNOWN", "NO", "YES", "NO"), changed.toKeyMap());
        assertEquals(SolutionDomainTest.facts("YES", "NO", "UNKNOWN", "NO"), original.toKeyMap());
        assertThrows(IllegalArgumentException.class, () -> original.withChanges(Map.of()));
        // AI 병합은 여전히 알려진 값을 바꾸지 않아요.
        assertEquals("YES", original.mergeExtracted(SolutionFacts.fromStrings(SolutionDomainTest.facts("NO", "NO", "NO", "NO")))
                .facts().toKeyMap().get("transferredMoney"));
    }
}
