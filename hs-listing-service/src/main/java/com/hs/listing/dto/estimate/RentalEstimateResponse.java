package com.hs.listing.dto.estimate;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record RentalEstimateResponse(
        String listingId,
        Integer occupantCount,
        Integer occupantLimit,
        VehicleSlotEstimate motorbike,
        VehicleSlotEstimate car,
        BigDecimal effectiveMonthlyRent,
        List<PredictableChargeItem> predictableCharges,
        BigDecimal predictableMonthlyChargesTotal,
        BigDecimal estimatedMonthlyTotal,
        BigDecimal depositAmount,
        BigDecimal estimatedInitialTotal,
        BigDecimal estimatedLeaseTotal,
        List<ExcludedChargeItem> excludedCharges,
        String disclaimer
) {}
