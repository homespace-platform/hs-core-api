package com.hs.listing.dto.request;

import com.hs.listing.model.constant.FurnishingStatus;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.PositionType;
import com.hs.listing.model.constant.ListingEnums.RestroomType;
import com.hs.listing.model.constant.ListingSubtype;

import java.math.BigDecimal;

public record PublicListingSearchRequest(
        int page,
        int size,
        ListingCategory category,
        ListingSubtype subtype,
        String keyword,
        String provinceCode,
        String wardCode,
        BigDecimal priceMin,
        BigDecimal priceMax,
        BigDecimal areaMin,
        BigDecimal areaMax,
        Integer bedrooms,
        Boolean hasVideo,
        FurnishingStatus furnishingStatus,
        String direction,
        String officeGrade,
        PositionType positionType,
        RestroomType restroomType,
        Boolean hasMezzanine,
        Boolean hasRooftop,
        Boolean hasGarage,
        String sort
) {
}

