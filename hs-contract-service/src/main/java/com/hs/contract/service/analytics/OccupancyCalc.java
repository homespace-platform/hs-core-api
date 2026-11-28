package com.hs.contract.service.analytics;

import com.hs.common.time.BillingTime;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse.OccupancyMonth;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.model.constant.ContractStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * occupancy(m) = room-days with a tenant / (rooms x days of m). A lease holds its room from move-in (inclusive) to
 * the lease end (exclusive, ContractLeaseCalc), or to the handover day when the room was taken back earlier. A lease
 * that ran past its end without handover keeps the room through today. The current month counts only up to today;
 * a month that has not started has no room-days. Rooms with several leases on one day count once.
 */
public final class OccupancyCalc {
    private static final Set<ContractStatus> COUNTED =
            Set.of(ContractStatus.ACTIVE, ContractStatus.EXPIRED, ContractStatus.CLOSED, ContractStatus.TERMINATED);

    private OccupancyCalc() {}

    /** @param leaseRooms lease id to room id (BranchRoomSchedule.shownRooms); leases without a room are ignored. */
    public static List<OccupancyMonth> monthly(YearMonth from, YearMonth to, Set<String> rooms,
            Collection<BranchLeaseRow> leases, Map<String, String> leaseRooms, LocalDate today) {
        List<OccupancyMonth> months = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            int days = m.isAfter(current) ? 0 : m.equals(current) ? today.getDayOfMonth() : m.lengthOfMonth();
            int roomDays = rooms.size() * days;
            Map<String, BitSet> occupied = new HashMap<>();
            if (days > 0) {
                LocalDate first = m.atDay(1);
                LocalDate endExclusive = first.plusDays(days);
                for (BranchLeaseRow lease : leases) {
                    String room = leaseRooms.get(lease.id());
                    LocalDate start = lease.moveInDate();
                    LocalDate end = end(lease, today);
                    if (room == null || !rooms.contains(room) || start == null || end == null
                            || !COUNTED.contains(lease.status())) continue;
                    LocalDate s = start.isAfter(first) ? start : first;
                    LocalDate e = end.isBefore(endExclusive) ? end : endExclusive;
                    BitSet bits = occupied.computeIfAbsent(room, k -> new BitSet(days));
                    for (LocalDate d = s; d.isBefore(e); d = d.plusDays(1)) bits.set(d.getDayOfMonth() - 1);
                }
            }
            int count = occupied.values().stream().mapToInt(BitSet::cardinality).sum();
            Double rate = roomDays == 0 ? null : Math.round(count * 1000d / roomDays) / 1000d;
            months.add(new OccupancyMonth(m.toString(), rooms.size(), count, roomDays, rate));
        }
        return months;
    }

    private static LocalDate end(BranchLeaseRow lease, LocalDate today) {
        LocalDate end = lease.endExclusive();
        if (end == null) return null;
        if (lease.handedOverAt() != null) {
            LocalDate handover = LocalDate.ofInstant(lease.handedOverAt(), BillingTime.ZONE);
            return handover.isBefore(end) ? handover : end;
        }
        boolean stillThere = lease.status() == ContractStatus.EXPIRED || lease.awaitingHandover();
        return stillThere && !end.isAfter(today) ? today.plusDays(1) : end;
    }
}
