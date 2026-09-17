package com.hs.listing.dto.estimate;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record PredictableChargeItem(
        String chargeType,
        String displayName,
        String billingMethod,
        BigDecimal unitAmount,
        Integer quantity,
        BigDecimal amount,
        boolean includedInRent,
        String note
) {}
