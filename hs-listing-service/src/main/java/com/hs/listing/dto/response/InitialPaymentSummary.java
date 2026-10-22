package com.hs.listing.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record InitialPaymentSummary(
        String id,
        String type,
        String displayName,
        String status,
        BigDecimal totalAmount,
        String transferReference,
        Instant expiresAt,
        Instant payerReportedAt,
        Instant confirmationDueAt,
        Instant payeeConfirmedAt,
        Instant paidAt,
        Instant confirmedAt,
        Instant contractDueAt,
        boolean canCancelImmediately
) {}
