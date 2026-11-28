package com.hs.contract.service.analytics;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.analytics.LandlordAnalyticsQuery;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse.*;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.model.BranchContractAssignment;
import com.hs.contract.model.Contract;
import com.hs.contract.repository.BranchAttentionReadRepository;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.contract.service.analytics.LandlordAnalyticsQueries.Scope;
import com.hs.contract.service.BranchRoomSchedule;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.repository.PropertyBranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * One aggregated, read-only view for the landlord analytics page. The owner always comes from the verified identity;
 * a branch or listing of somebody else is a 404. Money follows P1/P3: rent and fees by receipt (deposits excluded),
 * Asia/Ho_Chi_Minh months, due dates on the billing clock, view/appointment/request timestamps on the real clock.
 */
@Service
@RequiredArgsConstructor
public class LandlordAnalyticsService {
    public static final int MAX_MONTHS = 24;
    public static final int DEFAULT_MONTHS = 12;
    public static final int MAX_PAGE_SIZE = 50;
    static final int TOP_DEBTS = 10;
    private static final int BATCH = 500;
    private static final int MAX_PAGE = 10_000;

    private final PropertyBranchRepository branches;
    private final LandlordAnalyticsQueries queries;
    private final BranchAttentionReadRepository reads;
    private final BranchContractAssignmentRepository assignments;
    private final BillingTime billingTime;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LandlordAnalyticsResponse analytics(String ownerId, LandlordAnalyticsQuery q) {
        Instant now = billingTime.now();
        LocalDate today = LocalDate.ofInstant(now, BillingTime.ZONE);
        YearMonth to = q.to() != null ? q.to() : YearMonth.from(today);
        YearMonth from = q.from() != null ? q.from() : to.minusMonths(DEFAULT_MONTHS - 1);
        Set<String> include = q.include() == null || q.include().isEmpty() ? LandlordAnalyticsQuery.SECTIONS : q.include();
        int page = q.listingPage() <= 0 ? 1 : q.listingPage();
        int size = q.listingSize() <= 0 ? 10 : q.listingSize();
        long months = ChronoUnit.MONTHS.between(from, to) + 1;
        if (from.isAfter(to) || months > MAX_MONTHS || size > MAX_PAGE_SIZE || page > MAX_PAGE
                || !LandlordAnalyticsQuery.SECTIONS.containsAll(include)) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        if (q.branchId() != null && branches.findByIdAndOwnerIdAndActiveTrue(q.branchId(), ownerId).isEmpty()) {
            throw new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND);
        }
        if (q.listingId() != null && !queries.listingOwned(ownerId, q.listingId())) {
            throw new AppException(404, "Không tìm thấy tin đăng", HttpStatus.NOT_FOUND);
        }
        Scope scope = new Scope(ownerId, q.branchId(), q.listingId());
        Instant start = from.atDay(1).atStartOfDay(BillingTime.ZONE).toInstant();
        Instant end = to.plusMonths(1).atDay(1).atStartOfDay(BillingTime.ZONE).toInstant();

        return new LandlordAnalyticsResponse(now, from.toString(), to.toString(), queries.receiptsSince(ownerId),
                include.contains("revenue") ? revenue(scope, from, to, start, end) : null,
                include.contains("occupancy") ? occupancy(scope, from, to, today) : null,
                include.contains("receivables") ? receivables(scope, now, today) : null,
                include.contains("listings") ? listings(scope, from, to, start, end, page, size, q.noLeadsOnly()) : null,
                include.contains("funnel") ? funnel(scope, from, to, start, end) : null);
    }

    private List<RevenueMonth> revenue(Scope s, YearMonth from, YearMonth to, Instant start, Instant end) {
        Map<String, LandlordAnalyticsQueries.ReceiptMonth> receipts = queries.receipts(s, start, end).stream()
                .collect(Collectors.toMap(LandlordAnalyticsQueries.ReceiptMonth::month, r -> r));
        Map<String, LandlordAnalyticsQueries.DueMonth> due = queries.due(s, start, end).stream()
                .collect(Collectors.toMap(LandlordAnalyticsQueries.DueMonth::month, r -> r));
        return revenue(from, to, receipts, due, queries.lateFees(s, start, end), queries.rolledOver(s, start, end),
                queries.membership(s.ownerId(), start, end));
    }

    /** Zero-filled series, one row per month of the range. */
    static List<RevenueMonth> revenue(YearMonth from, YearMonth to,
            Map<String, LandlordAnalyticsQueries.ReceiptMonth> receipts,
            Map<String, LandlordAnalyticsQueries.DueMonth> due, Map<String, BigDecimal> lateFees,
            Map<String, BigDecimal> rolledOver, Map<String, BigDecimal> membership) {
        List<RevenueMonth> out = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            String key = m.toString();
            var r = receipts.get(key);
            var d = due.get(key);
            BigDecimal received = r == null ? BigDecimal.ZERO : vnd(r.received());
            BigDecimal refunded = r == null ? BigDecimal.ZERO : vnd(r.refunded());
            out.add(new RevenueMonth(key, received, refunded, received.subtract(refunded), d == null ? BigDecimal.ZERO : vnd(d.due()),
                    d == null ? BigDecimal.ZERO : vnd(d.collected()), r == null ? BigDecimal.ZERO : vnd(r.lateCollected()),
                    vnd(lateFees.get(key)), vnd(rolledOver.get(key)), vnd(membership.get(key))));
        }
        return out;
    }

    static BigDecimal vnd(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<OccupancyMonth> occupancy(Scope s, YearMonth from, YearMonth to, LocalDate today) {
        List<String> branchIds = s.branchId() != null ? List.of(s.branchId()) : branches.findActiveIds(s.ownerId());
        if (branchIds.isEmpty()) return OccupancyCalc.monthly(from, to, Set.of(), List.of(), Map.of(), today);
        var slotRows = reads.findSlots(branchIds);
        Set<String> rooms = slotRows.stream().map(row -> (BranchSlot) row[1])
                .filter(slot -> slot.getRoomCode() != null).map(BranchSlot::getId).collect(Collectors.toSet());
        if (s.listingId() != null) {
            // With a listing the block shows only the rooms linked to it; no link, nothing to show.
            Set<String> linked = reads.findRoomLinks(branchIds).stream()
                    .filter(l -> s.listingId().equals(l.listingId())).map(BranchAttentionReadRepository.RoomLink::roomId)
                    .collect(Collectors.toSet());
            rooms.retainAll(linked);
            if (rooms.isEmpty()) return List.of();
        }
        Map<String, Contract> roots = reads.findManagedRoots(branchIds, s.ownerId()).stream()
                .collect(Collectors.toMap(Contract::getId, c -> c, (a, b) -> a));
        List<BranchLeaseRow> leases = new ArrayList<>();
        List<String> ids = List.copyOf(roots.keySet());
        for (int i = 0; i < ids.size(); i += BATCH) {
            leases.addAll(assignments.findLeaseRows(ids.subList(i, Math.min(ids.size(), i + BATCH))));
        }
        Map<String, String> stored = new HashMap<>();
        if (!roots.isEmpty()) {
            for (BranchContractAssignment a : reads.findAssignments(branchIds)) {
                if (roots.containsKey(a.getContractId()) && a.getRoomId() != null) stored.put(a.getContractId(), a.getRoomId());
            }
        }
        var placed = BranchRoomSchedule.shownRooms(leases, stored, today);
        if (s.listingId() != null) return OccupancyCalc.monthly(from, to, rooms, leases, placed, today);
        Map<String, LandlordAnalyticsQueries.OccupancySnapshot> frozen = queries.occupancySnapshots(s.ownerId(), branchIds,
                from.atDay(1), to.atDay(1)).stream().collect(Collectors.toMap(v -> v.branchId() + ":" + v.month(), v -> v));
        Map<String, int[]> totals = new TreeMap<>();
        for (String branch : branchIds) {
            Set<String> branchRooms = slotRows.stream().filter(row -> branch.equals(row[0]))
                    .map(row -> (BranchSlot) row[1]).filter(slot -> slot.getRoomCode() != null)
                    .map(BranchSlot::getId).collect(Collectors.toSet());
            for (var live : OccupancyCalc.monthly(from, to, branchRooms, leases, placed, today)) {
                var snapshot = frozen.get(branch + ":" + live.month());
                int[] sum = totals.computeIfAbsent(live.month(), k -> new int[3]);
                sum[0] += snapshot == null ? live.rooms() : snapshot.rooms();
                sum[1] += snapshot == null ? live.occupiedRoomDays() : snapshot.occupiedRoomDays();
                sum[2] += snapshot == null ? live.roomDays() : snapshot.roomDays();
            }
        }
        return totals.entrySet().stream().map(e -> new OccupancyMonth(e.getKey(), e.getValue()[0], e.getValue()[1],
                e.getValue()[2], e.getValue()[2] == 0 ? null : Math.round(e.getValue()[1] * 1000d / e.getValue()[2]) / 1000d)).toList();
    }

    @Transactional
    public void capturePreviousMonth(String owner, String branch) {
        if (branches.findByIdAndOwnerIdAndActiveTrue(branch, owner).isEmpty()) return;
        LocalDate today = LocalDate.ofInstant(billingTime.now(), BillingTime.ZONE);
        YearMonth month = YearMonth.from(today).minusMonths(1);
        var value = occupancy(new Scope(owner, branch, null), month, month, today).getFirst();
        queries.snapshot(branch, month.atDay(1), value);
    }

    /** D0_7 for 0..7 days late, D8_30 for 8..30, D31_PLUS above. */
    static String bucket(int daysLate) {
        return daysLate <= 7 ? "D0_7" : daysLate <= 30 ? "D8_30" : "D31_PLUS";
    }

    private Receivables receivables(Scope s, Instant now, LocalDate today) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (String key : List.of("D0_7", "D8_30", "D31_PLUS")) amounts.put(key, BigDecimal.ZERO);
        for (var day : queries.debtDays(s, now, today)) {
            String key = bucket(Math.max(day.days(), 0));
            counts.merge(key, day.count(), Integer::sum);
            amounts.merge(key, vnd(day.vnd()), BigDecimal::add);
        }
        List<Bucket> buckets = amounts.entrySet().stream()
                .map(e -> new Bucket(e.getKey(), counts.getOrDefault(e.getKey(), 0), e.getValue())).toList();
        List<Debt> top = queries.topDebts(s, now, TOP_DEBTS).stream().map(r -> new Debt(r.paymentId(),
                r.contractNumber(), r.roomCode(), r.tenantName(), r.dueAt(),
                (int) Math.max(ChronoUnit.DAYS.between(LocalDate.ofInstant(r.dueAt(), BillingTime.ZONE), today), 0),
                vnd(r.amount()), r.status(), href(r))).toList();
        return new Receivables(now, buckets.stream().map(Bucket::vnd).reduce(BigDecimal.ZERO, BigDecimal::add),
                buckets.stream().mapToInt(Bucket::count).sum(), buckets, top);
    }

    private static String href(LandlordAnalyticsQueries.DebtRow r) {
        if (r.contractId() == null || r.invoiceId() == null) return "/dashboard/invoices";
        return "/dashboard/landlord/contracts/" + UriUtils.encode(r.contractId(), StandardCharsets.UTF_8)
                + "?tab=payment&section=monthly&invoice=" + UriUtils.encode(r.invoiceId(), StandardCharsets.UTF_8);
    }

    Listings listings(Scope s, YearMonth from, YearMonth to, Instant start, Instant end, int page, int size,
            boolean noLeadsOnly) {
        Instant realNow = Instant.now();
        long total = queries.countListings(s, noLeadsOnly, realNow);
        var raw = queries.listings(s, noLeadsOnly, realNow, (page - 1) * size, size);
        List<String> ids = raw.stream().map(LandlordAnalyticsQueries.ListingRaw::id).toList();
        var appointments = queries.appointments(s.ownerId(), ids, start, end);
        var requests = queries.requests(s.ownerId(), ids, start, end);
        var contracts = queries.contracts(s.ownerId(), ids, start, end);
        LocalDate viewsSince = queries.viewsSince(s);
        Map<String, Long> views = viewsSince == null ? Map.of()
                : queries.viewsInRange(ids, from.atDay(1), to.atEndOfMonth());
        List<ListingRow> items = raw.stream().map(l -> {
            long req = requests.getOrDefault(l.id(), 0L);
            return new ListingRow(l.id(), l.title(), l.branchName(), l.status(), l.publishedAt(), l.expiresAt(),
                    l.viewCount(), viewsSince == null ? null : views.getOrDefault(l.id(), 0L),
                    appointments.getOrDefault(l.id(), 0L), req, contracts.getOrDefault(l.id(), 0L), l.favorites(),
                    l.noLeads(), l.viewCount() > 0
                            ? BigDecimal.valueOf(req).divide(BigDecimal.valueOf(l.viewCount()), 4, RoundingMode.HALF_UP)
                                    .doubleValue() : null);
        }).toList();
        return new Listings(viewsSince, page, size, total, items,
                queries.dailyViews(s, from.atDay(1), to.atEndOfMonth()));
    }

    private Funnel funnel(Scope s, YearMonth from, YearMonth to, Instant start, Instant end) {
        LocalDate since = queries.viewsSince(s);
        Views views = since == null ? new Views(queries.viewsCumulative(s), true, null)
                : new Views(queries.viewsDaily(s, from.atDay(1), to.atEndOfMonth()), false, since);
        return new Funnel(views, queries.funnelAppointments(s, start, end), queries.funnelRequests(s, start, end),
                queries.funnelContracts(s, start, end));
    }
}
