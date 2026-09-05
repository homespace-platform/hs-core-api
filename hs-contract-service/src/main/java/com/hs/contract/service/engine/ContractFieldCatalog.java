package com.hs.contract.service.engine;

import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ContractFieldCatalog {

    private static final Map<String, TemplateFieldDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        // --- Bên cho thuê (Bên A) ---
        add("landlord.fullName", "Họ và tên chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Họ và tên đầy đủ của bên cho thuê", "Nguyễn Văn A", true);
        add("landlord.idNumber", "Số CCCD / Hộ chiếu chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Số căn cước công dân hoặc hộ chiếu", "079090001234", false);
        add("landlord.idIssueDate", "Ngày cấp CCCD chủ nhà", "Bên cho thuê (Bên A)", "DATE", "Ngày cấp căn cước công dân", "15/05/2021", false);
        add("landlord.idIssuePlace", "Nơi cấp CCCD chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Nơi cấp giấy tờ tùy thân", "Cục Cảnh sát QLHC về TTXH", false);
        add("landlord.permanentAddress", "Địa chỉ thường trú chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Địa chỉ hộ khẩu thường trú bên A", "123 Đường Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM", false);
        add("landlord.phone", "Số điện thoại chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Số điện thoại liên hệ chính", "0901234567", true);
        add("landlord.email", "Email chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Địa chỉ hòm thư điện tử", "chuanha@example.com", false);
        add("landlord.bankAccount", "Số tài khoản ngân hàng", "Bên cho thuê (Bên A)", "TEXT", "Số tài khoản nhận tiền thuê nhà", "19031234567890", false);
        add("landlord.bankName", "Tên ngân hàng chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Tên ngân hàng và chi nhánh", "Techcombank - CN Sài Gòn", false);

        // --- Bên thuê (Bên B) ---
        add("tenant.fullName", "Họ và tên người thuê", "Bên thuê (Bên B)", "TEXT", "Họ và tên đầy đủ người thuê đại diện ký", "Trần Thị B", true);
        add("tenant.idNumber", "Số CCCD / Hộ chiếu người thuê", "Bên thuê (Bên B)", "TEXT", "Số căn cước công dân hoặc hộ chiếu", "079195009876", false);
        add("tenant.idIssueDate", "Ngày cấp CCCD người thuê", "Bên thuê (Bên B)", "DATE", "Ngày cấp căn cước công dân", "20/08/2022", false);
        add("tenant.idIssuePlace", "Nơi cấp CCCD người thuê", "Bên thuê (Bên B)", "TEXT", "Nơi cấp giấy tờ tùy thân", "Cục Cảnh sát QLHC về TTXH", false);
        add("tenant.permanentAddress", "Địa chỉ thường trú người thuê", "Bên thuê (Bên B)", "TEXT", "Địa chỉ hộ khẩu thường trú bên B", "456 Lê Lợi, Phường 4, Quận 3, TP.HCM", false);
        add("tenant.phone", "Số điện thoại người thuê", "Bên thuê (Bên B)", "TEXT", "Số điện thoại liên hệ", "0987654321", true);
        add("tenant.email", "Email người thuê", "Bên thuê (Bên B)", "TEXT", "Email nhận thông báo và hợp đồng", "nguoithue@example.com", false);
        add("tenant.occupantCount", "Số người vào ở", "Bên thuê (Bên B)", "NUMBER", "Số lượng người dọn vào ở thực tế", "2", true);
        add("tenant.organizationName", "Tên công ty / tổ chức", "Bên thuê (Bên B)", "TEXT", "Tên pháp nhân thuê (nếu là doanh nghiệp)", "Công ty TNHH Giải Pháp Công Nghệ Mới", false);
        add("tenant.representativeName", "Người đại diện theo PL", "Bên thuê (Bên B)", "TEXT", "Họ tên người đại diện pháp nhân", "Trần Văn C", false);
        add("tenant.representativePosition", "Chức vụ người đại diện", "Bên thuê (Bên B)", "TEXT", "Chức vụ của người đại diện ký kết", "Giám Đốc", false);

        // --- Tài sản & Bất động sản ---
        add("property.fullAddress", "Địa chỉ BĐS cho thuê", "Bất động sản", "TEXT", "Địa chỉ chi tiết nơi cho thuê", "Tầng 5, Căn hộ A-05, Tòa Landmark 81, 720A Điện Biên Phủ, P.22, Q.Bình Thạnh, TP.HCM", true);
        add("property.areaText", "Diện tích thuê", "Bất động sản", "TEXT", "Diện tích sử dụng cho thuê", "65 m²", true);
        add("property.propertyType", "Loại hình bất động sản", "Bất động sản", "TEXT", "Loại hình căn hộ, nhà riêng, phòng trọ...", "Căn hộ chung cư", true);
        add("property.unitNumber", "Số căn hộ / số phòng", "Bất động sản", "TEXT", "Mã căn hộ hoặc số phòng cụ thể", "Phòng 502", false);
        add("property.floor", "Tầng", "Bất động sản", "TEXT", "Số tầng của bất động sản", "Tầng 5", false);

        // --- Thời hạn thuê ---
        add("lease.startDateText", "Ngày bắt đầu thuê", "Thời hạn thuê", "DATE", "Ngày bắt đầu có hiệu lực và bàn giao", "15/09/2026", true);
        add("lease.endDateText", "Ngày kết thúc thuê", "Thời hạn thuê", "DATE", "Ngày hết hạn hợp đồng thuê", "14/09/2027", true);
        add("lease.durationMonths", "Số tháng thuê", "Thời hạn thuê", "NUMBER", "Tổng thời gian thuê tính theo tháng", "12", true);
        add("lease.durationText", "Diễn giải thời hạn thuê", "Thời hạn thuê", "TEXT", "Quy đổi thời gian theo năm và tháng", "1 năm (12 tháng)", true);
        add("lease.handoverDateText", "Ngày bàn giao nhà", "Thời hạn thuê", "DATE", "Ngày thực hiện bàn giao tài sản", "15/09/2026", false);

        // --- Giá thuê & Đặt cọc ---
        add("rent.amountNumber", "Giá thuê bằng số", "Giá thuê & Cọc", "NUMBER", "Số tiền thuê mỗi kỳ (định dạng dấu chấm)", "10.000.000 VNĐ/tháng", true);
        add("rent.amountWords", "Giá thuê bằng chữ", "Giá thuê & Cọc", "TEXT", "Số tiền thuê viết bằng chữ tiếng Việt", "Mười triệu đồng chẵn", true);
        add("rent.paymentCycle", "Chu kỳ thanh toán", "Giá thuê & Cọc", "TEXT", "Kỳ hạn trả tiền thuê (tháng, quý, năm)", "Hàng tháng", true);
        add("rent.paymentDueDay", "Hạn đóng tiền hàng tháng", "Giá thuê & Cọc", "TEXT", "Ngày thanh toán cố định trong tháng", "Từ ngày 01 đến ngày 05 hàng tháng", true);
        add("rent.paymentMethod", "Phương thức thanh toán", "Giá thuê & Cọc", "TEXT", "Hình thức chuyển khoản hoặc tiền mặt", "Chuyển khoản ngân hàng", false);
        add("deposit.amountNumber", "Tiền cọc bằng số", "Giá thuê & Cọc", "NUMBER", "Số tiền đặt cọc", "10.000.000 VNĐ", true);
        add("deposit.amountWords", "Tiền cọc bằng chữ", "Giá thuê & Cọc", "TEXT", "Số tiền đặt cọc viết bằng chữ tiếng Việt", "Mười triệu đồng chẵn", true);
        add("deposit.description", "Nội dung thỏa thuận cọc", "Giá thuê & Cọc", "TEXT", "Chi tiết điều khoản hoàn trả tiền cọc", "Tiền cọc được hoàn lại sau khi hết hạn hợp đồng và trừ các chi phí chưa thanh toán (nếu có).", false);

        // --- Chỉ số điện nước ban đầu ---
        add("meters.electricityInitial", "Chỉ số điện ban đầu", "Chỉ số bàn giao", "NUMBER", "Chỉ số công tơ điện lúc giao nhận nhà", "1250 kWh", false);
        add("meters.waterInitial", "Chỉ số nước ban đầu", "Chỉ số bàn giao", "NUMBER", "Chỉ số đồng hồ nước lúc giao nhận nhà", "85 m³", false);

        // --- Hợp đồng & Pháp lý ---
        add("contract.number", "Số hợp đồng", "Pháp lý hợp đồng", "TEXT", "Mã hiệu hợp đồng tự sinh", "HD-20260905-001", true);
        add("contract.signingDate", "Ngày ký kết", "Pháp lý hợp đồng", "DATE", "Ngày hai bên ký kết hợp đồng", "05/09/2026", true);
        add("contract.signingCity", "Địa điểm ký", "Pháp lý hợp đồng", "TEXT", "Tỉnh/Thành phố lập hợp đồng", "Thành phố Hồ Chí Minh", false);

        // --- Bảng động poi-tl ---
        add("#chargesTable", "Bảng biểu phí dịch vụ", "Bảng động", "DYNAMIC_TABLE", "Bảng chi tiết các khoản tiền điện, nước, gửi xe, quản lý... tự động mở rộng theo thỏa thuận", "[Bảng 3 cột: Khoản phí | Đơn giá / Cách tính | Ghi chú]", true);
        add("#equipmentTable", "Bảng trang thiết bị bàn giao", "Bảng động", "DYNAMIC_TABLE", "Biên bản danh mục trang thiết bị nội thất bàn giao", "[Bảng 4 cột: STT | Tên tài sản | Số lượng | Hiện trạng]", false);
    }

    private static void add(String key, String label, String group, String dataType, String description, String example, boolean required) {
        DEFINITIONS.put(key, TemplateFieldDefinition.builder()
                .key(key)
                .label(label)
                .group(group)
                .dataType(dataType)
                .description(description)
                .example(example)
                .required(required)
                .build());
    }

    public List<TemplateFieldDefinition> getAllDefinitions() {
        return new ArrayList<>(DEFINITIONS.values());
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
