package com.hs.contract.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Pure cost periods of a root lease, anchored to the move-in date. */
public final class BranchBillingPeriods {

    public record Period(int index, LocalDate start, LocalDate endExclusive) {}

    private BranchBillingPeriods() {}

    /** Periods 0..months-1, each cut at the effective end of the lease; missing facts give no period. */
    public static List<Period> of(LocalDate moveIn, Integer months, LocalDate leaseEndExclusive) {
        List<Period> periods = new ArrayList<>();
        if (moveIn == null || months == null || leaseEndExclusive == null) return periods;
        for (int index = 0; index < months; index++) {
            LocalDate start = moveIn.plusMonths(index);
            if (!start.isBefore(leaseEndExclusive)) break;
            LocalDate end = moveIn.plusMonths(index + 1L);
            periods.add(new Period(index, start, end.isAfter(leaseEndExclusive) ? leaseEndExclusive : end));
        }
        return periods;
    }
}
