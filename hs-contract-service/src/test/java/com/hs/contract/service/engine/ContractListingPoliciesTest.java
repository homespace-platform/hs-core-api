package com.hs.contract.service.engine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.service.AmendmentTermsBuilder;
import com.hs.listing.dto.response.ListingPolicyResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractListingPoliciesTest {
    ListingPolicyResponse permission() { return new ListingPolicyResponse("petPolicy","Nuôi thú cưng","PERMISSION","APPROVAL_REQUIRED","Không gây ồn", "Cần chủ nhà chấp thuận trước — Không gây ồn",null,true,null,null,10); }
    @Test void permissionsAreNotCollapsedAndNumericBusinessDeadlinesComeFromListing() {
        var policy = ContractListingPolicies.from(List.of(permission(),new ListingPolicyResponse("depositRefundDays","Hoàn cọc","INTEGER","7","","7 ngày","ngày",true,1,60,70)));
        assertNull(policy.get("petsAllowed")); assertEquals(7,policy.get("depositRefundDays"));
        assertEquals("Cần chủ nhà chấp thuận trước — Không gây ồn",policy.get("petPolicy"));
        assertFalse(policy.containsKey("latePaymentFeeAmount"));
    }
    @Test void amendmentsUseFrozenDefinitionAndCannotInjectFeesOrChangeRefundDeadline() {
        var builder = new AmendmentTermsBuilder(new ObjectMapper());
        var rows = List.of(permission(),new ListingPolicyResponse("depositRefundDays","Hoàn cọc","INTEGER","7","","7 ngày","ngày",true,1,60,70));
        var base = ContractRevision.builder().financialSnapshot("{\"amountValue\":\"2000000\"}").tenantSnapshot("{\"occupantCount\":1}")
                .propertySnapshot("{}").policiesSnapshot(builder.write(ContractListingPolicies.from(rows))).build();
        var original = builder.map(base.getPoliciesSnapshot());
        var values = builder.rows(builder.write(original.get("listingPolicies"))); values.getFirst().put("value","ALLOWED");
        var result = builder.build(base,Map.of("policies",Map.of("listingPolicies",values)));
        assertEquals(true,builder.object(result.terms().get("policies")).get("petsAllowed"));
        assertTrue(result.comparison().stream().anyMatch(d -> d.field().equals("policies.petPolicy") && d.after().startsWith("Cho phép")));
        values.getFirst().put("minimumValue",999); assertThrows(AppException.class,() -> builder.build(base,Map.of("policies",Map.of("listingPolicies",values))));
        values.getFirst().remove("minimumValue"); values.getLast().put("value","30");
        assertThrows(AppException.class,() -> builder.build(base,Map.of("policies",Map.of("listingPolicies",values))));
        assertThrows(AppException.class,() -> builder.build(base,Map.of("policies",Map.of("petsAllowed",true))));
    }
    @Test void wordPolicyTableAndLegacyFallbackTextBothCarryActualValues() throws Exception {
        var policy = ContractListingPolicies.from(List.of(permission()));
        var model = new ContractRenderService(new ObjectMapper()).buildDataModelFromSnapshots(null,null,null,null,null,List.of(),List.of(),null,null,List.of(),policy,"Điều khoản gốc",3,1,"HD",java.time.LocalDate.of(2026,10,15),"HCM");
        assertTrue(new ObjectMapper().writeValueAsString(model.get("policiesTable")).contains("Cần chủ nhà chấp thuận trước"));
        assertTrue(ContractRenderService.resolvePath(model,"contract.specialTerms").toString().contains("Nuôi thú cưng: Cần chủ nhà chấp thuận trước"));
    }
    @Test void renderingUpdatedTemplateDoesNotDuplicatePolicyFallbackOrAlterSourceModel() throws Exception {
        var renderer = new ContractRenderService(new ObjectMapper());
        var model = renderer.buildDataModelFromSnapshots(null,null,null,null,null,List.of(),List.of(),null,null,List.of(),ContractListingPolicies.from(List.of(permission())),"Điều khoản gốc",3,1,"HD",java.time.LocalDate.of(2026,10,15),"HCM");
        byte[] bytes;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var buffer = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("{{#policiesTable}}");
            document.createParagraph().createRun().setText("{{contract.specialTerms}}");
            document.write(buffer); bytes = buffer.toByteArray();
        }
        var result = renderer.renderDocx(new java.io.ByteArrayInputStream(bytes),model);
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(result))) {
            assertEquals(1,document.getTables().size());
            assertTrue(document.getTables().getFirst().getText().contains("Cần chủ nhà chấp thuận trước"));
            assertFalse(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("CHÍNH SÁCH ÁP DỤNG")));
        }
        assertTrue(ContractRenderService.resolvePath(model,"contract.specialTerms").toString().contains("CHÍNH SÁCH ÁP DỤNG"));
    }
}
