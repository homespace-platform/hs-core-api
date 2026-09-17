package com.hs.listing.dto.response;

import com.hs.listing.model.constant.RentalPaymentStatus;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record InitialPaymentSummary(
        String id,
        RentalPaymentStatus status,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant paidAt,
        Instant contractDueAt
) {}
