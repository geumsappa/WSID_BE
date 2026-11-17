package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.dto.UpdateSolutionFactsRequest;
import com.wsid.solution.repository.*;
import com.wsid.solution.service.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 PostgreSQL: SOL-04 CAS·정정 기록·롤백·SOL-03과의 경쟁. 다른 프로젝트 DB에서는 실행하지 않아요. */
@Tag("db")
@SpringBootTest(properties = "spring.datasource.url=${WSID_TEST_DB_URL:jdbc:postgresql://localhost:5432/wsid_ai01_test}")
class SolutionFactsDatabaseTest {
    @Autowired StartSolutionService starts;
    @Autowired UpdateSolutionFactsService service;
    @Autowired SendSolutionMessageService messageService;
    @Autowired SolutionQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean SolutionSessionRepository sessions;
    @MockitoSpyBean SolutionMessageRepository messages;
    @MockitoBean AiClient ai;
    @MockitoBean ResponseCardCatalog cards;
    private final List<Long> created = new ArrayList<>();
    private final SolutionOwner owner = SolutionOwner.anonymous(UUID.randomUUID());

    @BeforeEach void setup() {
        when(cards.findReviewed(anyString())).thenAnswer(i -> SolutionDomainTest.CATALOG.findReviewed(i.getArgument(0)));
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
    private UpdateSolutionFactsRequest change(int version, String... keyValues) {
        return UpdateSolutionFactsRequest.from(UpdateSolutionFactsRequestTest.body(version, keyValues));
    }
    private com.wsid.solution.dto.SolutionMessageResponse update(long id, UpdateSolutionFactsRequest request) {
        return service.update(id, request, () -> Optional.of(owner));
    }
    private SendSolutionMessageRequest choice(int version, String key, String value) {
        return SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.choice(UUID.randomUUID(), version, key, value));
    }
    private int version(long id) { return jdbc.queryForObject("SELECT state_version FROM solution_sessions WHERE id = ?", Integer.class, id); }
    private int count(long id) { return jdbc.queryForObject("SELECT count(*) FROM solution_messages WHERE solution_id = ?", Integer.class, id); }
    private String factsJson(long id) { return jdbc.queryForObject("SELECT facts_json::text FROM solution_sessions WHERE id = ?", String.class, id); }
    private void fails(ErrorCode code, Runnable run) { assertEquals(code, assertThrows(ApiException.class, run::run).errorCode()); }

    @Test void correctionCommitsOneVersionRecordsWithNullUuidAndRestoresSameState() {
        long id = start();
        var after = update(id, change(0, "installedApp", "YES", "transferredMoney", "YES"));
        assertEquals(1, after.stateVersion()); assertEquals(1, version(id));
        assertEquals(5, count(id)); // 시작 2 + 정정 2 + 바뀐 질문 1
        var rows = jdbc.queryForList("SELECT fact_key, selected_code, input_kind, client_request_id, safe_content FROM solution_messages "
                + "WHERE solution_id = ? AND sequence_no IN (3, 4) ORDER BY sequence_no", id);
        assertEquals(List.of("transferredMoney", "installedApp"), rows.stream().map(r -> r.get("fact_key")).toList());
        assertTrue(rows.stream().allMatch(r -> r.get("client_request_id") == null && "OPTION".equals(r.get("input_kind"))));
        assertTrue(rows.stream().allMatch(r -> ((String) r.get("safe_content")).startsWith(SolutionMessage.CORRECTION_PREFIX)));
        var restored = queries.get(id, () -> Optional.of(owner));
        assertEquals(after.facts(), restored.facts());
        assertEquals(after.nextQuestion(), restored.nextQuestion());
        assertEquals(after.responseCards(), restored.responseCards());
    }

    @Test void unknownCorrectionStaysAnsweredForLaterSol03Choices() {
        long id = start();
        update(id, change(0, "transferredMoney", "UNKNOWN"));
        assertEquals(List.of("transferredMoney"), messages.answeredUnknownKeys(id));
        var next = messageService.send(id, choice(1, "sharedInformation", "NO"), () -> Optional.of(owner));
        assertEquals("installedApp", next.nextQuestion().factKey(), "UNKNOWN으로 고친 송금 질문을 다시 묻지 않아요.");
        assertEquals(2, next.stateVersion());
    }

    @Test void failedRecordFlushRollsBackStateAndRecords() {
        long id = start(); String before = factsJson(id);
        doThrow(new IllegalStateException("forced")).when(messages).flush();
        assertThrows(IllegalStateException.class, () -> update(id, change(0, "installedApp", "NO")));
        assertEquals(0, version(id)); assertEquals(2, count(id)); assertEquals(before, factsJson(id));
    }

    @Test void staleForeignAndCompletedChangeNothing() {
        long id = start();
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> update(id, change(1, "installedApp", "NO")));
        fails(ErrorCode.SOLUTION_NOT_FOUND, () -> service.update(id, change(0, "installedApp", "NO"),
                () -> Optional.of(SolutionOwner.anonymous(UUID.randomUUID()))));
        jdbc.update("UPDATE solution_sessions SET status = 'COMPLETED' WHERE id = ?", id);
        fails(ErrorCode.SOLUTION_FINISHED, () -> update(id, change(0, "installedApp", "NO")));
        assertEquals(0, version(id)); assertEquals(2, count(id));
    }

    @Test void twoCorrectionsOfDifferentFactsHaveOneWinnerWithoutMerging() throws Exception {
        long id = start();
        race(id, () -> update(id, change(0, "installedApp", "YES")).stateVersion(),
                () -> update(id, change(0, "contactContinues", "NO")).stateVersion());
        var facts = queries.get(id, () -> Optional.of(owner)).facts();
        assertEquals(1, facts.values().stream().filter(v -> !v.equals("UNKNOWN")).count(), "두 수정을 자동으로 합치지 않아요.");
    }

    @Test void correctionAndSol03ChoiceRaceHasOneWinner() throws Exception {
        long id = start();
        race(id, () -> update(id, change(0, "installedApp", "YES")).stateVersion(),
                () -> messageService.send(id, choice(0, "transferredMoney", "NO"), () -> Optional.of(owner)).stateVersion());
    }

    private void race(long id, Callable<Object> a, Callable<Object> b) throws Exception {
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
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(wrap(a), wrap(b)), 20, TimeUnit.SECONDS);
            List<Object> outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertEquals(1, outcomes.stream().filter(Integer.valueOf(1)::equals).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter(ErrorCode.STATE_VERSION_CONFLICT::equals).count(), outcomes.toString());
        }
        assertEquals(1, lost.get(), "READ COMMITTED 경쟁의 패자는 UPDATE 0행이어야 해요.");
        assertEquals(1, version(id));
    }

    private static Callable<Object> wrap(Callable<Object> call) {
        return () -> { try { return call.call(); } catch (ApiException e) { return e.errorCode(); } };
    }

    @Test void lateSol03TextAfterCorrectionIs409AndKeepsCorrectedValue() {
        long id = start();
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            // AI 응답을 기다리는 동안 사용자가 사실을 직접 고쳐요.
            assertEquals(1, update(id, change(0, "transferredMoney", "NO")).stateVersion());
            var facts = SolutionDomainTest.facts("YES", "UNKNOWN", "UNKNOWN", "UNKNOWN");
            return new SolutionExtractResult(facts, List.of("sharedInformation", "installedApp", "contactContinues"));
        });
        var text = SendSolutionMessageRequest.from(SendSolutionMessageRequestTest.text(UUID.randomUUID(), 0, "송금했어요"));
        fails(ErrorCode.STATE_VERSION_CONFLICT, () -> messageService.send(id, text, () -> Optional.of(owner)));
        assertEquals(1, version(id));
        assertEquals("NO", queries.get(id, () -> Optional.of(owner)).facts().get("transferredMoney"));
    }

    @Test void memberCorrectionUsesMemberOwnerCondition() {
        Long userId = jdbc.queryForObject("INSERT INTO users (email, password_hash, status) VALUES (?, 'test', 'ACTIVE') RETURNING id", Long.class,
                "facts-db-" + UUID.randomUUID() + "@example.com");
        long id = 0;
        try {
            var member = SolutionOwner.member(userId);
            id = starts.start(StartSolutionRequest.empty(), () -> member).solutionId(); created.add(id);
            long sid = id;
            assertEquals(1, service.update(sid, change(0, "installedApp", "NO"), () -> Optional.of(member)).stateVersion());
            fails(ErrorCode.SOLUTION_NOT_FOUND, () -> service.update(sid, change(1, "installedApp", "YES"), () -> Optional.of(owner)));
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
