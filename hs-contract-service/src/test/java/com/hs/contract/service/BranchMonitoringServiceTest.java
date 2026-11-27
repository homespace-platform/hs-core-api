package com.hs.contract.service;

import com.hs.contract.dto.branch.BranchInvoiceRow;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.MeterStatus;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.model.constant.ContractStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchMonitoringServiceTest {
    private static final LocalDate START = LocalDate.of(2026, 1, 31);
    private static final LocalDate READ = LocalDate.of(2026, 2, 27);

    @Test
    void monthEndAnchorAndVietnamMidnightApplyEvenBeforeADraftExists() {
        var periods = BranchMonitoringService.periods(lease(START, 3), List.of());
        assertEquals(LocalDate.of(2026, 3, 30), periods.get(1).meterDate());
        assertTrue(BranchMonitoringService.candidates(periods, READ.minusDays(4)).isEmpty());
        var upcoming = BranchMonitoringService.meter(
                BranchMonitoringService.candidates(periods, READ.minusDays(3)), p -> true, READ.minusDays(3));
        assertEquals(MeterStatus.UPCOMING, upcoming.status());
        assertEquals(READ, upcoming.meterDate());
        assertEquals(0, upcoming.periodIndex());
        assertEquals(Instant.parse("2026-02-27T17:00:00Z"), upcoming.deadlineAt());
        assertTrue(upcoming.target().href().endsWith("?tab=payment&section=monthly"));
        assertEquals(MeterStatus.TODAY, BranchMonitoringService.meter(
                BranchMonitoringService.candidates(periods, READ), p -> true, READ).status());
    }

    @ParameterizedTest
    @CsvSource({"DRAFT,false,TODAY", "DRAFT,true,RECORDED", "UNPAID,false,RECORDED", "CANCELLED,false,"})
    void storedPeriodKeepsItsWindowAndOnlyRecordedPeriodsRemainAfterReadingDay(
            MonthlyInvoiceStatus status, boolean prepared, MeterStatus expected) {
        var storedDay = READ.minusDays(1);
        var invoice = new BranchInvoiceRow("invoice", "lease", 0, storedDay.plusDays(1), status, null,
                null, null, START, null, false, prepared);
        var periods = BranchMonitoringService.periods(lease(START, 3), List.of(invoice));
        var line = BranchMonitoringService.meter(
                BranchMonitoringService.candidates(periods, storedDay), p -> true, storedDay);
        assertEquals(expected, line == null ? null : line.status());
        if (line != null) {
            assertEquals(storedDay, line.meterDate());
            assertTrue(line.target().href().endsWith("&invoice=invoice"));
        }
        assertEquals(expected == MeterStatus.RECORDED, !BranchMonitoringService.candidates(periods, READ).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"PER_KWH,false,true", "PER_M3,false,true", "PER_M3,true,false", "FIXED,false,false"})
    void onlySeparatelyChargedMeasuredUtilitiesQualify(String method, boolean includedInRent, boolean expected) {
        assertEquals(expected, BranchMonitoringService.metered(
                "[{\"billingMethod\":\"%s\",\"includedInRent\":%s}]".formatted(method, includedInRent)));
        assertFalse(BranchMonitoringService.metered("[broken"));
    }

    @Test
    void expiryHasExclusiveBoundariesAndSignedRenewalMovesThem() {
        var lease = lease(START, 3);
        LocalDate end = START.plusMonths(3);
        var long61 = BranchMonitoringService.expiry(lease, end.minusDays(61));
        assertEquals(ExpiryStatus.LONG, long61.status());
        assertEquals(61, long61.daysRemaining());
        var within60 = BranchMonitoringService.expiry(lease, end.minusDays(60));
        assertEquals(ExpiryStatus.WITHIN_60, within60.status());
        assertEquals(end, within60.endDateExclusive());
        assertEquals(ExpiryStatus.WITHIN_60, BranchMonitoringService.expiry(lease, end.minusDays(31)).status());
        assertEquals(ExpiryStatus.WITHIN_30, BranchMonitoringService.expiry(lease, end.minusDays(30)).status());
        var lastDay = BranchMonitoringService.expiry(lease, end.minusDays(1));
        assertEquals(1, lastDay.daysRemaining());
        assertTrue(lastDay.target().href().endsWith("?tab=end"));
        // A renewal or signed amendment moves the stored months:.
        var renewed = BranchMonitoringService.expiry(lease(START, 6), end.minusDays(30));
        assertEquals(ExpiryStatus.LONG, renewed.status());
        assertEquals(START.plusMonths(6), renewed.endDateExclusive());
    }

    @Test
    void expiredLeaseNotHandedOverKeepsARowWithNonPositiveDays() {
        var lease = lease(START, 3);
        LocalDate end = START.plusMonths(3);
        var onEnd = BranchMonitoringService.expiry(lease, end);
        assertEquals(0, onEnd.daysRemaining());
        assertEquals(ExpiryStatus.WITHIN_30, onEnd.status());
        var expired = BranchMonitoringService.expiry(row(ContractStatus.EXPIRED, null, null, null, null), end.plusDays(5));
        assertEquals(-5, expired.daysRemaining());
        assertEquals(end, expired.endDateExclusive());
    }

    @Test
    void endUsesTheLifecycleCalculationAndOnlyLeasesStillHoldingTheRoomShowARow() {
        // Applied renewal / signed amendment: effective months win over the requested ones.
        var extended = row(ContractStatus.ACTIVE, null, null, 5, null);
        assertEquals(ContractLeaseCalc.end(START, ContractLeaseCalc.months(5, 3)), extended.endExclusive());
        assertEquals(START.plusMonths(5), BranchMonitoringService.expiry(extended, READ).endDateExclusive());
        // Not-yet-signed changes never reach the stored months.
        assertEquals(START.plusMonths(3), BranchMonitoringService.expiry(row(ContractStatus.ACTIVE, null, null, null, null), READ)
                .endDateExclusive());
        // Signed mutual early termination: the agreed end moves in.
        LocalDate early = START.plusMonths(2);
        var agreed = BranchMonitoringService.expiry(row(ContractStatus.ACTIVE, early, 2, 5, null), READ);
        assertEquals(early, agreed.endDateExclusive());
        assertEquals(early.toEpochDay() - READ.toEpochDay(), agreed.daysRemaining());
        // Unilateral termination, closed or handed-over lease: no longer occupying.
        assertNull(BranchMonitoringService.expiry(row(ContractStatus.TERMINATED, null, null, null, null), READ));
        assertNull(BranchMonitoringService.expiry(row(ContractStatus.CLOSED, null, null, null, null), READ));
        assertNull(BranchMonitoringService.expiry(row(ContractStatus.EXPIRED, null, null, null, Instant.EPOCH), READ));
    }

    private static BranchLeaseRow row(ContractStatus status, LocalDate agreedEnd, Integer agreedMonths,
                                      Integer effectiveMonths, Instant handedOverAt) {
        return new BranchLeaseRow("lease", "Test lease", status, agreedEnd, agreedMonths, effectiveMonths, START, 3,
                "Tenant", handedOverAt);
    }

    private static BranchLeaseRow lease(LocalDate startsOn, int effectiveMonths) {
        return new BranchLeaseRow("lease", "Test lease", ContractStatus.ACTIVE, null, null,
                effectiveMonths, startsOn, 3, "Tenant", null);
    }
}
