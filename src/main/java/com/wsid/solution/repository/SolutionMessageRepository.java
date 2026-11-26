package com.wsid.solution.repository;

import com.wsid.solution.domain.SolutionMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SolutionMessageRepository extends JpaRepository<SolutionMessage, Long> {

    List<SolutionMessage> findBySolutionIdOrderBySequenceNoAsc(Long solutionId);

    /**
     * from 위치의 SYSTEM 메시지(다음 고정 질문)를 비어 있는 to 위치로 옮겨요. 옮겼으면 1, SYSTEM이 아니면 0.
     * 후속 안내(AI)를 그 질문 앞에 끼워 넣을 때만 써요.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SolutionMessage m set m.sequenceNo = :to
             where m.solutionId = :id and m.sequenceNo = :from
               and m.sender = com.wsid.common.domain.MessageSender.SYSTEM
            """)
    int moveSystemMessage(@Param("id") long id, @Param("from") int from, @Param("to") int to);

    boolean existsBySolutionIdAndClientRequestId(Long solutionId, UUID clientRequestId);

    long countBySolutionId(Long solutionId);

    /** 상담 삭제(저장 없이 나가기·기록 삭제)할 때 대화를 먼저 지워요 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SolutionMessage m where m.solutionId = :id")
    int deleteAllOfSolution(@Param("id") long id);

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
