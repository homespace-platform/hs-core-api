package com.hs.payment.dto;

public record UpdateBankAccountDefaultsRequest(
        Boolean defaultForIncomingPayments,
        Boolean defaultForRefunds
) {}
