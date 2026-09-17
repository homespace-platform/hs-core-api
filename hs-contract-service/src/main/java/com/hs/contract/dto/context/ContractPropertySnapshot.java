package com.hs.contract.dto.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot thông tin tài sản bất động sản cho thuê.
 * Tuyệt đối không chứa branchId, tên chi nhánh, mã chi nhánh.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractPropertySnapshot {

    private String listingCode;
    private String rentalScope;
    private String fullAddress;
    private String areaText;
    private BigDecimal areaM2;
    private String propertyType;
    private String unitNumber;
    private String floor;
    private String buildingName;
    private Integer maxOccupants;
    private Integer maxVehicles;

    @Builder.Default
    private List<PropertyFeatureItem> features = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PropertyFeatureItem {
        private String featureName;
        private String featureValue;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listingCode", listingCode != null ? listingCode : "");
        m.put("rentalScope", rentalScope != null ? rentalScope : "");
        m.put("fullAddress", fullAddress != null ? fullAddress : "");
        m.put("areaText", areaText != null ? areaText : "");
        m.put("propertyType", propertyType != null ? propertyType : "");
        m.put("unitNumber", unitNumber != null ? unitNumber : "");
        m.put("floor", floor != null ? floor : "");
        m.put("buildingName", buildingName != null ? buildingName : "");
        m.put("maxOccupants", maxOccupants != null ? String.valueOf(maxOccupants) : "");
        m.put("maxVehicles", maxVehicles != null ? String.valueOf(maxVehicles) : "");
        return m;
    }
}
