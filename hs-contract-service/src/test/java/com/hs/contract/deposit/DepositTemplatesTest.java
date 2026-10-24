package com.hs.contract.deposit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.service.engine.*;
import com.hs.listing.model.constant.ListingCategory;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DepositTemplatesTest {
    @Test void suppliedWordFilesValidateAgainstD2AndActuallyRenderWithoutUnresolvedMarkers() throws Exception {
        var catalog=new ContractFieldCatalog();var analyzer=new TemplateAnalysisService(catalog);var renderer=new ContractRenderService(new ObjectMapper());
        var categories=List.of(ListingCategory.HOUSE,ListingCategory.APARTMENT,ListingCategory.ROOM);
        var files=List.of("HomeSpace_01_Hop_Dong_Dat_Coc_Nha_Nguyen_Can.docx","HomeSpace_02_Hop_Dong_Dat_Coc_Can_Ho_Chung_Cu.docx","HomeSpace_03_Hop_Dong_Dat_Coc_Phong_Tro.docx");
        for(int i=0;i<files.size();i++) {
            byte[] bytes=Files.readAllBytes(Path.of("../hs-api-service/src/main/resources/deposit-templates",files.get(i)));
            var validation=analyzer.analyzeTemplate(new ByteArrayInputStream(bytes),categories.get(i),"DEPOSIT");
            assertTrue(validation.isValid(),validation.getWarnings().toString());assertTrue(validation.getMissingRequiredFields().isEmpty());
            assertEquals(i==0?41:42,validation.getDetectedPlaceholders().size());
            assertFalse(analyzer.analyzeTemplate(new ByteArrayInputStream(bytes),categories.get(i)).getInvalidPlaceholders().isEmpty(),"Lease catalog must not accept deposit tags");
            var model=DepositDataModel.dummy(renderer.buildDummyDataModel());
            @SuppressWarnings("unchecked") var owner=(Map<String,Object>)model.get("landlord");owner.put("bankAccountNumber","000123456789");
            byte[] rendered=renderer.renderDocx(new ByteArrayInputStream(bytes),model);
            try(var doc=new XWPFDocument(new ByteArrayInputStream(rendered));var extractor=new XWPFWordExtractor(doc)) {
                String text=extractor.getText();assertFalse(text.contains("{{"),text);assertTrue(text.contains("000123456789"));assertTrue(text.contains("DC-20261024-DEMO"));
            }
        }
    }
    @Test void splitRunsAndRepeatedPolicyBlockAreRejectedForDepositOnly() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var p=doc.createParagraph();p.createRun().setText("{{depositAgreement.");p.createRun().setText("number}}");
            doc.createParagraph().createRun().setText("{{depositAgreement.reservationTerms}}");
            doc.createParagraph().createRun().setText("{{depositAgreement.reservationTerms}}");
            doc.write(out);
            var v=new TemplateAnalysisService(new ContractFieldCatalog()).analyzeTemplate(new ByteArrayInputStream(out.toByteArray()),ListingCategory.HOUSE,"DEPOSIT");
            assertFalse(v.isValid());assertTrue(v.getWarnings().stream().anyMatch(s->s.contains("tách run")));assertTrue(v.getWarnings().stream().anyMatch(s->s.contains("một lần")));
        }
    }
}
