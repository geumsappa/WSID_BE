package com.wsid.report.service;

import com.wsid.auth.CurrentUser;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.report.domain.ReportEvaluation;
import com.wsid.report.domain.TrainingReport;
import com.wsid.report.repository.TrainingReportRepository;
import com.wsid.training.domain.TrainingSession;
import com.wsid.training.domain.TrainingStatus;
import com.wsid.training.domain.UserAction;
import com.wsid.training.repository.TrainingMessageRepository;
import com.wsid.training.repository.TrainingSessionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class ReportService {
    private final CurrentUser currentUser;
    private final TrainingSessionRepository sessions;
    private final TrainingMessageRepository messages;
    private final TrainingReportRepository reports;
    private final ObjectMapper json;

    public ReportService(CurrentUser currentUser, TrainingSessionRepository sessions,
            TrainingMessageRepository messages, TrainingReportRepository reports, ObjectMapper json) {
        this.currentUser = currentUser;
        this.sessions = sessions;
        this.messages = messages;
        this.reports = reports;
        this.json = json;
    }

    /** 기존 훈련이 완료되는 트랜잭션에서 정해진 문구로 기본 평가를 남겨요. */
    @Transactional
    public void recordCompletion(TrainingSession training, UserAction lastAction, Instant now) {
        recordCompletion(training, lastAction == UserAction.END_CONTACT_AND_VERIFY,
                new ReportEvaluation.MockPageEvaluation(false, false, false), now);
    }

    @Transactional
    public long recordCompletion(TrainingSession training, boolean verified,
            ReportEvaluation.MockPageEvaluation page, Instant now) {
        var evaluation = new ReportEvaluation(
                verified ? "상대와의 연락을 끊고 사실을 확인하는 선택을 했어요." : "훈련을 마쳤어요. 다음 훈련에서는 공식 채널로 사실을 확인해 보세요.",
                verified ? List.of("연락 중단 및 확인 선택") : List.of(),
                verified ? List.of() : List.of("요청을 받으면 응답하기 전에 사실 확인하기"),
                List.of(), page,
                List.of("상대가 보내준 링크 대신 직접 찾은 공식 채널에서 확인하세요."));
        return recordEvaluation(training, evaluation, now);
    }

    @Transactional
    public long recordEvaluation(TrainingSession training, ReportEvaluation evaluation, Instant now) {
        // Snapshot before saving removes the training conversation/scenario. AI evaluation cannot invent credits.
        var sourceCredits = training.getScenarioJson() == null ? List.<com.wsid.report.domain.ReportSource>of()
                : ReportSources.fromScenario(json.readTree(training.getScenarioJson()));
        evaluation = evaluation.withSources(sourceCredits);
        String title = titleOf(fraudType(training.getFraudTypeCode()));
        return reports.save(TrainingReport.create(training.getId(), title, json.writeValueAsString(evaluation), now)).getId();
    }

    public long existingReportId(long trainingId) {
        return reports.findByTrainingId(trainingId).orElseThrow(ReportService::notFound).getId();
    }

    public TrainingResult trainingReport(long id) {
        TrainingSession training = ownerTraining(id, currentUser.id());
        TrainingReport report = reports.findByTrainingId(id).orElseThrow(ReportService::notFound);
        return new TrainingResult(report.getId(), id, report.getTitle(), fraudType(training.getFraudTypeCode()),
                report.isSaved(), evaluation(report));
    }

    /** 마이페이지에서 리포트 삭제. 본인 리포트만, 없거나 남의 리포트면 404 */
    @Transactional
    public void delete(long id) {
        TrainingReport report = reports.lockOwned(id, currentUser.id()).orElseThrow(ReportService::notFound);
        reports.delete(report);
    }

    @Transactional
    public Saved save(long id) {
        long userId = currentUser.id();
        TrainingReport report = reports.lockOwned(id, userId).orElseThrow(ReportService::notFound);
        if (report.isSaved()) {
            return new Saved(report.getId(), true, report.getSavedAt());
        }
        TrainingSession training = ownerTraining(report.getTrainingId(), userId);
        if (training.getStatus() != TrainingStatus.COMPLETED) {
            throw notFound();
        }
        training.clearReportConversation();
        messages.deleteConversation(training.getId());
        report.saveToMyReports(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return new Saved(report.getId(), true, report.getSavedAt());
    }

    public SavedPage saved(int size, String cursor) {
        long userId = currentUser.id();
        if (size < 1 || size > 100) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        Cursor before = cursor == null ? new Cursor(Instant.EPOCH, 0) : Cursor.decode(cursor);
        var rows = reports.findSavedPage(userId, cursor == null, before.completedAt(), before.reportId(),
                PageRequest.of(0, size + 1));
        var items = rows.stream().limit(size).map(row -> new SavedItem(row.getReportId(), row.getTitle(),
                fraudType(row.getFraudTypeCode()), row.getCompletedAt())).toList();
        boolean hasNext = rows.size() > size;
        String next = hasNext ? new Cursor(items.getLast().completedAt(), items.getLast().reportId()).encode() : null;
        return new SavedPage(items, next, hasNext);
    }

    public Detail detail(long id) {
        TrainingReport report = reports.findOwned(id, currentUser.id())
                .filter(TrainingReport::isSaved).orElseThrow(ReportService::notFound);
        return detail(report);
    }

    public Detail forDownload(long id) {
        return detail(reports.findOwned(id, currentUser.id()).orElseThrow(ReportService::notFound));
    }

    private Detail detail(TrainingReport report) {
        return new Detail(report.getId(), report.getTitle(), report.isSaved(), evaluation(report));
    }

    private TrainingSession ownerTraining(long id, long userId) {
        return sessions.findByIdAndUserId(id, userId).orElseThrow(ReportService::notFound);
    }

    private ReportEvaluation evaluation(TrainingReport report) {
        return json.readValue(report.getReportJson(), ReportEvaluation.class);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.REPORT_NOT_FOUND);
    }

    /** 사기 유형별 리포트 제목. 모르는 유형이면 공통 제목 */
    public static String titleOf(String fraudTypeCode) {
        if (fraudTypeCode == null) return "금융사기 예방 훈련";
        return switch (fraudTypeCode) {
            case "SECONDHAND_TRADE" -> "중고거래 금융사기 예방 훈련";
            case "SMISHING" -> "택배·문자 스미싱 예방 훈련";
            case "LOAN_FRAUD" -> "대출 사기 예방 훈련";
            case "JOB_SCAM" -> "취업 사기 예방 훈련";
            case "INSTITUTION_IMPERSONATION" -> "기관 사칭 사기 예방 훈련";
            default -> "금융사기 예방 훈련";
        };
    }

    private static String fraudType(String code) {
        return "USED_GOODS_TRADE".equals(code) ? "SECONDHAND_TRADE" : code;
    }

    public record TrainingResult(long reportId, long trainingId, String title, String fraudTypeCode, boolean saved,
            ReportEvaluation evaluation) {}
    public record Saved(long reportId, boolean saved, Instant savedAt) {}
    public record SavedItem(long reportId, String title, String fraudTypeCode, Instant completedAt) {}
    public record SavedPage(List<SavedItem> items, String nextCursor, boolean hasNext) {}
    public record Detail(long reportId, String title, boolean saved, ReportEvaluation evaluation) {}

    private record Cursor(Instant completedAt, long reportId) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((completedAt + "|" + reportId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            if (value.length() > 128) {
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            }
            try {
                var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2 || Long.parseLong(parts[1]) <= 0) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            }
        }
    }
}
