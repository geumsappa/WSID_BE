package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SolutionSessionRepository extends JpaRepository<SolutionSession, Long>, SolutionSessionUpdates {

    interface CompletedRow {
        Long getId(); String getTitle(); String getFactsJson(); java.time.Instant getCreatedAt(); java.time.Instant getCompletedAt();
    }
    @org.springframework.data.jpa.repository.Query("""
        select s.id as id, s.title as title, s.factsJson as factsJson, s.createdAt as createdAt, s.completedAt as completedAt
          from SolutionSession s where s.userId = :userId and s.status = com.wsid.solution.domain.SolutionStatus.COMPLETED
          and (:first = true or s.completedAt < :before or (s.completedAt = :before and s.id < :beforeId))
          order by s.completedAt desc, s.id desc
        """)
    java.util.List<CompletedRow> findCompletedPage(@org.springframework.data.repository.query.Param("userId") long userId,
            @org.springframework.data.repository.query.Param("first") boolean first,
            @org.springframework.data.repository.query.Param("before") java.time.Instant before,
            @org.springframework.data.repository.query.Param("beforeId") long beforeId,
            org.springframework.data.domain.Pageable pageable);

    /** 소유권 확인을 겸한 조회. 다른 사용자의 세션이면 비어 있어요. */
    Optional<SolutionSession> findByIdAndUserId(Long id, Long userId);

    /** 비회원 소유권 확인을 겸한 조회. 다른 익명 세션의 상담이면 비어 있어요. */
    Optional<SolutionSession> findByIdAndAnonymousOwnerId(Long id, UUID anonymousOwnerId);

    /** SOL-02~05가 공통으로 쓰는 소유자별 조회. 없는 상담과 남의 상담을 구분하지 않아요. */
    default Optional<SolutionSession> findOwned(long id, SolutionOwner owner) {
        return owner.isMember() ? findByIdAndUserId(id, owner.userId())
                : findByIdAndAnonymousOwnerId(id, owner.anonymousId());
    }
}
