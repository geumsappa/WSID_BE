package com.wsid.report.controller;

import com.wsid.auth.dto.AuthResponses.Success;
import com.wsid.report.service.ReportPdf;
import com.wsid.report.service.ReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {
    private final ReportService reports;
    private final ReportPdf pdf;

    public ReportController(ReportService reports, ReportPdf pdf) {
        this.reports = reports;
        this.pdf = pdf;
    }

    @GetMapping("/api/trainings/{id}/report")
    public Success<ReportService.TrainingResult> trainingReport(@PathVariable long id) {
        return Success.of(reports.trainingReport(id));
    }

    @PostMapping("/api/reports/{id}/save")
    public Success<ReportService.Saved> save(@PathVariable long id) {
        return Success.of(reports.save(id));
    }

    @GetMapping("/api/reports")
    public Success<ReportService.SavedPage> saved(@RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String cursor) {
        return Success.of(reports.saved(size, cursor));
    }

    @GetMapping("/api/reports/{id}")
    public Success<ReportService.Detail> detail(@PathVariable long id) {
        return Success.of(reports.detail(id));
    }

    @GetMapping("/api/reports/{id}/pdf")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        byte[] document = pdf.render(reports.forDownload(id));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("training-report.pdf").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(document);
    }
}
