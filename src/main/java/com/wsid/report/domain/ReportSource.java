package com.wsid.report.domain;

/** Public source credit only; no user conversation or generated dialogue. */
public record ReportSource(String title, String issuer, String author, String publishedDate,
        String sourceUrl, String license) {}
