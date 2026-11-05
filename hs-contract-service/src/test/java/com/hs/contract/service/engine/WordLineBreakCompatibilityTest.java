package com.hs.contract.service.engine;

import com.deepoove.poi.XWPFTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.deposit.DepositDataModel;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class WordLineBreakCompatibilityTest {
    private static final String WORD_NS="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String SHORT_LINES = "Thời hạn báo trước khi chấm dứt: tối thiểu 45 ngày.\n"
            + "Nuôi thú cưng: Không được phép.\nHút thuốc: Chỉ tại khu vực cho phép.\nKiểm tra tài sản: 48 giờ";
    private final ContractRenderService renderer = new ContractRenderService(new ObjectMapper());

    static Stream<String> templates() {
        return Stream.of("HomeSpace_01_Hop_Dong_Thue_Nha_Nguyen_Can.docx",
                "HomeSpace_02_Hop_Dong_Thue_Can_Ho_Chung_Cu.docx", "HomeSpace_03_Hop_Dong_Thue_Phong_Tro.docx",
                "HomeSpace_01_Hop_Dong_Dat_Coc_Nha_Nguyen_Can.docx", "HomeSpace_02_Hop_Dong_Dat_Coc_Can_Ho_Chung_Cu.docx",
                "HomeSpace_03_Hop_Dong_Dat_Coc_Phong_Tro.docx");
    }

    @ParameterizedTest @MethodSource("templates")
    void realLeaseAndDepositTemplatesKeepTheirLayoutAndMultilineText(String filename) throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("hs-infra/documents"))) root=root.getParent();
        assertNotNull(root,"Cannot locate the real Word templates");
        boolean deposit=filename.contains("Dat_Coc");
        Path path=root.resolve("hs-infra/documents/"+(deposit?"deposit-contract-template/":"contract-template/")+filename);
        byte[] source=Files.readAllBytes(path);
        Map<String,Object> model=renderer.buildDummyDataModel();
        if(deposit) model=DepositDataModel.dummy(model);
        String type=filename.contains("Nha_Nguyen_Can")?"Nhà nguyên căn":filename.contains("Can_Ho")?"Căn hộ chung cư":"Phòng trọ";
        put(model,"property","propertyType",type);
        put(model,"tenant","permanentAddress","123 Nguyễn Huệ\nPhường Bến Nghé, Thành phố Hồ Chí Minh");
        if(deposit) {
            put(model,"depositAgreement","reservationTerms","Điều kiện giữ chỗ đã được các bên chấp thuận. ".repeat(15)+"\n"+SHORT_LINES);
            put(model,"depositAgreement","nextStepsText","Hoàn tất ký hợp đồng cọc.\nTiếp tục lập và ký hợp đồng thuê.");
        } else {
            String terms=Objects.toString(ContractRenderService.resolvePath(model,"contract.specialTerms"));
            put(model,"contract","specialTerms",terms+"\n"+SHORT_LINES);
        }
        byte[] rendered=renderer.renderDocx(new ByteArrayInputStream(source),model);
        assertFlag(rendered);
        var parts=parts(rendered);
        try(var doc=new XWPFDocument(new ByteArrayInputStream(rendered));var text=new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
            assertFalse(text.getText().contains("{{"),filename+" unresolved marker");
            assertTrue(text.getText().contains("tối thiểu 45 ngày."));
            assertTrue(text.getText().contains("Kiểm tra tài sản: 48 giờ"));
            assertTrue(text.getText().contains("Nguyễn Văn An"));
            assertFalse(doc.getTables().isEmpty()); assertFalse(doc.getHeaderList().isEmpty()); assertFalse(doc.getFooterList().isEmpty());
        }
        // Applying the compatibility switch twice does not change document text/styles or duplicate settings.
        byte[] second=WordLineBreakCompatibility.normalize(rendered);
        assertArrayEquals(parts.get("word/document.xml"),parts(second).get("word/document.xml"));
        assertArrayEquals(parts.get("word/styles.xml"),parts(second).get("word/styles.xml"));
        assertArrayEquals(source,Files.readAllBytes(path),"Rendering must not modify source templates");
        String qa=System.getProperty("hs.layout.qa");
        if(qa!=null) {
            Path output=Path.of(qa); Files.createDirectories(output);
            Files.write(output.resolve(filename),rendered);
        }
    }

    @Test void existingFalseSettingIsEnabledWithoutChangingTextStylesOrOtherCompatibilitySettings() throws Exception {
        byte[] plain;
        try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            var p=doc.createParagraph(); p.setAlignment(ParagraphAlignment.BOTH);
            p.createRun().setBold(true);p.getRuns().getFirst().setText("Nội dung in đậm");
            p.createRun().addBreak();p.createRun().setText("Dòng ngắn");
            doc.write(output); plain=output.toByteArray();
        }
        var entries=parts(plain);
        var xml=parse(entries.get("word/settings.xml"));
        var compat=xml.createElementNS(WORD_NS,"w:compat");xml.getDocumentElement().appendChild(compat);
        var flag=xml.createElementNS(WORD_NS,"w:doNotExpandShiftReturn");flag.setAttributeNS(WORD_NS,"w:val","false");compat.appendChild(flag);
        compat.appendChild(xml.createElementNS(WORD_NS,"w:doNotAutofitConstrainedTables"));
        var settingsOutput=new ByteArrayOutputStream();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(new javax.xml.transform.dom.DOMSource(xml),new javax.xml.transform.stream.StreamResult(settingsOutput));
        entries.put("word/settings.xml",settingsOutput.toByteArray());
        var edited=WordLineBreakCompatibility.normalize(zip(entries));assertFlag(edited);
        var actual=parts(edited);
        assertArrayEquals(entries.get("word/document.xml"),actual.get("word/document.xml"));
        var after=parse(actual.get("word/settings.xml"));
        assertEquals(1,after.getElementsByTagNameNS(WORD_NS,"doNotAutofitConstrainedTables").getLength());
    }

    @Test void plainTemplatesWithoutSettingsReceiveAProperlyRelatedSettingsPart() throws Exception {
        byte[] rendered;
        try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("{{value}}"); doc.write(output);
            // POI creates settings by default. Use an OPC package to remove its relationship and content type too.
            try(var pkg=org.apache.poi.openxml4j.opc.OPCPackage.open(new ByteArrayInputStream(output.toByteArray()))) {
                var part=pkg.getPartsByContentType(XWPFRelation.SETTINGS.getContentType()).getFirst();
                pkg.removePartRecursive(part.getPartName());var stripped=new ByteArrayOutputStream();pkg.save(stripped);
                rendered=renderer.renderDocx(new ByteArrayInputStream(stripped.toByteArray()),Map.of("value","Dòng một\nDòng hai"));
            }
        }
        assertFlag(rendered);
        try(var doc=new XWPFDocument(new ByteArrayInputStream(rendered))) {
            assertNotNull(doc.getSettings()); assertTrue(doc.getParagraphs().getFirst().getText().contains("Dòng hai"));
        }
    }

    @Test void aPageBreakAndFormattedInlineTextArePreserved() throws Exception {
        byte[] source;
        try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            var p=doc.createParagraph();p.setAlignment(ParagraphAlignment.BOTH);
            var label=p.createRun();label.setBold(true);label.setText("Ghi chú: ");p.createRun().setText("{{value}}");
            p.createRun().addBreak(BreakType.PAGE);
            doc.createTable(1,1).getRow(0).getCell(0).setText("{{value}}");
            doc.write(output);source=output.toByteArray();
        }
        var data=Map.<String,Object>of("value","Dòng thứ nhất\nDòng thứ hai");
        byte[] before;
        try(var template=XWPFTemplate.compile(new ByteArrayInputStream(source)).render(data);var output=new ByteArrayOutputStream()) {
            template.write(output);before=output.toByteArray();
        }
        byte[] after=renderer.renderDocx(new ByteArrayInputStream(source),data);assertFlag(after);
        assertArrayEquals(parts(before).get("word/document.xml"),parts(after).get("word/document.xml"));
    }

    @SuppressWarnings("unchecked")
    private void put(Map<String,Object> model,String group,String key,String value) {
        var values=new LinkedHashMap<>((Map<String,Object>)model.getOrDefault(group,Map.of()));values.put(key,value);model.put(group,values);
    }
    private void assertFlag(byte[] bytes) throws Exception {
        var xml=parse(parts(bytes).get("word/settings.xml"));
        var flags=xml.getElementsByTagNameNS(WORD_NS,"doNotExpandShiftReturn");assertEquals(1,flags.getLength());
        assertEquals("true",((org.w3c.dom.Element)flags.item(0)).getAttributeNS(WORD_NS,"val"));
    }
    private org.w3c.dom.Document parse(byte[] bytes) throws Exception {
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private Map<String,byte[]> parts(byte[] bytes) throws Exception {
        var result=new LinkedHashMap<String,byte[]>();
        try(var input=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;while((entry=input.getNextEntry())!=null)result.put(entry.getName(),input.readAllBytes());
        }
        return result;
    }
    private byte[] zip(Map<String,byte[]> entries) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var output=new ZipOutputStream(bytes)) {
            for(var entry:entries.entrySet()) {output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();}
        }
        return bytes.toByteArray();
    }
}
