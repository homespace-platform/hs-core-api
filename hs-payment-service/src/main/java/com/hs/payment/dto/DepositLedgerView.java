package com.hs.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Recorded balances and separately confirmed cash. Null means unavailable, never inferred as zero. */
public record DepositLedgerView(String status, BigDecimal originalAmount, BigDecimal heldAmount,
        BigDecimal retainedAmount, BigDecimal refundableAmount, BigDecimal confirmedReceivedAmount,
        BigDecimal confirmedRefundedAmount, Instant retainedAt, String retainedReason) {}
