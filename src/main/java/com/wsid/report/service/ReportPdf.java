package com.wsid.report.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ReportPdf {
    private static final float MARGIN = 48;
    private static final float FONT_SIZE = 12;
    private static final float LINE_HEIGHT = 19;
    private static final PDRectangle A4 = PDRectangle.A4;

    public byte[] render(ReportService.Detail report) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
                var fontFile = new ClassPathResource("fonts/NanumGothic-Regular.ttf").getInputStream()) {
            var font = PDType0Font.load(document, fontFile);
            int linesPerPage = (int) ((A4.getHeight() - 2 * MARGIN) / LINE_HEIGHT);
            var lines = new ArrayList<PdfLine>();
            for (String paragraph : paragraphs(report)) {
                // A source heading must stay with the institution/title/link that follows it.
                if (paragraph.equals("참고한 실제 사기 수법")) {
                    int remaining = linesPerPage - lines.size() % linesPerPage;
                    if (remaining < 6) for (int i = 0; i < remaining; i++) lines.add(new PdfLine("", null));
                }
                wrap(font, paragraph, paragraph.startsWith("https://") ? paragraph : null, lines);
            }
            for (int start = 0; start < lines.size(); start += linesPerPage) {
                var page = new PDPage(A4);
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, FONT_SIZE);
                    stream.setLeading(LINE_HEIGHT);
                    stream.newLineAtOffset(MARGIN, A4.getHeight() - MARGIN);
                    int row = 0;
                    for (PdfLine line : lines.subList(start, Math.min(start + linesPerPage, lines.size()))) {
                        stream.showText(line.text());
                        if (line.uri() != null && !line.text().isBlank()) {
                            var annotation = new PDAnnotationLink();
                            annotation.setRectangle(new PDRectangle(MARGIN, A4.getHeight() - MARGIN - row * LINE_HEIGHT - 3,
                                    font.getStringWidth(line.text()) * FONT_SIZE / 1000, LINE_HEIGHT));
                            var border = new PDBorderStyleDictionary();
                            border.setWidth(0);
                            annotation.setBorderStyle(border);
                            var action = new PDActionURI();
                            action.setURI(line.uri());
                            annotation.setAction(action);
                            page.getAnnotations().add(annotation);
                        }
                        stream.newLine();
                        row++;
                    }
                    stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("리포트 PDF 생성 실패", e);
        }
    }

    private static List<String> paragraphs(ReportService.Detail report) {
        var evaluation = report.evaluation();
        var lines = new ArrayList<>(List.of(report.title(), "", "종합 평가", evaluation.overallFeedback()));
        var info = evaluation.infoDisclosure();
        if (info != null && (!info.requested().isEmpty() || !info.provided().isEmpty())) {
            lines.add("");
            lines.add("개인정보 제공 여부");
            if (!info.requested().isEmpty()) lines.add("- 상대가 요구한 정보: " + String.join(", ", info.requested()));
            lines.add(info.provided().isEmpty() ? "- 요구받은 정보를 넘기지 않았어요." : "- 실제로 넘긴 정보: " + String.join(", ", info.provided()));
        }
        addItems(lines, "잘한 점", evaluation.strengths());
        addItems(lines, "개선할 점", evaluation.improvements());
        addItems(lines, "권장 행동", evaluation.recommendations());
        if (!evaluation.sources().isEmpty()) {
            lines.add("");
            lines.add("참고한 실제 사기 수법");
            for (var source : evaluation.sources()) {
                lines.add(source.issuer() + (source.author() == null ? "" : " / 작성자 " + source.author())
                        + (source.publishedDate() == null ? "" : " / " + source.publishedDate()));
                lines.add(source.title());
                lines.add(source.sourceUrl());
            }
            lines.add("공공누리 제1유형 자료의 수법을 참고해 요약·재구성했습니다.");
            lines.add("등장인물과 대화는 가상이며, 원기관이 제작·후원한 훈련이 아닙니다.");
        }
        return lines;
    }

    private static void addItems(List<String> lines, String heading, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        lines.add("");
        lines.add(heading);
        items.forEach(item -> lines.add("- " + item));
    }

    private record PdfLine(String text, String uri) {}

    private static void wrap(PDType0Font font, String text, String uri, List<PdfLine> out) throws IOException {
        for (String paragraph : text.split("\\R", -1)) {
            var line = new StringBuilder();
            float width = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                offset += Character.charCount(codePoint);
                String glyph = Character.isISOControl(codePoint) ? " " : Character.toString(codePoint);
                float glyphWidth;
                try {
                    glyphWidth = font.getStringWidth(glyph) * FONT_SIZE / 1000;
                } catch (IllegalArgumentException e) {
                    glyph = "?";
                    glyphWidth = font.getStringWidth(glyph) * FONT_SIZE / 1000;
                }
                if (width + glyphWidth > A4.getWidth() - 2 * MARGIN && !line.isEmpty()) {
                    out.add(new PdfLine(line.toString(), uri));
                    line.setLength(0);
                    width = 0;
                }
                line.append(glyph);
                width += glyphWidth;
            }
            out.add(new PdfLine(line.toString(), uri));
        }
    }
}
