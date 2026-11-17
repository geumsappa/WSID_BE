package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.wsid.common.domain.InputKind;
import com.wsid.common.domain.MessageSender;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import com.wsid.solution.service.SolutionQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class SolutionQueryServiceTest {
    private final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    private final SolutionMessageRepository messages = mock(SolutionMessageRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());
    private SolutionSession session;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var facts = SolutionFacts.fromStrings(SolutionDomainTest.facts("YES", "NO", "YES", "NO"));
        session = SolutionSession.start(owner, facts, List.of(SolutionDomainTest.TRANSFER, SolutionDomainTest.APP), null, Instant.EPOCH);
        ReflectionTestUtils.setField(session, "id", 301L);
        ReflectionTestUtils.setField(session, "stateVersion", 2);
        when(sessions.findOwned(301L, owner)).thenReturn(Optional.of(session));
        var choice = SolutionMessage.of(301L, MessageSender.USER, InputKind.OPTION, 2, null, null, Instant.EPOCH);
        ReflectionTestUtils.setField(choice, "selectedCode", "NO");
        when(messages.findBySolutionIdOrderBySequenceNoAsc(301L)).thenReturn(List.of(
                SolutionMessage.of(301L, MessageSender.USER, InputKind.TEXT, 1, "피해 설명을 제출했습니다.", UUID.randomUUID(), Instant.EPOCH),
                choice));
    }

    private SolutionQueryService service(ResponseCardCatalog catalog) {
        return new SolutionQueryService(sessions, messages, catalog, transactions);
    }

    @Test
    void readsOwnedStateInReadOnlyRepeatableReadTransaction() {
        var response = service(SolutionDomainTest.CATALOG).get(301L, () -> Optional.of(owner));
        assertEquals(301L, response.solutionId());
        assertEquals(2, response.stateVersion());
        assertEquals("YES", response.facts().get("installedApp"));
        assertNull(response.nextQuestion());
        assertEquals(List.of("TRANSFER_RESPONSE", "MALICIOUS_APP_RESPONSE"),
                response.responseCards().stream().map(c -> c.code()).toList());
        assertEquals("CHOICE", response.messages().get(1).inputKind());
        assertEquals("NO", response.messages().get(1).selectedCode());
        assertNull(response.messages().get(1).content());

        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertTrue(definition.getValue().isReadOnly());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, definition.getValue().getIsolationLevel());
        verify(sessions, never()).save(any());
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void missingOwnerIs404WithoutQuery() {
        var error = assertThrows(ApiException.class, () -> service(SolutionDomainTest.CATALOG).get(301L, Optional::empty));
        assertEquals(ErrorCode.SOLUTION_NOT_FOUND, error.errorCode());
        verifyNoInteractions(sessions, messages, transactions);
    }

    @Test
    void otherOwnerOrMissingSessionIsSame404() {
        var other = SolutionOwner.anonymous(UUID.randomUUID());
        when(sessions.findOwned(anyLong(), any())).thenReturn(Optional.empty());
        assertEquals(ErrorCode.SOLUTION_NOT_FOUND, assertThrows(ApiException.class,
                () -> service(SolutionDomainTest.CATALOG).get(301L, () -> Optional.of(other))).errorCode());
        assertEquals(ErrorCode.SOLUTION_NOT_FOUND, assertThrows(ApiException.class,
                () -> service(SolutionDomainTest.CATALOG).get(999L, () -> Optional.of(owner))).errorCode());
        verifyNoInteractions(messages);
    }

    @Test
    void showsCurrentReviewedCardAndResortsByCurrentPriority() {
        var updated = new ResponseCard("TRANSFER_RESPONSE", 5, CardPriority.NORMAL, "바뀐 송금 안내", List.of("BANK"));
        ResponseCardCatalog current = code -> Optional.of(code.equals("TRANSFER_RESPONSE") ? updated : SolutionDomainTest.APP);
        var cards = service(current).get(301L, () -> Optional.of(owner)).responseCards();
        assertEquals("MALICIOUS_APP_RESPONSE", cards.get(0).code());
        assertEquals("바뀐 송금 안내", cards.get(1).title());
        assertEquals("NORMAL", cards.get(1).priority());
    }

    @Test
    void removedCardIs503NotSilentlyDropped() {
        ResponseCardCatalog partial = code -> code.equals("TRANSFER_RESPONSE") ? Optional.of(SolutionDomainTest.TRANSFER) : Optional.empty();
        var error = assertThrows(ApiException.class, () -> service(partial).get(301L, () -> Optional.of(owner)));
        assertEquals(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE, error.errorCode());
    }

    @Test
    void actionMessageIsTreatedAsStorageError() {
        when(messages.findBySolutionIdOrderBySequenceNoAsc(301L)).thenReturn(List.of(
                SolutionMessage.of(301L, MessageSender.USER, InputKind.ACTION, 1, null, null, Instant.EPOCH)));
        assertThrows(IllegalStateException.class, () -> service(SolutionDomainTest.CATALOG).get(301L, () -> Optional.of(owner)));
    }
}
