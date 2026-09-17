package com.hs.listing.dto.response;

import com.hs.listing.model.constant.RentalRequestStatus;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Builder
public record RentalRequestResponse(
        String id,
        String listingId,
        String listingTitle,
        String listingAddress,
        String listingThumbnail,
        BigDecimal listingPrice,
        String ownerId,
        String renterId,
        String renterName,
        String renterPhone,
        String renterEmail,
        LocalDate moveInDate,
        Integer leaseMonths,
        Integer occupantCount,
        Integer motorbikeCount,
        Integer carCount,
        BigDecimal monthlyRentPrice,
        BigDecimal effectiveMonthlyRent,
        BigDecimal estimatedMonthlyCharges,
        BigDecimal estimatedMonthlyTotal,
        BigDecimal depositAmount,
        BigDecimal estimatedInitialTotal,
        BigDecimal estimatedLeaseTotal,
        String costBreakdownSnapshot,
        String excludedChargesSnapshot,
        String renterNote,
        RentalRequestStatus status,
        String rejectReason,
        Instant acceptedAt,
        Instant holdExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {}
