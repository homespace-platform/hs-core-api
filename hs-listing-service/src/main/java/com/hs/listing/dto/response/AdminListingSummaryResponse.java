package com.hs.listing.dto.response;

import com.hs.listing.model.constant.*;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminListingSummaryResponse(
        String id,
        String title,
        ListingCategory category,
        ListingSubtype subtype,
        ListingStatus status,
        String statusReason,
        BigDecimal priceAmount,
        String currency,
        PriceUnit priceUnit,
        String coverImageUrl,
        String fullAddress,
        ListingOwnerResponse owner,
        Instant submittedAt,
        Instant publishedAt,
        Instant expiresAt,
        Instant statusChangedAt,
        String statusChangedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
