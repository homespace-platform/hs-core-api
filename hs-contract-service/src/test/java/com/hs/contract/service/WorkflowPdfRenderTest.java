package com.hs.contract.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.model.*;
import com.hs.contract.pdf.PdfSignatureEngine;
import com.hs.contract.service.converter.DocumentConversionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Real DOCX -> Gotenberg -> PDF + the production signature page. No provider call. */
@EnabledIfEnvironmentVariable(named="HS_WORKFLOW_PDF_QA_URL",matches="http://127\\.0\\.0\\.1:3001")
class WorkflowPdfRenderTest {
    @Test void rendersBothVietnameseDocumentsWithRealConverter() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var support=new RentalWorkflowSupport(null,null,null,null,null,null,mapper,null);
        var conversion=new DocumentConversionService(5,45);
        ReflectionTestUtils.setField(conversion,"gotenbergUrl",System.getenv("HS_WORKFLOW_PDF_QA_URL"));ReflectionTestUtils.setField(conversion,"gotenbergEnabled",true);
        var renderer=new WorkflowPdfRenderer(conversion,support);
        var root=Contract.builder().contractNumber("HD-KIEM-THU-20261103").build();
        var revision=ContractRevision.builder().landlordSnapshot(support.json(Map.of("fullName","Nguyễn Văn Chủ Nhà"))).tenantSnapshot(support.json(Map.of("fullName","Trần Thị Người Thuê"))).propertySnapshot(support.json(Map.of("fullAddress","12 đường Nguyễn Văn Bảo, phường Hạnh Thông, Thành phố Hồ Chí Minh","unitNumber","P.302"))).build();
        var target=Path.of("target","workflow-pdf-qa");Files.createDirectories(target);
        for(String kind:List.of("MOVE_IN","EARLY_EXIT")){
            var w=new RentalWorkflowRecord();w.setKind(kind);w.setRevision(7);
            w.setPayloadJson(support.json(kind.equals("MOVE_IN")?Map.of("reason","Hai bên đã cùng kiểm tra đầy đủ nhà, chìa khóa và tài sản trước khi nhận.","handedOverAt",Instant.parse("2026-11-03T03:00:00Z"),"keys",2,"condition","Phòng sạch, cửa sổ và khóa hoạt động; tường gần cửa có một vết xước nhỏ đã chụp ảnh đối chiếu.","electricity",1245.5,"water",62.25,"inventory",List.of(Map.of("name","Máy lạnh Panasonic 1 HP","quantity",1,"condition","Chạy ổn định; bàn giao kèm điều khiển."),Map.of("name","Bộ bàn ghế và tủ quần áo","quantity",3,"condition","Đầy đủ, một vết xước nhỏ ở cạnh tủ đã được ghi nhận."))):Map.of("reason","Người thuê chuyển công tác, hai bên thống nhất kết thúc sớm và đối soát đầy đủ nghĩa vụ.","endExclusive","2026-12-20","handoverTerms","Trả phòng trống, đủ hai chìa khóa, đối chiếu tài sản và công tơ lúc 10:00 ngày 19/12/2026. Hai bên kiểm tra và xác nhận thực tế tại mục trả nhà.","finalRentPolicy","DAILY_PRORATED","rentRefunds",List.of())));
            w.setCalculationJson(support.json(Map.of("monthlyRent",5000000,"usedDays",8,"periodDays",31,"agreedFinalRent",1290323,"explanation","Kỳ thuê từ 12/12/2026 đến 12/01/2027; tiền thuê tính theo số ngày thực tế. Khoản hoàn phải ghi đúng nguồn tiền thực nhận, không tự sửa hóa đơn đã phát hành.")));
            byte[] pdf=new PdfSignatureEngine().addSignaturePage(renderer.render(root,w,revision));Files.write(target.resolve(kind.toLowerCase(Locale.ROOT)+".pdf"),pdf);
            try(var parsed=Loader.loadPDF(pdf)){var pages=new org.apache.pdfbox.rendering.PDFRenderer(parsed);for(int page=0;page<parsed.getNumberOfPages();page++)javax.imageio.ImageIO.write(pages.renderImageWithDPI(page,100),"png",target.resolve(kind.toLowerCase(Locale.ROOT)+"-"+(page+1)+".png").toFile());String text=new PDFTextStripper().getText(parsed);assertTrue(text.contains("Nguyễn Văn Chủ Nhà"));assertTrue(text.contains("Trần Thị Người Thuê"));assertTrue(text.contains("Nguyễn Văn Bảo"));assertTrue(parsed.getNumberOfPages()>=2);assertTrue(text.contains(kind.equals("MOVE_IN")?"BIÊN BẢN GIAO NHẬN NHÀ":"THỎA THUẬN CHẤM DỨT THUÊ TRƯỚC HẠN"));}
        }
    }
}
