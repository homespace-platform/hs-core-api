package com.hs.listing.dto.request;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateListingRequest(
        @Size(max = 255) String title,
        @Size(max = 5000) String description,
        @DecimalMin("0.0") BigDecimal priceMonthly,
        @DecimalMin("0.0") BigDecimal depositAmount,
        @DecimalMin("0.1") BigDecimal areaM2,
        @Min(0) Integer bedrooms,
        @Min(0) Integer bathrooms,
        @Size(max = 20) String provinceCode,
        @Size(max = 20) String districtCode,
        @Size(max = 20) String wardCode,
        @Size(max = 500) String address,
        JsonNode details
) {
}
