package com.hs.contract.service.engine;

import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.listing.model.constant.ListingCategory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ContractFieldCatalog {

    /** Ba loại hình bất động sản được hỗ trợ trên hệ thống HomeSpace. */
    public static final Set<ListingCategory> SUPPORTED_CATEGORIES = Collections.unmodifiableSet(
            EnumSet.of(ListingCategory.APARTMENT, ListingCategory.HOUSE, ListingCategory.ROOM));
    /** Bắt buộc với cả ba loại hình bất động sản được hỗ trợ. */
    private static final Set<ListingCategory> ALL = SUPPORTED_CATEGORIES;
    /** Luôn tùy chọn. */
    private static final Set<ListingCategory> OPTIONAL = Collections.unmodifiableSet(EnumSet.noneOf(ListingCategory.class));
    /** Bắt buộc đối với căn hộ chung cư và phòng trọ (nhà nguyên căn không bắt buộc số phòng/căn). */
    private static final Set<ListingCategory> APARTMENT_AND_ROOM = Collections.unmodifiableSet(
            EnumSet.of(ListingCategory.APARTMENT, ListingCategory.ROOM));

    private static final Map<String, TemplateFieldDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        // --- Bên cho thuê (Bên A) ---
        add("landlord.fullName", "Họ và tên chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Họ và tên đầy đủ của bên cho thuê", "Nguyễn Văn A", ALL);
        add("landlord.idNumber", "Số CCCD / Hộ chiếu chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Số căn cước công dân hoặc hộ chiếu", "079090001234", OPTIONAL);
        add("landlord.permanentAddress", "Nơi thường trú chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Nơi thường trú của bên A theo Luật Cư trú 2020", "123 Đường Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM", OPTIONAL);
        add("landlord.phone", "Số điện thoại chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Số điện thoại liên hệ chính", "0901234567", ALL);
        add("landlord.email", "Email chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Địa chỉ hòm thư điện tử", "chuanha@example.com", OPTIONAL);
        add("landlord.bankName", "Tên ngân hàng chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Tên ngân hàng nhận thanh toán của Bên A", "Ngân hàng TMCP Quân đội (MBBank)", ALL);
        add("landlord.bankAccountNumber", "Số tài khoản chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Số tài khoản ngân hàng nhận thanh toán của Bên A", "0123456789", ALL);
        add("landlord.bankAccountHolder", "Tên chủ tài khoản chủ nhà", "Bên cho thuê (Bên A)", "TEXT", "Tên chủ tài khoản ngân hàng nhận thanh toán của Bên A", "NGUYEN VAN A", ALL);

        // --- Bên thuê (Bên B) ---
        add("tenant.fullName", "Họ và tên người thuê", "Bên thuê (Bên B)", "TEXT", "Họ và tên đầy đủ người thuê đại diện ký", "Trần Thị B", ALL);
        add("tenant.idNumber", "Số CCCD / Hộ chiếu người thuê", "Bên thuê (Bên B)", "TEXT", "Số căn cước công dân hoặc hộ chiếu", "079195009876", OPTIONAL);
        add("tenant.permanentAddress", "Nơi thường trú người thuê", "Bên thuê (Bên B)", "TEXT", "Nơi thường trú của bên B theo Luật Cư trú 2020", "456 Lê Lợi, Phường 4, Quận 3, TP.HCM", OPTIONAL);
        add("tenant.phone", "Số điện thoại người thuê", "Bên thuê (Bên B)", "TEXT", "Số điện thoại liên hệ", "0987654321", ALL);
        add("tenant.email", "Email người thuê", "Bên thuê (Bên B)", "TEXT", "Email nhận thông báo và hợp đồng", "nguoithue@example.com", OPTIONAL);
        add("tenant.occupantCount", "Số người vào ở", "Bên thuê (Bên B)", "NUMBER", "Số lượng người dọn vào ở thực tế", "2", ALL);
        add("tenant.motorbikeCount", "Số xe máy đăng ký", "Bên thuê (Bên B)", "NUMBER", "Số lượng xe máy đăng ký gửi", "0", OPTIONAL);
        add("tenant.carCount", "Số ô tô đăng ký", "Bên thuê (Bên B)", "NUMBER", "Số lượng ô tô đăng ký gửi", "0", OPTIONAL);
        add("tenant.bankName", "Tên ngân hàng người thuê", "Bên thuê (Bên B)", "TEXT", "Tên ngân hàng nhận hoàn trả của Bên B", "Ngân hàng TMCP Ngoại thương Việt Nam (Vietcombank)", ALL);
        add("tenant.bankAccountNumber", "Số tài khoản người thuê", "Bên thuê (Bên B)", "TEXT", "Số tài khoản ngân hàng nhận hoàn trả của Bên B", "9876543210", ALL);
        add("tenant.bankAccountHolder", "Tên chủ tài khoản người thuê", "Bên thuê (Bên B)", "TEXT", "Tên chủ tài khoản ngân hàng nhận hoàn trả của Bên B", "TRAN THI B", ALL);

        // --- Tài sản & Bất động sản ---
        add("property.fullAddress", "Địa chỉ BĐS cho thuê", "Bất động sản", "TEXT", "Địa chỉ chi tiết nơi cho thuê", "Tầng 5, Căn hộ A-05, Tòa Landmark 81, 720A Điện Biên Phủ, P.22, Q.Bình Thạnh, TP.HCM", ALL);
        add("property.areaText", "Diện tích thuê", "Bất động sản", "TEXT", "Diện tích sử dụng cho thuê", "65 m²", ALL);
        add("property.propertyType", "Loại hình bất động sản", "Bất động sản", "TEXT", "Loại hình căn hộ chung cư, nhà nguyên căn, phòng trọ", "Căn hộ chung cư", ALL);
        add("property.unitNumber", "Số căn hộ / số phòng", "Bất động sản", "TEXT", "Mã căn hộ hoặc số phòng cụ thể", "Phòng 502", APARTMENT_AND_ROOM);
        add("property.floor", "Tầng", "Bất động sản", "TEXT", "Số tầng của bất động sản", "Tầng 5", OPTIONAL);

        // --- Thời hạn thuê ---
        add("lease.startDateText", "Ngày bắt đầu thuê", "Thời hạn thuê", "DATE", "Ngày bắt đầu có hiệu lực và bàn giao", "15/09/2026", ALL);
        add("lease.endDateText", "Ngày kết thúc thuê", "Thời hạn thuê", "DATE", "Ngày hết hạn hợp đồng thuê", "14/09/2027", ALL);
        add("lease.durationMonths", "Số tháng thuê", "Thời hạn thuê", "NUMBER", "Tổng thời gian thuê tính theo tháng", "12", ALL);
        add("lease.durationText", "Diễn giải thời hạn thuê", "Thời hạn thuê", "TEXT", "Quy đổi thời gian theo năm và tháng", "1 năm (12 tháng)", ALL);
        add("lease.handoverDateText", "Ngày bàn giao nhà", "Thời hạn thuê", "DATE", "Ngày thực hiện bàn giao tài sản", "15/09/2026", OPTIONAL);

        // --- Giá thuê & Đặt cọc ---
        add("rent.amountNumber", "Giá thuê bằng số", "Giá thuê & Cọc", "NUMBER", "Số tiền thuê mỗi kỳ (định dạng dấu chấm)", "10.000.000 VNĐ/tháng", ALL);
        add("rent.amountWords", "Giá thuê bằng chữ", "Giá thuê & Cọc", "TEXT", "Số tiền thuê viết bằng chữ tiếng Việt", "Mười triệu đồng chẵn", ALL);
        add("rent.paymentCycle", "Chu kỳ thanh toán", "Giá thuê & Cọc", "TEXT", "Kỳ hạn trả tiền thuê (Hàng tháng)", "Hàng tháng", ALL);
        add("rent.paymentDueDay", "Hạn đóng tiền hàng tháng", "Giá thuê & Cọc", "TEXT", "Ngày thanh toán cố định trong tháng", "Từ ngày 01 đến ngày 05 hàng tháng", ALL);
        add("rent.paymentMethod", "Phương thức thanh toán", "Giá thuê & Cọc", "TEXT", "Kênh thanh toán tiền thuê, chuyển khoản trực tiếp vào tài khoản ngân hàng của Bên A", "Chuyển khoản trực tiếp vào tài khoản ngân hàng của Bên A chỉ định trong Hợp đồng này", OPTIONAL);
        add("deposit.amountNumber", "Tiền cọc bằng số", "Giá thuê & Cọc", "NUMBER", "Số tiền đặt cọc", "10.000.000 VNĐ", ALL);
        add("deposit.amountWords", "Tiền cọc bằng chữ", "Giá thuê & Cọc", "TEXT", "Số tiền đặt cọc viết bằng chữ tiếng Việt", "Mười triệu đồng chẵn", ALL);
        add("deposit.description", "Nội dung quy định cọc", "Giá thuê & Cọc", "TEXT", "Chi tiết điều khoản cọc và hoàn trả tiền cọc", "Tiền cọc được hoàn lại sau khi hết hạn hợp đồng và trừ các chi phí chưa thanh toán (nếu có).", OPTIONAL);

        // --- Chỉ số điện nước ban đầu ---
        add("meters.electricityInitial", "Chỉ số điện ban đầu", "Chỉ số bàn giao", "NUMBER", "Chỉ số công tơ điện lúc giao nhận nhà", "1250 kWh", OPTIONAL);
        add("meters.waterInitial", "Chỉ số nước ban đầu", "Chỉ số bàn giao", "NUMBER", "Chỉ số đồng hồ nước lúc giao nhận nhà", "85 m³", OPTIONAL);

        // --- Hợp đồng & Pháp lý ---
        add("contract.number", "Số hợp đồng", "Pháp lý hợp đồng", "TEXT", "Mã hiệu hợp đồng tự sinh", "HD-20260905-001", ALL);
        add("contract.signingDate", "Ngày ký kết", "Pháp lý hợp đồng", "DATE", "Ngày hai bên ký kết hợp đồng", "05/09/2026", ALL);
        add("contract.signingCity", "Địa điểm ký", "Pháp lý hợp đồng", "TEXT", "Tỉnh/Thành phố lập hợp đồng", "Thành phố Hồ Chí Minh", OPTIONAL);
        add("contract.schemaVersion", "Phiên bản schema hợp đồng", "Pháp lý hợp đồng", "NUMBER", "Phiên bản cấu trúc dữ liệu hợp đồng (mặc định 3)", "3", OPTIONAL);
        add("contract.revisionNumber", "Số lần sửa đổi hợp đồng", "Pháp lý hợp đồng", "NUMBER", "Số thứ tự phiên bản sửa đổi của hợp đồng", "1", OPTIONAL);
        add("contract.specialTerms", "Điều khoản đặc biệt / Thỏa thuận riêng", "Pháp lý hợp đồng", "TEXT", "Nội dung điều khoản thỏa thuận bổ sung giữa hai bên", "Bên B không được gây ồn sau 23h.", OPTIONAL);

        // --- Bất động sản ---
        add("property.listingCode", "Mã tin đăng", "Bất động sản", "TEXT", "Mã hiệu quản lý của tin đăng", "HS-2026-001", OPTIONAL);
        add("property.rentalScope", "Phạm vi cho thuê", "Bất động sản", "TEXT", "Mô tả phạm vi thuê tài sản", "Thuê toàn bộ căn hộ chung cư", OPTIONAL);
        add("property.maxOccupants", "Số người tối đa cho phép", "Bất động sản", "NUMBER", "Số lượng người lưu trú tối đa cho phép", "4", OPTIONAL);
        add("property.maxVehicles", "Số phương tiện tối đa cho phép", "Bất động sản", "NUMBER", "Số lượng phương tiện tối đa cho phép gửi", "2", OPTIONAL);

        // --- Thanh toán ban đầu (Schema V3) ---
        add("payment.initial.status", "Trạng thái thanh toán ban đầu", "Thanh toán ban đầu", "TEXT", "Trạng thái xác nhận thanh toán ban đầu trước khi ký hợp đồng", "Khoản chuyển đã được hai bên xác nhận", OPTIONAL);
        add("payment.initial.paidAt", "Thời điểm thanh toán ban đầu", "Thanh toán ban đầu", "TEXT", "Thời gian hoàn tất thanh toán ban đầu", "15/09/2026 14:30:00", OPTIONAL);
        add("payment.initial.payerReportedAt", "Thời điểm người thuê báo chuyển", "Thanh toán ban đầu", "TEXT", "Thời điểm Bên B khai báo chuyển khoản trên hệ thống", "15/09/2026 14:30:00", OPTIONAL);
        add("payment.initial.payeeConfirmedAt", "Thời điểm chủ nhà xác nhận", "Thanh toán ban đầu", "TEXT", "Thời điểm Bên A xác nhận đã nhận đủ tiền", "15/09/2026 14:45:00", OPTIONAL);
        add("payment.initial.confirmedAt", "Thời điểm xác nhận hoàn tất", "Thanh toán ban đầu", "TEXT", "Thời điểm xác nhận hoàn tất", "15/09/2026 14:45:00", OPTIONAL);
        add("payment.initial.transferReference", "Mã nội dung chuyển khoản", "Thanh toán ban đầu", "TEXT", "Mã nội dung chuyển khoản VietQR", "HS7K29P4A1", OPTIONAL);
        add("payment.initial.bankTransactionReference", "Mã giao dịch ngân hàng", "Thanh toán ban đầu", "TEXT", "Mã tham chiếu ngân hàng Bên B cung cấp", "FT2625901234", OPTIONAL);
        add("payment.initial.transactionCode", "Mã tham chiếu thanh toán", "Thanh toán ban đầu", "TEXT", "Mã nội dung chuyển khoản hoặc mã giao dịch", "HS7K29P4A1", OPTIONAL);
        add("payment.initial.totalAmount", "Tổng số tiền ban đầu", "Thanh toán ban đầu", "TEXT", "Tổng khoản tiền người thuê đã thanh toán", "20.000.000 VNĐ", OPTIONAL);

        // --- Bảng động poi-tl ---
        add("#chargesTable", "Bảng biểu phí dịch vụ", "Bảng động", "DYNAMIC_TABLE", "Bảng chi tiết các khoản tiền điện, nước, gửi xe, quản lý... tự động mở rộng theo thỏa thuận", "[Bảng 3 cột: Khoản phí | Đơn giá / Cách tính | Ghi chú]", ALL);
        add("#equipmentTable", "Bảng trang thiết bị bàn giao", "Bảng động", "DYNAMIC_TABLE", "Biên bản danh mục trang thiết bị nội thất bàn giao", "[Bảng 4 cột: STT | Tên tài sản | Số lượng | Hiện trạng]", OPTIONAL);
        add("#propertyFeaturesTable", "Bảng đặc điểm bất động sản", "Bảng động", "DYNAMIC_TABLE", "Bảng chi tiết các thông số kỹ thuật và đặc điểm của tài sản cho thuê theo loại hình", "[Bảng 2 cột: Đặc điểm | Giá trị]", OPTIONAL);
        add("#amenitiesTable", "Bảng tiện ích & quyền sử dụng", "Bảng động", "DYNAMIC_TABLE", "Bảng chi tiết các tiện ích và quyền sử dụng đi kèm", "[Bảng 5 cột: STT | Tiện ích / Quyền sử dụng | Phạm vi | Chi phí | Điều kiện / Ghi chú]", OPTIONAL);
        add("#initialPaymentTable", "Bảng phụ lục thanh toán ban đầu", "Bảng động", "DYNAMIC_TABLE", "Bảng xác nhận các khoản tiền người thuê đã thanh toán trước thời điểm ký hợp đồng", "[Bảng 3 cột: Khoản thanh toán | Số tiền | Trạng thái / Ghi chú]", OPTIONAL);
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
     * Với mẫu chưa gắn loại hình (dữ liệu cũ), chỉ áp bộ trường bắt buộc chung cho cả 3 loại hình.
     */
    public List<TemplateFieldDefinition> getRequiredDefinitions(ListingCategory category) {
        return DEFINITIONS.values().stream()
                .filter(def -> isRequiredFor(def, category))
                .toList();
    }

    public boolean isRequiredFor(String rawKey, ListingCategory category) {
        String cleanKey = normalizeKey(rawKey);
        TemplateFieldDefinition def = DEFINITIONS.get(cleanKey);
        if (def == null) return false;
        return isRequiredFor(def, category);
    }

    private static boolean isRequiredFor(TemplateFieldDefinition def, ListingCategory category) {
        Set<ListingCategory> scope = def.getRequiredForCategories();
        if (scope == null || scope.isEmpty()) {
            return false;
        }
        return category == null ? scope.containsAll(SUPPORTED_CATEGORIES) : scope.contains(category);
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
