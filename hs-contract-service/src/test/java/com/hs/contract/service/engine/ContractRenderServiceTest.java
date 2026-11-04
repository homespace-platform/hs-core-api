package com.hs.contract.service.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContractRenderServiceTest {

    private final ContractRenderService renderService = new ContractRenderService(new ObjectMapper());

    @Test void wordAppliesDepositTermsOnlyWhenDepositIsDueAndPreservesHistoricalPaymentAmount() throws Exception {
        byte[] template;
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("{{deposit.description}}");
            document.createParagraph().createRun().setText("{{#initialPaymentTable}}");
            document.createParagraph().createRun().setText("{{#policiesTable}}");
            document.createParagraph().createRun().setText("{{contract.specialTerms}}");
            document.write(out);
            template = out.toByteArray();
        }

        String oldTerms = "Hoàn cọc sau bàn giao theo điều khoản đã ký";
        String oldNote = "Bên A hoàn cọc trực tiếp cho Bên B khi chấm dứt hợp đồng";
        var policies = Map.<String, Object>of("depositRefundDays", 7, "depositDeductionConditions", "Đối chiếu thiệt hại",
                "listingPolicies", List.of(Map.of("code", "depositRefundDays", "label", "Hạn hoàn cọc", "displayValue", "7 ngày"),
                        Map.of("code", "petPolicy", "label", "Vật nuôi", "displayValue", "Được phép")));
        for (String amount : List.of("0", "3400000")) {
            boolean noDeposit = amount.equals("0");
            String displayAmount = noDeposit ? "0 VNĐ" : "3.400.000 VNĐ";
            var financial = Map.<String, Object>of("depositAmountValue", amount, "depositAmountNumber", displayAmount,
                    "depositDescription", oldTerms);
            for (boolean hasRows : List.of(true, false)) {
                var initialPayment = Map.<String, Object>of("depositAmount", "0 VNĐ", "monthlyRent", "3.400.000 VNĐ",
                        "rows", hasRows ? List.of(Map.of("itemName", "Tiền đặt cọc", "amountText", "0 VNĐ", "note", oldNote)) : List.of());
                var model = renderService.buildDataModelFromSnapshots(null, null, null, null, financial, List.of(), List.of(),
                        null, initialPayment, List.of(), policies, null, 3, 1, "HD-TEST", null, null);
                byte[] rendered = renderService.renderDocx(new ByteArrayInputStream(template), model);
                try (var document = new XWPFDocument(new ByteArrayInputStream(rendered)); var extractor = new XWPFWordExtractor(document)) {
                    String text = extractor.getText();
                    assertEquals(noDeposit, text.contains("Không đặt cọc theo điều khoản này."));
                    assertEquals(!noDeposit, text.contains(oldTerms));
                    assertEquals(!noDeposit, text.contains("Hạn hoàn cọc"));
                    assertEquals(!noDeposit, text.contains("Đối chiếu thiệt hại"));
                    assertTrue(text.contains("Không phát sinh thanh toán tiền cọc"));
                    assertTrue(text.contains("Vật nuôi"));
                    assertFalse(text.contains(oldNote));
                }
            }
        }
    }

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
        assertTrue(terms.contains("tối đa 5 ngày"));assertTrue(terms.contains("tổng phí tối đa 500.000 VNĐ"));
        assertEquals(terms.indexOf("Phí chậm thanh toán:"),terms.lastIndexOf("Phí chậm thanh toán:"));
        byte[] template;
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("{{contract.specialTerms}}");document.write(out);template=out.toByteArray();
        }
        byte[] rendered=renderService.renderDocx(new java.io.ByteArrayInputStream(template),model);
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(rendered));var extractor=new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
            assertTrue(extractor.getText().contains("500.000 VNĐ"));assertTrue(extractor.getText().contains("Ghi chú nhập tay"));assertFalse(extractor.getText().contains("{{"));
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
