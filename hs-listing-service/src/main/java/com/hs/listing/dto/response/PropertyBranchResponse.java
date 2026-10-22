package com.hs.listing.dto.response;

import com.hs.listing.model.constant.ListingCategory;
import lombok.*;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropertyBranchResponse {
    private String id;
    private String ownerId;
    private String name;
    private String code;
    private ListingCategory category;
    private String street;
    private String wardId;
    private String ward;
    private String cityId;
    private String city;
    private String display;
    private String refId;
    private String houseNumber;
    private String address;
    private String districtId;
    private String district;
    private java.math.BigDecimal latitude;
    private java.math.BigDecimal longitude;
    private String addressNote;
    private String description;
    private String buildingRules;
    private Integer totalUnits;
    private Integer activeListingsCount;
    private Integer motorbikeParkingCapacity;
    private Integer carParkingCapacity;
    private String coverImageId;
    private String coverImageUrl;
    private Boolean isComplete;
    private List<String> missingCharges;
    private List<BranchChargeResponse> defaultCharges;
    private List<String> buildingAmenityCodes;
    private Instant createdAt;
    private Instant updatedAt;
}
