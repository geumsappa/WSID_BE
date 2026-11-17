package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionOwner;
import com.wsid.solution.domain.SolutionSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SolutionSessionRepository extends JpaRepository<SolutionSession, Long>, SolutionSessionUpdates {

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
