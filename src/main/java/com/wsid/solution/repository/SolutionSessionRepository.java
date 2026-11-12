package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SolutionSessionRepository extends JpaRepository<SolutionSession, Long> {

    /** 소유권 확인을 겸한 조회. 다른 사용자의 세션이면 비어 있어요. */
    Optional<SolutionSession> findByIdAndUserId(Long id, Long userId);
}
