package com.hs.listing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AiListingCostResponse(List<Item> items, Instant readAt) {
    public record Item(String listingId, BigDecimal monthlyRent, BigDecimal knownFixedMonthly,
                       BigDecimal fixedMonthlyTotal, BigDecimal estimatedMonthlyTotal,
                       List<String> missingInputs, Boolean motorbikesAllowed, Boolean carsAllowed) {}
}
