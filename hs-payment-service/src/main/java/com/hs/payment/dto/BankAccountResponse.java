package com.hs.payment.dto;

import com.hs.payment.model.constant.BankAccountStatus;
import com.hs.payment.model.constant.BankAccountVerificationMethod;
import lombok.Builder;

import java.time.Instant;

@Builder
public record BankAccountResponse(
        String id,
        String userId,
        String bankBin,
        String bankCode,
        String bankName,
        String accountNumber,
        String accountHolderName,
        Boolean defaultForIncomingPayments,
        Boolean defaultForRefunds,
        BankAccountStatus status,
        BankAccountVerificationMethod verificationMethod,
        Instant createdAt,
        Instant updatedAt
) {}
