package com.hs.listing.dto.estimate;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record ExcludedChargeItem(
        String chargeType,
        String displayName,
        String billingMethod,
        BigDecimal unitAmount,
        String reason
) {}
