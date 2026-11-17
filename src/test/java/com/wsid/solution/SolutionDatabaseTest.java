package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.wsid.ai.AiClient;
import com.wsid.ai.dto.SolutionExtractResult;
import com.wsid.auth.domain.User;
import com.wsid.auth.repository.UserRepository;
import com.wsid.solution.domain.ResponseCardCatalog;
import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.repository.SolutionMessageRepository;
import com.wsid.solution.repository.SolutionSessionRepository;
import com.wsid.solution.service.SolutionQueryService;
import com.wsid.solution.service.StartSolutionService;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** V3 마이그레이션·소유자 제약·초기 버전·원문 미저장을 실제 PostgreSQL에서 확인해요. */
@Tag("db")
@SpringBootTest(properties = "spring.datasource.url=${WSID_TEST_DB_URL:jdbc:postgresql://localhost:5432/wsid_ai01_test}")
class SolutionDatabaseTest {
    private static final String SECRET = "모르는 사람에게 돈을 보냈고 연락이 끊겼어요. 비공개문장";

    @Autowired StartSolutionService service;
    @Autowired SolutionQueryService queries;
    @Autowired SolutionSessionRepository sessions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean AiClient ai;
    @MockitoBean ResponseCardCatalog cards;
    @MockitoSpyBean SolutionMessageRepository messages;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> solutionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(cards.findReviewed(any())).thenAnswer(i -> SolutionDomainTest.CATALOG.findReviewed(i.getArgument(0)));
        when(ai.extractSolutionFacts(any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new SolutionExtractResult(SolutionDomainTest.facts("YES", "UNKNOWN", "UNKNOWN", "NO"),
                    List.of("sharedInformation", "installedApp"));
        });
    }

    @AfterEach
    void cleanUp() {
        for (long id : solutionIds) {
            jdbc.update("DELETE FROM solution_messages WHERE solution_id = ?", id);
            jdbc.update("DELETE FROM solution_sessions WHERE id = ?", id);
        }
        for (long id : userIds) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    private long user() {
        long id = users.saveAndFlush(User.localDev(UUID.randomUUID() + "@test.invalid", Instant.now())).getId();
        userIds.add(id);
        return id;
    }

    private StartSolutionRequest described() {
        return StartSolutionRequest.from(new ObjectMapper().createObjectNode().put("description", SECRET));
    }

    @Test
    void memberStartIsStoredWithVersionZeroCanonicalJsonAndNoDescription() {
        long userId = user();
        var response = service.start(described(), () -> SolutionOwner.member(userId));
        solutionIds.add(response.solutionId());
        assertEquals(0, response.stateVersion());
        var row = jdbc.queryForMap("SELECT user_id, anonymous_owner_id, status, state_version, fraud_type_code, "
                + "facts_json::text AS facts, card_codes_json::text AS cards, next_question_json::text AS question "
                + "FROM solution_sessions WHERE id = ?", response.solutionId());
        assertEquals(userId, ((Number) row.get("user_id")).longValue());
        assertNull(row.get("anonymous_owner_id"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(0, ((Number) row.get("state_version")).intValue());
        assertNull(row.get("fraud_type_code"));
        var json = new ObjectMapper();
        assertEquals(json.readTree("{\"transferredMoney\":\"YES\",\"sharedInformation\":\"UNKNOWN\",\"installedApp\":\"UNKNOWN\",\"contactContinues\":\"NO\"}"),
                json.readTree((String) row.get("facts")));
        assertEquals(json.readTree("[{\"code\":\"TRANSFER_RESPONSE\",\"version\":1}]"), json.readTree((String) row.get("cards")));
        assertEquals("sharedInformation", json.readTree((String) row.get("question")).path("factKey").asString());

        var stored = jdbc.queryForList("SELECT sender, input_kind, safe_content, sequence_no, client_request_id "
                + "FROM solution_messages WHERE solution_id = ? ORDER BY sequence_no", response.solutionId());
        assertEquals(2, stored.size());
        assertEquals("USER", stored.get(0).get("sender"));
        assertEquals("피해 설명을 제출했습니다.", stored.get(0).get("safe_content"));
        assertNotNull(stored.get(0).get("client_request_id"));
        assertEquals("SYSTEM", stored.get(1).get("sender"));
        assertNull(stored.get(1).get("client_request_id"));
        Integer leaked = jdbc.queryForObject("SELECT count(*) FROM solution_sessions s LEFT JOIN solution_messages m "
                + "ON m.solution_id = s.id WHERE s.id = ? AND (s::text LIKE '%비공개문장%' OR m::text LIKE '%비공개문장%')",
                Integer.class, response.solutionId());
        assertEquals(0, leaked);
    }

    @Test
    void anonymousOwnersAreSeparatedFromEachOtherAndFromMembers() {
        long userId = user();
        var anonymousA = UUID.randomUUID();
        var anonymousB = UUID.randomUUID();
        long a = service.start(StartSolutionRequest.empty(), () -> SolutionOwner.anonymous(anonymousA)).solutionId();
        long member = service.start(StartSolutionRequest.empty(), () -> SolutionOwner.member(userId)).solutionId();
        solutionIds.addAll(List.of(a, member));
        verifyNoInteractions(ai);
        assertTrue(sessions.findOwned(a, SolutionOwner.anonymous(anonymousA)).isPresent());
        assertTrue(sessions.findOwned(a, SolutionOwner.anonymous(anonymousB)).isEmpty());
        assertTrue(sessions.findOwned(a, SolutionOwner.member(userId)).isEmpty());
        assertTrue(sessions.findOwned(member, SolutionOwner.member(userId)).isPresent());
        assertTrue(sessions.findOwned(member, SolutionOwner.anonymous(anonymousA)).isEmpty());
        assertEquals(anonymousA, jdbc.queryForObject("SELECT anonymous_owner_id FROM solution_sessions WHERE id = ?", UUID.class, a));
    }

    @Test
    void checkConstraintRejectsMissingOrDoubleOwnerAndForeignKeyStays() {
        long userId = user();
        String insert = "INSERT INTO solution_sessions (user_id, anonymous_owner_id) VALUES (?, ?)";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, userId, UUID.randomUUID()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, Long.MAX_VALUE, null));
        assertEquals(3, jdbc.queryForObject(
                "SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class));
    }

    @Test
    void messageFailureRollsBackTheSession() {
        var owner = UUID.randomUUID();
        doThrow(new IllegalStateException("message insert failed")).when(messages).save(any());
        assertThrows(IllegalStateException.class,
                () -> service.start(StartSolutionRequest.empty(), () -> SolutionOwner.anonymous(owner)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM solution_sessions WHERE anonymous_owner_id = ?",
                Integer.class, owner));
    }

    // ---------- SOL-02 ----------

    @Test
    void restoreReadsOwnedRowsAndChangesNothing() {
        long userId = user();
        var anonymous = UUID.randomUUID();
        var created = service.start(described(), () -> SolutionOwner.member(userId));
        long anon = service.start(StartSolutionRequest.empty(), () -> SolutionOwner.anonymous(anonymous)).solutionId();
        solutionIds.addAll(List.of(created.solutionId(), anon));
        String before = jdbc.queryForObject("SELECT row(s.*)::text FROM solution_sessions s WHERE id = ?", String.class, created.solutionId());

        var restored = queries.get(created.solutionId(), () -> Optional.of(SolutionOwner.member(userId)));
        assertEquals(created.facts(), restored.facts());
        assertEquals(created.responseCards(), restored.responseCards());
        assertEquals(created.nextQuestion(), restored.nextQuestion());
        assertEquals(0, restored.stateVersion());
        assertEquals(List.of(1, 2), restored.messages().stream().map(m -> m.sequenceNo()).toList());
        assertEquals(before, jdbc.queryForObject("SELECT row(s.*)::text FROM solution_sessions s WHERE id = ?", String.class, created.solutionId()));

        assertEquals(anon, queries.get(anon, () -> Optional.of(SolutionOwner.anonymous(anonymous))).solutionId());
        assertThrows(com.wsid.common.error.ApiException.class,
                () -> queries.get(anon, () -> Optional.of(SolutionOwner.anonymous(UUID.randomUUID()))));
        assertThrows(com.wsid.common.error.ApiException.class,
                () -> queries.get(anon, () -> Optional.of(SolutionOwner.member(userId))));
        assertThrows(com.wsid.common.error.ApiException.class,
                () -> queries.get(created.solutionId(), () -> Optional.of(SolutionOwner.anonymous(anonymous))));
    }

    /** 상담 행을 읽은 뒤 다른 트랜잭션이 메시지를 추가·버전을 올려도, 응답은 같은 시점의 버전·메시지예요(REPEATABLE_READ). */
    @Test
    void restoreUsesOneSnapshotEvenIfAnotherWriteCommitsMidway() {
        var owner = UUID.randomUUID();
        long id = service.start(StartSolutionRequest.empty(), () -> SolutionOwner.anonymous(owner)).solutionId();
        solutionIds.add(id);
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> {
                jdbc.update("INSERT INTO solution_messages (solution_id, sender, input_kind, sequence_no) VALUES (?, 'USER', 'OPTION', 3)", id);
                jdbc.update("UPDATE solution_sessions SET state_version = state_version + 1 WHERE id = ?", id);
            }).join();
            // 스파이는 JPA 프록시를 감싸 실제 메서드를 호출할 수 없어서, 같은 트랜잭션의 공유 EntityManager로 만든 저장소에 맡겨요.
            return new JpaRepositoryFactory(entityManager).getRepository(SolutionMessageRepository.class)
                    .findBySolutionIdOrderBySequenceNoAsc(id);
        }).when(messages).findBySolutionIdOrderBySequenceNoAsc(id);

        var restored = queries.get(id, () -> Optional.of(SolutionOwner.anonymous(owner)));
        assertEquals(0, restored.stateVersion());
        assertEquals(2, restored.messages().size(), "중간에 커밋된 메시지는 같은 응답에 섞이지 않아요.");
        reset(messages);
        var latest = queries.get(id, () -> Optional.of(SolutionOwner.anonymous(owner)));
        assertEquals(1, latest.stateVersion());
        assertEquals(3, latest.messages().size());
    }
}
