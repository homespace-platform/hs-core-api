package com.hs.payment.dto;

import com.hs.payment.model.constant.PaymentStatus;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record InitialPaymentSummaryDto(
        String paymentRequestId,
        PaymentStatus status,
        BigDecimal totalAmount,
        String transferReference,
        Instant dueAt,
        Instant payerReportedAt,
        Instant payeeConfirmedAt,
        Instant confirmedAt,
        Instant contractDueAt
) {}
