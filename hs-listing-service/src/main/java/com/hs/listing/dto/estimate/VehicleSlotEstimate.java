package com.hs.listing.dto.estimate;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record VehicleSlotEstimate(
        boolean allowed,
        int requested,
        int capacity,
        int reserved,
        int available,
        BigDecimal monthlyAmount,
        String note
) {}
