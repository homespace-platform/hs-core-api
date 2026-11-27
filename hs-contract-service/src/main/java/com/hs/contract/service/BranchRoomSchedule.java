package com.hs.contract.service;

import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.model.constant.ContractStatus;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Pure room-schedule rules. Nothing here writes: conflicts caused by time passing are only projected. */
public final class BranchRoomSchedule {
    private static final Set<ContractStatus> ENDED =
            Set.of(ContractStatus.CANCELLED, ContractStatus.CLOSED, ContractStatus.TERMINATED);

    private BranchRoomSchedule() {}

    /** Ended or handed-over leases keep their link for history but never occupy a room. */
    public static boolean historical(BranchLeaseRow lease) {
        return ENDED.contains(lease.status()) && !lease.awaitingHandover() || lease.handedOverAt() != null;
    }

    static boolean living(BranchLeaseRow lease, LocalDate today) {
        return (lease.status() == ContractStatus.ACTIVE || lease.status() == ContractStatus.EXPIRED
                || lease.awaitingHandover())
                && lease.moveInDate() != null && !lease.moveInDate().isAfter(today);
    }

    /** End used for conflicts: someone still living there past the end date blocks the room through today. */
    private static LocalDate blockedUntil(BranchLeaseRow lease, LocalDate today) {
        LocalDate end = lease.endExclusive();
        return living(lease, today) && !end.isAfter(today) ? today.plusDays(1) : end;
    }

    public static boolean overlaps(BranchLeaseRow a, BranchLeaseRow b, LocalDate today) {
        if (historical(a) || historical(b) || a.endExclusive() == null || b.endExclusive() == null
                || a.moveInDate() == null || b.moveInDate() == null) return false;
        return a.moveInDate().isBefore(blockedUntil(b, today)) && b.moveInDate().isBefore(blockedUntil(a, today));
    }

    /** Effective room of each lease (id to room id); a lease that is absent belongs to the unplaced tray. */
    public static Map<String, String> shownRooms(Collection<BranchLeaseRow> leases, Map<String, String> storedRooms,
                                                 LocalDate today) {
        Map<String, String> shown = new HashMap<>();
        leases.stream().filter(l -> storedRooms.get(l.id()) != null)
                .collect(Collectors.groupingBy(l -> storedRooms.get(l.id())))
                .forEach((roomId, inRoom) -> placed(inRoom, today).forEach(l -> shown.put(l.id(), roomId)));
        return shown;
    }

    /**
     * Leases of one room that keep their place. The current occupant wins, then the earlier start, then the id;
     * every other overlapping lease is shown in the unplaced tray.
     */
    public static List<BranchLeaseRow> placed(Collection<BranchLeaseRow> inRoom, LocalDate today) {
        List<BranchLeaseRow> ordered = new ArrayList<>(inRoom);
        ordered.sort(Comparator.<BranchLeaseRow, Boolean>comparing(l -> !living(l, today))
                .thenComparing(BranchLeaseRow::moveInDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(BranchLeaseRow::id));
        List<BranchLeaseRow> kept = new ArrayList<>();
        for (BranchLeaseRow lease : ordered) {
            if (kept.stream().noneMatch(other -> overlaps(lease, other, today))) kept.add(lease);
        }
        return kept;
    }
}
