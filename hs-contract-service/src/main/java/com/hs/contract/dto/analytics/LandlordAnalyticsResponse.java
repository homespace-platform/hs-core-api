package com.hs.contract.dto.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

/** Landlord analytics. A section is null when it was not requested through {@code include}. Money preserves the database BigDecimal precision. */
public record LandlordAnalyticsResponse(Instant billingNow, String from, String to, LocalDate receiptsSince,
        List<RevenueMonth> revenue, List<OccupancyMonth> occupancy, Receivables receivables, Listings listings,
        Funnel funnel) {

    /** Received is by receipt issue month, due by payment due month (two clocks, see the design). Deposits excluded. */
    public record RevenueMonth(String month, BigDecimal receivedVnd, BigDecimal refundedVnd, BigDecimal netVnd, BigDecimal dueVnd,
            BigDecimal dueCollectedVnd, BigDecimal lateCollectedVnd, BigDecimal lateFeeVnd, BigDecimal rolledOverVnd, BigDecimal membershipFeeVnd) {}

    /** {@code rate} is null when the month has no room-days (no rooms, or a month that has not started). */
    public record OccupancyMonth(String month, int rooms, int occupiedRoomDays, int roomDays, Double rate) {}

    public record Receivables(Instant asOf, BigDecimal totalVnd, int count, List<Bucket> buckets, List<Debt> top) {}

    /** key: D0_7, D8_30, D31_PLUS. */
    public record Bucket(String key, int count, BigDecimal vnd) {}

    public record Debt(String paymentId, String contractNumber, String roomCode, String tenantName, Instant dueAt,
            int daysLate, BigDecimal amountVnd, String status, String href) {}

    /** {@code viewsSince}: first day the daily view table has data (null: no daily data yet). */
    public record Listings(LocalDate viewsSince, int page, int size, long total, List<ListingRow> items,
            List<DailyViews> dailyViews) {}

    public record DailyViews(LocalDate day, long views) {}

    public record ListingRow(String id, String title, String branchName, String status, Instant publishedAt,
            Instant expiresAt, long viewCountTotal, Long viewsInRange, long appointments, long requests, long contracts,
            long favorites, boolean noLeads, Double viewToRequestRate) {}

    public record Funnel(Views views, long appointments, long requests, long contracts) {}

    /** {@code cumulative}: all-time view counter (no daily data yet); otherwise views in range counted since {@code since}. */
    public record Views(long value, boolean cumulative, LocalDate since) {}
}
