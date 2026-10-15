package com.hs.contract.service.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContractRenderServiceTest {

    private final ContractRenderService renderService = new ContractRenderService(new ObjectMapper());
    @Test void all64CatalogMarkersRenderForEachSupportedCategoryIncludingBothBankAccounts() throws Exception {
        var catalog=new ContractFieldCatalog();
        for(var category:ContractFieldCatalog.SUPPORTED_CATEGORIES) {
            var model=renderService.buildDummyDataModel();
            @SuppressWarnings("unchecked") var property=(Map<String,Object>)model.get("property");
            property.put("propertyType",category.name());
            @SuppressWarnings("unchecked") var owner=(Map<String,Object>)model.get("landlord");
            @SuppressWarnings("unchecked") var tenant=(Map<String,Object>)model.get("tenant");
            owner.put("bankAccountNumber","000123456789");owner.put("bankAccountHolder","CHU TAI KHOAN A");owner.put("bankName","Ngan hang A");
            tenant.put("bankAccountNumber","000987654321");tenant.put("bankAccountHolder","CHU TAI KHOAN B");tenant.put("bankName","Ngan hang B");
            byte[] template;
            try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) {
                for(var field:catalog.getAllDefinitions()) {
                    String key=field.getKey();assertNotNull(ContractRenderService.resolvePath(model,key.startsWith("#")?key.substring(1):key),category+": "+key);
                    doc.createParagraph().createRun().setText("{{"+key+"}}");
                }
                doc.write(out);template=out.toByteArray();
            }
            var rendered=renderService.renderDocx(new java.io.ByteArrayInputStream(template),model);
            try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(rendered));var text=new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
                assertFalse(text.getText().contains("{{"));
                for(String value:java.util.List.of("000123456789","000987654321","CHU TAI KHOAN A","CHU TAI KHOAN B","Ngan hang A","Ngan hang B"))assertTrue(text.getText().contains(value),category+": "+value);
            }
        }
    }
    @Test void wordReceivesSystemDeadlineAndComputedFeeCeilingWithoutClientInputs() throws Exception {
        var policy=com.hs.contract.service.LatePaymentPolicy.forNewAgreement(Map.of("latePaymentFeeMode","FIXED_PER_DAY","latePaymentFeeAmount",100000),Map.of());
        var model=renderService.buildDataModelFromSnapshots(Map.of(),Map.of(),Map.of(),Map.of(),
                Map.of("paymentDueDay",com.hs.contract.service.MonthlyBillingSchedule.paymentDueDescription()),java.util.List.of(),java.util.List.of(),Map.of(),Map.of(),java.util.List.of(),policy,
                "Ghi chú nhập tay",3,1,"HD-SYSTEM",java.time.LocalDate.of(2026,10,15),"HCM");
        String terms=String.valueOf(ContractRenderService.resolvePath(model,"contract.specialTerms"));
        assertTrue(terms.contains("ngày thứ 5"));assertTrue(terms.contains("từ ngày đầu tiên sau hạn thanh toán"));
        assertTrue(terms.contains("tối đa 10 ngày"));assertTrue(terms.contains("tổng phí tối đa 1.000.000 VNĐ"));
        assertEquals(terms.indexOf("Phí chậm thanh toán:"),terms.lastIndexOf("Phí chậm thanh toán:"));
        byte[] template;
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("{{contract.specialTerms}}");document.write(out);template=out.toByteArray();
        }
        byte[] rendered=renderService.renderDocx(new java.io.ByteArrayInputStream(template),model);
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(rendered));var extractor=new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
            assertTrue(extractor.getText().contains("1.000.000 VNĐ"));assertTrue(extractor.getText().contains("Ghi chú nhập tay"));assertFalse(extractor.getText().contains("{{"));
        }
    }
    @Test void equipmentNotesSurviveFourColumnWordTableAndEmptyTablesDoNotInventTerms() throws Exception {
        var model = renderService.buildDataModelFromSnapshots(null,null,null,null,null,java.util.List.of(),
                java.util.List.of(Map.of("name","Giường","quantity",2,"condition","Còn tốt","note","Xước đầu giường")),
                null,"HD",java.time.LocalDate.of(2026,10,14),"Hồ Chí Minh");
        var mapper = new ObjectMapper();
        assertTrue(mapper.writeValueAsString(model.get("equipmentTable")).contains("Xước đầu giường"));
        assertFalse(mapper.writeValueAsString(model.get("chargesTable")).contains("Đã bao gồm trong giá thuê"));
        var empty = renderService.buildDataModelFromSnapshots(null,null,null,null,null,java.util.List.of(),java.util.List.of(),null,"HD",java.time.LocalDate.of(2026,10,14),"Hồ Chí Minh");
        assertFalse(mapper.writeValueAsString(empty.get("equipmentTable")).contains("Nhà trống bàn giao cơ bản"));
    }

    @Test
    void testBuildDummyDataModel() {
        Map<String, Object> dummy = renderService.buildDummyDataModel();
        assertNotNull(dummy);
        assertTrue(dummy.containsKey("landlord"));
        assertTrue(dummy.containsKey("tenant"));
        assertTrue(dummy.containsKey("property"));
        assertTrue(dummy.containsKey("chargesTable"));
        assertTrue(dummy.containsKey("equipmentTable"));
        assertEquals("Nguyễn Văn An (Chủ nhà)", ContractRenderService.resolvePath(dummy, "landlord.fullName"));
        assertEquals("HD-20260905-DEMO", ContractRenderService.resolvePath(dummy, "contract.number"));
        assertEquals("Căn hộ chung cư", ContractRenderService.resolvePath(dummy, "property.propertyType"));
        assertNotNull(ContractRenderService.resolvePath(dummy, "rent.amountNumber"));
        assertEquals("3", ContractRenderService.resolvePath(dummy, "contract.schemaVersion"));
        assertTrue(String.valueOf(ContractRenderService.resolvePath(dummy, "rent.paymentMethod"))
                .contains("Chuyển khoản trực tiếp"));
        assertTrue(String.valueOf(ContractRenderService.resolvePath(dummy, "contract.specialTerms"))
                .contains("hết 05 ngày quá hạn"));

        // Must not contain rentalMode or B2B tenant fields
        assertNull(ContractRenderService.resolvePath(dummy, "lease.rentalMode"));
        assertNull(ContractRenderService.resolvePath(dummy, "tenant.organizationName"));
        assertNull(ContractRenderService.resolvePath(dummy, "tenant.representativeName"));
        assertNull(ContractRenderService.resolvePath(dummy, "tenant.representativePosition"));
    }
}
