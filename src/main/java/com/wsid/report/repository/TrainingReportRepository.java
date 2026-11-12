package com.wsid.report.repository;

import com.wsid.report.domain.TrainingReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingReportRepository extends JpaRepository<TrainingReport, Long> {

    Optional<TrainingReport> findByTrainingId(Long trainingId);

    // TODO(RPT-002): 로그인한 사용자가 저장한 리포트 목록 (training_sessions.user_id로 소유권 확인, 최신 완료순)
}
