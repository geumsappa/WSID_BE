package com.wsid.training.repository;

import com.wsid.training.domain.TrainingSession;
import com.wsid.training.domain.TrainingStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface TrainingSessionRepository extends JpaRepository<TrainingSession, Long> {

    /** 소유권 확인을 겸한 조회. 다른 사용자의 세션이면 비어 있어요. */
    Optional<TrainingSession> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TrainingSession s where s.id = :id and s.userId = :userId")
    Optional<TrainingSession> lockOwned(long id, long userId);

    /** 홈의 '이어서 하기': 가장 최근에 활동한 진행 중 훈련 */
    Optional<TrainingSession> findFirstByUserIdAndStatusAndExpiresAtAfterOrderByLastActivityAtDescIdDesc(
            Long userId, TrainingStatus status, Instant now);
}
