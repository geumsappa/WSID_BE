package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** JpaTransactionManager와 같은 DataSource를 사용해 메시지 저장과 원자적으로 커밋해요. */
public class SolutionSessionUpdatesImpl implements SolutionSessionUpdates {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public SolutionSessionUpdatesImpl(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Override
    public int compareAndSet(long id, SolutionOwner owner, int expectedVersion, SolutionSession.Update update) {
        String ownerCondition = owner.isMember() ? "user_id = ?" : "anonymous_owner_id = ?";
        int rows = jdbc.update("""
                UPDATE solution_sessions
                   SET facts_json = CAST(? AS jsonb), card_codes_json = CAST(? AS jsonb),
                       next_question_json = CAST(? AS jsonb), updated_at = ?, state_version = state_version + 1
                 WHERE id = ? AND status = 'ACTIVE' AND state_version = ? AND
                """ + ownerCondition,
                update.factsJson(), update.cardsJson(), update.questionJson(), Timestamp.from(update.updatedAt()),
                id, expectedVersion, owner.isMember() ? owner.userId() : owner.anonymousId());
        // 전용 쓰기 트랜잭션에서 아직 메시지를 저장하기 전 호출해요. 변경 전 JPA 캐시를 버려요.
        entityManager.clear();
        return rows;
    }

    @Override
    public int completeActive(long id, SolutionOwner owner, Instant now) {
        String ownerCondition = owner.isMember() ? "user_id = ?" : "anonymous_owner_id = ?";
        int rows = jdbc.update("""
                UPDATE solution_sessions
                   SET status = 'COMPLETED', completed_at = ?, updated_at = ?,
                       state_version = state_version + 1, next_question_json = NULL
                 WHERE id = ? AND status = 'ACTIVE' AND
                """ + ownerCondition,
                Timestamp.from(now), Timestamp.from(now), id, owner.isMember() ? owner.userId() : owner.anonymousId());
        // 0행일 때도 다른 종료가 커밋한 최신 상태를 읽어야 해요.
        entityManager.clear();
        return rows;
    }
}
