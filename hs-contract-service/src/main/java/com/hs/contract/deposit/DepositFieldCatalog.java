package com.hs.contract.deposit;

import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.contract.service.engine.ContractFieldCatalog;
import com.hs.listing.model.constant.ListingCategory;
import java.util.*;

/** D2 Word contract. This catalog deliberately excludes lease billing/meter fields. */
public final class DepositFieldCatalog {
    public static final String KIND = "DEPOSIT";
    private final Map<String,TemplateFieldDefinition> fields = new LinkedHashMap<>();
    public DepositFieldCatalog(ContractFieldCatalog lease) {
        String[] reused={"landlord.fullName","landlord.phone","landlord.bankAccountHolder","landlord.bankAccountNumber","landlord.bankName",
            "tenant.fullName","tenant.phone","tenant.bankAccountHolder","tenant.bankAccountNumber","tenant.bankName","tenant.occupantCount","tenant.motorbikeCount","tenant.carCount",
            "property.propertyType","property.fullAddress","property.areaText","property.unitNumber","lease.startDateText","lease.endDateText","lease.durationMonths",
            "rent.amountNumber","rent.amountWords","deposit.amountNumber","deposit.amountWords"};
        for(String key:reused) {
            var original=lease.getDefinition(key).orElseThrow();
            add(key,original.getLabel(),original.getDescription(),original.getExample());
        }
        add("depositAgreement.number","Số hợp đồng cọc","Mã văn bản cọc riêng, không phải số hợp đồng thuê","DC-20261024-DEMO");
        add("depositAgreement.issuedAtText","Ngày giờ lập","Ngày lập snapshot, không phải thời gian ký số","24/10/2026 09:00:00");
        add("depositAgreement.reservationTerms","Điều kiện giữ chỗ","Toàn văn điều kiện đã chấp thuận; đặt một lần","Điều kiện giữ chỗ của hồ sơ minh họa");
        add("depositAgreement.nextStepsText","Bước tiếp theo","Đúng luồng vào ngay/vào sau; đặt một lần","Ký đủ hợp đồng cọc trước khi ký hợp đồng thuê.");
        add("rentalRequest.reference","Mã yêu cầu thuê","Mã tham chiếu hồ sơ được chấp nhận","YC-DEMO");
        add("rentalRequest.acceptedAtText","Ngày chấp nhận","Thời điểm chủ nhà chấp nhận","24/10/2026 08:00:00");
        add("rentalRequest.policyVersion","Phiên bản giữ chỗ","Version đã được khách đồng ý","RESERVATION_DEFAULT_V2");
        add("rentalRequest.policyAcceptedAtText","Thời điểm chấp thuận","Không phải thời gian ký PDF","24/10/2026 07:00:00");
        add("depositPayment.typeText","Loại khoản chuyển","Cọc riêng hoặc khoản gộp","Cọc giữ chỗ");
        add("depositPayment.totalAmountNumber","Tổng khoản chuyển","Tổng payment nguồn đã xác nhận","4.000.000 VNĐ");
        add("depositPayment.rentComponentAmountNumber","Phần tiền thuê","Phần RENT, 0 hợp lệ","0 VNĐ");
        add("depositPayment.confirmedAmountNumber","Phần cọc thực nhận","Chỉ phần DEPOSIT của payment đã xác nhận","4.000.000 VNĐ");
        add("depositPayment.confirmedAmountWords","Cọc thực nhận bằng chữ","Sinh từ cùng phần cọc","Bốn triệu đồng chẵn");
        add("depositPayment.payerReportedAt","Báo chuyển lúc","Khai báo của người trả, không phải xác nhận ngân hàng","24/10/2026 08:15:00");
        add("depositPayment.payeeConfirmedAt","Chủ nhà xác nhận lúc","Mốc xác nhận đã nhận đủ","24/10/2026 08:20:00");
        add("depositPayment.confirmedAt","Hoàn tất xác nhận lúc","Thời điểm ghi nhận payment CONFIRMED","24/10/2026 08:20:00");
        add("depositPayment.transferReference","Nội dung chuyển khoản","Mã payment chứa cọc, có thể là khoản gộp","HSDEMO123");
        add("#depositOptionalInfoTable","Thông tin bổ sung","Bảng Thông tin bổ sung | Giá trị ghi nhận; chỉ in dữ liệu có thật","[Bảng thông tin bổ sung]");
    }
    private void add(String key,String label,String description,String example) {
        fields.put(key,TemplateFieldDefinition.builder().key(key).label(label).group("Hợp đồng cọc D2")
            .dataType(key.startsWith("#")?"DYNAMIC_TABLE":"TEXT").description(description).example(example).required(true)
            .requiredForCategories("property.unitNumber".equals(key)?Set.of(ListingCategory.APARTMENT,ListingCategory.ROOM):ContractFieldCatalog.SUPPORTED_CATEGORIES).build());
    }
    public List<TemplateFieldDefinition> fields(){return new ArrayList<>(fields.values());}
    public boolean valid(String key){return fields.containsKey(ContractFieldCatalog.normalizeKey(key));}
    public boolean required(String key,ListingCategory category){var f=fields.get(key);return f!=null && (category==null?f.getRequiredForCategories().containsAll(ContractFieldCatalog.SUPPORTED_CATEGORIES):f.getRequiredForCategories().contains(category));}
    public List<TemplateFieldDefinition> required(ListingCategory category){return fields().stream().filter(f->required(f.getKey(),category)).toList();}
}
