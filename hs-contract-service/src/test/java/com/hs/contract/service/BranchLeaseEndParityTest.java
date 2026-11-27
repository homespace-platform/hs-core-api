package com.hs.contract.service;

import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.listing.model.RentalRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** The branch map must show the lease end of the contract page, whatever mix of term fields the contract carries. */
class BranchLeaseEndParityTest {
    private static final LocalDate MOVE_IN = LocalDate.of(2026, 11, 27);
    private static final LocalDate BILLING_TODAY = LocalDate.of(2026, 12, 26);

    private static BranchLeaseRow row(LocalDate agreedEnd, Integer agreedMonths, Integer effective, Integer requested) {
        return new BranchLeaseRow("c", "N", ContractStatus.ACTIVE, agreedEnd, agreedMonths, effective, MOVE_IN,
                requested, "T", null);
    }

    private static LocalDate contractPageEnd(LocalDate agreedEnd, Integer agreedMonths, Integer effective,
                                             Integer requested) {
        Contract c = Contract.builder().agreedEarlyEndExclusive(agreedEnd).agreedEarlyBillingMonths(agreedMonths)
                .effectiveLeaseMonths(effective).build();
        return ContractLeaseCalc.end(c, RentalRequest.builder().moveInDate(MOVE_IN).leaseMonths(requested).build());
    }

    @Test
    void branchEndEqualsContractEndForEveryTermFieldMix() {
        LocalDate[] ends = {null, LocalDate.of(2027, 1, 27)};
        Integer[] months = {null, 2, 6};
        for (LocalDate end : ends) for (Integer agreed : months) for (Integer eff : months) for (Integer req : months) {
            if (req == null) continue; // the rental always carries its requested months
            assertEquals(contractPageEnd(end, agreed, eff, req), row(end, agreed, eff, req).endExclusive(),
                    end + "/" + agreed + "/" + eff + "/" + req);
        }
    }

    /** Lease signed for 2 months, then renewed by 4: the stored effective months are 6 and the end moves to 27/05. */
    @Test
    void renewedLeaseShowsSixMonthsEverywhereOnTheSameClock() {
        var before = row(null, null, null, 2);
        var renewed = row(null, null, 6, 2);
        assertEquals(LocalDate.of(2027, 1, 27), before.endExclusive());
        assertEquals(32, BranchMonitoringService.expiry(before, BILLING_TODAY).daysRemaining());
        var expiry = BranchMonitoringService.expiry(renewed, BILLING_TODAY);
        assertEquals(LocalDate.of(2027, 5, 27), renewed.endExclusive());
        assertEquals(renewed.endExclusive(), expiry.endDateExclusive());
        assertEquals(152, expiry.daysRemaining());
        assertEquals(ExpiryStatus.LONG, expiry.status());
    }

    /** Agreed early-exit months without an agreed end used to be ignored by the onboarding view (6 months, not 2). */
    @Test
    void agreedEarlyMonthsWinOverRenewalMonthsOnTheContractPageToo() {
        assertEquals(LocalDate.of(2027, 1, 27), contractPageEnd(null, 2, 6, 6));
        assertEquals(LocalDate.of(2027, 1, 27), row(null, 2, 6, 6).endExclusive());
    }
}
