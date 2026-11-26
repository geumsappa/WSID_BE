package com.wsid.report.repository;

import com.wsid.report.domain.TrainingReport;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TrainingReportRepository extends JpaRepository<TrainingReport, Long> {

    Optional<TrainingReport> findByTrainingId(Long trainingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from TrainingReport r
            where r.id = :reportId and exists (
                select 1 from TrainingSession s where s.id = r.trainingId and s.userId = :userId)
            """)
    Optional<TrainingReport> lockOwned(long reportId, long userId);

    @Query("""
            select r from TrainingReport r
            where r.id = :reportId and exists (
                select 1 from TrainingSession s where s.id = r.trainingId and s.userId = :userId)
            """)
    Optional<TrainingReport> findOwned(long reportId, long userId);

    @Query("""
            select r.id as reportId, r.title as title, s.fraudTypeCode as fraudTypeCode, s.completedAt as completedAt
            from TrainingReport r join TrainingSession s on s.id = r.trainingId
            where s.userId = :userId and r.savedAt is not null and s.completedAt is not null
                and (:firstPage = true or s.completedAt < :before
                    or (s.completedAt = :before and r.id < :beforeId))
            order by s.completedAt desc, r.id desc
            """)
    List<SavedRow> findSavedPage(long userId, boolean firstPage, Instant before, long beforeId, Pageable limit);

    interface SavedRow {
        long getReportId();
        String getTitle();
        String getFraudTypeCode();
        Instant getCompletedAt();
    }
}
