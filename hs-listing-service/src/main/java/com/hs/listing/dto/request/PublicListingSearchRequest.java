package com.hs.listing.dto.request;

import com.hs.listing.model.constant.FurnishingStatus;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.PositionType;
import com.hs.listing.model.constant.ListingEnums.RestroomType;
import java.math.BigDecimal;

public record PublicListingSearchRequest(
        int page,
        int size,
        ListingCategory category,
        String keyword,
        String provinceCode,
        String wardCode,
        BigDecimal priceMin,
        BigDecimal priceMax,
        BigDecimal areaMin,
        BigDecimal areaMax,
        Integer bedrooms,
        Integer bathrooms,
        Boolean hasVideo,
        FurnishingStatus furnishingStatus,
        String direction,
        String balconyDirection,
        String officeGrade,
        PositionType positionType,
        RestroomType restroomType,
        String kitchenType,
        String accessType,
        String legalStatus,
        Boolean hasMezzanine,
        Boolean hasRooftop,
        Boolean hasGarage,
        String sort
) {
}

