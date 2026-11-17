package com.hs.contract.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.service.StorageService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class WorkflowPdfRenderTest {
    @Test void populatedWordUsesEffectiveSnapshotsAndNewEndThenAppendsBeforeAfterWithoutChangingOriginal()throws Exception{
        var mapper=new ObjectMapper().findAndRegisterModules();var time=mock(BillingTime.class);when(time.today()).thenReturn(LocalDate.of(2026,11,1));
        var rentals=mock(RentalRequestRepository.class);when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().moveInDate(LocalDate.of(2026,10,1)).leaseMonths(6).build()));
        var support=new RentalWorkflowSupport(null,rentals,null,null,null,null,mapper,time);
        var conversion=mock(DocumentConversionService.class);var templates=mock(ContractTemplateVersionRepository.class);var storage=mock(StorageService.class);var render=spy(new ContractRenderService(mapper));var terms=mock(ContractTermsResolver.class);
        var root=Contract.builder().id("root").contractNumber("HD-GOC").build();
        var current=ContractRevision.builder().id("effective").leaseSnapshot("{\"endDateText\":\"31/03/2027\"}").build();when(terms.at(any(),any())).thenReturn(current);
        var child=ContractRevision.builder().id("child").templateVersionId("template").revisionNumber(1).landlordSnapshot("{\"fullName\":\"Chủ nhà hiệu lực\"}").tenantSnapshot("{}").propertySnapshot("{}").leaseSnapshot("{\"endDateText\":\"30/11/2026\",\"durationMonths\":2}").financialSnapshot("{}").build();
        when(templates.findById("template")).thenReturn(Optional.of(ContractTemplateVersion.builder().storageObjectId("word-template").build()));
        byte[] populated;try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("Chủ nhà {{landlord.fullName}}; ngày kết thúc {{lease.endDateText}}; thời hạn {{lease.durationMonths}} tháng");doc.write(out);populated=out.toByteArray();}
        when(storage.downloadDirect("word-template")).thenReturn(populated);
        var workflow=new RentalWorkflowRecord();workflow.setRentalRequestId("rental");workflow.setSourceRevisionId("effective");workflow.setRevision(2);workflow.setPayloadJson("{\"endExclusive\":\"2026-12-01\",\"reason\":\"Kết thúc sau khi ở đủ kỳ\"}");
        var renderer=new WorkflowPdfRenderer(conversion,support,templates,storage,render,terms);byte[] word=renderer.renderDocx(root,workflow,child);
        try(var doc=new XWPFDocument(new ByteArrayInputStream(word))){String text=doc.getParagraphs().stream().map(p->p.getText()).reduce("",(a,b)->a+"\n"+b);assertTrue(text.contains("PHỤ LỤC ĐIỀU CHỈNH"));assertTrue(text.contains("31/03/2027"));assertTrue(text.contains("30/11/2026"));assertTrue(text.contains("01/12/2026"));assertTrue(text.contains("Chủ nhà Chủ nhà hiệu lực; ngày kết thúc 30/11/2026"));assertFalse(text.contains("{{lease.endDateText}}"));assertFalse(text.contains("{{landlord.fullName}}"));}
        verify(storage).downloadDirect("word-template");verify(render).renderDocx(any(),argThat(model->((Map<?,?>)model.get("lease")).get("endDateText").equals("30/11/2026")));
        assertEquals("{\"endDateText\":\"31/03/2027\"}",current.getLeaseSnapshot());assertNull(root.getAgreedEarlyEndExclusive());
        when(conversion.convertDocxToPdf(same(word),anyString())).thenReturn(Optional.of(new byte[]{4,5}));assertArrayEquals(new byte[]{4,5},renderer.pdf(word));
    }
}
