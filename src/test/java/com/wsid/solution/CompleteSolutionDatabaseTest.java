package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.*;
import com.wsid.solution.repository.*;
import com.wsid.solution.service.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 PostgreSQL에서 종료·수정의 잠금 순서와 원자성을 확인해요. */
@Tag("db")
@SpringBootTest(properties = "spring.datasource.url=${WSID_TEST_DB_URL:jdbc:postgresql://localhost:5432/wsid_ai01_test}")
class CompleteSolutionDatabaseTest {
    @Autowired CompleteSolutionService completes;
    @Autowired StartSolutionService starts;
    @Autowired SendSolutionMessageService sends;
    @Autowired UpdateSolutionFactsService updates;
    @Autowired SolutionQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean SolutionSessionRepository sessions;
    @MockitoBean AiClient ai;
    @MockitoBean ResponseCardCatalog cards;
    private final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());
    private final List<Long> created = new ArrayList<>();

    @AfterEach void cleanup() {
        for (long id : created) {
            jdbc.update("DELETE FROM solution_messages WHERE solution_id = ?", id);
            jdbc.update("DELETE FROM solution_sessions WHERE id = ?", id);
        }
    }

    private long start() {
        long id = starts.start(StartSolutionRequest.empty(), () -> owner).solutionId(); created.add(id); return id;
    }
    private CompleteSolutionResponse complete(long id) { return completes.complete(id, () -> Optional.of(owner)); }
    private Map<String,Object> row(long id) { return jdbc.queryForMap("SELECT * FROM solution_sessions WHERE id = ?", id); }
    private int count(long id) { return jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ?", Integer.class, id); }
    private SolutionSessionUpdatesImpl delegate() { return new SolutionSessionUpdatesImpl(jdbc, entityManager); }
    private SolutionMessageResponse edit(long id, boolean patch) {
        if (patch) {
            var body = new tools.jackson.databind.ObjectMapper().readTree("{\"stateVersion\":0,\"changes\":{\"transferredMoney\":\"NO\"}}");
            return updates.update(id, UpdateSolutionFactsRequest.from(body), () -> Optional.of(owner));
        }
        return sends.send(id, SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.choice(
                UUID.randomUUID(), 0, "transferredMoney", "NO")), () -> Optional.of(owner));
    }
    private void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(10, TimeUnit.SECONDS), "동기화 시간 초과"); }

    @Test void firstCompletionPreservesStateAndRepeatKeepsOriginalTimestampsAndVersion() {
        long id = start(); var before = row(id); int count = count(id);
        var response = complete(id); var after = row(id);
        assertEquals(SolutionStatus.COMPLETED, response.status()); assertEquals(id, response.solutionId());
        assertEquals("COMPLETED", after.get("status")); assertEquals(1, after.get("state_version"));
        assertNotNull(after.get("completed_at")); assertEquals(after.get("completed_at"), after.get("updated_at"));
        assertNull(after.get("next_question_json"));
        for (String key : List.of("facts_json", "card_codes_json", "created_at", "user_id", "anonymous_owner_id")) assertEquals(before.get(key), after.get(key));
        assertEquals(count, count(id));
        assertEquals(response, complete(id)); assertEquals(after, row(id));
        var restored = queries.get(id, () -> Optional.of(owner));
        assertEquals(SolutionStatus.COMPLETED, restored.status()); assertNull(restored.nextQuestion());
        assertEquals(count, restored.messages().size());
        verifyNoInteractions(ai, cards);
    }

    @Test void foreignOwnerDoesNotCompleteAndSameOwnerCanContinueOtherSession() {
        long id = start(); long other = start();
        assertEquals(ErrorCode.SOLUTION_NOT_FOUND, assertThrows(ApiException.class, () -> completes.complete(id,
                () -> Optional.of(SolutionOwner.anonymous(UUID.randomUUID())))).errorCode());
        assertEquals("ACTIVE", row(id).get("status"));
        complete(id); assertEquals(1, edit(other, true).stateVersion());
        assertEquals("ACTIVE", row(other).get("status"));
    }

    @Test void completionDoesNotReadBrokenFactsCardsOrQuestionJson() {
        long id = start();
        jdbc.update("UPDATE solution_sessions SET facts_json = '[]', card_codes_json = '[null]', next_question_json = '\"bad\"' WHERE id = ?", id);
        var before = row(id); complete(id); var after = row(id);
        assertEquals(before.get("facts_json"), after.get("facts_json"));
        assertEquals(before.get("card_codes_json"), after.get("card_codes_json"));
        assertEquals("COMPLETED", after.get("status"));
        verifyNoInteractions(ai, cards);
    }

    @Test void failureAfterSqlUpdateRollsEverythingBack() {
        long id = start(); var before = row(id);
        doAnswer(i -> {
            delegate().completeActive(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            throw new IllegalStateException("forced failure after update");
        }).when(sessions).completeActive(eq(id), any(), any());
        assertThrows(IllegalStateException.class, () -> complete(id));
        assertEquals(before, row(id)); assertEquals(2, count(id));
    }

    @Test void exhaustedVersionRollsBackInsteadOfPartiallyCompleting() {
        long id = start();
        jdbc.update("UPDATE solution_sessions SET state_version = ? WHERE id = ?", Integer.MAX_VALUE, id);
        var before = row(id);
        assertThrows(DataAccessException.class, () -> complete(id));
        assertEquals(before, row(id)); assertEquals(2, count(id));
    }

    @Test void twoConcurrentCompletionsBothSucceedWithOnlyOneTransition() throws Exception {
        long id = start(); var barrier = new CyclicBarrier(2); var wins = new AtomicInteger(); var losses = new AtomicInteger();
        doAnswer(i -> {
            assertEquals("read committed", jdbc.queryForObject("SHOW transaction_isolation", String.class));
            barrier.await(10, TimeUnit.SECONDS);
            int rows = delegate().completeActive(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            if (rows == 1) wins.incrementAndGet(); else if (rows == 0) losses.incrementAndGet();
            return rows;
        }).when(sessions).completeActive(eq(id), any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> complete(id)); var b = executor.submit(() -> complete(id));
            assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, wins.get()); assertEquals(1, losses.get());
        assertEquals(1, row(id).get("state_version")); assertEquals(2, count(id));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void editCommitsFirstThenCompletionPreservesThatEditAndBothSucceed(boolean patch) throws Exception {
        long id = start(); var editUpdated = new CountDownLatch(1); var releaseEdit = new CountDownLatch(1); var endAttempted = new CountDownLatch(1);
        doAnswer(i -> {
            int rows = delegate().compareAndSet(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
            editUpdated.countDown(); await(releaseEdit); return rows;
        }).when(sessions).compareAndSet(eq(id), any(), anyInt(), any());
        doAnswer(i -> {
            endAttempted.countDown(); return delegate().completeActive(i.getArgument(0), i.getArgument(1), i.getArgument(2));
        }).when(sessions).completeActive(eq(id), any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var edit = executor.submit(() -> edit(id, patch));
            try {
                await(editUpdated); var end = executor.submit(() -> complete(id));
                await(endAttempted); releaseEdit.countDown();
                assertEquals(1, edit.get(15, TimeUnit.SECONDS).stateVersion());
                assertEquals(SolutionStatus.COMPLETED, end.get(15, TimeUnit.SECONDS).status());
            } finally { releaseEdit.countDown(); }
        }
        var restored = queries.get(id, () -> Optional.of(owner));
        assertEquals("NO", restored.facts().get("transferredMoney"));
        assertEquals(2, restored.stateVersion()); assertEquals(4, count(id)); assertNull(restored.nextQuestion());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completionCommitsFirstThenPendingEditIsRejectedWithoutNewMessages(boolean patch) throws Exception {
        long id = start(); var endUpdated = new CountDownLatch(1); var releaseEnd = new CountDownLatch(1); var editAttempted = new CountDownLatch(1);
        doAnswer(i -> {
            int rows = delegate().completeActive(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            endUpdated.countDown(); await(releaseEnd); return rows;
        }).when(sessions).completeActive(eq(id), any(), any());
        doAnswer(i -> {
            editAttempted.countDown(); return delegate().compareAndSet(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        }).when(sessions).compareAndSet(eq(id), any(), anyInt(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var end = executor.submit(() -> complete(id));
            try {
                await(endUpdated);
                var edit = executor.submit(() -> {
                    try { edit(id, patch); return null; } catch (ApiException e) { return e.errorCode(); }
                });
                await(editAttempted); releaseEnd.countDown();
                assertEquals(SolutionStatus.COMPLETED, end.get(15, TimeUnit.SECONDS).status());
                assertEquals(ErrorCode.SOLUTION_FINISHED, edit.get(15, TimeUnit.SECONDS));
            } finally { releaseEnd.countDown(); }
        }
        assertEquals(1, row(id).get("state_version")); assertEquals(2, count(id));
    }

    @Test void lateAiResponseCannotSaveAfterCompletion() throws Exception {
        long id = start(); var waiting = new CountDownLatch(1); var releaseAi = new CountDownLatch(1);
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            waiting.countDown(); await(releaseAi);
            return new SolutionExtractResult(SolutionFacts.unknown().toKeyMap(), List.of("transferredMoney", "sharedInformation", "installedApp", "contactContinues"));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> {
                try {
                    sends.send(id, SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.text(UUID.randomUUID(), 0, "일반 피해 설명")), () -> Optional.of(owner));
                    return null;
                } catch (ApiException e) { return e.errorCode(); }
            });
            try {
                await(waiting); complete(id); releaseAi.countDown();
                assertEquals(ErrorCode.SOLUTION_FINISHED, pending.get(15, TimeUnit.SECONDS));
            } finally { releaseAi.countDown(); }
        }
        assertEquals(1, row(id).get("state_version")); assertEquals(2, count(id));
    }

    @Test void memberBranchUsesMemberOwnerAndCompletesWithoutChangingUser() {
        long userId = jdbc.queryForObject("INSERT INTO users (email, password_hash, status) VALUES (?, 'test', 'ACTIVE') RETURNING id", Long.class,
                "complete-db-" + UUID.randomUUID() + "@example.com");
        long id = 0;
        try {
            var member = SolutionOwner.member(userId);
            id = starts.start(StartSolutionRequest.empty(), () -> member).solutionId(); created.add(id);
            assertEquals(SolutionStatus.COMPLETED, completes.complete(id, () -> Optional.of(member)).status());
            assertEquals(userId, row(id).get("user_id")); assertNull(row(id).get("anonymous_owner_id"));
        } finally {
            if (id != 0) {
                jdbc.update("DELETE FROM solution_messages WHERE solution_id = ?", id);
                jdbc.update("DELETE FROM solution_sessions WHERE id = ?", id); created.remove(id);
            }
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
