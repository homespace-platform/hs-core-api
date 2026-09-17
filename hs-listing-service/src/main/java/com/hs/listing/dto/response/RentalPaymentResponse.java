package com.hs.listing.dto.response;

import com.hs.listing.model.constant.RentalPaymentStatus;
import com.hs.listing.model.constant.RentalPaymentType;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record RentalPaymentResponse(
        String id,
        String rentalRequestId,
        String listingId,
        String renterId,
        String ownerId,
        RentalPaymentType type,
        RentalPaymentStatus status,
        String currency,
        BigDecimal monthlyRent,
        BigDecimal monthlyCharges,
        BigDecimal depositAmount,
        BigDecimal totalAmount,
        String costBreakdownSnapshot,
        String excludedChargesSnapshot,
        Instant expiresAt,
        Instant paidAt,
        Instant contractDueAt,
        String provider,
        String providerTransactionId,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt
) {}
