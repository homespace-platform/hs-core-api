package com.hs.contract.service.engine;

import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/** Effective-date and comparison appendix appended to a freshly populated original Word template. */
@Component @RequiredArgsConstructor
public class ContractAmendmentRenderer {
    private final ContractRepository contracts;
    private final ContractAmendmentRepository amendments;
    private final ContractAmendmentService service;
    private final AmendmentTermsBuilder builder;

    public byte[] appendAdjustment(byte[] populatedTemplate, Contract agreement) throws Exception {
        var amendment = amendments.findByAgreementContractId(agreement.getId()).orElseThrow();
        var root = contracts.findById(amendment.getContractId()).orElseThrow();
        try (var doc = new XWPFDocument(new java.io.ByteArrayInputStream(populatedTemplate)); var out = new ByteArrayOutputStream()) {
            var heading = doc.createParagraph(); heading.setPageBreak(true);
            var run = heading.createRun(); run.setFontFamily("Times New Roman"); run.setFontSize(14); run.setBold(true); run.setText("PHỤ LỤC ĐIỀU CHỈNH VÀ BẢN ĐỐI CHIẾU");
            line(doc,"Liên kết hợp đồng gốc: " + root.getContractNumber() + ". Mã văn bản điều chỉnh: " + agreement.getContractNumber(),true,ParagraphAlignment.LEFT);
            line(doc,"Phần nội dung hợp đồng phía trên được điền từ điều khoản mới đã thống nhất. Văn bản này không tạo quan hệ thuê độc lập, không thu lại cọc hoặc tiền phòng ban đầu; không thay thế PDF, chữ ký và giao dịch lịch sử của hợp đồng gốc.",false,ParagraphAlignment.LEFT);
            line(doc,amendment.isImmediateEffect() ? "Các thay đổi chỉ áp dụng từ thời điểm cả hai bên hoàn tất ký số hợp lệ."
                    : "Các thay đổi áp dụng từ 00:00 ngày " + amendment.getEffectiveOn().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " theo giờ Việt Nam, sau khi hai bên ký số đầy đủ trước hạn.",true,ParagraphAlignment.LEFT);
            line(doc,"Lý do: " + amendment.getReason(),false,ParagraphAlignment.LEFT);
            replacementNotice(doc,amendment);
            for(var change:service.comparison(amendment)) {
                line(doc,change.label(),true,ParagraphAlignment.LEFT);
                line(doc,"Trước điều chỉnh: " + change.before(),false,ParagraphAlignment.LEFT);
                line(doc,"Sau điều chỉnh: " + change.after(),false,ParagraphAlignment.LEFT);
            }
            line(doc,"Các nội dung không điều chỉnh tiếp tục theo hợp đồng gốc và phụ lục đã có hiệu lực. Hóa đơn đã phát hành và khoản đã thanh toán không được tính lại. Chủ nhà ký trước, người thuê ký sau trên cùng PDF này; trang ký số do hệ thống bổ sung.",false,ParagraphAlignment.LEFT);
            doc.write(out); return out.toByteArray();
        }
    }

    public byte[] render(Contract agreement, ContractRevision revision) throws Exception {
        var a = amendments.findByAgreementContractId(agreement.getId()).orElseThrow();
        var root = contracts.findById(a.getContractId()).orElseThrow();
        var landlord = builder.map(revision.getLandlordSnapshot());
        var tenant = builder.map(revision.getTenantSnapshot());
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            line(doc, "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", true, ParagraphAlignment.CENTER);
            line(doc, "Độc lập - Tự do - Hạnh phúc", false, ParagraphAlignment.CENTER);
            line(doc, "PHỤ LỤC ĐIỀU CHỈNH HỢP ĐỒNG", true, ParagraphAlignment.CENTER);
            line(doc, "Số: " + agreement.getContractNumber(), false, ParagraphAlignment.CENTER);
            line(doc, "Kèm hợp đồng số " + root.getContractNumber() + ". Phiên bản điều khoản đối chiếu: " + a.getBaseRevisionId(), false, ParagraphAlignment.LEFT);
            line(doc, "Ngày lập: " + a.getDecidedAt().atZone(BillingTime.ZONE).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), false, ParagraphAlignment.LEFT);
            line(doc, "BÊN A: " + landlord.get("fullName") + " — CCCD/Hộ chiếu: " + landlord.get("idNumber"), true, ParagraphAlignment.LEFT);
            line(doc, "BÊN B: " + tenant.get("fullName") + " — CCCD/Hộ chiếu: " + tenant.get("idNumber"), true, ParagraphAlignment.LEFT);
            line(doc, "Tài sản thuê: " + builder.map(revision.getPropertySnapshot()).get("fullAddress"), false, ParagraphAlignment.LEFT);
            line(doc, "Lý do điều chỉnh: " + a.getReason(), false, ParagraphAlignment.LEFT);
            replacementNotice(doc,a);
            line(doc, "ĐIỀU 1. NỘI DUNG ĐIỀU CHỈNH", true, ParagraphAlignment.LEFT);
            for (var change : service.comparison(a)) {
                line(doc, change.label(), true, ParagraphAlignment.LEFT);
                line(doc, "Nội dung trước điều chỉnh:\n" + change.before(), false, ParagraphAlignment.LEFT);
                line(doc, "Nội dung sau điều chỉnh:\n" + change.after(), false, ParagraphAlignment.LEFT);
            }
            line(doc, "ĐIỀU 2. THỜI ĐIỂM ÁP DỤNG", true, ParagraphAlignment.LEFT);
            line(doc, (a.isImmediateEffect() ? "Các thay đổi áp dụng ngay từ thời điểm hai bên hoàn tất ký số trước hạn ký."
                    : "Các thay đổi áp dụng từ 00:00 ngày " + a.getEffectiveOn().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " (giờ Việt Nam), sau khi hai bên hoàn tất ký số trước hạn ký.")
                    + " Chi phí được tính theo điều khoản có hiệu lực trong kỳ phát sinh; tiền thuê trả trước theo kỳ thuê được thanh toán. Không tính lại hóa đơn đã phát hành hoặc khoản đã thanh toán.", false, ParagraphAlignment.LEFT);
            line(doc, "ĐIỀU 3. CÁC NỘI DUNG CÒN LẠI", true, ParagraphAlignment.LEFT);
            line(doc, "Những nội dung không được sửa đổi cụ thể tại phụ lục này tiếp tục thực hiện theo hợp đồng và các phụ lục đã có hiệu lực trước đó. Thời hạn thuê, tiền cọc, chỉ số công tơ ban đầu, giao dịch và các chữ ký của hợp đồng gốc được giữ nguyên. Không thu lại thanh toán ban đầu. Phụ lục này được lưu cùng hợp đồng gốc.", false, ParagraphAlignment.LEFT);
            line(doc, "Danh mục thiết bị điều chỉnh thể hiện bàn giao bổ sung/thu hồi/thay thế từ ngày áp dụng; không thay thế biên bản bàn giao lịch sử.", false, ParagraphAlignment.LEFT);
            line(doc, "BÊN A — CHỦ NHÀ                         BÊN B — NGƯỜI THUÊ", true, ParagraphAlignment.CENTER);
            line(doc, "Hai bên ký số trên bản PDF này qua VNPT SmartCA.", false, ParagraphAlignment.CENTER);
            doc.write(out); return out.toByteArray();
        }
    }
    private void replacementNotice(XWPFDocument doc,ContractAmendment a) {
        var seen=new java.util.HashSet<String>();String id=a.getPreviousProposalId();
        while(id!=null&&seen.add(id)) {
            var old=amendments.findById(id).orElseThrow();
            if(old.getSignedAt()!=null){
                var previous=contracts.findById(old.getAgreementContractId()).orElseThrow();
                line(doc,"VĂN BẢN THAY THẾ: Hai bên thống nhất thay thế toàn bộ phụ lục "+previous.getContractNumber()+" đã ký ngày "+old.getSignedAt().atZone(BillingTime.ZONE).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))+" từ ngày "+a.getEffectiveOn().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))+" khi bản thay thế ký đủ. Nếu bản thay thế không hoàn tất đúng hạn, phụ lục đã ký trước đó được giữ nguyên. Không xóa hoặc sửa PDF và chữ ký cũ.",true,ParagraphAlignment.LEFT);return;
            }
            id=old.getPreviousProposalId();
        }
    }
    private void line(XWPFDocument doc, String text, boolean bold, ParagraphAlignment alignment) {
        var p = doc.createParagraph(); p.setAlignment(alignment); p.setSpacingAfter(140);
        var run = p.createRun(); run.setFontFamily("Times New Roman"); run.setFontSize(12); run.setBold(bold);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) { if (i > 0) run.addBreak(); run.setText(lines[i]); }
    }
}
