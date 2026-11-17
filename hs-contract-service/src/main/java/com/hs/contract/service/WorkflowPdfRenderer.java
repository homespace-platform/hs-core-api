package com.hs.contract.service;
import com.hs.contract.model.*;
import com.hs.contract.repository.ContractTemplateVersionRepository;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.contract.service.engine.ContractRenderService;
import com.hs.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Populate the recorded Word template with effective terms, then append the dated termination adjustment. */
@Component @RequiredArgsConstructor
public class WorkflowPdfRenderer {
    private final DocumentConversionService conversion;
    private final RentalWorkflowSupport support;
    private final ContractTemplateVersionRepository templates;
    private final StorageService storage;
    private final ContractRenderService renderService;
    private final ContractTermsResolver effectiveTerms;
    public byte[] render(Contract root,RentalWorkflowRecord source,ContractRevision revision){return pdf(renderDocx(root,source,revision));}
    public byte[] pdf(byte[] docx){return conversion.convertDocxToPdf(docx,"homespace-termination-annex.docx").orElseThrow(()->support.invalid("Chưa chuyển được PDF phụ lục. Hợp đồng gốc không thay đổi."));}
    public byte[] renderDocx(Contract root,RentalWorkflowRecord source,ContractRevision revision){
        try{
            var template=templates.findById(revision.getTemplateVersionId()).orElseThrow(support::missing);
            var current=effectiveTerms.at(root,support.time.today());
            if(!current.getId().equals(source.getSourceRevisionId()))throw support.invalid("Điều khoản hiệu lực đã đổi; cần lập lại phụ lục.");
            var model=renderService.buildDataModelFromSnapshots(support.map(revision.getLandlordSnapshot()),support.map(revision.getTenantSnapshot()),
                support.map(revision.getPropertySnapshot()),support.map(revision.getLeaseSnapshot()),support.map(revision.getFinancialSnapshot()),
                rows(revision.getChargesSnapshot()),rows(revision.getEquipmentSnapshot()),support.map(revision.getInitialMetersSnapshot()),support.map(revision.getInitialPaymentSnapshot()),
                rows(revision.getAmenitiesSnapshot()),support.map(revision.getPoliciesSnapshot()),Objects.toString(revision.getSpecialTerms(),""),
                revision.getSchemaVersion()==null?2:revision.getSchemaVersion(),revision.getRevisionNumber(),root.getContractNumber(),support.time.today(),"Thành phố Hồ Chí Minh");
            byte[] populated=renderService.renderDocx(new ByteArrayInputStream(storage.downloadDirect(template.getStorageObjectId())),model);
            try(var doc=new XWPFDocument(new ByteArrayInputStream(populated));var out=new ByteArrayOutputStream()){
                var heading=doc.createParagraph();heading.setPageBreak(true);var run=heading.createRun();run.setBold(true);run.setFontSize(14);run.setText("PHỤ LỤC ĐIỀU CHỈNH THỜI HẠN HỢP ĐỒNG");
                line(doc,"Kèm hợp đồng số "+root.getContractNumber()+"; điều khoản đang áp dụng: "+current.getId()+"; bản đề nghị: "+source.getRevision());
                var rental=support.rentals.findById(source.getRentalRequestId()).orElseThrow(support::missing);
                var oldEnd=ContractLeaseCalc.end(rental.getMoveInDate(),ContractLeaseCalc.months(root,rental));
                var newEnd=LocalDate.parse(support.map(source.getPayloadJson()).get("endExclusive").toString());
                line(doc,"Ngày ở cuối cùng trước điều chỉnh: "+display(oldEnd.minusDays(1))+". Mốc kết thúc cũ: "+display(oldEnd)+" lúc 00:00 (không gồm ngày này).");
                line(doc,"Ngày ở cuối cùng sau điều chỉnh: "+display(newEnd.minusDays(1))+". Mốc kết thúc mới: "+display(newEnd)+" lúc 00:00 (không gồm ngày này).");
                line(doc,"Lý do: "+Objects.toString(support.map(source.getPayloadJson()).get("reason"),""));
                line(doc,"Chỉ áp dụng thời hạn mới sau khi hai bên ký số đầy đủ và được hệ thống kiểm chứng trên cùng PDF. Chủ nhà ký trước, người thuê ký sau. Chưa ký đủ thì thời hạn đang áp dụng giữ nguyên.");
                line(doc,"Các điều khoản khác giữ nguyên theo hợp đồng và các phụ lục đã có hiệu lực. Không viết lại văn bản đã ký, không tính lại hóa đơn đã phát hành hay khoản tiền đã nhận. Bàn giao nhà thực tế, hóa đơn cuối và quyết toán cọc thực hiện riêng; chữ ký không thay thế xác nhận nhận tiền.");
                doc.write(out);return out.toByteArray();
            }
        }catch(com.hs.common.advice.entity.AppException e){throw e;}catch(Exception e){throw new IllegalStateException("Cannot render termination annex from Word template",e);}
    }
    private List<Map<String,Object>> rows(String json)throws Exception{return json==null?List.of():support.mapper.readValue(json,new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});}
    private String display(LocalDate d){return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));}
    private void line(XWPFDocument d,String text){var p=d.createParagraph();p.setSpacingAfter(120);var r=p.createRun();r.setFontFamily("Times New Roman");r.setFontSize(12);r.setText(text);}
}
