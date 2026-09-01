package com.hs.listing.dto.response;

import com.hs.listing.model.constant.*;

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
        boolean hasVideo,
        String fullAddress,
        String provinceCode,
        String provinceName,
        String wardCode,
        String wardName,
        Integer bedroomCount,
        Instant publishedAt,
        LocalDate availableFrom
) {
}
