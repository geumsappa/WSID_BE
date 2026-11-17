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
            var lines = new ArrayList<String>();
            for (String paragraph : paragraphs(report)) {
                wrap(font, paragraph, lines);
            }
            int linesPerPage = (int) ((A4.getHeight() - 2 * MARGIN) / LINE_HEIGHT);
            for (int start = 0; start < lines.size(); start += linesPerPage) {
                var page = new PDPage(A4);
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, FONT_SIZE);
                    stream.setLeading(LINE_HEIGHT);
                    stream.newLineAtOffset(MARGIN, A4.getHeight() - MARGIN);
                    for (String line : lines.subList(start, Math.min(start + linesPerPage, lines.size()))) {
                        stream.showText(line);
                        stream.newLine();
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
        addItems(lines, "잘한 점", evaluation.strengths());
        addItems(lines, "개선할 점", evaluation.improvements());
        addItems(lines, "권장 행동", evaluation.recommendations());
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

    private static void wrap(PDType0Font font, String text, List<String> out) throws IOException {
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
                    out.add(line.toString());
                    line.setLength(0);
                    width = 0;
                }
                line.append(glyph);
                width += glyphWidth;
            }
            out.add(line.toString());
        }
    }
}
