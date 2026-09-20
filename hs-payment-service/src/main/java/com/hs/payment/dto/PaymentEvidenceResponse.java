package com.hs.payment.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record PaymentEvidenceResponse(
        String id,
        String uploadedBy,
        String storageObjectId,
        Instant declaredTransferTime,
        String bankTransactionReference,
        String payerAccountLast4,
        String note,
        Instant createdAt
) {}
