package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.AiUnavailableException;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.repository.*;
import com.wsid.solution.service.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PostgreSQL: CAS·행 잠금·JPA 캐시·메시지 롤백·동시성. 다른 프로젝트 DB에서는 실행하지 않아요. */
@Tag("db")
@SpringBootTest(properties = "spring.datasource.url=${WSID_TEST_DB_URL:jdbc:postgresql://localhost:5432/wsid_ai01_test}")
class SolutionMessageDatabaseTest {
    @Autowired StartSolutionService starts;
    @Autowired SendSolutionMessageService service;
    @Autowired SolutionQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean SolutionSessionRepository sessions;
    @MockitoSpyBean SolutionMessageRepository messages;
    @MockitoBean AiClient ai;
    @MockitoBean ResponseCardCatalog cards;
    private final List<Long> created = new ArrayList<>();
    private final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());

    @BeforeEach void setup() {
        when(cards.findReviewed(anyString())).thenAnswer(i -> SolutionDomainTest.CATALOG.findReviewed(i.getArgument(0)));
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return result("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN");
        });
    }

    @AfterEach void cleanup() {
        for (long id : created) {
            jdbc.update("DELETE FROM solution_messages WHERE solution_id = ?", id);
            jdbc.update("DELETE FROM solution_sessions WHERE id = ?", id);
        }
    }

    private long start() {
        long id = starts.start(StartSolutionRequest.empty(), () -> owner).solutionId(); created.add(id); return id;
    }
    private SendSolutionMessageRequest choice(UUID requestId, int version, String key, String value) {
        return SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.choice(requestId, version, key, value));
    }
    private SendSolutionMessageRequest text(UUID requestId, int version, String content) {
        return SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.text(requestId, version, content));
    }
    private com.wsid.solution.dto.SolutionMessageResponse send(long id, SendSolutionMessageRequest request) {
        return service.send(id, request, () -> Optional.of(owner));
    }
    private SolutionExtractResult result(String a, String b, String c, String d) {
        var facts = SolutionDomainTest.facts(a,b,c,d);
        return new SolutionExtractResult(facts, facts.entrySet().stream().filter(e -> e.getValue().equals("UNKNOWN")).map(Map.Entry::getKey).toList());
    }
    private int version(long id) { return jdbc.queryForObject("SELECT state_version FROM solution_sessions WHERE id = ?", Integer.class, id); }
    private int count(long id) { return jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ?", Integer.class, id); }
    private void fails(ErrorCode code, Runnable run) { assertEquals(code, assertThrows(ApiException.class, run::run).errorCode()); }

    @Test void choiceCommitsCanonicalStateVersionAndRestoreInOneTurn() {
        long id = start();
        var request = choice(UUID.randomUUID(), 0, "transferredMoney", "NO");
        var after = send(id, request);
        assertEquals(1, after.stateVersion()); assertEquals(1, version(id));
        var restored = queries.get(id, () -> Optional.of(owner));
        assertEquals(after.facts(), restored.facts()); assertEquals(after.nextQuestion(), restored.nextQuestion());
        assertEquals(4, restored.messages().size());
        assertEquals("CHOICE", restored.messages().get(2).inputKind());
        var row = jdbc.queryForMap("SELECT fact_key, selected_code, input_kind, client_request_id FROM solution_messages WHERE solution_id = ? AND sequence_no = 3", id);
        assertEquals("transferredMoney", row.get("fact_key")); assertEquals("NO", row.get("selected_code"));
        assertEquals("OPTION", row.get("input_kind")); assertEquals(request.requestId(), row.get("client_request_id"));
        fails(ErrorCode.DUPLICATE_REQUEST, () -> send(id, request));
        assertEquals(4, count(id)); assertEquals(1, version(id));
    }

    @Test void unknownAnswersAdvanceUntilQuestionNullAndStayActive() {
        long id = start();
        int version = 0;
        for (SolutionFact fact : SolutionFact.values()) {
            var response = send(id, choice(UUID.randomUUID(), version++, fact.key(), "UNKNOWN"));
            assertEquals(version, response.stateVersion());
        }
        var restored = queries.get(id, () -> Optional.of(owner));
        assertNull(restored.nextQuestion()); assertEquals(SolutionStatus.ACTIVE, restored.status());
        assertTrue(restored.facts().values().stream().allMatch("UNKNOWN"::equals));
        assertEquals(9, count(id)); // 시작 2 + 앞 세 답변 6 + 마지막 답변 1
        assertEquals(4, messages.answeredUnknownKeys(id).size());
    }

    @Test void repeatedUnknownTextIncreasesVersionOnceWithoutRepeatingQuestionOrStoringRawText() {
        long id = start();
        String secret = "특정인 이름을 포함한 비공개 문장";
        send(id, text(UUID.randomUUID(), 0, secret));
        send(id, text(UUID.randomUUID(), 1, secret));
        assertEquals(2, version(id)); assertEquals(4, count(id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ? AND safe_content LIKE ?", Integer.class, id, "%" + secret + "%"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ? AND safe_content = ?", Integer.class, id, SolutionFact.TRANSFERRED_MONEY.question()));
    }

    @Test void updatedStateAndInsertedMessageRollBackTogetherWhenFlushFails() {
        long id = start();
        String before = jdbc.queryForObject("SELECT facts_json::text FROM solution_sessions WHERE id = ?", String.class, id);
        doThrow(new IllegalStateException("forced message failure")).when(messages).flush();
        assertThrows(IllegalStateException.class, () -> send(id, choice(UUID.randomUUID(), 0, "transferredMoney", "NO")));
        assertEquals(0, version(id)); assertEquals(2, count(id));
        assertEquals(before, jdbc.queryForObject("SELECT facts_json::text FROM solution_sessions WHERE id = ?", String.class, id));
    }

    @Test void lastSequenceUsesMaximumEvenWithDeletedRowsAndGaps() {
        long id = start();
        jdbc.update("DELETE FROM solution_messages WHERE solution_id = ? AND sequence_no = 1", id);
        jdbc.update("INSERT INTO solution_messages (solution_id, sender, input_kind, sequence_no) VALUES (?, 'SYSTEM', 'TEXT', 20)", id);
        send(id, choice(UUID.randomUUID(), 0, "transferredMoney", "NO"));
        assertEquals(List.of(2,20,21,22), jdbc.queryForList("SELECT sequence_no FROM solution_messages WHERE solution_id = ? ORDER BY sequence_no", Integer.class, id));
    }

    @Test void factKeyConstraintRejectsUnknownButAcceptsLegacyNull() {
        long id = start();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ? AND fact_key IS NULL", Integer.class, id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE solution_messages SET fact_key = 'unsupported' WHERE solution_id = ?", id));
        assertEquals(2, count(id));
    }

    @Test void aiAndCardFailuresLeaveStateAndRequestIdRetryable() {
        long id = start(); UUID requestId = UUID.randomUUID();
        when(ai.extractSolutionFacts(any())).thenThrow(new AiUnavailableException("private"));
        var text = text(requestId, 0, "설명");
        fails(ErrorCode.AI_SERVICE_UNAVAILABLE, () -> send(id, text));
        assertFalse(messages.existsBySolutionIdAndClientRequestId(id, requestId));
        doReturn(result("YES", "UNKNOWN", "UNKNOWN", "NO")).when(ai).extractSolutionFacts(any());
        when(cards.findReviewed(anyString())).thenReturn(Optional.empty());
        fails(ErrorCode.RESPONSE_CONTENT_UNAVAILABLE, () -> send(id, text));
        assertEquals(0, version(id)); assertEquals(2, count(id));
        when(cards.findReviewed(anyString())).thenAnswer(i -> SolutionDomainTest.CATALOG.findReviewed(i.getArgument(0)));
        assertEquals(1, send(id, text).stateVersion());
    }

    @Test void staleVersionForeignOwnerAndCompletedStatusCannotChangeAnything() {
        long id = start();
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> send(id, choice(UUID.randomUUID(), 1, "transferredMoney", "NO")));
        fails(ErrorCode.SOLUTION_NOT_FOUND, () -> service.send(id, text(UUID.randomUUID(), 0, "설명"),
                () -> Optional.of(SolutionOwner.anonymous(UUID.randomUUID()))));
        jdbc.update("UPDATE solution_sessions SET status = 'COMPLETED' WHERE id = ?", id);
        fails(ErrorCode.SOLUTION_FINISHED, () -> send(id, text(UUID.randomUUID(), 0, "설명")));
        assertEquals(0, version(id)); assertEquals(2, count(id)); verifyNoInteractions(ai);
    }

    @Test void concurrentDifferentIdsHaveOneWinnerAndOneVersionConflict() throws Exception { race(false); }
    @Test void concurrentSameIdHasOneWinnerAndOneDuplicateWithoutExtraMessages() throws Exception { race(true); }

    private void race(boolean sameId) throws Exception {
        long id = start();
        var barrier = new CyclicBarrier(2);
        var lost = new AtomicInteger();
        var update = new SolutionSessionUpdatesImpl(jdbc, entityManager);
        doAnswer(i -> {
            assertEquals("read committed", jdbc.queryForObject("SHOW transaction_isolation", String.class));
            barrier.await(10, TimeUnit.SECONDS);
            int rows = update.compareAndSet(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
            if (rows == 0) lost.incrementAndGet();
            return rows;
        }).when(sessions).compareAndSet(eq(id), any(), anyInt(), any());
        UUID first = UUID.randomUUID(); UUID second = sameId ? first : UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Object> a = () -> outcome(id, choice(first, 0, "transferredMoney", "NO"));
            Callable<Object> b = () -> outcome(id, choice(second, 0, "transferredMoney", "UNKNOWN"));
            var results = executor.invokeAll(List.of(a,b), 20, TimeUnit.SECONDS);
            List<Object> outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertEquals(1, outcomes.stream().filter(Integer.valueOf(1)::equals).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter((sameId ? ErrorCode.DUPLICATE_REQUEST : ErrorCode.STATE_VERSION_CONFLICT)::equals).count(), outcomes.toString());
        }
        assertEquals(1, lost.get(), "READ COMMITTED 경쟁의 패자는 UPDATE 0행이어야 해요.");
        assertEquals(1, version(id)); assertEquals(4, count(id));
    }

    private Object outcome(long id, SendSolutionMessageRequest request) {
        try { return send(id, request).stateVersion(); }
        catch (ApiException e) { return e.errorCode(); }
    }

    @Test void callingInsideOuterTransactionSuspendsItForAiAndRestoresItAfterwards() {
        long id = start();
        new TransactionTemplate(manager).execute(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            send(id, text(UUID.randomUUID(), 0, "일반 설명"));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        });
        assertEquals(1, version(id));
    }

    @Test void memberCasUsesMemberConditionAndNeverAnonymousOwnerCondition() {
        Long userId = jdbc.queryForObject("INSERT INTO users (email, password_hash, status) VALUES (?, 'test', 'ACTIVE') RETURNING id", Long.class,
                "message-db-" + UUID.randomUUID() + "@example.com");
        long id = 0;
        try {
            var member = SolutionOwner.member(userId);
            id = starts.start(StartSolutionRequest.empty(), () -> member).solutionId(); created.add(id);
            assertEquals(1, service.send(id, choice(UUID.randomUUID(), 0, "transferredMoney", "NO"), () -> Optional.of(member)).stateVersion());
        } finally {
            if (id != 0) {
                jdbc.update("DELETE FROM solution_messages WHERE solution_id = ?", id);
                jdbc.update("DELETE FROM solution_sessions WHERE id = ?", id);
                created.remove(id);
            }
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
