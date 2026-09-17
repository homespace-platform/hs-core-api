package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.estimate.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.DepositType;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import com.hs.listing.model.constant.ListingEnums.ParkingPolicy;
import com.hs.listing.model.constant.PriceUnit;
import com.hs.listing.model.constant.VehicleType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class RentalCostCalculator {

    private final ParkingReservationService parkingReservationService;

    private static final DecimalFormat VND_FORMAT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.of("vi", "VN"));
        symbols.setGroupingSeparator('.');
        VND_FORMAT = new DecimalFormat("#,###", symbols);
    }

    public static String formatVND(BigDecimal amount) {
        if (amount == null) return "0đ";
        return VND_FORMAT.format(amount) + "đ";
    }

    /**
     * Thực hiện tính toán và kiểm tra toàn bộ chi phí, giới hạn người ở và slot gửi xe.
     * Dùng chung cho cả API Estimate và Create Rental Request.
     */
    public RentalEstimateResponse calculate(
            Listing listing,
            LocalDate moveInDate,
            int leaseMonths,
            int occupantCount,
            int motorbikeCount,
            int carCount,
            BigDecimal negotiatedDepositAmount) {

        if (listing == null) {
            throw new AppException(ListingErrorCode.LISTING_NOT_FOUND);
        }

        if (leaseMonths <= 0) {
            throw new AppException(ListingErrorCode.RENTAL_ESTIMATE_INVALID, "Thời hạn thuê tối thiểu là 1 tháng.");
        }

        if (moveInDate == null) {
            throw new AppException(ListingErrorCode.RENTAL_ESTIMATE_INVALID, "Ngày bắt đầu thuê không được để trống.");
        }

        LocalDate endDateExclusive = moveInDate.plusMonths(leaseMonths);
        PropertyBranch branch = listing.getBranch();

        // 1. Kiểm tra giới hạn số người ở
        Integer occupantLimit = resolveOccupantLimit(listing);
        if (occupantLimit != null && occupantCount > occupantLimit) {
            throw new AppException(
                    ListingErrorCode.OCCUPANT_LIMIT_EXCEEDED,
                    String.format("Số người sẽ ở (%d người) vượt quá giới hạn tối đa của bài đăng (%d người).",
                            occupantCount, occupantLimit)
            );
        }

        // 2. Kiểm tra xe máy
        VehicleSlotEstimate motorbikeEstimate = evaluateVehicle(
                listing, branch, VehicleType.MOTORBIKE, motorbikeCount, moveInDate, endDateExclusive);

        // 3. Kiểm tra ô tô
        VehicleSlotEstimate carEstimate = evaluateVehicle(
                listing, branch, VehicleType.CAR, carCount, moveInDate, endDateExclusive);

        // 4. Giá thuê cơ bản (effectiveMonthlyRent)
        BigDecimal effectiveMonthlyRent = calculateEffectiveMonthlyRent(listing, occupantCount);
        boolean isPerM2Listing = isPerM2Unit(listing.getPriceUnit(), listing.getCategory());

        // 5. Danh sách các khoản phí có thể tính trước (predictableCharges) và chưa bao gồm (excludedCharges)
        List<PredictableChargeItem> predictableCharges = new ArrayList<>();
        List<ExcludedChargeItem> excludedCharges = new ArrayList<>();

        if (isPerM2Listing) {
            excludedCharges.add(ExcludedChargeItem.builder()
                    .chargeType("RENT")
                    .displayName("Giá thuê theo m²")
                    .billingMethod(listing.getPriceUnit() != null ? listing.getPriceUnit().name() : "PER_M2_MONTH")
                    .unitAmount(listing.getPriceAmount())
                    .reason(String.format("Giá thuê %s/m² — tính theo diện tích thực tế, cần xác nhận lại với chủ nhà.",
                            formatVND(listing.getPriceAmount())))
                    .build());
        }

        processListingCharges(
                listing, occupantCount, motorbikeCount, carCount,
                motorbikeEstimate, carEstimate, predictableCharges, excludedCharges);

        BigDecimal predictableMonthlyChargesTotal = predictableCharges.stream()
                .map(PredictableChargeItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 6. Tổng dự kiến mỗi tháng
        BigDecimal estimatedMonthlyTotal = effectiveMonthlyRent.add(predictableMonthlyChargesTotal);

        // 7. Tiền cọc
        BigDecimal depositAmount = calculateDeposit(listing, effectiveMonthlyRent, negotiatedDepositAmount);

        // 8. Tổng dự kiến ban đầu và toàn thời hạn thuê
        BigDecimal estimatedInitialTotal = estimatedMonthlyTotal.add(depositAmount);
        BigDecimal estimatedLeaseTotal = estimatedMonthlyTotal.multiply(BigDecimal.valueOf(leaseMonths)).add(depositAmount);

        String disclaimer = "Ước tính chỉ bao gồm các khoản chi phí cố định có thể dự kiến trước. " +
                "Chi phí điện, nước theo đồng hồ và các chi phí phát sinh thực tế chưa được bao gồm.";

        return RentalEstimateResponse.builder()
                .listingId(listing.getId())
                .occupantCount(occupantCount)
                .occupantLimit(occupantLimit)
                .motorbike(motorbikeEstimate)
                .car(carEstimate)
                .effectiveMonthlyRent(effectiveMonthlyRent)
                .predictableCharges(predictableCharges)
                .predictableMonthlyChargesTotal(predictableMonthlyChargesTotal)
                .estimatedMonthlyTotal(estimatedMonthlyTotal)
                .depositAmount(depositAmount)
                .estimatedInitialTotal(estimatedInitialTotal)
                .estimatedLeaseTotal(estimatedLeaseTotal)
                .excludedCharges(excludedCharges)
                .disclaimer(disclaimer)
                .build();
    }

    private Integer resolveOccupantLimit(Listing listing) {
        if (listing == null) return null;
        if (listing.getCategory() == ListingCategory.APARTMENT && listing.getApartmentDetail() != null) {
            return listing.getApartmentDetail().getMaxOccupants();
        }
        if (listing.getCategory() == ListingCategory.HOUSE && listing.getHouseDetail() != null) {
            return listing.getHouseDetail().getMaxOccupants();
        }
        if (listing.getCategory() == ListingCategory.ROOM && listing.getRoomDetail() != null) {
            return listing.getRoomDetail().getMaxOccupants();
        }
        return null;
    }

    private boolean isPerM2Unit(PriceUnit unit, ListingCategory category) {
        if (category != ListingCategory.HOUSE && category != ListingCategory.APARTMENT && category != ListingCategory.ROOM) {
            return false;
        }
        return unit != null && (unit.name().contains("M2") || unit.name().contains("PER_M2"));
    }

    private BigDecimal calculateEffectiveMonthlyRent(Listing listing, int occupantCount) {
        if (listing.getPriceAmount() == null) {
            return BigDecimal.ZERO;
        }
        if (isPerM2Unit(listing.getPriceUnit(), listing.getCategory())) {
            return BigDecimal.ZERO;
        }
        if (listing.getPriceUnit() == PriceUnit.PERSON_MONTH) {
            return listing.getPriceAmount().multiply(BigDecimal.valueOf(occupantCount));
        }
        return listing.getPriceAmount();
    }

    private VehicleSlotEstimate evaluateVehicle(
            Listing listing,
            PropertyBranch branch,
            VehicleType vehicleType,
            int requestedCount,
            LocalDate startDate,
            LocalDate endDateExclusive) {

        boolean isMotorbike = (vehicleType == VehicleType.MOTORBIKE);
        String vehicleName = isMotorbike ? "xe máy" : "ô tô";

        // 1. Kiểm tra chính sách theo phòng (ROOM)
        if (listing.getCategory() == ListingCategory.ROOM && listing.getRoomDetail() != null) {
            ParkingPolicy policy = listing.getRoomDetail().getParkingPolicy();
            if (policy == ParkingPolicy.NONE) {
                if (requestedCount > 0) {
                    throw isMotorbike
                            ? new AppException(ListingErrorCode.MOTORBIKE_PARKING_NOT_ALLOWED, "Phòng trọ này không nhận gửi xe máy.")
                            : new AppException(ListingErrorCode.CAR_PARKING_NOT_ALLOWED, "Phòng trọ này không nhận gửi xe ô tô.");
                }
                return VehicleSlotEstimate.builder()
                        .allowed(false)
                        .requested(requestedCount)
                        .capacity(0)
                        .reserved(0)
                        .available(0)
                        .monthlyAmount(BigDecimal.ZERO)
                        .note("Không hỗ trợ chỗ gửi " + vehicleName)
                        .build();
            }
        }

        // 2. Tìm ListingCharge liên quan
        ChargeType targetChargeType = isMotorbike ? ChargeType.MOTORBIKE_PARKING : ChargeType.CAR_PARKING;
        ListingCharge vehicleCharge = findCharge(listing, targetChargeType);

        if (vehicleCharge != null && vehicleCharge.getBillingMethod() == BillingMethod.NOT_APPLICABLE) {
            if (requestedCount > 0) {
                throw isMotorbike
                        ? new AppException(ListingErrorCode.MOTORBIKE_PARKING_NOT_ALLOWED, "Bài đăng này không hỗ trợ gửi xe máy.")
                        : new AppException(ListingErrorCode.CAR_PARKING_NOT_ALLOWED, "Bài đăng này không hỗ trợ gửi xe ô tô.");
            }
            return VehicleSlotEstimate.builder()
                    .allowed(false)
                    .requested(requestedCount)
                    .capacity(0)
                    .reserved(0)
                    .available(0)
                    .monthlyAmount(BigDecimal.ZERO)
                    .note("Bên thuê tự gửi " + vehicleName + " bên ngoài")
                    .build();
        }

        // 3. Tính toán availability
        VehicleAvailabilityInfo avail = parkingReservationService.getAvailability(
                branch, listing, vehicleType, startDate, endDateExclusive);

        if (avail.capacity() <= 0) {
            if (requestedCount > 0) {
                throw isMotorbike
                        ? new AppException(ListingErrorCode.MOTORBIKE_PARKING_NOT_ALLOWED, "Bài đăng này không có chỗ gửi xe máy.")
                        : new AppException(ListingErrorCode.CAR_PARKING_NOT_ALLOWED, "Bài đăng này không có chỗ gửi xe ô tô.");
            }
            return VehicleSlotEstimate.builder()
                    .allowed(false)
                    .requested(requestedCount)
                    .capacity(0)
                    .reserved(0)
                    .available(0)
                    .monthlyAmount(BigDecimal.ZERO)
                    .note("Không có sức chứa gửi " + vehicleName)
                    .build();
        }

        // 4. Kiểm tra số lượng yêu cầu so với slot khả dụng
        if (requestedCount > avail.available()) {
            throw isMotorbike
                    ? new AppException(
                            ListingErrorCode.MOTORBIKE_CAPACITY_EXCEEDED,
                            String.format("Chi nhánh/bài đăng chỉ còn %d chỗ xe máy trong thời gian thuê đã chọn, nhưng yêu cầu cần %d chỗ.",
                                    avail.available(), requestedCount))
                    : new AppException(
                            ListingErrorCode.CAR_CAPACITY_EXCEEDED,
                            String.format("Chi nhánh/bài đăng chỉ còn %d chỗ ô tô trong thời gian thuê đã chọn, nhưng yêu cầu cần %d chỗ.",
                                    avail.available(), requestedCount));
        }

        // 5. Tính chi phí gửi xe
        BigDecimal monthlyAmount = BigDecimal.ZERO;
        String note = "Còn " + avail.available() + " chỗ trống";

        if (vehicleCharge != null) {
            if (vehicleCharge.isIncludedInRent() || vehicleCharge.getBillingMethod() == BillingMethod.INCLUDED) {
                note = "Đã bao gồm trong giá thuê (Còn " + avail.available() + " chỗ)";
            } else if (vehicleCharge.getBillingMethod() == BillingMethod.FREE) {
                note = "Miễn phí gửi xe (Còn " + avail.available() + " chỗ)";
            } else if (vehicleCharge.getBillingMethod() == BillingMethod.PER_VEHICLE_MONTH && vehicleCharge.getAmount() != null) {
                monthlyAmount = vehicleCharge.getAmount().multiply(BigDecimal.valueOf(requestedCount));
                note = formatVND(vehicleCharge.getAmount()) + "/xe/tháng (Còn " + avail.available() + " chỗ)";
            }
        }

        return VehicleSlotEstimate.builder()
                .allowed(true)
                .requested(requestedCount)
                .capacity(avail.capacity())
                .reserved(avail.reserved())
                .available(avail.available())
                .monthlyAmount(monthlyAmount)
                .note(note)
                .build();
    }

    private ListingCharge findCharge(Listing listing, ChargeType chargeType) {
        if (listing.getCharges() == null) return null;
        return listing.getCharges().stream()
                .filter(c -> c.getChargeType() == chargeType)
                .findFirst()
                .orElse(null);
    }

    private void processListingCharges(
            Listing listing,
            int occupantCount,
            int motorbikeCount,
            int carCount,
            VehicleSlotEstimate motorbikeEstimate,
            VehicleSlotEstimate carEstimate,
            List<PredictableChargeItem> predictable,
            List<ExcludedChargeItem> excluded) {

        if (listing.getCharges() == null || listing.getCharges().isEmpty()) {
            return;
        }

        List<ListingCharge> sorted = new ArrayList<>(listing.getCharges());
        sorted.sort(Comparator.comparing(c -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()));

        for (ListingCharge charge : sorted) {
            ChargeType type = charge.getChargeType();
            BillingMethod method = charge.getBillingMethod();
            String displayName = chargeDisplayName(charge);

            // Xử lý riêng cho xe máy
            if (type == ChargeType.MOTORBIKE_PARKING) {
                if (!motorbikeEstimate.allowed()) {
                    excluded.add(ExcludedChargeItem.builder()
                            .chargeType(type.name())
                            .displayName(displayName)
                            .billingMethod(method != null ? method.name() : "NOT_APPLICABLE")
                            .unitAmount(charge.getAmount())
                            .reason("Không hỗ trợ chỗ gửi xe máy tại chỗ")
                            .build());
                } else {
                    predictable.add(PredictableChargeItem.builder()
                            .chargeType(type.name())
                            .displayName(displayName)
                            .billingMethod(method != null ? method.name() : "PER_VEHICLE_MONTH")
                            .unitAmount(charge.getAmount() != null ? charge.getAmount() : BigDecimal.ZERO)
                            .quantity(motorbikeCount)
                            .amount(motorbikeEstimate.monthlyAmount())
                            .includedInRent(charge.isIncludedInRent() || method == BillingMethod.INCLUDED)
                            .note(charge.isIncludedInRent() || method == BillingMethod.INCLUDED
                                    ? "Đã bao gồm"
                                    : method == BillingMethod.FREE ? "Miễn phí" : formatVND(charge.getAmount()) + "/xe × " + motorbikeCount + " xe")
                            .build());
                }
                continue;
            }

            // Xử lý riêng cho ô tô
            if (type == ChargeType.CAR_PARKING) {
                if (!carEstimate.allowed()) {
                    excluded.add(ExcludedChargeItem.builder()
                            .chargeType(type.name())
                            .displayName(displayName)
                            .billingMethod(method != null ? method.name() : "NOT_APPLICABLE")
                            .unitAmount(charge.getAmount())
                            .reason("Không hỗ trợ chỗ gửi xe ô tô tại chỗ")
                            .build());
                } else {
                    predictable.add(PredictableChargeItem.builder()
                            .chargeType(type.name())
                            .displayName(displayName)
                            .billingMethod(method != null ? method.name() : "PER_VEHICLE_MONTH")
                            .unitAmount(charge.getAmount() != null ? charge.getAmount() : BigDecimal.ZERO)
                            .quantity(carCount)
                            .amount(carEstimate.monthlyAmount())
                            .includedInRent(charge.isIncludedInRent() || method == BillingMethod.INCLUDED)
                            .note(charge.isIncludedInRent() || method == BillingMethod.INCLUDED
                                    ? "Đã bao gồm"
                                    : method == BillingMethod.FREE ? "Miễn phí" : formatVND(charge.getAmount()) + "/xe × " + carCount + " xe")
                            .build());
                }
                continue;
            }

            // Các khoản ĐÃ BAO GỒM HOẶC MIỄN PHÍ
            if (charge.isIncludedInRent() || method == BillingMethod.INCLUDED) {
                predictable.add(PredictableChargeItem.builder()
                        .chargeType(type != null ? type.name() : "OTHER")
                        .displayName(displayName)
                        .billingMethod(BillingMethod.INCLUDED.name())
                        .unitAmount(BigDecimal.ZERO)
                        .quantity(1)
                        .amount(BigDecimal.ZERO)
                        .includedInRent(true)
                        .note("Đã bao gồm trong giá thuê")
                        .build());
                continue;
            }

            if (method == BillingMethod.FREE) {
                predictable.add(PredictableChargeItem.builder()
                        .chargeType(type != null ? type.name() : "OTHER")
                        .displayName(displayName)
                        .billingMethod(BillingMethod.FREE.name())
                        .unitAmount(BigDecimal.ZERO)
                        .quantity(1)
                        .amount(BigDecimal.ZERO)
                        .includedInRent(false)
                        .note("Miễn phí")
                        .build());
                continue;
            }

            // Các khoản tính theo tháng cố định
            if (method == BillingMethod.PER_MONTH) {
                BigDecimal amount = charge.getAmount() != null ? charge.getAmount() : BigDecimal.ZERO;
                predictable.add(PredictableChargeItem.builder()
                        .chargeType(type != null ? type.name() : "OTHER")
                        .displayName(displayName)
                        .billingMethod(BillingMethod.PER_MONTH.name())
                        .unitAmount(amount)
                        .quantity(1)
                        .amount(amount)
                        .includedInRent(false)
                        .note(formatVND(amount) + "/tháng")
                        .build());
                continue;
            }

            // Tính theo đầu người mỗi tháng (ví dụ: nước theo người)
            if (method == BillingMethod.PER_PERSON_MONTH) {
                BigDecimal unit = charge.getAmount() != null ? charge.getAmount() : BigDecimal.ZERO;
                BigDecimal total = unit.multiply(BigDecimal.valueOf(occupantCount));
                predictable.add(PredictableChargeItem.builder()
                        .chargeType(type != null ? type.name() : "OTHER")
                        .displayName(displayName)
                        .billingMethod(BillingMethod.PER_PERSON_MONTH.name())
                        .unitAmount(unit)
                        .quantity(occupantCount)
                        .amount(total)
                        .includedInRent(false)
                        .note(formatVND(unit) + "/người × " + occupantCount + " người")
                        .build());
                continue;
            }

            // Các khoản không thể tính trước -> Excluded
            String reason = buildExcludedReason(charge);
            excluded.add(ExcludedChargeItem.builder()
                    .chargeType(type != null ? type.name() : "OTHER")
                    .displayName(displayName)
                    .billingMethod(method != null ? method.name() : "OTHER")
                    .unitAmount(charge.getAmount())
                    .reason(reason)
                    .build());
        }
    }

    private String chargeDisplayName(ListingCharge charge) {
        if (charge.getCustomName() != null && !charge.getCustomName().isBlank()) {
            return charge.getCustomName().trim();
        }
        ChargeType type = charge.getChargeType();
        if (type == null) return "Phí dịch vụ";
        return switch (type) {
            case ELECTRICITY -> "Tiền điện";
            case WATER -> "Tiền nước";
            case MANAGEMENT -> "Phí quản lý";
            case INTERNET -> "Internet / WiFi";
            case SERVICE_OR_GARBAGE -> "Phí dịch vụ & rác";
            case MOTORBIKE_PARKING -> "Phí gửi xe máy";
            case CAR_PARKING -> "Phí gửi ô tô";
            case OVERTIME_AIR_CONDITIONING -> "Điều hòa ngoài giờ";
            case OTHER -> "Phí khác";
            default -> "Phí khác";
        };
    }

    private String buildExcludedReason(ListingCharge charge) {
        BillingMethod method = charge.getBillingMethod();
        BigDecimal amount = charge.getAmount();

        if (method == null) {
            return "Khoản phí này cần xác nhận với chủ nhà, chưa bao gồm.";
        }

        return switch (method) {
            case PER_KWH -> String.format("Tiền điện %s/kWh — tính theo chỉ số thực tế, chưa bao gồm.",
                    amount != null ? formatVND(amount) : "theo đơn giá");
            case PER_M3 -> String.format("Tiền nước %s/m³ — tính theo đồng hồ thực tế, chưa bao gồm.",
                    amount != null ? formatVND(amount) : "theo đơn giá");
            case STATE_WATER_RATE -> "Tiền nước theo giá nhà nước — tính theo đồng hồ thực tế, chưa bao gồm.";
            case PER_HOUR -> String.format("Phí tính theo giờ sử dụng %s/giờ — chưa bao gồm.",
                    amount != null ? formatVND(amount) : "");
            case NEGOTIABLE -> "Khoản phí theo thỏa thuận giữa hai bên — chưa bao gồm.";
            case CUSTOM -> "Phí tùy chỉnh không xác định được công thức cố định — chưa bao gồm.";
            case NOT_APPLICABLE -> "Người thuê tự đăng ký và chi trả với nhà cung cấp dịch vụ.";
            case PER_M2_MONTH -> "Phí tính theo diện tích m² — cần xác nhận với chủ nhà, chưa bao gồm.";
            default -> "Khoản phí phát sinh thực tế, chưa bao gồm.";
        };
    }

    private BigDecimal calculateDeposit(Listing listing, BigDecimal effectiveMonthlyRent, BigDecimal negotiatedDepositAmount) {
        DepositType type = listing.getDepositType();
        if (type == null || type == DepositType.NONE) {
            return BigDecimal.ZERO;
        }

        return switch (type) {
            case FIXED_AMOUNT -> listing.getDepositAmount() != null ? listing.getDepositAmount() : BigDecimal.ZERO;
            case MONTH_COUNT -> {
                int months = (listing.getDepositMonths() != null && listing.getDepositMonths() > 0)
                        ? listing.getDepositMonths()
                        : 1;
                yield effectiveMonthlyRent.multiply(BigDecimal.valueOf(months));
            }
            case NEGOTIABLE -> {
                if (negotiatedDepositAmount != null) {
                    if (negotiatedDepositAmount.compareTo(BigDecimal.ZERO) < 0) {
                        throw new AppException(ListingErrorCode.RENTAL_ESTIMATE_INVALID, "Tiền đặt cọc đề xuất không được âm.");
                    }
                    yield negotiatedDepositAmount;
                }
                yield BigDecimal.ZERO;
            }
            default -> BigDecimal.ZERO;
        };
    }
}
