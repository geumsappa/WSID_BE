package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SolutionMessageRepository extends JpaRepository<SolutionMessage, Long> {

    List<SolutionMessage> findBySolutionIdOrderBySequenceNoAsc(Long solutionId);

    boolean existsBySolutionIdAndClientRequestId(Long solutionId, UUID clientRequestId);

    long countBySolutionId(Long solutionId);
}
