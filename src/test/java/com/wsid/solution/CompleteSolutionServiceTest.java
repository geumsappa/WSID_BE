package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.repository.SolutionSessionRepository;
import com.wsid.solution.service.CompleteSolutionService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;

class CompleteSolutionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private final SolutionSessionRepository sessions = mock(SolutionSessionRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());
    private SolutionSession session;
    private CompleteSolutionService service;

    @BeforeEach void setup() throws Exception {
        var constructor = CompleteSolutionService.class.getDeclaredConstructor(
                SolutionSessionRepository.class, PlatformTransactionManager.class, Clock.class);
        constructor.setAccessible(true);
        service = constructor.newInstance(sessions, manager, Clock.fixed(NOW, ZoneOffset.UTC));
        session = SolutionSession.start(owner, SolutionFacts.unknown(), List.of(),
                SolutionQuestion.next(SolutionFacts.unknown()).orElseThrow(), Instant.EPOCH);
        ReflectionTestUtils.setField(session, "id", 301L);
        ReflectionTestUtils.setField(session, "stateVersion", 2);
        when(manager.getTransaction(any())).thenAnswer(i -> {
            TransactionDefinition def = i.getArgument(0);
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, def.getIsolationLevel());
            return mock(TransactionStatus.class);
        });
        when(sessions.findOwned(301L, owner)).thenReturn(Optional.of(session));
        when(sessions.completeActive(301L, owner, NOW)).thenAnswer(i -> {
            ReflectionTestUtils.setField(session, "status", SolutionStatus.COMPLETED);
            ReflectionTestUtils.setField(session, "stateVersion", 3);
            ReflectionTestUtils.setField(session, "completedAt", NOW);
            ReflectionTestUtils.setField(session, "updatedAt", NOW);
            ReflectionTestUtils.setField(session, "nextQuestionJson", null);
            return 1;
        });
    }

    @Test void firstCompletionUsesServerClockAndRepeatDoesNotWrite() {
        var first = service.complete(301L, () -> Optional.of(owner));
        assertEquals(301L, first.solutionId()); assertEquals(SolutionStatus.COMPLETED, first.status());
        assertEquals(first, service.complete(301L, () -> Optional.of(owner)));
        assertEquals(3, session.getStateVersion());
        assertEquals(NOW, ReflectionTestUtils.getField(session, "completedAt"));
        verify(sessions, times(1)).completeActive(301L, owner, NOW);
        verify(sessions, never()).save(any());
    }

    @Test void alreadyCompletedKeepsOriginalTimestampsAndVersion() {
        ReflectionTestUtils.setField(session, "status", SolutionStatus.COMPLETED);
        ReflectionTestUtils.setField(session, "completedAt", Instant.EPOCH);
        service.complete(301L, () -> Optional.of(owner));
        assertEquals(2, session.getStateVersion());
        assertEquals(Instant.EPOCH, ReflectionTestUtils.getField(session, "completedAt"));
        verify(sessions, never()).completeActive(anyLong(), any(), any());
    }

    @Test void missingOwnerIs404WithoutDbOrTransaction() {
        assertEquals(ErrorCode.SOLUTION_NOT_FOUND, assertThrows(ApiException.class,
                () -> service.complete(301L, Optional::empty)).errorCode());
        verifyNoInteractions(sessions, manager);
    }

    @Test void missingOrForeignSessionIs404BeforeWrite() {
        when(sessions.findOwned(301L, owner)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.SOLUTION_NOT_FOUND, assertThrows(ApiException.class,
                () -> service.complete(301L, () -> Optional.of(owner))).errorCode());
        verify(sessions, never()).completeActive(anyLong(), any(), any());
        verify(manager).rollback(any());
    }

    @Test void zeroRowRaceReadsCompletedAndReturnsSuccess() {
        var completed = SolutionSession.start(owner, SolutionFacts.unknown(), List.of(), null, Instant.EPOCH);
        ReflectionTestUtils.setField(completed, "status", SolutionStatus.COMPLETED);
        when(sessions.findOwned(301L, owner)).thenReturn(Optional.of(session)).thenReturn(Optional.of(completed));
        doReturn(0).when(sessions).completeActive(301L, owner, NOW);
        assertEquals(SolutionStatus.COMPLETED, service.complete(301L, () -> Optional.of(owner)).status());
        verify(manager).commit(any());
    }

    @Test void zeroRowWithDeletedSessionIs404() {
        when(sessions.findOwned(301L, owner)).thenReturn(Optional.of(session)).thenReturn(Optional.empty());
        doReturn(0).when(sessions).completeActive(301L, owner, NOW);
        assertEquals(ErrorCode.SOLUTION_NOT_FOUND, assertThrows(ApiException.class,
                () -> service.complete(301L, () -> Optional.of(owner))).errorCode());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, -1})
    void unexplainedActiveResultAndInvalidRowCountsAreServerErrors(int rows) {
        doReturn(rows).when(sessions).completeActive(301L, owner, NOW);
        assertThrows(IllegalStateException.class, () -> service.complete(301L, () -> Optional.of(owner)));
        verify(manager).rollback(any());
    }

    @Test void sqlFailureIsNotHiddenAsDuplicateOrNotFound() {
        var failure = new DataIntegrityViolationException("numeric overflow");
        doThrow(failure).when(sessions).completeActive(301L, owner, NOW);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.complete(301L, () -> Optional.of(owner))));
        verify(manager).rollback(any());
    }
}
