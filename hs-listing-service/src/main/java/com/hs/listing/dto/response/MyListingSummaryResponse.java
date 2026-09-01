package com.hs.listing.dto.response;

import com.hs.listing.model.constant.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MyListingSummaryResponse(
        String id,
        String title,
        ListingCategory category,
        ListingSubtype subtype,
        ListingStatus status,
        LocalDate availableFrom,
        BigDecimal areaM2,
        BigDecimal priceAmount,
        String currency,
        PriceUnit priceUnit,
        boolean negotiable,
        String coverImageUrl,
        String coverStorageObjectId,
        int mediaCount,
        String fullAddress,
        String statusReason,
        Instant submittedAt,
        Instant publishedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        Long viewCount
) {
}
