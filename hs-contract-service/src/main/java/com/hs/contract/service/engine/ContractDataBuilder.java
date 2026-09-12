package com.hs.contract.service.engine;

import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.ListingFurnishingAsset;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.HandoverCondition;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import com.hs.listing.model.constant.PaymentCycle;
import com.hs.listing.model.constant.RentalMode;
import com.hs.user.model.Address;
import com.hs.user.model.User;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gom dữ liệu thật từ hồ sơ hai bên, tin đăng và yêu cầu thuê thành các snapshot của hợp đồng.
 *
 * <p>Bản chụp được lưu vào {@code ContractRevision} nên sau này chủ nhà có sửa hồ sơ cá nhân hay
 * tin đăng thì nội dung hợp đồng đã tạo vẫn giữ nguyên.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractDataBuilder {

    private final UserRepository userRepository;
    private final AddressRepository addressRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Tập hợp 8 snapshot của một revision hợp đồng. */
    @Getter
    @Builder
    public static class ContractSnapshots {
        private Map<String, Object> landlord;
        private Map<String, Object> tenant;
        private Map<String, Object> property;
        private Map<String, Object> lease;
        private Map<String, Object> financial;
        private List<Map<String, Object>> charges;
        private List<Map<String, Object>> equipments;
        private Map<String, Object> meters;
    }

    public ContractSnapshots build(RentalRequest request, Listing listing) {
        int occupants = resolveOccupantCount(request);
        return ContractSnapshots.builder()
                .landlord(buildLandlord(request.getOwnerId()))
                .tenant(buildTenant(request))
                .property(buildProperty(listing))
                .lease(buildLease(request, listing))
                .financial(buildFinancial(request, listing))
                .charges(buildCharges(listing, occupants))
                .equipments(buildEquipments(listing))
                .meters(buildMeters(listing, occupants))
                .build();
    }

    // --- Bên A / Bên B ---

    private Map<String, Object> buildLandlord(String landlordId) {
        Map<String, Object> map = new LinkedHashMap<>();
        User user = findUser(landlordId);

        map.put("fullName", fullNameOf(user, "Chủ nhà (Bên A)"));
        map.put("idNumber", nullToEmpty(user == null ? null : user.getCccd()));
        map.put("permanentAddress", permanentAddressOf(landlordId));
        map.put("phone", nullToEmpty(user == null ? null : user.getPhone()));
        map.put("email", nullToEmpty(user == null ? null : user.getEmail()));
        return map;
    }

    private Map<String, Object> buildTenant(RentalRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        User user = findUser(request.getRenterId());

        // Tên/điện thoại/email do người thuê tự khai trong yêu cầu thuê được ưu tiên,
        // vì đó là thông tin họ chủ động dùng để ký hợp đồng này.
        map.put("fullName", firstNonBlank(request.getRenterName(), fullNameOf(user, "Người thuê (Bên B)")));
        map.put("idNumber", nullToEmpty(user == null ? null : user.getCccd()));
        map.put("permanentAddress", permanentAddressOf(request.getRenterId()));
        map.put("phone", firstNonBlank(request.getRenterPhone(), user == null ? null : user.getPhone()));
        map.put("email", firstNonBlank(request.getRenterEmail(), user == null ? null : user.getEmail()));
        map.put("occupantCount", resolveOccupantCount(request));

        // Chỉ có ý nghĩa với hợp đồng thuê văn phòng / mặt bằng, chủ nhà tự điền sau.
        map.put("organizationName", "");
        map.put("representativeName", "");
        map.put("representativePosition", "");
        return map;
    }

    // --- Bất động sản ---

    private Map<String, Object> buildProperty(Listing listing) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (listing == null) {
            return map;
        }

        String unitNumber = resolveUnitNumber(listing);
        String floor = resolveFloor(listing);
        String baseAddress = addressRepository.findByListingIdAndActiveTrue(listing.getId())
                .map(Address::getFullAddress)
                .orElse("");

        map.put("fullAddress", joinNonBlank(", ", unitNumber, floor, resolveBuildingName(listing), baseAddress));
        map.put("areaText", listing.getAreaM2() != null ? stripTrailingZeros(listing.getAreaM2()) + " m²" : "");
        map.put("propertyType", categoryLabel(listing.getCategory()));
        map.put("unitNumber", unitNumber);
        map.put("floor", floor);
        return map;
    }

    /** Mã căn hộ / số phòng nằm ở bảng chi tiết riêng theo từng loại hình. */
    private static String resolveUnitNumber(Listing listing) {
        if (listing.getApartmentDetail() != null && isNotBlank(listing.getApartmentDetail().getUnitCode())) {
            return "Căn hộ " + listing.getApartmentDetail().getUnitCode().trim();
        }
        if (listing.getRoomDetail() != null && isNotBlank(listing.getRoomDetail().getRoomCode())) {
            return "Phòng " + listing.getRoomDetail().getRoomCode().trim();
        }
        return "";
    }

    private static String resolveFloor(Listing listing) {
        Integer floor = null;
        if (listing.getApartmentDetail() != null) {
            floor = listing.getApartmentDetail().getFloorNumber();
        } else if (listing.getRoomDetail() != null) {
            floor = listing.getRoomDetail().getFloorNumber();
        } else if (listing.getOfficeDetail() != null) {
            floor = listing.getOfficeDetail().getFloorNumber();
        } else if (listing.getHouseDetail() != null && listing.getHouseDetail().getTotalFloors() != null) {
            return "Nhà " + listing.getHouseDetail().getTotalFloors() + " tầng";
        }
        return floor != null ? "Tầng " + floor : "";
    }

    private static String resolveBuildingName(Listing listing) {
        if (listing.getApartmentDetail() != null) {
            return joinNonBlank(" ",
                    nullToEmpty(listing.getApartmentDetail().getProjectName()),
                    nullToEmpty(listing.getApartmentDetail().getBuildingBlock()));
        }
        if (listing.getOfficeDetail() != null) {
            return nullToEmpty(listing.getOfficeDetail().getBuildingName());
        }
        return "";
    }

    // --- Thời hạn thuê ---

    private Map<String, Object> buildLease(RentalRequest request, Listing listing) {
        Map<String, Object> map = new LinkedHashMap<>();
        LocalDate start = request.getMoveInDate() != null ? request.getMoveInDate() : LocalDate.now();
        int months = request.getLeaseMonths() != null ? request.getLeaseMonths() : 12;
        // Hợp đồng hết hiệu lực vào ngày liền trước mốc tròn kỳ hạn.
        LocalDate end = start.plusMonths(months).minusDays(1);

        map.put("rentalMode", "");
        map.put("startDateText", start.format(DATE_FORMATTER));
        map.put("endDateText", end.format(DATE_FORMATTER));
        map.put("durationMonths", months);
        map.put("durationText", formatDurationText(months));
        map.put("handoverDateText", start.format(DATE_FORMATTER));
        return map;
    }

    // --- Giá thuê & cọc ---

    private Map<String, Object> buildFinancial(RentalRequest request, Listing listing) {
        Map<String, Object> map = new LinkedHashMap<>();
        BigDecimal rent = request.getMonthlyRentPrice() != null ? request.getMonthlyRentPrice() : BigDecimal.ZERO;
        BigDecimal deposit = request.getDepositAmount() != null ? request.getDepositAmount() : rent;

        map.put("amountValue", rent.toPlainString());
        map.put("amountNumber", ContractRenderService.formatVND(rent) + "/tháng");
        map.put("amountWords", VietnameseCurrencyTextConverter.toWords(rent));
        map.put("paymentCycle", paymentCycleLabel(listing == null ? null : listing.getPaymentCycle()));
        map.put("paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng");
        map.put("paymentMethod", "Thanh toán trực tuyến qua hệ thống HomeSpace");
        map.put("depositAmountValue", deposit.toPlainString());
        map.put("depositAmountNumber", ContractRenderService.formatVND(deposit));
        map.put("depositAmountWords", VietnameseCurrencyTextConverter.toWords(deposit));
        map.put("depositDescription", "Tiền đặt cọc được Bên A hoàn trả cho Bên B sau khi hết hạn hợp đồng, "
                + "sau khi đã khấu trừ các khoản chi phí phát sinh chưa thanh toán (nếu có).");
        return map;
    }

    // --- Bảng phí dịch vụ ---

    /**
     * Mỗi dòng gồm 3 cột hiển thị trong Word, kèm {@code estimatedMonthlyAmount} phục vụ bước
     * tính tiền thanh toán đợt đầu. Phí theo công tơ (điện kWh, nước m³) không ước tính trước
     * được nên để {@code null} và sẽ chốt vào hóa đơn cuối tháng.
     */
    private List<Map<String, Object>> buildCharges(Listing listing, int occupants) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (listing == null || listing.getCharges() == null || listing.getCharges().isEmpty()) {
            return rows;
        }

        List<ListingCharge> sorted = new ArrayList<>(listing.getCharges());
        sorted.sort(Comparator.comparing(c -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()));

        for (ListingCharge charge : sorted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", chargeName(charge));
            row.put("amountAndMethod", chargeAmountAndMethod(charge, occupants, listing.getAreaM2()));
            row.put("note", firstNonBlank(charge.getDescription(), "-"));

            BigDecimal estimated = estimateMonthlyAmount(charge, occupants, listing.getAreaM2());
            row.put("estimatedMonthlyAmount", estimated == null ? null : estimated.toPlainString());
            row.put("chargeType", charge.getChargeType() == null ? null : charge.getChargeType().name());
            row.put("billingMethod", charge.getBillingMethod() == null ? null : charge.getBillingMethod().name());
            rows.add(row);
        }
        return rows;
    }

    private static String chargeName(ListingCharge charge) {
        if (charge.getChargeType() == ChargeType.OTHER) {
            return firstNonBlank(charge.getCustomName(), "Phí dịch vụ khác");
        }
        return firstNonBlank(charge.getCustomName(), chargeTypeLabel(charge.getChargeType()));
    }

    private static String chargeTypeLabel(ChargeType type) {
        if (type == null) return "Phí dịch vụ";
        return switch (type) {
            case ELECTRICITY -> "Tiền điện";
            case WATER -> "Tiền nước";
            case MANAGEMENT -> "Phí quản lý";
            case INTERNET -> "Internet / WiFi";
            case SERVICE_OR_GARBAGE -> "Phí dịch vụ, vệ sinh & rác";
            case MOTORBIKE_PARKING -> "Phí gửi xe máy";
            case CAR_PARKING -> "Phí gửi xe ô tô";
            case OVERTIME_AIR_CONDITIONING -> "Điều hòa ngoài giờ";
            case OTHER -> "Phí dịch vụ khác";
        };
    }

    private static String chargeAmountAndMethod(ListingCharge charge, int occupants, BigDecimal areaM2) {
        if (charge.isIncludedInRent() || charge.getBillingMethod() == BillingMethod.INCLUDED) {
            return "Đã bao gồm trong giá thuê";
        }

        BillingMethod method = charge.getBillingMethod();
        BigDecimal amount = charge.getAmount();
        String price = amount != null ? ContractRenderService.formatVND(amount) : null;

        if (method == null) {
            return price != null ? price : "Theo thỏa thuận";
        }

        return switch (method) {
            case FREE -> "Miễn phí";
            case NOT_APPLICABLE -> "Bên thuê tự đăng ký với đơn vị cung cấp";
            case NEGOTIABLE -> "Theo thỏa thuận giữa hai bên";
            case STATE_WATER_RATE -> "Theo đơn giá nhà nước";
            case PER_KWH -> price == null ? "Theo chỉ số công tơ" : price + " / kWh (theo chỉ số công tơ)";
            case PER_M3 -> price == null ? "Theo chỉ số đồng hồ" : price + " / m³ (theo chỉ số đồng hồ)";
            case PER_HOUR -> price == null ? "Theo giờ sử dụng" : price + " / giờ";
            case PER_VEHICLE_MONTH -> price == null ? "Theo số xe đăng ký" : price + " / xe / tháng";
            case PER_MONTH -> price == null ? "Theo tháng" : price + " / tháng";
            case PER_PERSON_MONTH -> {
                if (amount == null) yield "Theo số người ở";
                BigDecimal total = amount.multiply(BigDecimal.valueOf(occupants));
                yield price + " / người / tháng × " + occupants + " người = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            case PER_M2_MONTH -> {
                if (amount == null) yield "Theo diện tích";
                if (areaM2 == null) yield price + " / m² / tháng";
                BigDecimal total = amount.multiply(areaM2);
                yield price + " / m² / tháng × " + stripTrailingZeros(areaM2) + " m² = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            case CUSTOM, INCLUDED -> price != null
                    ? price + (isNotBlank(charge.getUnit()) ? " / " + charge.getUnit() : "")
                    : "Theo thỏa thuận";
        };
    }

    /**
     * Số tiền cố định ước tính được mỗi tháng. Trả về {@code null} khi khoản phí đo bằng công tơ
     * hoặc do bên thuê tự chi trả, vì không thể chốt trước khi vào ở.
     */
    public static BigDecimal estimateMonthlyAmount(ListingCharge charge, int occupants, BigDecimal areaM2) {
        if (charge.isIncludedInRent() || charge.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        BillingMethod method = charge.getBillingMethod();
        if (method == null) {
            return null;
        }
        return switch (method) {
            case FREE, INCLUDED -> BigDecimal.ZERO;
            case PER_MONTH -> charge.getAmount();
            case PER_PERSON_MONTH -> charge.getAmount().multiply(BigDecimal.valueOf(occupants));
            case PER_M2_MONTH -> areaM2 == null ? null : charge.getAmount().multiply(areaM2);
            // Điện/nước theo công tơ, phí theo giờ, theo xe, thỏa thuận: chốt ở hóa đơn cuối tháng.
            case PER_KWH, PER_M3, STATE_WATER_RATE, PER_HOUR, PER_VEHICLE_MONTH,
                 NOT_APPLICABLE, NEGOTIABLE, CUSTOM -> null;
        };
    }

    // --- Biên bản bàn giao trang thiết bị ---

    private List<Map<String, Object>> buildEquipments(Listing listing) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (listing == null || listing.getFurnishings() == null || listing.getFurnishings().isEmpty()) {
            return rows;
        }

        List<ListingFurnishingAsset> sorted = new ArrayList<>(listing.getFurnishings());
        sorted.sort(Comparator.comparing(a -> a.getSortOrder() == null ? Integer.MAX_VALUE : a.getSortOrder()));

        int index = 1;
        for (ListingFurnishingAsset asset : sorted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            row.put("name", nullToEmpty(asset.getAssetName()));
            row.put("quantity", asset.getQuantity() != null ? asset.getQuantity() : 1);
            row.put("condition", conditionText(asset));
            rows.add(row);
        }
        return rows;
    }

    private static String conditionText(ListingFurnishingAsset asset) {
        HandoverCondition condition = asset.getHandoverCondition();
        String label = condition != null ? condition.label() : "Bình thường";
        return isNotBlank(asset.getConditionNote()) ? label + " — " + asset.getConditionNote().trim() : label;
    }

    // --- Chỉ số công tơ bàn giao ---

    /** Chủ nhà nhập chỉ số thực tế lúc bàn giao; nước theo đầu người không dùng đồng hồ. */
    private Map<String, Object> buildMeters(Listing listing, int occupants) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("electricityInitial", "");
        map.put("waterInitial", waterMeterNotApplicableText(listing, occupants));
        return map;
    }

    private static String waterMeterNotApplicableText(Listing listing, int occupants) {
        if (listing == null || listing.getCharges() == null) {
            return "";
        }

        return listing.getCharges().stream()
                .filter(charge -> charge.getChargeType() == ChargeType.WATER)
                .filter(charge -> charge.getBillingMethod() == BillingMethod.PER_PERSON_MONTH)
                .findFirst()
                .map(charge -> {
                    if (charge.getAmount() == null) {
                        return "Không áp dụng - nước tính theo " + occupants + " người";
                    }
                    BigDecimal total = charge.getAmount().multiply(BigDecimal.valueOf(occupants));
                    return "Không áp dụng - nước tính theo " + occupants + " người ("
                            + ContractRenderService.formatVND(total) + "/tháng)";
                })
                .orElse("");
    }

    // --- Helper ---

    private User findUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private String permanentAddressOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        return addressRepository.findByUser_IdAndActiveTrue(userId)
                .map(Address::getFullAddress)
                .map(ContractDataBuilder::nullToEmpty)
                .orElse("");
    }

    private static String fullNameOf(User user, String fallback) {
        if (user == null) {
            return fallback;
        }
        String name = joinNonBlank(" ", nullToEmpty(user.getFirstName()), nullToEmpty(user.getLastName()));
        return isNotBlank(name) ? name : fallback;
    }

    private static int resolveOccupantCount(RentalRequest request) {
        Integer count = request.getOccupantCount();
        return count != null && count > 0 ? count : 1;
    }

    static String categoryLabel(ListingCategory category) {
        if (category == null) return "";
        return switch (category) {
            case APARTMENT -> "Căn hộ / Chung cư";
            case HOUSE -> "Nhà nguyên căn";
            case OFFICE -> "Văn phòng";
            case COMMERCIAL_SPACE -> "Mặt bằng kinh doanh";
            case ROOM -> "Nhà trọ / Căn hộ dịch vụ";
        };
    }

    static String rentalModeLabel(RentalMode mode) {
        if (mode == null) return "";
        return switch (mode) {
            case WHOLE_UNIT -> "Thuê nguyên căn / toàn bộ";
            case PARTIAL -> "Thuê một phần / phòng riêng";
        };
    }

    private static String paymentCycleLabel(PaymentCycle cycle) {
        if (cycle == null) return "Hàng tháng";
        return switch (cycle) {
            case MONTHLY -> "Hàng tháng";
            case EVERY_2_MONTHS -> "Định kỳ 2 tháng một lần";
            case QUARTERLY -> "Hàng quý (3 tháng một lần)";
            case EVERY_6_MONTHS -> "Định kỳ 6 tháng một lần";
            case NEGOTIABLE -> "Theo thỏa thuận giữa hai bên";
        };
    }

    static String formatDurationText(int months) {
        if (months < 12) return months + " tháng";
        int years = months / 12;
        int rest = months % 12;
        if (rest == 0) return years + " năm (" + months + " tháng)";
        return years + " năm " + rest + " tháng (" + months + " tháng)";
    }

    private static String stripTrailingZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String joinNonBlank(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (isNotBlank(part)) {
                kept.add(part.trim());
            }
        }
        return String.join(separator, kept);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
