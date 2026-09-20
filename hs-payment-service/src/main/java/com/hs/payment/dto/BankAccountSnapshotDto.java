package com.hs.payment.dto;

import lombok.Builder;

@Builder
public record BankAccountSnapshotDto(
        String bankBin,
        String bankCode,
        String bankName,
        String accountNumber,
        String accountHolderName
) {}
