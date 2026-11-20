package com.wsid.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnTransformer;

/**
 * training_reports 테이블. 훈련이 끝나면 만들어지고(SIM-010),
 * 사용자가 '내 리포트에 저장'을 누르면 saved_at이 채워져요(RPT-001).
 */
@Entity
@Table(name = "training_reports")
public class TrainingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "training_id", nullable = false, updatable = false)
    private Long trainingId;

    @Column(nullable = false, length = 200)
    private String title;

    /** 평가 결과 JSON 문자열. 대화 원문은 넣지 않아요. */
    @Column(name = "report_json", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String reportJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "saved_at")
    private Instant savedAt;

    protected TrainingReport() {
        // JPA용
    }

    public static TrainingReport create(long trainingId, String title, String reportJson, Instant now) {
        TrainingReport report = new TrainingReport();
        report.trainingId = trainingId;
        report.title = title;
        report.reportJson = reportJson;
        report.createdAt = now;
        return report;
    }

    /** RPT-001: 다시 눌러도 처음 저장한 시각을 유지해요. 목록에는 한 번만 보여요. */
    public void saveToMyReports(Instant now) {
        if (savedAt == null) {
            savedAt = now;
        }
    }

    public boolean isSaved() {
        return savedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getTrainingId() {
        return trainingId;
    }

    public String getTitle() {
        return title;
    }

    public String getReportJson() {
        return reportJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}
