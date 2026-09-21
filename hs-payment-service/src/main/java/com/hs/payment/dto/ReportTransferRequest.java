package com.hs.payment.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record ReportTransferRequest(
        Instant declaredTransferTime,
        String bankTransactionReference,
        String payerAccountLast4,
        String proofStorageId,
        String evidenceUploadSessionId,
        String note
) {
    public ReportTransferRequest(
            Instant declaredTransferTime,
            String bankTransactionReference,
            String payerAccountLast4,
            String proofStorageId,
            String note
    ) {
        this(declaredTransferTime, bankTransactionReference, payerAccountLast4, proofStorageId, null, note);
    }
}
