package com.hs.payment.dto;

import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Builder
public record PaymentRequestResponse(
        String id,
        String rentalRequestId,
        String contractId,
        String listingId,
        String payerId,
        String payeeId,
        PaymentType type,
        PaymentDirection direction,
        PaymentStatus status,
        String currency,
        BigDecimal totalAmount,
        String transferReference,
        String bankTransactionReference,
        BankAccountSnapshotDto payerBankAccountSnapshot,
        BankAccountSnapshotDto payeeBankAccountSnapshot,
        String breakdownSnapshot,
        String excludedChargesSnapshot,
        String qrProvider,
        String qrImageUrl,
        Instant dueAt,
        Instant payerReportedAt,
        Instant payeeConfirmedAt,
        Instant confirmedAt,
        Instant confirmationDueAt,
        Instant contractDueAt,
        Instant rejectedAt,
        String rejectedReason,
        Instant cancelledAt,
        String cancelledReason,
        Instant expiredAt,
        List<PaymentLineItemResponse> lineItems,
        List<PaymentEvidenceResponse> evidences,
        Instant createdAt,
        Instant updatedAt
) {}
