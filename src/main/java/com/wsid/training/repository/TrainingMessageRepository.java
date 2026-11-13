package com.wsid.training.repository;

import com.wsid.training.domain.TrainingMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingMessageRepository extends JpaRepository<TrainingMessage, Long> {

    List<TrainingMessage> findByTrainingIdOrderBySequenceNoAsc(Long trainingId);

    List<TrainingMessage> findByTrainingIdAndPhaseOrderBySequenceNoAsc(Long trainingId, com.wsid.training.domain.TrainingPhase phase);

    boolean existsByTrainingIdAndClientRequestId(Long trainingId, UUID clientRequestId);

    long countByTrainingId(Long trainingId);
}
