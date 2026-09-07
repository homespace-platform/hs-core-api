package com.hs.contract.service.engine;

import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.listing.model.constant.ListingCategory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ContractFieldCatalog {

    /** Bắt buộc với mọi loại hình bất động sản. */
    private static final Set<ListingCategory> ALL = Collections.unmodifiableSet(EnumSet.allOf(ListingCategory.class));
    /** Luôn tùy chọn. */
    private static final Set<ListingCategory> OPTIONAL = Collections.unmodifiableSet(EnumSet.noneOf(ListingCategory.class));
    /** Hợp đồng thuê để ở: có người vào ở thực tế. */
    private static final Set<ListingCategory> RESIDENTIAL = Collections.unmodifiableSet(
            EnumSet.of(ListingCategory.APARTMENT, ListingCategory.HOUSE, ListingCategory.ROOM));
    /** Hợp đồng thuê kinh doanh: bên thuê thường là pháp nhân. */
    private static final Set<ListingCategory> BUSINESS = Collections.unmodifiableSet(
            EnumSet.of(ListingCategory.OFFICE, ListingCategory.COMMERCIAL_SPACE));

    private static final Map<String, TemplateFieldDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        // --- Bên cho thuê (Bên A) ---
        add("landlord.fullName", "Họ và tên chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Họ và tên đầy đủ của bên cho thuê", "Nguyễn Văn A", ALL);
        add("landlord.idNumber", "Số CCCD / Hộ chiếu chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Số căn cước công dân hoặc hộ chiếu", "079090001234", OPTIONAL);
        add("landlord.idIssueDate", "Ngày cấp CCCD chủ nhà", "Bên cho thuê (Bên A)", "DATE", "Ngày cấp căn cước công dân", "15/05/2021", OPTIONAL);
        add("landlord.idIssuePlace", "Nơi cấp CCCD chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Nơi cấp giấy tờ tùy thân", "Cục Cảnh sát QLHC về TTXH", OPTIONAL);
        add("landlord.permanentAddress", "Nơi thường trú chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Nơi thường trú của bên A theo Luật Cư trú 2020", "123 Đường Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM", OPTIONAL);
        add("landlord.phone", "Số điện thoại chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Số điện thoại liên hệ chính", "0901234567", ALL);
        add("landlord.email", "Email chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Địa chỉ hòm thư điện tử", "chuanha@example.com", OPTIONAL);

        // --- Bên thuê (Bên B) ---
        add("tenant.fullName", "Họ và tên người thuê", "Bên thuê (Bên B)", "TEXT", "Họ và tên đầy đủ người thuê đại diện ký", "Trần Thị B", ALL);
        add("tenant.idNumber", "Số CCCD / Hộ chiếu người thuê", "Bên thuê (Bên B)", "TEXT", "Số căn cước công dân hoặc hộ chiếu", "079195009876", OPTIONAL);
        add("tenant.idIssueDate", "Ngày cấp CCCD người thuê", "Bên thuê (Bên B)", "DATE", "Ngày cấp căn cước công dân", "20/08/2022", OPTIONAL);
        add("tenant.idIssuePlace", "Nơi cấp CCCD người thuê", "Bên thuê (Bên B)", "TEXT", "Nơi cấp giấy tờ tùy thân", "Cục Cảnh sát QLHC về TTXH", OPTIONAL);
        add("tenant.permanentAddress", "Nơi thường trú người thuê", "Bên thuê (Bên B)", "TEXT", "Nơi thường trú của bên B theo Luật Cư trú 2020", "456 Lê Lợi, Phường 4, Quận 3, TP.HCM", OPTIONAL);
        add("tenant.phone", "Số điện thoại người thuê", "Bên thuê (Bên B)", "TEXT", "Số điện thoại liên hệ", "0987654321", ALL);
        add("tenant.email", "Email người thuê", "Bên thuê (Bên B)", "TEXT", "Email nhận thông báo và hợp đồng", "nguoithue@example.com", OPTIONAL);
        add("tenant.occupantCount", "Số người vào ở", "Bên thuê (Bên B)", "NUMBER", "Số lượng người dọn vào ở thực tế", "2", RESIDENTIAL);
        add("tenant.organizationName", "Tên công ty / tổ chức", "Bên thuê (Bên B)", "TEXT", "Tên pháp nhân thuê mặt bằng hoặc văn phòng", "Công ty TNHH Giải Pháp Công Nghệ Mới", BUSINESS);
        add("tenant.representativeName", "Người đại diện theo PL", "Bên thuê (Bên B)", "TEXT", "Họ tên người đại diện pháp nhân", "Trần Văn C", BUSINESS);
        add("tenant.representativePosition", "Chức vụ người đại diện", "Bên thuê (Bên B)", "TEXT", "Chức vụ của người đại diện ký kết", "Giám Đốc", BUSINESS);

        // --- Tài sản & Bất động sản ---
        add("property.fullAddress", "Địa chỉ BĐS cho thuê", "Bất động sản", "TEXT", "Địa chỉ chi tiết nơi cho thuê", "Tầng 5, Căn hộ A-05, Tòa Landmark 81, 720A Điện Biên Phủ, P.22, Q.Bình Thạnh, TP.HCM", ALL);
        add("property.areaText", "Diện tích thuê", "Bất động sản", "TEXT", "Diện tích sử dụng cho thuê", "65 m²", ALL);
        add("property.propertyType", "Loại hình bất động sản", "Bất động sản", "TEXT", "Loại hình căn hộ, nhà nguyên căn, phòng trọ...", "Căn hộ chung cư", ALL);
        add("property.unitNumber", "Số căn hộ / số phòng", "Bất động sản", "TEXT", "Mã căn hộ hoặc số phòng cụ thể", "Phòng 502", OPTIONAL);
        add("property.floor", "Tầng", "Bất động sản", "TEXT", "Số tầng của bất động sản", "Tầng 5", OPTIONAL);

        // --- Thời hạn thuê ---
        add("lease.rentalMode", "Hình thức thuê", "Thời hạn thuê", "TEXT", "Thuê nguyên căn / toàn bộ hay thuê một phần / phòng riêng, lấy từ tin đăng", "Thuê nguyên căn / toàn bộ", ALL);
        add("lease.startDateText", "Ngày bắt đầu thuê", "Thời hạn thuê", "DATE", "Ngày bắt đầu có hiệu lực và bàn giao", "15/09/2026", ALL);
        add("lease.endDateText", "Ngày kết thúc thuê", "Thời hạn thuê", "DATE", "Ngày hết hạn hợp đồng thuê", "14/09/2027", ALL);
        add("lease.durationMonths", "Số tháng thuê", "Thời hạn thuê", "NUMBER", "Tổng thời gian thuê tính theo tháng", "12", ALL);
        add("lease.durationText", "Diễn giải thời hạn thuê", "Thời hạn thuê", "TEXT", "Quy đổi thời gian theo năm và tháng", "1 năm (12 tháng)", ALL);
        add("lease.handoverDateText", "Ngày bàn giao nhà", "Thời hạn thuê", "DATE", "Ngày thực hiện bàn giao tài sản", "15/09/2026", OPTIONAL);

        // --- Giá thuê & Đặt cọc ---
        add("rent.amountNumber", "Giá thuê bằng số", "Giá thuê & Cọc", "NUMBER", "Số tiền thuê mỗi kỳ (định dạng dấu chấm)", "10.000.000 VNĐ/tháng", ALL);
        add("rent.amountWords", "Giá thuê bằng chữ", "Giá thuê & Cọc", "TEXT", "Số tiền thuê viết bằng chữ tiếng Việt", "Mười triệu đồng chẵn", ALL);
        add("rent.paymentCycle", "Chu kỳ thanh toán", "Giá thuê & Cọc", "TEXT", "Kỳ hạn trả tiền thuê (tháng, quý, năm)", "Hàng tháng", ALL);
        add("rent.paymentDueDay", "Hạn đóng tiền hàng tháng", "Giá thuê & Cọc", "TEXT", "Ngày thanh toán cố định trong tháng", "Từ ngày 01 đến ngày 05 hàng tháng", ALL);
        add("rent.paymentMethod", "Phương thức thanh toán", "Giá thuê & Cọc", "TEXT", "Kênh thanh toán tiền thuê, mặc định qua hệ thống HomeSpace", "Thanh toán trực tuyến qua hệ thống HomeSpace", OPTIONAL);
        add("deposit.amountNumber", "Tiền cọc bằng số", "Giá thuê & Cọc", "NUMBER", "Số tiền đặt cọc", "10.000.000 VNĐ", ALL);
        add("deposit.amountWords", "Tiền cọc bằng chữ", "Giá thuê & Cọc", "TEXT", "Số tiền đặt cọc viết bằng chữ tiếng Việt", "Mười triệu đồng chẵn", ALL);
        add("deposit.description", "Nội dung thỏa thuận cọc", "Giá thuê & Cọc", "TEXT", "Chi tiết điều khoản hoàn trả tiền cọc", "Tiền cọc được hoàn lại sau khi hết hạn hợp đồng và trừ các chi phí chưa thanh toán (nếu có).", OPTIONAL);

        // --- Chỉ số điện nước ban đầu ---
        add("meters.electricityInitial", "Chỉ số điện ban đầu", "Chỉ số bàn giao", "NUMBER", "Chỉ số công tơ điện lúc giao nhận nhà", "1250 kWh", OPTIONAL);
        add("meters.waterInitial", "Chỉ số nước ban đầu", "Chỉ số bàn giao", "NUMBER", "Chỉ số đồng hồ nước lúc giao nhận nhà", "85 m³", OPTIONAL);

        // --- Hợp đồng & Pháp lý ---
        add("contract.number", "Số hợp đồng", "Pháp lý hợp đồng", "TEXT", "Mã hiệu hợp đồng tự sinh", "HD-20260905-001", ALL);
        add("contract.signingDate", "Ngày ký kết", "Pháp lý hợp đồng", "DATE", "Ngày hai bên ký kết hợp đồng", "05/09/2026", ALL);
        add("contract.signingCity", "Địa điểm ký", "Pháp lý hợp đồng", "TEXT", "Tỉnh/Thành phố lập hợp đồng", "Thành phố Hồ Chí Minh", OPTIONAL);

        // --- Bảng động poi-tl ---
        add("#chargesTable", "Bảng biểu phí dịch vụ", "Bảng động", "DYNAMIC_TABLE", "Bảng chi tiết các khoản tiền điện, nước, gửi xe, quản lý... tự động mở rộng theo thỏa thuận", "[Bảng 3 cột: Khoản phí | Đơn giá / Cách tính | Ghi chú]", ALL);
        add("#equipmentTable", "Bảng trang thiết bị bàn giao", "Bảng động", "DYNAMIC_TABLE", "Biên bản danh mục trang thiết bị nội thất bàn giao", "[Bảng 4 cột: STT | Tên tài sản | Số lượng | Hiện trạng]", OPTIONAL);
    }

    private static void add(String key, String label, String group, String dataType, String description,
                            String example, Set<ListingCategory> requiredForCategories) {
        DEFINITIONS.put(key, TemplateFieldDefinition.builder()
                .key(key)
                .label(label)
                .group(group)
                .dataType(dataType)
                .description(description)
                .example(example)
                .required(!requiredForCategories.isEmpty())
                .requiredForCategories(requiredForCategories)
                .build());
    }

    public List<TemplateFieldDefinition> getAllDefinitions() {
        return new ArrayList<>(DEFINITIONS.values());
    }

    /**
     * Danh sách trường bắt buộc cho một loại hình bất động sản.
     * Với mẫu chưa gắn loại hình (dữ liệu cũ), chỉ áp bộ trường bắt buộc chung cho cả 5 loại hình.
     */
    public List<TemplateFieldDefinition> getRequiredDefinitions(ListingCategory category) {
        return DEFINITIONS.values().stream()
                .filter(def -> isRequiredFor(def, category))
                .toList();
    }

    private static boolean isRequiredFor(TemplateFieldDefinition def, ListingCategory category) {
        Set<ListingCategory> scope = def.getRequiredForCategories();
        if (scope == null || scope.isEmpty()) {
            return false;
        }
        return category == null ? scope.size() == ListingCategory.values().length : scope.contains(category);
    }

    public Optional<TemplateFieldDefinition> getDefinition(String rawKey) {
        String cleanKey = normalizeKey(rawKey);
        return Optional.ofNullable(DEFINITIONS.get(cleanKey));
    }

    public boolean isValidPlaceholder(String rawKey) {
        String cleanKey = normalizeKey(rawKey);
        return DEFINITIONS.containsKey(cleanKey);
    }

    public static String normalizeKey(String rawKey) {
        if (rawKey == null) return "";
        String trimmed = rawKey.trim();
        // Xóa dấu {{ }} nếu có
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            trimmed = trimmed.substring(2, trimmed.length() - 2).trim();
        }
        // Giữ lại dấu # nếu là dynamic table
        return trimmed;
    }
}
