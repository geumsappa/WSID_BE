package com.wsid.report.service;

import static org.junit.jupiter.api.Assertions.*;
import com.wsid.report.domain.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReportPdfTest {
    @Test void sourceCreditsAndClickableOriginalLinksSurvivePageWrapping() throws Exception {
        String url = ReportSourcesTest.URL + "&search=" + "a".repeat(160);
        var source = new ReportSource("가족 사칭 피해 예방 자료", "자료 발행기관", "작성자", "2025-05-22", url, "KOGL-1");
        var evaluation = new ReportEvaluation("공식 채널에서 확인하세요.", Collections.nCopies(25, "모르는 상대의 요청은 먼저 확인하세요."),
                List.of("개인정보를 전달하기 전에 확인하세요."), List.of(), new ReportEvaluation.MockPageEvaluation(false, false, true),
                List.of("상대가 보내준 연락처를 그대로 믿지 마세요."), List.of(source));
        byte[] bytes = new ReportPdf().render(new ReportService.Detail(7L, "출처 표시 검증용 훈련 리포트", true, evaluation));
        var output = Path.of("build/reports/source-attribution");
        Files.createDirectories(output);
        Files.write(output.resolve("report.pdf"), bytes);
        try (var pdf = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("참고한 실제 사기 수법"));
            assertTrue(text.contains("자료 발행기관"));
            assertTrue(text.contains("공공누리 제1유형"));
            assertTrue(text.contains("요약·재구성"));
            assertTrue(text.replaceAll("\\s+", "").contains("원기관이제작·후원한자료가아닙니다"));
            assertTrue(pdf.getNumberOfPages() > 1);
            var last = new PDFTextStripper();
            last.setStartPage(pdf.getNumberOfPages());
            assertTrue(last.getText(pdf).contains("참고한 실제 사기 수법"), "Credit heading must not be orphaned on previous page");
            int links = 0;
            for (var page : pdf.getPages()) for (var annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link) {
                    assertEquals(url, ((PDActionURI) link.getAction()).getURI());
                    assertTrue(link.getRectangle().getLowerLeftY() >= 40);
                    assertTrue(link.getRectangle().getUpperRightY() <= page.getMediaBox().getHeight());
                    links++;
                }
            }
            assertTrue(links >= 2, "Long URLs should wrap with a clickable annotation per line");
            var renderer = new PDFRenderer(pdf);
            ImageIO.write(renderer.renderImageWithDPI(0, 100), "png", output.resolve("page-first.png").toFile());
            ImageIO.write(renderer.renderImageWithDPI(pdf.getNumberOfPages() - 1, 100), "png", output.resolve("page-last.png").toFile());
        }
    }

    @Test void oldReportDoesNotShowEmptyCredits() throws Exception {
        var evaluation = new ReportEvaluation("이전 평가", List.of(), List.of(), List.of(),
                new ReportEvaluation.MockPageEvaluation(false, false, false), List.of());
        try (var pdf = Loader.loadPDF(new ReportPdf().render(new ReportService.Detail(7L, "이전 리포트", true, evaluation)))) {
            assertFalse(new PDFTextStripper().getText(pdf).contains("참고한 실제 사기 수법"));
        }
    }
}
