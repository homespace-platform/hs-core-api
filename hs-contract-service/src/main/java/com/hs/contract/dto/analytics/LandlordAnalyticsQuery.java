package com.hs.contract.dto.analytics;

import java.time.YearMonth;
import java.util.Set;

/** Validated request; null months mean "the 12 months ending with the billing month". */
public record LandlordAnalyticsQuery(YearMonth from, YearMonth to, String branchId, String listingId,
        Set<String> include, int listingPage, int listingSize, boolean noLeadsOnly) {
    public static final Set<String> SECTIONS = Set.of("revenue", "occupancy", "receivables", "listings", "funnel");
}
