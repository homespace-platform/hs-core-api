package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.model.Contract;
import com.hs.listing.model.RentalRequest;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Pure lease-calendar rules shared by lifecycle code and read-only consumers (AI queries).
 * The end date is exclusive: an end of 16/01 means the last rental day is 15/01.
 */
public final class ContractLeaseCalc {
    public static final int RENEWAL_WINDOW_DAYS = 30;

    private ContractLeaseCalc() {}

    public static int months(Contract c, RentalRequest rental) {
        return months(c.getEffectiveLeaseMonths(), rental.getLeaseMonths());
    }

    public static int months(Integer effectiveLeaseMonths, Integer requestedLeaseMonths) {
        return effectiveLeaseMonths != null ? effectiveLeaseMonths : requestedLeaseMonths;
    }

    public static LocalDate end(LocalDate moveIn, int months) { return moveIn.plusMonths(months); }

    /** Renewal requests open 30 days before the exclusive end. */
    public static Instant renewalOpens(LocalDate end) {
        return end.minusDays(RENEWAL_WINDOW_DAYS).atStartOfDay(BillingTime.ZONE).toInstant();
    }

    /** Renewal must be signed before the last rental day begins. */
    public static Instant renewalSignBy(LocalDate end) {
        return end.minusDays(1).atStartOfDay(BillingTime.ZONE).toInstant();
    }
}
