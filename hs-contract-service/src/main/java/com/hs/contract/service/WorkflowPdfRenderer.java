package com.hs.contract.service;
import com.hs.contract.model.*;
import com.hs.contract.service.converter.DocumentConversionService;
import com.hs.common.time.BillingTime;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component @RequiredArgsConstructor
public class WorkflowPdfRenderer {
    private final DocumentConversionService conversion;
    private final RentalWorkflowSupport support;
    public byte[] render(Contract root,RentalWorkflowRecord source,ContractRevision revision){
        try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()){
            var body=doc.getDocument().getBody();var section=body.isSetSectPr()?body.getSectPr():body.addNewSectPr();
            var page=section.addNewPgSz();page.setW(BigInteger.valueOf(11906));page.setH(BigInteger.valueOf(16838));
            var margin=section.addNewPgMar();margin.setTop(BigInteger.valueOf(1000));margin.setBottom(BigInteger.valueOf(1000));margin.setLeft(BigInteger.valueOf(1100));margin.setRight(BigInteger.valueOf(1100));
            text(doc,"HOMESPACE",16,true);text(doc,"MOVE_IN".equals(source.getKind())?"BIÊN BẢN GIAO NHẬN NHÀ":"THỎA THUẬN CHẤM DỨT THUÊ TRƯỚC HẠN",16,true);
            text(doc,"Liên kết HĐ: "+root.getContractNumber()+" · Bản nguồn: "+source.getRevision(),10,false);
            text(doc,"Bên cho thuê: "+Objects.toString(support.map(revision.getLandlordSnapshot()).get("fullName"),"Theo hợp đồng gốc"),11,true);
            text(doc,"Bên thuê: "+Objects.toString(support.map(revision.getTenantSnapshot()).get("fullName"),"Theo hợp đồng gốc"),11,true);
            var property=support.map(revision.getPropertySnapshot());
            text(doc,"Nhà/phòng: "+Objects.toString(property.get("fullAddress"),"Theo địa chỉ trong hợp đồng gốc")+" · Phòng/căn: "+Objects.toString(property.get("unitNumber"),"Theo hợp đồng gốc"),11,false);
            var p=support.map(source.getPayloadJson());text(doc,"Nội dung và căn cứ",12,true);text(doc,Objects.toString(p.get("reason"),""),11,false);
            if("MOVE_IN".equals(source.getKind())){
                text(doc,"Thời điểm thực tế: "+date(p.get("handedOverAt"))+" · Số chìa khóa: "+Objects.toString(p.get("keys"),"chưa ghi nhận"),11,false);
                text(doc,"Hiện trạng: "+Objects.toString(p.get("condition"),""),11,false);
                text(doc,"Chỉ số điện: "+Objects.toString(p.get("electricity"),"chưa ghi nhận")+" kWh · nước: "+Objects.toString(p.get("water"),"chưa ghi nhận")+" m³",11,false);
                if(p.get("inventory") instanceof List<?> items&&!items.isEmpty()){
                    var table=doc.createTable(items.size()+1,3);table.setWidth("100%");cell(table,0,0,"Tài sản");cell(table,0,1,"Số lượng");cell(table,0,2,"Hiện trạng");
                    int i=1;for(var raw:items){var item=(Map<?,?>)raw;cell(table,i,0,Objects.toString(item.get("name"),""));cell(table,i,1,Objects.toString(item.get("quantity"),""));cell(table,i++,2,Objects.toString(item.get("condition"),""));}
                }
                text(doc,"Hai bên xác nhận việc giao/nhận nhà, chìa khóa và tài sản theo hiện trạng nêu trên. Chỉ số thực tế là bằng chứng đối chiếu, không tự sửa công tơ trong HĐ đã ký hoặc hóa đơn.",11,false);
            }else{
                text(doc,"Ngày kết thúc (không tính ngày này): "+Objects.toString(p.get("endExclusive"),""),11,true);
                text(doc,"Cách trả nhà và tài sản: "+Objects.toString(p.get("handoverTerms"),""),11,false);
                text(doc,"DAILY_PRORATED".equals(p.get("finalRentPolicy"))?"Tiền thuê kỳ cuối tính theo ngày thực tế của kỳ; phí khác theo HĐ/hóa đơn.":"Tiền thuê và phí cố định tính trọn kỳ đã bắt đầu.",11,false);
                var calc=support.map(source.getCalculationJson());if(!calc.isEmpty()){text(doc,"Đơn giá tháng: "+money(calc.get("monthlyRent"))+" · Số ngày: "+calc.get("usedDays")+"/"+calc.get("periodDays")+" · Tiền thuê kỳ cuối: "+money(calc.get("agreedFinalRent")),11,false);text(doc,Objects.toString(calc.get("explanation"),""),10,false);}
                if(p.get("rentRefunds") instanceof List<?> refunds&&!refunds.isEmpty())for(int i=0;i<refunds.size();i++){var refund=(Map<?,?>)refunds.get(i);var payment=support.payments.findById(Objects.toString(refund.get("paymentId"),"")).orElseThrow(support::missing);text(doc,"Khoản hoàn tiền thuê "+(i+1)+": "+money(refund.get("amount"))+" · Nguồn nhận: "+payment.getTransferReference(),11,false);}
                else text(doc,"Không có khoản hoàn tiền thuê bổ sung trong thỏa thuận này.",11,false);
                text(doc,"Chưa coi đã trả nhà hoặc hoàn tất tiền chỉ vì ký văn bản. Hai bên xác nhận trả nhà thực tế, thanh toán hóa đơn cuối rồi quyết toán cọc theo hồ sơ riêng; cọc không tự bị giữ toàn bộ.",11,false);
            }
            text(doc,"Xác nhận và chữ ký",12,true);text(doc,"Hai bên đã đối chiếu đúng nội dung này. Chữ ký số được kiểm chứng trên PDF. Việc xác nhận nhận tiền vẫn thực hiện riêng qua thanh toán; văn bản này không tạo chứng từ ngân hàng.",11,false);
            text(doc,"BÊN CHO THUÊ                                      BÊN THUÊ",11,true);
            doc.write(output);return conversion.convertDocxToPdf(output.toByteArray(),"homespace-workflow.docx").orElseThrow(()->support.invalid("Chưa chuyển được PDF. Kiểm tra dịch vụ chuyển văn bản và thử lại; hồ sơ gốc không thay đổi."));
        }catch(com.hs.common.advice.entity.AppException e){throw e;}catch(Exception e){throw new IllegalStateException("Cannot render workflow PDF",e);}
    }
    private void text(XWPFDocument d,String value,int size,boolean bold){var p=d.createParagraph();p.setSpacingAfter(120);p.setSpacingBetween(1.12);var r=p.createRun();r.setFontFamily("Arial");r.setFontSize(size);r.setBold(bold);String[] lines=value.split("\\R",-1);for(int i=0;i<lines.length;i++){if(i>0)r.addBreak();r.setText(lines[i]);}}
    private void cell(XWPFTable t,int row,int col,String value){var c=t.getRow(row).getCell(col);c.setText(value);for(var p:c.getParagraphs())for(var r:p.getRuns()){r.setFontFamily("Arial");r.setFontSize(10);}}
    private String money(Object value){try{return String.format(Locale.forLanguageTag("vi-VN"),"%,.0f đ",new BigDecimal(value.toString()));}catch(Exception e){return "chưa ghi nhận";}}
    private String date(Object value){try{return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(BillingTime.ZONE).format(Instant.parse(value.toString()));}catch(Exception e){return Objects.toString(value,"");}}
}
