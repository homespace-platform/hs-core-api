package com.hs.contract.service.analytics;

import com.hs.contract.dto.analytics.LandlordAnalyticsResponse.OccupancyMonth;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.model.constant.ContractStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OccupancyCalcTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static BranchLeaseRow lease(String id, ContractStatus status, LocalDate moveIn, int months, Instant handedOver) {
        return new BranchLeaseRow(id, "HD-" + id, status, null, null, null, moveIn, months, "Tenant", handedOver);
    }

    private static List<OccupancyMonth> run(YearMonth from, YearMonth to, Set<String> rooms, List<BranchLeaseRow> leases,
            Map<String, String> placed) {
        return OccupancyCalc.monthly(from, to, rooms, leases, placed, TODAY);
    }

    @Test
    void leaseSpanningMonthsCountsEveryDayFromMoveInUntilEndExclusive() {
        // 2026-09-16 + 2 months = end 2026-11-16 (exclusive): Sep 15 days, Oct 31, Nov 1..10 (today) = 10.
        var l = lease("a", ContractStatus.ACTIVE, LocalDate.of(2026, 9, 16), 2, null);
        var months = run(YearMonth.of(2026, 9), YearMonth.of(2026, 11), Set.of("r1", "r2"), List.of(l), Map.of("a", "r1"));
        assertEquals(15, months.get(0).occupiedRoomDays());
        assertEquals(60, months.get(0).roomDays());
        assertEquals(0.25, months.get(0).rate());
        assertEquals(31, months.get(1).occupiedRoomDays());
        assertEquals(62, months.get(1).roomDays());
        assertEquals(0.5, months.get(1).rate());
        assertEquals(10, months.get(2).occupiedRoomDays()); // current month counts up to today only
        assertEquals(20, months.get(2).roomDays());
        assertEquals(2, months.get(2).rooms());
    }

    @Test
    void lastDayOfLeaseIsEndMinusOne() {
        // move in 2026-10-01 for 1 month: end 2026-11-01 exclusive, so October is full and November empty.
        var l = lease("a", ContractStatus.CLOSED, LocalDate.of(2026, 10, 1), 1, null);
        var months = run(YearMonth.of(2026, 10), YearMonth.of(2026, 11), Set.of("r"), List.of(l), Map.of("a", "r"));
        assertEquals(31, months.get(0).occupiedRoomDays());
        assertEquals(1.0, months.get(0).rate());
        assertEquals(0, months.get(1).occupiedRoomDays());
    }

    @Test
    void handoverEndsOccupancyEarlyOnTheVietnameseDay() {
        // 2026-10-20T17:30Z is 21/10 00:30 in Ho Chi Minh: occupied 1..20 October.
        var l = lease("a", ContractStatus.TERMINATED, LocalDate.of(2026, 10, 1), 6, Instant.parse("2026-10-20T17:30:00Z"));
        var oct = run(YearMonth.of(2026, 10), YearMonth.of(2026, 10), Set.of("r"), List.of(l), Map.of("a", "r")).getFirst();
        assertEquals(20, oct.occupiedRoomDays());
    }

    @Test
    void expiredLeaseWithoutHandoverKeepsTheRoomThroughToday() {
        var l = lease("a", ContractStatus.EXPIRED, LocalDate.of(2026, 8, 1), 3, null); // end 2026-11-01
        var nov = run(YearMonth.of(2026, 11), YearMonth.of(2026, 11), Set.of("r"), List.of(l), Map.of("a", "r")).getFirst();
        assertEquals(10, nov.occupiedRoomDays());
        assertEquals(1.0, nov.rate());
    }

    @Test
    void cancelledAndPreEffectiveLeasesNeverOccupy() {
        var leases = List.of(lease("c", ContractStatus.CANCELLED, LocalDate.of(2026, 10, 1), 3, null),
                lease("d", ContractStatus.DRAFT, LocalDate.of(2026, 10, 1), 3, null),
                lease("p", ContractStatus.TENANT_SIGNATURE_PENDING, LocalDate.of(2026, 10, 1), 3, null));
        var oct = run(YearMonth.of(2026, 10), YearMonth.of(2026, 10), Set.of("r"), leases,
                Map.of("c", "r", "d", "r", "p", "r")).getFirst();
        assertEquals(0, oct.occupiedRoomDays());
        assertEquals(0.0, oct.rate());
    }

    @Test
    void sameRoomTwoLeasesCountOnceAndUnplacedOrForeignRoomIsIgnored() {
        var a = lease("a", ContractStatus.ACTIVE, LocalDate.of(2026, 10, 1), 3, null);
        var b = lease("b", ContractStatus.TERMINATED, LocalDate.of(2026, 10, 1), 3, null);
        var unplaced = lease("u", ContractStatus.ACTIVE, LocalDate.of(2026, 10, 1), 3, null);
        var foreign = lease("f", ContractStatus.ACTIVE, LocalDate.of(2026, 10, 1), 3, null);
        var oct = run(YearMonth.of(2026, 10), YearMonth.of(2026, 10), Set.of("r"), List.of(a, b, unplaced, foreign),
                Map.of("a", "r", "b", "r", "f", "not-in-scope")).getFirst();
        assertEquals(31, oct.occupiedRoomDays());
        assertEquals(31, oct.roomDays());
    }

    @Test
    void futureMonthHasNoRoomDaysAndNoRate() {
        var months = run(YearMonth.of(2026, 12), YearMonth.of(2026, 12), Set.of("r"), List.of(), Map.of());
        assertEquals(0, months.getFirst().roomDays());
        assertNull(months.getFirst().rate());
    }

    @Test
    void noRoomsMeansNoRate() {
        var m = run(YearMonth.of(2026, 10), YearMonth.of(2026, 10), Set.of(), List.of(), Map.of()).getFirst();
        assertEquals(0, m.rooms());
        assertNull(m.rate());
    }

    @Test
    void rateRoundsToThreeDecimals() {
        // 652 of 720 room-days = 0.9055...
        var l = lease("a", ContractStatus.ACTIVE, LocalDate.of(2026, 9, 1), 12, null);
        var m = run(YearMonth.of(2026, 9), YearMonth.of(2026, 9), Set.of("r"), List.of(l), Map.of("a", "r")).getFirst();
        assertEquals(1.0, m.rate());
        var partial = lease("p", ContractStatus.ACTIVE, LocalDate.of(2026, 9, 5), 12, null); // 26 of 30 days
        assertEquals(0.867, run(YearMonth.of(2026, 9), YearMonth.of(2026, 9), Set.of("r"), List.of(partial),
                Map.of("p", "r")).getFirst().rate());
    }
}
