package com.hs.listing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;
import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingStatus;

public record ListingResponse(
        String id,
        String ownerId,
        String title,
        String description,
        ListingCategory category,
        ListingStatus status,
        BigDecimal priceMonthly,
        BigDecimal depositAmount,
        BigDecimal areaM2,
        Integer bedrooms,
        Integer bathrooms,
        String provinceCode,
        String districtCode,
        String wardCode,
        String address,
        JsonNode details,
        List<ListingImageResponse> images,
        Instant createdAt,
        Instant updatedAt
) {

    public static ListingResponse from(Listing listing, JsonNode details, List<ListingImageResponse> images) {
        return new ListingResponse(
                listing.getId(), listing.getOwnerId(), listing.getTitle(), listing.getDescription(),
                listing.getCategory(), listing.getStatus(), listing.getPriceMonthly(), listing.getDepositAmount(),
                listing.getAreaM2(), listing.getBedrooms(), listing.getBathrooms(), listing.getProvinceCode(),
                listing.getDistrictCode(), listing.getWardCode(), listing.getAddress(), details, images,
                listing.getCreatedAt(), listing.getUpdatedAt());
    }
}
