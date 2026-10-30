package com.hs.listing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Bounded candidates outside the original budget; the main result page/budget are not changed. */
public record AiListingValueResponse(String budgetField, BigDecimal budgetCeiling, BigDecimal maxExtraMonthly,
                                     List<String> candidateIds, Instant readAt) {}
