package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SolutionMessageRepository extends JpaRepository<SolutionMessage, Long> {

    List<SolutionMessage> findBySolutionIdOrderBySequenceNoAsc(Long solutionId);

    boolean existsBySolutionIdAndClientRequestId(Long solutionId, UUID clientRequestId);

    long countBySolutionId(Long solutionId);

    @Query("select coalesce(max(m.sequenceNo), 0) from SolutionMessage m where m.solutionId = :id")
    int lastSequence(@Param("id") long id);

    @Query("""
            select m.factKey from SolutionMessage m where m.solutionId = :id
              and m.sender = com.wsid.common.domain.MessageSender.USER
              and m.inputKind = com.wsid.common.domain.InputKind.OPTION
              and m.selectedCode = 'UNKNOWN' and m.factKey is not null
            """)
    List<String> answeredUnknownKeys(@Param("id") long id);
}
