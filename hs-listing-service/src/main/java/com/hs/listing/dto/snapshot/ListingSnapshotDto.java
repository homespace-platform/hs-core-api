package com.hs.listing.dto.snapshot;

import com.hs.listing.model.constant.ListingCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bản chụp bất biến của tin đăng (Listing) tại thời điểm thỏa thuận thuê được xác lập.
 * Chứa dữ liệu đã giải quyết hoàn chỉnh để phục vụ tạo và ký hợp đồng bất biến.
 * Tuyệt đối không chứa branchId, tên chi nhánh, mã chi nhánh.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListingSnapshotDto {

    @Builder.Default
    private int schemaVersion = 1;

    private String listingId;
    private String listingCode;
    private ListingCategory category;
    private String categoryLabel;
    private String title;

    // Địa chỉ đầy đủ đã giải quyết
    private String fullAddress;
    private String unitNumber;
    private String floor;
    private String buildingName;

    private BigDecimal areaM2;
    private BigDecimal priceAmount;
    private String currency;
    private String priceUnit;
    private String paymentCycle;
    private String depositType;
    private BigDecimal depositAmount;
    private Integer depositMonths;
    private Integer minimumLeaseMonths;
    private Boolean vatIncluded;
    private Boolean managementFeeIncluded;

    private Integer maxOccupants;
    private Integer maxVehicles;
    private Integer maxMotorbikeCount;
    private Integer maxCarCount;
    private String rentalScopeDescription;

    // Chi tiết theo loại hình
    private ApartmentDetailSnapshot apartmentDetail;
    private HouseDetailSnapshot houseDetail;
    private RoomDetailSnapshot roomDetail;

    // Nội quy tài sản
    private String buildingRules;

    // Tiện ích & dịch vụ
    @Builder.Default
    private List<AmenityItemSnapshot> amenities = new ArrayList<>();

    @Builder.Default
    private List<String> customAmenities = new ArrayList<>();

    // Trang thiết bị / nội thất bàn giao
    @Builder.Default
    private List<FurnishingItemSnapshot> furnishings = new ArrayList<>();

    // Biểu phí dịch vụ
    @Builder.Default
    private List<ChargeItemSnapshot> charges = new ArrayList<>();

    private Instant capturedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApartmentDetailSnapshot {
        private String projectName;
        private String buildingBlock;
        private String unitCode;
        private Integer floorNumber;
        private Integer buildingTotalFloors;
        private Integer bedroomCount;
        private Integer bathroomCount;
        private Integer livingRoomCount;
        private Integer kitchenCount;
        private String furnishingStatus;
        private String mainDoorDirection;
        private String balconyDirection;
        private String viewDescription;
        private Integer maxOccupants;
        private String legalStatus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HouseDetailSnapshot {
        private BigDecimal landAreaM2;
        private BigDecimal frontageWidthM;
        private BigDecimal lengthM;
        private BigDecimal accessRoadWidthM;
        private Integer frontageCount;
        private Integer totalFloors;
        private Integer bedroomCount;
        private Integer bathroomCount;
        private Integer livingRoomCount;
        private Integer kitchenCount;
        private Boolean hasRooftop;
        private Boolean hasGarage;
        private String accessType;
        private Integer maxOccupants;
        private Integer maxVehicles;
        private String furnishingStatus;
        private String legalStatus;
        private String rentalScopeDescription;
        private Integer rentedFloorFrom;
        private Integer rentedFloorTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomDetailSnapshot {
        private String roomCode;
        private Integer floorNumber;
        private String restroomType;
        private String kitchenType;
        private Boolean hasWindow;
        private String balconyType;
        private Boolean hasMezzanine;
        private String furnishingStatus;
        private String accessType;
        private String accessHoursType;
        private String electricMeterType;
        private String waterMeterType;
        private Integer maxOccupants;
        private Integer maxVehicles;
        private String parkingPolicy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AmenityItemSnapshot {
        private String code;
        private String name;
        private String scope; // "Riêng trong căn/phòng/nhà" | "Dùng chung"
        private String sourceType; // "LISTING" | "SHARED_PROPERTY" | "CUSTOM" | "DERIVED_POLICY"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FurnishingItemSnapshot {
        private String itemCode;
        private String assetName;
        private Integer quantity;
        private String handoverCondition;
        private String conditionNote;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargeItemSnapshot {
        private String chargeType;
        private String billingMethod;
        private BigDecimal amount;
        private String currency;
        private String unit;
        private boolean includedInRent;
        private String customName;
        private String description;
        private Integer sortOrder;
    }
}
