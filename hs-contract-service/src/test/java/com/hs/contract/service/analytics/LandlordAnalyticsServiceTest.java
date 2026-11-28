package com.hs.contract.service.analytics;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.analytics.LandlordAnalyticsQuery;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse;
import com.hs.contract.repository.BranchAttentionReadRepository;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.contract.service.analytics.LandlordAnalyticsQueries.*;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.repository.PropertyBranchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordAnalyticsServiceTest {
    // 2026-11-30T17:00Z is 2026-12-01 00:00 in Ho Chi Minh: the billing clock already reads December.
    private static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z");

    private final LandlordAnalyticsQueries queries = mock(LandlordAnalyticsQueries.class);
    private final PropertyBranchRepository branches = mock(PropertyBranchRepository.class);
    private LandlordAnalyticsService service;

    @BeforeEach
    void setup() {
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        service = new LandlordAnalyticsService(branches, queries, mock(BranchAttentionReadRepository.class),
                mock(BranchContractAssignmentRepository.class), new BillingTime(NOW.toString(), env));
    }

    private static LandlordAnalyticsQuery only(String section) {
        return new LandlordAnalyticsQuery(null, null, null, null, Set.of(section), 0, 0, false);
    }

    @Test
    void defaultRangeIsTwelveMonthsEndingWithTheBillingMonthAndOnlyRequestedSectionsAreFilled() {
        var r = service.analytics("owner", only("revenue"));
        assertEquals("2026-01", r.from());
        assertEquals("2026-12", r.to());
        assertEquals(12, r.revenue().size());
        assertNull(r.occupancy());
        assertNull(r.receivables());
        assertNull(r.listings());
        assertNull(r.funnel());
        assertEquals(NOW, r.billingNow());
        verify(queries).receipts(eq(new Scope("owner", null, null)),
                eq(Instant.parse("2025-12-31T17:00:00Z")), eq(Instant.parse("2026-12-31T17:00:00Z")));
    }

    @Test
    void revenueIsZeroFilledNetOfRefundsAndKeepsCostsAndDueSeparate() {
        when(queries.receipts(any(), any(), any())).thenReturn(List.of(
                new ReceiptMonth("2026-11", new BigDecimal("52300000.00"), new BigDecimal("1000000.00"),
                        new BigDecimal("3300000.00"))));
        when(queries.due(any(), any(), any())).thenReturn(List.of(
                new DueMonth("2026-11", new BigDecimal("68000000.00"), new BigDecimal("49000000.00"))));
        when(queries.lateFees(any(), any(), any())).thenReturn(Map.of("2026-10", new BigDecimal("150000.00")));
        when(queries.rolledOver(any(), any(), any())).thenReturn(Map.of("2026-10", new BigDecimal("2000000.00")));
        when(queries.membership(eq("owner"), any(), any())).thenReturn(Map.of("2026-11", new BigDecimal("199000")));
        var q = new LandlordAnalyticsQuery(YearMonth.of(2026, 10), YearMonth.of(2026, 12), null, null,
                Set.of("revenue"), 0, 0, false);

        var rows = service.analytics("owner", q).revenue();

        assertEquals(List.of("2026-10", "2026-11", "2026-12"), rows.stream().map(m -> m.month()).toList());
        var oct = rows.get(0);
        assertEquals(0, new BigDecimal("0").compareTo(oct.receivedVnd()));
        assertEquals(0, new BigDecimal("150000").compareTo(oct.lateFeeVnd()));
        assertEquals(0, new BigDecimal("2000000").compareTo(oct.rolledOverVnd()));
        var nov = rows.get(1);
        assertEquals(0, new BigDecimal("52300000").compareTo(nov.receivedVnd()));
        assertEquals(0, new BigDecimal("1000000").compareTo(nov.refundedVnd()));
        assertEquals(0, new BigDecimal("51300000").compareTo(nov.netVnd()));
        assertEquals(0, new BigDecimal("68000000").compareTo(nov.dueVnd()));
        assertEquals(0, new BigDecimal("49000000").compareTo(nov.dueCollectedVnd()));
        assertEquals(0, new BigDecimal("3300000").compareTo(nov.lateCollectedVnd()));
        assertEquals(0, new BigDecimal("199000").compareTo(nov.membershipFeeVnd()));
        var dec = rows.get(2);
        assertEquals(0, new BigDecimal("0").compareTo(dec.dueVnd()));
        assertEquals(0, new BigDecimal("0").compareTo(dec.membershipFeeVnd()));
    }

    @Test
    void vndPreservesDecimalMoney() {
        assertEquals(new BigDecimal("2.50"), LandlordAnalyticsService.vnd(new BigDecimal("2.50")));
        assertEquals(new BigDecimal("2.49"), LandlordAnalyticsService.vnd(new BigDecimal("2.49")));
        assertEquals(BigDecimal.ZERO, LandlordAnalyticsService.vnd(null));
    }

    @ParameterizedTest
    @CsvSource({"0,D0_7", "7,D0_7", "8,D8_30", "30,D8_30", "31,D31_PLUS", "400,D31_PLUS"})
    void agingBucketsAtTheBoundaries(int days, String bucket) {
        assertEquals(bucket, LandlordAnalyticsService.bucket(days));
    }

    @Test
    void receivablesAreBucketedTotalledAndTopDebtsCarryDaysLateAndHref() {
        when(queries.debtDays(any(), eq(NOW), eq(LocalDate.of(2026, 12, 1)))).thenReturn(List.of(
                new DebtDay(0, 1, new BigDecimal("500000")), new DebtDay(7, 1, new BigDecimal("1000000")),
                new DebtDay(8, 2, new BigDecimal("2000000")), new DebtDay(30, 1, new BigDecimal("3000000")),
                new DebtDay(31, 1, new BigDecimal("4000000"))));
        // due 2026-11-19 17:00Z = 20/11 in Ho Chi Minh, 11 calendar days before 01/12.
        when(queries.topDebts(any(), eq(NOW), eq(10))).thenReturn(List.of(
                new DebtRow("p1", "HD-1", "P101", "Tenant A", Instant.parse("2026-11-19T17:00:00Z"),
                        new BigDecimal("3000000"), "OVERDUE", "c 1", "inv/1"),
                new DebtRow("p2", null, null, "Tenant B", Instant.parse("2026-11-30T17:00:00Z"),
                        new BigDecimal("500000"), "TRANSFER_REPORTED", null, null)));

        var rec = service.analytics("owner", only("receivables")).receivables();

        assertEquals(NOW, rec.asOf());
        assertEquals(6, rec.count());
        assertEquals(0, new BigDecimal("10500000").compareTo(rec.totalVnd()));
        assertEquals(List.of("D0_7", "D8_30", "D31_PLUS"), rec.buckets().stream().map(b -> b.key()).toList());
        assertEquals(2, rec.buckets().get(0).count());
        assertEquals(0, new BigDecimal("1500000").compareTo(rec.buckets().get(0).vnd()));
        assertEquals(3, rec.buckets().get(1).count());
        assertEquals(0, new BigDecimal("5000000").compareTo(rec.buckets().get(1).vnd()));
        assertEquals(1, rec.buckets().get(2).count());
        assertEquals(0, new BigDecimal("4000000").compareTo(rec.buckets().get(2).vnd()));
        assertEquals(11, rec.top().get(0).daysLate());
        assertEquals("/dashboard/landlord/contracts/c%201?tab=payment&section=monthly&invoice=inv%2F1",
                rec.top().get(0).href());
        assertEquals(0, rec.top().get(1).daysLate());
        assertEquals("/dashboard/invoices", rec.top().get(1).href());
    }

    @Test
    void emptyDebtStillReturnsThreeZeroBuckets() {
        var rec = service.analytics("owner", only("receivables")).receivables();
        assertEquals(0, rec.count());
        assertEquals(3, rec.buckets().size());
        assertTrue(rec.top().isEmpty());
    }

    @Test
    void listingsKeepCumulativeViewsOnlyUntilDailyDataExists() {
        var raw = new ListingRaw("l1", "Room", "Branch", "PUBLISHED", Instant.parse("2026-10-01T00:00:00Z"), null,
                200, 7, true);
        var none = new ListingRaw("l2", "Empty", null, "PUBLISHED", null, null, 0, 0, false);
        when(queries.countListings(any(), eq(false), any())).thenReturn(2L);
        when(queries.listings(any(), eq(false), any(), eq(0), eq(10))).thenReturn(List.of(raw, none));
        when(queries.requests(eq("owner"), eq(List.of("l1", "l2")), any(), any())).thenReturn(Map.of("l1", 10L));
        when(queries.appointments(eq("owner"), any(), any(), any())).thenReturn(Map.of("l1", 25L));
        when(queries.contracts(eq("owner"), any(), any(), any())).thenReturn(Map.of("l1", 4L));

        var noDaily = service.analytics("owner", only("listings")).listings();
        assertNull(noDaily.viewsSince());
        assertEquals(2, noDaily.total());
        var row = noDaily.items().get(0);
        assertNull(row.viewsInRange());
        assertEquals(200, row.viewCountTotal());
        assertEquals(0.05, row.viewToRequestRate());
        assertEquals(25, row.appointments());
        assertEquals(10, row.requests());
        assertEquals(4, row.contracts());
        assertEquals(7, row.favorites());
        assertTrue(row.noLeads());
        assertNull(noDaily.items().get(1).viewToRequestRate());
        assertEquals(0, noDaily.items().get(1).requests());

        when(queries.viewsSince(any())).thenReturn(LocalDate.of(2026, 11, 28));
        when(queries.viewsInRange(any(), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31))))
                .thenReturn(Map.of("l1", 33L));
        var daily = service.analytics("owner", only("listings")).listings();
        assertEquals(LocalDate.of(2026, 11, 28), daily.viewsSince());
        assertEquals(33L, daily.items().get(0).viewsInRange());
        assertEquals(0L, daily.items().get(1).viewsInRange());
    }

    @Test
    void funnelUsesCumulativeViewsUntilDailyDataExistsThenRangeViews() {
        when(queries.viewsCumulative(any())).thenReturn(1204L);
        when(queries.funnelAppointments(any(), any(), any())).thenReturn(86L);
        when(queries.funnelRequests(any(), any(), any())).thenReturn(31L);
        when(queries.funnelContracts(any(), any(), any())).thenReturn(12L);
        var cumulative = service.analytics("owner", only("funnel")).funnel();
        assertTrue(cumulative.views().cumulative());
        assertEquals(1204, cumulative.views().value());
        assertEquals(86, cumulative.appointments());
        assertEquals(31, cumulative.requests());
        assertEquals(12, cumulative.contracts());

        when(queries.viewsSince(any())).thenReturn(LocalDate.of(2026, 11, 28));
        when(queries.viewsDaily(any(), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31)))).thenReturn(90L);
        var daily = service.analytics("owner", only("funnel")).funnel();
        assertFalse(daily.views().cumulative());
        assertEquals(90, daily.views().value());
        assertEquals(LocalDate.of(2026, 11, 28), daily.views().since());
    }

    @ParameterizedTest
    @CsvSource({"2026-11,2026-01", "2024-01,2026-01", "2024-12,2026-12"})
    void invalidRangesAreRejected(String from, String to) {
        var q = new LandlordAnalyticsQuery(YearMonth.parse(from), YearMonth.parse(to), null, null, Set.of("revenue"), 0, 0, false);
        assertEquals(1001, assertThrows(AppException.class, () -> service.analytics("owner", q)).getCode());
        verifyNoInteractions(queries);
    }

    @Test
    void twentyFourMonthsIsAllowedButTwentyFiveIsNot() {
        var ok = new LandlordAnalyticsQuery(YearMonth.of(2025, 1), YearMonth.of(2026, 12), null, null, Set.of("revenue"), 0, 0, false);
        assertEquals(24, service.analytics("owner", ok).revenue().size());
        var tooMany = new LandlordAnalyticsQuery(YearMonth.of(2024, 12), YearMonth.of(2026, 12), null, null, Set.of("revenue"), 0, 0, false);
        assertThrows(AppException.class, () -> service.analytics("owner", tooMany));
    }

    @Test
    void oversizedPageOrUnknownSectionIsRejected() {
        assertThrows(AppException.class, () -> service.analytics("owner",
                new LandlordAnalyticsQuery(null, null, null, null, Set.of("listings"), 1, 51, false)));
        assertThrows(AppException.class, () -> service.analytics("owner",
                new LandlordAnalyticsQuery(null, null, null, null, Set.of("bogus"), 1, 10, false)));
    }

    @Test
    void foreignOrUnknownBranchAndListingAreNotFoundWithoutAnyAggregateQuery() {
        when(branches.findByIdAndOwnerIdAndActiveTrue("b", "owner")).thenReturn(Optional.empty());
        var branch = new LandlordAnalyticsQuery(null, null, "b", null, Set.of("revenue"), 0, 0, false);
        assertEquals(404, assertThrows(AppException.class, () -> service.analytics("owner", branch)).getCode());

        var listing = new LandlordAnalyticsQuery(null, null, null, "l", Set.of("revenue"), 0, 0, false);
        assertEquals(404, assertThrows(AppException.class, () -> service.analytics("owner", listing)).getCode());
        verify(queries, never()).receipts(any(), any(), any());
        verify(queries, never()).receiptsSince(any());
    }

    @Test
    void ownedBranchAndListingScopeEveryQuery() {
        when(branches.findByIdAndOwnerIdAndActiveTrue("b", "owner")).thenReturn(Optional.of(new PropertyBranch()));
        when(queries.listingOwned("owner", "l")).thenReturn(true);
        var q = new LandlordAnalyticsQuery(null, null, "b", "l", Set.of("revenue"), 0, 0, false);
        service.analytics("owner", q);
        verify(queries).due(eq(new Scope("owner", "b", "l")), any(), any());
        verify(queries).membership(eq("owner"), any(), any());
    }
}
