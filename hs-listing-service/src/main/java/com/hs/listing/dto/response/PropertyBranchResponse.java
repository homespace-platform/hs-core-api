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
    private String streetLine;
    private String wardCode;
    private String wardName;
    private String provinceCode;
    private String provinceName;
    private String fullAddress;
    private String description;
    private String buildingRules;
    private Integer totalUnits;
    private List<BranchChargeResponse> defaultCharges;
    private List<String> buildingAmenityCodes;
    private Instant createdAt;
    private Instant updatedAt;
}
