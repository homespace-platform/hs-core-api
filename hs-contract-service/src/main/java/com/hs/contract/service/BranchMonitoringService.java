package com.hs.contract.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchInvoiceRow;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchOverviewResponse.Expiry;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.Meter;
import com.hs.contract.dto.branch.BranchOverviewResponse.MeterStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.Target;
import com.hs.contract.dto.branch.BranchOverviewResponse.TargetKind;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.model.constant.ContractStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure rules of the meter and contract layers, shared with the attention list. Nothing is loaded or written here:
 * the overview passes in what it read in its own snapshot.
 */
public final class BranchMonitoringService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BranchMonitoringService() {}

    /** A cost period of a lease; {@code row} is its stored regular invoice, or null. */
    record MeterPeriod(String contractId, int index, LocalDate start, LocalDate endExclusive, BranchInvoiceRow row) {
        LocalDate meterDate() {
            return endExclusive.minusDays(1);
        }

        boolean cancelled() {
            return row != null && row.status() == MonthlyInvoiceStatus.CANCELLED;
        }

        /** Prepared or issued: the readings are in. */
        boolean recorded() {
            return row != null && (row.status() != MonthlyInvoiceStatus.DRAFT || row.draftPrepared()) && !cancelled();
        }
    }

    /** Periods of a lease; a stored regular row keeps its own window. */
    static List<MeterPeriod> periods(BranchLeaseRow lease, Collection<BranchInvoiceRow> rows) {
        Map<Integer, BranchInvoiceRow> stored = rows.stream().filter(r -> !r.finalCostSupplement())
                .collect(Collectors.toMap(BranchInvoiceRow::periodIndex, Function.identity()));
        return BranchBillingPeriods.of(lease.moveInDate(), lease.billingMonths(), lease.endExclusive()).stream()
                .map(p -> {
                    BranchInvoiceRow row = stored.get(p.index());
                    return row == null
                            ? new MeterPeriod(lease.id(), p.index(), p.start(), p.endExclusive(), null)
                            : new MeterPeriod(lease.id(), p.index(), row.periodStart(), row.periodEndExclusive(), row);
                }).toList();
    }

    /**
     * Periods that may give the line of a lease: those read in [today, today+3], else only the newest one already
     * reached (never an older one, which would hide a late draft). Cancelled periods give no state.
     */
    static List<MeterPeriod> candidates(List<MeterPeriod> periods, LocalDate today) {
        List<MeterPeriod> window = periods.stream().filter(p -> !p.meterDate().isBefore(today)
                && !p.meterDate().isAfter(today.plusDays(3))).toList();
        if (!window.isEmpty()) return window.stream().filter(p -> !p.cancelled()).toList();
        return periods.stream().filter(p -> p.meterDate().isBefore(today))
                .max(Comparator.comparing(MeterPeriod::meterDate).thenComparingInt(MeterPeriod::index))
                .filter(MeterPeriod::recorded).stream().toList();
    }

    /** The first metered candidate by reading day, or null. {@code metered} reads the signed terms of a period. */
    static Meter meter(List<MeterPeriod> candidates, java.util.function.Predicate<MeterPeriod> metered,
            LocalDate today) {
        return candidates.stream()
                .sorted(Comparator.comparing(MeterPeriod::meterDate).thenComparingInt(MeterPeriod::index))
                .filter(metered).findFirst()
                .map(p -> new Meter(p.contractId(), p.index(),
                        p.recorded() ? MeterStatus.RECORDED
                                : p.meterDate().equals(today) ? MeterStatus.TODAY : MeterStatus.UPCOMING,
                        p.meterDate(), p.endExclusive().atStartOfDay(BillingTime.ZONE).toInstant(),
                        p.row() != null ? BranchOverviewService.invoiceTarget(p.contractId(), p.row().id())
                                : new Target(TargetKind.CONTRACT, p.contractId(),
                                        BranchOverviewService.contractPath(p.contractId())
                                                + "?tab=payment&section=monthly")))
                .orElse(null);
    }

    /** At least one charge billed by reading and not part of the rent; unreadable terms prove nothing. */
    static boolean metered(String chargesSnapshot) {
        if (chargesSnapshot == null || chargesSnapshot.isBlank()) return false;
        try {
            for (JsonNode charge : JSON.readTree(chargesSnapshot)) {
                String method = charge.path("billingMethod").asText();
                if (("PER_KWH".equals(method) || "PER_M3".equals(method))
                        && !charge.path("includedInRent").asBoolean(false)) return true;
            }
        } catch (JsonProcessingException unreadable) {
            return false;
        }
        return false;
    }

    /** The expiry line of a lease: active, moved in, not handed back, 1 to 60 days before its exclusive end. */
    static Expiry expiry(BranchLeaseRow lease, LocalDate today) {
        LocalDate end = lease.endExclusive();
        if (lease.status() != ContractStatus.ACTIVE || end == null || lease.handedOverAt() != null
                || lease.moveInDate() == null || lease.moveInDate().isAfter(today)) return null;
        long days = ChronoUnit.DAYS.between(today, end);
        if (days < 1 || days > 60) return null;
        return new Expiry(lease.id(), days <= 30 ? ExpiryStatus.WITHIN_30 : ExpiryStatus.WITHIN_60, end, (int) days,
                new Target(TargetKind.CONTRACT, lease.id(),
                        BranchOverviewService.contractPath(lease.id()) + "?tab=end"));
    }
}
