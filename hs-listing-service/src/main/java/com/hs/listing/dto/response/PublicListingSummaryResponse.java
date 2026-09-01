package com.hs.listing.dto.response;

import com.hs.listing.model.constant.FurnishingStatus;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.PositionType;
import com.hs.listing.model.constant.ListingEnums.RestroomType;
import com.hs.listing.model.constant.ListingSubtype;
import com.hs.listing.model.constant.PriceUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PublicListingSummaryResponse(
        String id,
        String title,
        ListingCategory category,
        ListingSubtype subtype,
        BigDecimal areaM2,
        BigDecimal priceAmount,
        String currency,
        PriceUnit priceUnit,
        boolean negotiable,
        String coverImageUrl,
        java.util.List<String> imageUrls,
        boolean hasVideo,
        String fullAddress,
        String provinceCode,
        String provinceName,
        String wardCode,
        String wardName,
        Integer bedroomCount,
        Integer bathroomCount,
        Integer floorNumber,
        Integer totalFloors,
        String restroomType,
        Boolean hasMezzanine,
        Boolean hasBalcony,
        Boolean hasWindow,
        Boolean hasRooftop,
        Boolean hasGarage,
        Integer expectedSeats,
        String officeGrade,
        BigDecimal frontageWidthM,
        PositionType positionType,
        FurnishingStatus furnishingStatus,
        String ownerId,
        String ownerName,
        String ownerAvatarUrl,
        Integer ownerListingCount,
        Instant publishedAt,
        LocalDate availableFrom
) {
}
