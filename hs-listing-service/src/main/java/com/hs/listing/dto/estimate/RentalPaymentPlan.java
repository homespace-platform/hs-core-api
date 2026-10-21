package com.hs.listing.dto.estimate;

import com.hs.common.time.BillingTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Quote only. The actual obligation is re-evaluated when the landlord accepts. */
public record RentalPaymentPlan(Instant calculatedAt, LocalDate businessDate, LocalDate moveInDate,
                                boolean depositOnly, BigDecimal amountAfterApproval,
                                BigDecimal firstRentAmount, LocalDate firstRentOpensOn) {
    public static RentalPaymentPlan at(Instant now, LocalDate startsOn, BigDecimal rent, BigDecimal deposit) {
        LocalDate today=now.atZone(BillingTime.ZONE).toLocalDate();
        boolean later=startsOn.isAfter(today);
        return new RentalPaymentPlan(now,today,startsOn,later,later?deposit:deposit.add(rent),rent,startsOn);
    }
}
