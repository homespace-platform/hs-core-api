package com.hs.contract.service.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LeaseDurationRenderTest {
    private final ContractRenderService renderer = new ContractRenderService(new ObjectMapper());
    private static final String PAIR = "{{lease.durationText}} ({{lease.durationMonths}} tháng)";

    private Map<String, Object> model(int months, String text) {
        return Map.of("lease", Map.of("durationMonths", months, "durationText", text));
    }
    private byte[] bytes(XWPFDocument document) throws Exception {
        var output = new ByteArrayOutputStream(); document.write(output); return output.toByteArray();
    }
    private XWPFDocument render(XWPFDocument source, Map<String,Object> model) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(renderer.renderDocx(new ByteArrayInputStream(bytes(source)),model)));
    }

    @ParameterizedTest @CsvSource({"3,3 tháng", "6,6 tháng", "12,1 năm (12 tháng)",
        "13,1 năm 1 tháng (13 tháng)", "18,1 năm 6 tháng (18 tháng)", "24,2 năm (24 tháng)"})
    void fullDurationIsPrintedOnceWithoutChangingTheSavedModel(int months, String text) throws Exception {
        var model=model(months,text);
        try (var source=new XWPFDocument()) {
            source.createParagraph().createRun().setText("2.1. Thời hạn thuê: "+PAIR+", từ ngày 24/10/2026 đến ngày 23/10/2027.");
            try (var result=render(source,model)) {
                assertEquals("2.1. Thời hạn thuê: "+text+", từ ngày 24/10/2026 đến ngày 23/10/2027.",result.getParagraphs().getFirst().getText());
            }
            assertTrue(source.getParagraphs().getFirst().getText().contains(PAIR));
        }
        assertEquals(text,ContractRenderService.resolvePath(model,"lease.durationText"));
        assertEquals(months,ContractRenderService.resolvePath(model,"lease.durationMonths"));
    }

    @Test void shortTextStillKeepsTheUsefulNumericMonthSuffix() throws Exception {
        try(var source=new XWPFDocument()) {
            source.createParagraph().createRun().setText(PAIR);
            try(var result=render(source,model(12,"1 năm"))) {
                assertEquals("1 năm (12 tháng)",result.getParagraphs().getFirst().getText());
            }
        }
    }

    @Test void handwrittenRepeatedTextEvenInTheSameParagraphAsTheMarkersIsPreserved() throws Exception {
        try(var source=new XWPFDocument()) {
            String handwritten="Ví dụ nhập tay: 1 năm (12 tháng) (12 tháng). ";
            source.createParagraph().createRun().setText(handwritten+"Thời hạn thuê: "+PAIR+".");
            try(var result=render(source,model(12,"1 năm (12 tháng)"))) {
                assertEquals(handwritten+"Thời hạn thuê: 1 năm (12 tháng).",result.getParagraphs().getFirst().getText());
            }
        }
    }

    @Test void separatedFieldsAndHandwrittenClausesAreNotRewritten() throws Exception {
        try(var source=new XWPFDocument()) {
            source.createParagraph().createRun().setText("Thời hạn: {{lease.durationText}}.");
            source.createParagraph().createRun().setText("Số tháng: {{lease.durationMonths}} tháng.");
            source.createParagraph().createRun().setText("Ghi chú nhập tay: 1 năm (12 tháng) (12 tháng).");
            try(var result=render(source,model(12,"1 năm (12 tháng)"))) {
                assertEquals("Thời hạn: 1 năm (12 tháng).",result.getParagraphs().get(0).getText());
                assertEquals("Số tháng: 12 tháng.",result.getParagraphs().get(1).getText());
                assertEquals("Ghi chú nhập tay: 1 năm (12 tháng) (12 tháng).",result.getParagraphs().get(2).getText());
            }
        }
    }

    @Test void splitRunsAndWhitespaceKeepTheSurroundingBoldAndItalicFormatting() throws Exception {
        try(var source=new XWPFDocument()) {
            var paragraph=source.createParagraph();
            var label=paragraph.createRun();label.setBold(true);label.setText("Thời hạn: ");
            paragraph.createRun().setText("{{lease.duration");
            paragraph.createRun().setText("Text}}\u00a0 ( {{lease.");
            paragraph.createRun().setText("durationMonths}} tháng )");
            var ending=paragraph.createRun();ending.setItalic(true);ending.setText(", không đổi ngày thuê.");
            try(var result=render(source,model(12,"1 năm (12 tháng)"))) {
                var rendered=result.getParagraphs().getFirst();
                assertEquals("Thời hạn: 1 năm (12 tháng), không đổi ngày thuê.",rendered.getText());
                assertTrue(rendered.getRuns().stream().anyMatch(run->run.isBold()&&run.text().contains("Thời hạn:")));
                assertTrue(rendered.getRuns().stream().anyMatch(run->run.isItalic()&&run.text().contains("không đổi ngày thuê")));
            }
        }
    }

    @Test void everyPairedOccurrenceInTablesHeadersAndFootersIsCleaned() throws Exception {
        try(var source=new XWPFDocument()) {
            source.createParagraph().createRun().setText(PAIR+"; "+PAIR+".");
            var cell=source.createTable(1,1).getRow(0).getCell(0);
            cell.getParagraphs().getFirst().createRun().setText("Trong bảng: "+PAIR);
            source.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header: "+PAIR);
            source.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer: "+PAIR);
            try(var result=render(source,model(6,"6 tháng"))) {
                assertEquals("6 tháng; 6 tháng.",result.getParagraphs().getFirst().getText());
                assertEquals("Trong bảng: 6 tháng",result.getTables().getFirst().getRow(0).getCell(0).getText());
                assertTrue(result.getHeaderList().getFirst().getText().contains("Header: 6 tháng"));
                assertFalse(result.getHeaderList().getFirst().getText().contains("6 tháng (6 tháng)"));
                assertTrue(result.getFooterList().getFirst().getText().contains("Footer: 6 tháng"));
                assertFalse(result.getFooterList().getFirst().getText().contains("6 tháng (6 tháng)"));
            }
        }
    }

    @Test void mismatchedMonthDataIsNotSilentlyHiddenByTheCleanup() throws Exception {
        try(var source=new XWPFDocument()) {
            source.createParagraph().createRun().setText(PAIR);
            try(var result=render(source,model(12,"1 năm (10 tháng)"))) {
                assertEquals("1 năm (10 tháng) (12 tháng)",result.getParagraphs().getFirst().getText());
            }
        }
    }
}
