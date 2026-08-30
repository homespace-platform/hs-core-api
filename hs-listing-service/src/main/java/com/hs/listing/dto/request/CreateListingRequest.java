package com.hs.listing.dto.request;

import java.math.BigDecimal;
import java.util.List;

import tools.jackson.databind.JsonNode;
import com.hs.listing.model.constant.ListingCategory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateListingRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 5000) String description,
        @NotNull ListingCategory category,
        @DecimalMin("0.0") BigDecimal priceMonthly,
        @DecimalMin("0.0") BigDecimal depositAmount,
        @DecimalMin("0.1") BigDecimal areaM2,
        @Min(0) Integer bedrooms,
        @Min(0) Integer bathrooms,
        @NotNull @Valid ListingAddressRequest address,
        JsonNode details,
        List<@NotBlank @Size(max = 2048) String> imageUrls,
        List<@NotBlank @Size(max = 2048) String> videoUrls
) {
}
