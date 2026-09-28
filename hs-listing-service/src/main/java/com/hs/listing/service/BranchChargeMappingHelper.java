package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.CreateBranchChargeRequest;
import com.hs.listing.model.BranchCharge;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public final class BranchChargeMappingHelper {

    private BranchChargeMappingHelper() {}

    /**
     * Chuyển đổi 1 BranchCharge sang ListingCharge gắn với tin đăng.
     */
    public static ListingCharge toListingCharge(BranchCharge bc, Listing listing) {
        if (bc == null) return null;
        return ListingCharge.builder()
                .id(UUID.randomUUID().toString())
                .listing(listing)
                .chargeType(bc.getChargeType())
                .billingMethod(bc.getBillingMethod())
                .amount(bc.getAmount())
                .currency(bc.getCurrency() != null ? bc.getCurrency() : "VND")
                .unit(bc.getUnit())
                .includedInRent(bc.isIncludedInRent())
                .customName(bc.getCustomName())
                .description(bc.getDescription())
                .sortOrder(bc.getSortOrder() != null ? bc.getSortOrder() : 0)
                .build();
    }

    /**
     * Chuyển toàn bộ BranchCharge sang ListingCharge và gắn vào listing.
     */
    public static List<ListingCharge> mapBranchChargesToListing(List<BranchCharge> branchCharges, Listing listing) {
        if (branchCharges == null || branchCharges.isEmpty()) {
            return new ArrayList<>();
        }
        return branchCharges.stream()
                .sorted(Comparator.comparing(c -> c.getSortOrder() != null ? c.getSortOrder() : 0))
                .map(bc -> toListingCharge(bc, listing))
                .collect(Collectors.toList());
    }

    /**
     * Liệt kê các khoản chi phí bắt buộc còn thiếu trong biểu phí chi nhánh.
     */
    public static List<String> findMissingCharges(ListingCategory category, List<BranchCharge> charges) {
        Set<ChargeType> presentTypes = charges == null ? Collections.emptySet() :
                charges.stream()
                        .map(BranchCharge::getChargeType)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        List<String> missing = new ArrayList<>();
        if (!presentTypes.contains(ChargeType.ELECTRICITY)) {
            missing.add("Tiền điện");
        }
        if (!presentTypes.contains(ChargeType.WATER)) {
            missing.add("Tiền nước sinh hoạt");
        }
        if (category == ListingCategory.APARTMENT && !presentTypes.contains(ChargeType.MANAGEMENT)) {
            missing.add("Phí quản lý tòa nhà");
        }
        if (!presentTypes.contains(ChargeType.INTERNET)) {
            missing.add("Internet / WiFi");
        }
        if (!presentTypes.contains(ChargeType.SERVICE_OR_GARBAGE)) {
            missing.add("Phí rác & Vệ sinh");
        }
        if (!presentTypes.contains(ChargeType.MOTORBIKE_PARKING)) {
            missing.add("Phí gửi xe máy");
        }
        if (!presentTypes.contains(ChargeType.CAR_PARKING)) {
            missing.add("Phí gửi ô tô");
        }
        return missing;
    }

    /**
     * Kiểm tra biểu phí chi nhánh đã hoàn chỉnh hay chưa.
     */
    public static boolean isChargesComplete(ListingCategory category, List<BranchCharge> charges) {
        return findMissingCharges(category, charges).isEmpty();
    }

    /**
     * Xác thực tính hợp lệ của biểu phí khi tạo/cập nhật chi nhánh.
     */
    public static void validateBranchCharges(List<CreateBranchChargeRequest> charges, int motorbikeCap, int carCap) {
        if (charges == null || charges.isEmpty()) return;

        for (CreateBranchChargeRequest c : charges) {
            ChargeType type = c.getChargeType();
            BillingMethod method = c.getBillingMethod();

            if (type == null) {
                throw new AppException(400, "Loại chi phí không được để trống", org.springframework.http.HttpStatus.BAD_REQUEST);
            }
            if (method == null) {
                throw new AppException(400, "Phương thức tính phí không được để trống cho loại phí " + type, org.springframework.http.HttpStatus.BAD_REQUEST);
            }

            // Kiểm tra số tiền âm
            if (c.getAmount() != null && c.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new AppException(400, "Số tiền cho khoản phí " + type + " không được là số âm", org.springframework.http.HttpStatus.BAD_REQUEST);
            }

            // Với phương thức tính tiền cụ thể, bắt buộc amount > 0 hoặc không null
            boolean isCostMethod = method == BillingMethod.PER_KWH
                    || method == BillingMethod.PER_M3
                    || method == BillingMethod.PER_PERSON_MONTH
                    || method == BillingMethod.PER_MONTH
                    || method == BillingMethod.PER_M2_MONTH
                    || method == BillingMethod.PER_VEHICLE_MONTH
                    || method == BillingMethod.PER_HOUR;

            if (isCostMethod && !c.isIncludedInRent()) {
                if (c.getAmount() == null || c.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                    throw new AppException(400, "Vui lòng nhập đơn giá hợp lệ (> 0) cho khoản phí " + type, org.springframework.http.HttpStatus.BAD_REQUEST);
                }
            }

            // Với khoản đã bao gồm / không áp dụng / miễn phí, amount phải là null hoặc 0
            if (c.isIncludedInRent() || method == BillingMethod.INCLUDED || method == BillingMethod.NOT_APPLICABLE || method == BillingMethod.FREE) {
                c.setAmount(null);
            }

            // Phí khác (OTHER) bắt buộc có customName và amount hợp lệ
            if (type == ChargeType.OTHER) {
                if (c.getCustomName() == null || c.getCustomName().trim().isBlank()) {
                    throw new AppException(400, "Khoản chi phí khác bắt buộc phải có tên cụ thể", org.springframework.http.HttpStatus.BAD_REQUEST);
                }
                if (c.getAmount() == null || c.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new AppException(400, "Khoản chi phí khác '" + c.getCustomName() + "' phải có số tiền lớn hơn 0", org.springframework.http.HttpStatus.BAD_REQUEST);
                }
            }

            // Sức chứa xe máy = 0 thì không được cấu hình có chỗ hoặc thu phí xe máy
            if (type == ChargeType.MOTORBIKE_PARKING && motorbikeCap == 0) {
                if (method == BillingMethod.FREE || method == BillingMethod.INCLUDED || c.isIncludedInRent() || method == BillingMethod.PER_VEHICLE_MONTH) {
                    throw new AppException(
                            ListingErrorCode.MOTORBIKE_PARKING_NOT_ALLOWED,
                            "Không thể cấu hình phí gửi xe máy khi tổng số chỗ xe máy của chi nhánh bằng 0."
                    );
                }
            }

            // Sức chứa ô tô = 0 thì không được cấu hình có chỗ hoặc thu phí ô tô
            if (type == ChargeType.CAR_PARKING && carCap == 0) {
                if (method == BillingMethod.FREE || method == BillingMethod.INCLUDED || c.isIncludedInRent() || method == BillingMethod.PER_VEHICLE_MONTH) {
                    throw new AppException(
                            ListingErrorCode.CAR_PARKING_NOT_ALLOWED,
                            "Không thể cấu hình phí gửi ô tô khi tổng số chỗ ô tô của chi nhánh bằng 0."
                    );
                }
            }

            // Không nhận giữ xe máy thì sức chứa xe máy phải bằng 0
            if (type == ChargeType.MOTORBIKE_PARKING && method == BillingMethod.NOT_APPLICABLE && motorbikeCap > 0) {
                throw new AppException(
                        ListingErrorCode.MOTORBIKE_PARKING_NOT_ALLOWED,
                        "Chi nhánh chọn không nhận giữ xe máy thì sức chứa xe máy phải bằng 0."
                );
            }

            // Không nhận giữ ô tô thì sức chứa ô tô phải bằng 0
            if (type == ChargeType.CAR_PARKING && method == BillingMethod.NOT_APPLICABLE && carCap > 0) {
                throw new AppException(
                        ListingErrorCode.CAR_PARKING_NOT_ALLOWED,
                        "Chi nhánh chọn không nhận giữ ô tô thì sức chứa ô tô phải bằng 0."
                );
            }
        }
    }
}
