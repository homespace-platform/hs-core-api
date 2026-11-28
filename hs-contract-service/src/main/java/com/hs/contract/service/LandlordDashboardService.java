package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchAttentionResponse.Priority;
import com.hs.contract.dto.branch.BranchAttentionResponse.Work;
import com.hs.contract.dto.branch.BranchAttentionResponse.WorkType;
import com.hs.contract.dto.branch.BranchOverviewResponse.RoomStatus;
import com.hs.contract.dto.dashboard.LandlordDashboardResponse;
import com.hs.contract.dto.dashboard.LandlordDashboardResponse.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.LandlordDashboardReadRepository;
import com.hs.contract.repository.LandlordDashboardDetailsRepository;
import com.hs.contract.repository.LandlordDashboardReadRepository.ExpiringListing;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * Landlord dashboard in one read: the attention scan of every branch is reused (never recomputed), the other
 * numbers are grouped, owner-scoped queries. Each group fails alone ({@code errors}); nothing is written or locked.
 * Deposits are held money, not revenue: they never enter received, refunded, expected or overdue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LandlordDashboardService {
    /** Groups of the {@code include} parameter. */
    public static final Set<String> GROUPS =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("setup", "money", "todo", "contracts", "rooms",
                    "leads", "complaints", "membership")));
    /** Owner decision D8. */
    static final int EXPIRING_LISTING_DAYS = 7;
    static final int TODO_LIMIT = 10;
    static final int EXPIRING_CONTRACT_LIMIT = 10;
    static final int EXPIRING_LISTING_LIMIT = 5;
    static final int VISIT_LIMIT = 5;
    private static final Set<PaymentType> REVENUE =
            EnumSet.of(PaymentType.INITIAL, PaymentType.MONTHLY_RENT, PaymentType.UTILITY, PaymentType.OTHER_CHARGE);
    /** Allocation lines of held money: the deposit paid with the first payment and the deposit refunded. */
    private static final Set<String> DEPOSIT_LINES = Set.of("DEPOSIT", "DEPOSIT_REFUND");

    private final BranchAttentionService attention;
    private final LandlordDashboardReadRepository reads;
    private final MembershipService membership;
    private final BillingTime billingTime;
    private final LandlordDashboardDetailsRepository details;
    private final RentalOnboardingService onboarding;

    /** @param month null for the current month; @param include null for every group */
    public LandlordDashboardResponse summary(String ownerId, YearMonth month, Set<String> include) {
        Instant now = billingTime.now();
        Instant realNow = Instant.now();
        YearMonth shown = month != null ? month : YearMonth.from(now.atZone(BillingTime.ZONE));
        Set<String> groups = include == null ? GROUPS : include;
        Map<String, Boolean> errors = new LinkedHashMap<>();
        groups.forEach(g -> errors.put(g, false));
        Once<BranchAttentionService.Overview> overview = new Once<>(() -> attention.overview(ownerId));

        Setup setup = groups.contains("setup")
                ? block("setup", errors, () -> new Setup(reads.receivingAccounts(ownerId) > 0)) : null;
        Revenue revenue = groups.contains("money")
                ? block("money", errors, () -> revenue(ownerId, shown, now, realNow, overview.get())) : null;
        Todo todo = groups.contains("todo")
                ? block("todo", errors, () -> todo(ownerId, overview.get(), realNow)) : null;
        Contracts contracts = null;
        List<BranchAttentionService.ExpiringLease> expiringContracts = null;
        if (groups.contains("contracts")) {
            contracts = block("contracts", errors, () -> contracts(ownerId, now));
            expiringContracts = block("contracts", errors,
                    () -> overview.get().expiring().stream().limit(EXPIRING_CONTRACT_LIMIT).toList());
        }
        Rooms rooms = null;
        Listings listings = null;
        List<LandlordDashboardResponse.ExpiringListing> expiringListings = null;
        if (groups.contains("rooms")) {
            rooms = block("rooms", errors, () -> rooms(overview.get().rooms()));
            listings = block("rooms", errors, () -> listings(ownerId, realNow));
            expiringListings = block("rooms", errors, () -> expiringListings(ownerId, realNow));
        }
        List<Visit> appointments = groups.contains("leads")
                ? block("leads", errors, () -> visits(ownerId, realNow)) : null;
        List<Competing> competing = groups.contains("leads")
                ? block("leads", errors, () -> details.competing(ownerId, now)) : null;
        List<Hold> holds = groups.contains("leads")
                ? block("leads", errors, () -> holds(ownerId, now)) : null;
        Complaints complaints = groups.contains("complaints")
                ? block("complaints", errors, () -> details.complaints(ownerId)) : null;
        Membership quota = groups.contains("membership")
                ? block("membership", errors, () -> quota(ownerId, shown, realNow)) : null;
        return new LandlordDashboardResponse(now, realNow, shown.toString(), errors, setup, rooms, listings, revenue,
                todo, contracts, expiringContracts, appointments, expiringListings, quota, competing, holds, complaints);
    }

    /* ---------- money ---------- */

    private Revenue revenue(String ownerId, YearMonth month, Instant now, Instant realNow, BranchAttentionService.Overview overview) {
        Instant from = month.atDay(1).atStartOfDay(BillingTime.ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(BillingTime.ZONE).toInstant();
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal deposit = BigDecimal.ZERO;
        long receipts = 0;
        for (Object[] row : reads.receiptTotals(ownerId, from, to)) {
            var type = (PaymentType) row[0];
            var direction = (PaymentDirection) row[1];
            if (direction == PaymentDirection.TENANT_TO_LANDLORD && REVENUE.contains(type)) {
                received = received.add(money(row[3]));
                receipts += (Long) row[2];
            } else if (direction == PaymentDirection.TENANT_TO_LANDLORD && type == PaymentType.RESERVATION_DEPOSIT) {
                deposit = deposit.add(money(row[3]));
            } else if (direction == PaymentDirection.LANDLORD_TO_TENANT && type == PaymentType.INITIAL_PAYMENT_REFUND) {
                refunded = refunded.add(money(row[3]));
            }
        }
        for (Object[] row : reads.receiptDepositLines(ownerId, from, to, DEPOSIT_LINES)) {
            var type = (PaymentType) row[0];
            var direction = (PaymentDirection) row[1];
            if (direction == PaymentDirection.TENANT_TO_LANDLORD && type == PaymentType.INITIAL) {
                received = received.subtract(money(row[2]));
                deposit = deposit.add(money(row[2]));
            } else if (direction == PaymentDirection.LANDLORD_TO_TENANT && type == PaymentType.INITIAL_PAYMENT_REFUND) {
                refunded = refunded.subtract(money(row[2]));
            }
        }

        BigDecimal expected = rentAndFees(reads.expectedTotals(ownerId, REVENUE, from, to), 1,
                reads.expectedDepositLines(ownerId, REVENUE, from, to, DEPOSIT_LINES));
        // The attention scan already resolves invoice/payment pairing, extensions and replaced work.
        var overdueIds = overview.allWork().stream().filter(w -> w.type() == WorkType.PAYMENT_OVERDUE)
                .map(w -> w.key().substring("PAYMENT_OVERDUE:".length())).toList();
        BigDecimal overdue = BranchOverviewService.batched(overdueIds, ids -> List.of(details.overdueAmount(ownerId, ids)))
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdueCount = overdueIds.size();

        Pending confirm = none();
        Pending rejected = none();
        Pending refunds = none();
        for (Object[] row : reads.waitingOnOwner(ownerId)) {
            var direction = (PaymentDirection) row[0];
            var status = (PaymentStatus) row[1];
            if (direction == PaymentDirection.LANDLORD_TO_TENANT) {
                refunds = add(refunds, row, (Instant) row[5]);
            } else if (status == PaymentStatus.TRANSFER_REPORTED) {
                confirm = add(confirm, row, (Instant) row[4]);
            } else {
                rejected = add(rejected, row, (Instant) row[5]);
            }
        }
        var extra = details.money(ownerId, now, realNow, from, to);
        return new Revenue(received.subtract(refunded), refunded, expected, receipts, overdueCount, overdue, deposit, confirm, rejected,
                refunds, extra.lateFromPrevious(), extra.autoConfirmed(), extra.lateFee(), extra.rolled(), extra.pdfIssues());
    }

    /** Sum of the column minus the deposit allocation lines of first payments. */
    private static BigDecimal rentAndFees(List<Object[]> totals, int sumColumn, List<Object[]> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Object[] row : totals) sum = sum.add(money(row[sumColumn]));
        for (Object[] row : lines) if (row[0] == PaymentType.INITIAL) sum = sum.subtract(money(row[1]));
        return sum;
    }

    private static Pending none() {
        return new Pending(0, BigDecimal.ZERO, null);
    }

    private static Pending add(Pending sum, Object[] row, Instant due) {
        Instant next = sum.nextDueAt() == null ? due : due == null ? sum.nextDueAt()
                : due.isBefore(sum.nextDueAt()) ? due : sum.nextDueAt();
        return new Pending(sum.count() + (Long) row[2], sum.vnd().add(money(row[3])), next);
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    /* ---------- work ---------- */

    private Todo todo(String ownerId, BranchAttentionService.Overview overview, Instant realNow) {
        List<Work> all = overview.allWork();
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (String g : List.of("RENTAL_REQUEST", "VIEWING", "SIGN", "PAYMENT_CONFIRM", "OVERDUE", "METER",
                "AMENDMENT", "OTHER", "UNBRANCHED_RENTAL", "UNBRANCHED_VIEWING")) groups.put(g, 0);
        all.forEach(w -> groups.merge(group(w.type()), 1, Integer::sum));
        // The attention list skips requests and viewings of listings outside a branch, so nothing is counted twice.
        LocalDateTime real = LocalDateTime.ofInstant(realNow, BillingTime.ZONE);
        int rentals = (int) reads.unbranchedRentalRequests(ownerId, overview.billingNow());
        int visits = (int) reads.unbranchedAppointments(ownerId, real.toLocalDate(), real.toLocalTime());
        groups.put("UNBRANCHED_RENTAL", rentals);
        groups.put("UNBRANCHED_VIEWING", visits);
        var extra = details.extraWork(ownerId, overview.billingNow(), realNow,
                LocalDate.ofInstant(overview.billingNow(), BillingTime.ZONE));
        for (String g : List.of("DEFAULT", "COMPLAINT", "CLOSURE", "SIGN")) groups.putIfAbsent(g, 0);
        extra.groups().forEach((type, count) -> groups.merge(extraGroup(type), count, Integer::sum));
        List<TodoItem> items = new ArrayList<>(all.stream().map(w -> new TodoItem(w.key(), w.type().name(), w.roomCode(),
                w.contextLabel(), w.dueAt(), w.clock(), w.priority(), w.target().href())).toList());
        items.addAll(extra.items());
        if (rentals > 0) items.add(new TodoItem("UNBRANCHED_RENTAL", "UNBRANCHED_RENTAL", null,
                "Yêu cầu thuê chưa thuộc chi nhánh", null, com.hs.contract.dto.branch.BranchOverviewResponse.Clock.BILLING,
                Priority.NORMAL, "/dashboard/rental-requests"));
        if (visits > 0) items.add(new TodoItem("UNBRANCHED_VIEWING", "UNBRANCHED_VIEWING", null,
                "Lịch hẹn chưa thuộc chi nhánh", null, com.hs.contract.dto.branch.BranchOverviewResponse.Clock.REAL,
                Priority.NORMAL, "/dashboard/viewing-schedules"));
        items.sort(Comparator.comparing(TodoItem::priority).thenComparingLong(i -> i.dueAt() == null ? Long.MAX_VALUE
                : Duration.between(i.clock() == com.hs.contract.dto.branch.BranchOverviewResponse.Clock.BILLING
                    ? overview.billingNow() : realNow, i.dueAt()).toMillis()).thenComparing(TodoItem::key));
        return new Todo(all.size() + rentals + visits + extra.groups().values().stream().mapToInt(Integer::intValue).sum(),
                (int) all.stream().filter(w -> w.priority() == Priority.URGENT).count() + extra.urgent(), groups,
                items.stream().limit(TODO_LIMIT).toList());
    }

    private static String extraGroup(String type) {
        return switch(type) {
            case "DEFAULT_NOTICE" -> "DEFAULT";
            case "COMPLAINT_RESPOND" -> "COMPLAINT";
            case "CLOSURE_ACTION" -> "CLOSURE";
            default -> "SIGN";
        };
    }

    static String group(WorkType type) {
        return switch (type) {
            case RENTAL_REQUEST_PENDING -> "RENTAL_REQUEST";
            case VIEWING_PENDING -> "VIEWING";
            case OWNER_SIGN, SIGNING_REOPEN -> "SIGN";
            case PAYMENT_CONFIRM, PAYMENT_REVIEW, OWNER_REFUND -> "PAYMENT_CONFIRM";
            case PAYMENT_OVERDUE, PAYMENT_EXTENSION_REQUEST, TERMINATION_RESPONSE -> "OVERDUE";
            case METER_UPCOMING, METER_TODAY, DRAFT_MISSED, METER_AUTOFILLED -> "METER";
            case AMENDMENT_RESPONSE -> "AMENDMENT";
            default -> "OTHER";
        };
    }

    /* ---------- rooms, listings, contracts ---------- */

    private static Rooms rooms(Map<RoomStatus, Integer> by) {
        return new Rooms(by.values().stream().mapToInt(Integer::intValue).sum(), count(by, RoomStatus.OCCUPIED),
                count(by, RoomStatus.VACANT, RoomStatus.UNLINKED, RoomStatus.ADVERTISED),
                count(by, RoomStatus.RESERVED, RoomStatus.MOVING_IN),
                count(by, RoomStatus.EXTERNAL_RENT, RoomStatus.REPAIR), by);
    }

    private static int count(Map<RoomStatus, Integer> by, RoomStatus... statuses) {
        return Arrays.stream(statuses).mapToInt(st -> by.getOrDefault(st, 0)).sum();
    }

    private Listings listings(String ownerId, Instant realNow) {
        int published = 0;
        int pending = 0;
        int draft = 0;
        Instant oldest = null;
        for (Object[] row : reads.listingCounts(ownerId)) {
            var status = (ListingStatus) row[0];
            int count = ((Long) row[2]).intValue();
            if (effective(status, (ListingStatus) row[1]) == ListingStatus.PUBLISHED) published += count;
            if (status == ListingStatus.PENDING_REVIEW) {
                pending += count;
                Instant submitted = (Instant) row[3];
                if (submitted != null && (oldest == null || submitted.isBefore(oldest))) oldest = submitted;
            }
            if (status == ListingStatus.DRAFT) draft += count;
        }
        // A published listing is live only before its expires_at (no expiry date means no limit).
        for (Object[] row : reads.pastExpiryListingCounts(ownerId, realNow)) {
            if (effective((ListingStatus) row[0], (ListingStatus) row[1]) == ListingStatus.PUBLISHED) {
                published -= ((Long) row[2]).intValue();
            }
        }
        return new Listings(published, pending, draft, oldest, details.unplaced(ownerId), details.needsFix(ownerId));
    }

    private static ListingStatus effective(ListingStatus status, ListingStatus review) {
        return status == ListingStatus.PENDING_REVIEW && review != null ? review : status;
    }

    private List<LandlordDashboardResponse.ExpiringListing> expiringListings(String ownerId, Instant realNow) {
        Instant until = realNow.plus(EXPIRING_LISTING_DAYS, ChronoUnit.DAYS);
        LocalDate today = LocalDate.ofInstant(realNow, BillingTime.ZONE);
        var listings = reads.expiringListings(ownerId, realNow, until, PageRequest.of(0, EXPIRING_LISTING_LIMIT));
        var signals = details.listingSignals(ownerId, listings.stream().map(ExpiringListing::id).toList(), realNow);
        return listings.stream()
                .map((ExpiringListing l) -> new LandlordDashboardResponse.ExpiringListing(l.id(), l.title(),
                        l.expiresAt(), (int) ChronoUnit.DAYS.between(today,
                        LocalDate.ofInstant(l.expiresAt(), BillingTime.ZONE)),
                        l.viewCount() == null ? 0 : l.viewCount(),
                        "/dashboard/properties/view?id=" + BranchOverviewService.encode(l.id()),
                        signals.getOrDefault(l.id(), new LandlordDashboardDetailsRepository.ListingSignals(0,false)).favorites(),
                        signals.getOrDefault(l.id(), new LandlordDashboardDetailsRepository.ListingSignals(0,false)).noLeads())).toList();
    }

    private Contracts contracts(String ownerId, Instant now) {
        Map<String, Integer> by = new LinkedHashMap<>(Map.of("ACTIVE", 0, "SIGNING", 0, "DRAFT", 0));
        for (Object[] row : reads.contractCounts(ownerId)) {
            String key = switch ((ContractStatus) row[0]) {
                case ACTIVE -> "ACTIVE";
                case LANDLORD_SIGNATURE_PENDING, TENANT_SIGNATURE_PENDING -> "SIGNING";
                case DRAFT, PENDING_REVIEW -> "DRAFT";
                default -> null;
            };
            if (key != null) by.merge(key, ((Long) row[1]).intValue(), Integer::sum);
        }
        return new Contracts(by, details.closures(ownerId, LocalDate.ofInstant(now, BillingTime.ZONE)),
                details.signatureIssues(ownerId), details.depositAgreements(ownerId));
    }

    /* ---------- leads, membership ---------- */

    private List<Visit> visits(String ownerId, Instant realNow) {
        LocalDate today = LocalDate.ofInstant(realNow, BillingTime.ZONE);
        return reads.upcomingVisits(ownerId, today, LocalTime.ofInstant(realNow, BillingTime.ZONE),
                PageRequest.of(0, VISIT_LIMIT)).stream().map(v -> {
            boolean proposed = Boolean.TRUE.equals(v.rescheduleRequested()) && v.proposedDate() != null
                    && v.proposedStartTime() != null;
            Instant at = LocalDateTime.of(proposed ? v.proposedDate() : v.date(),
                    proposed ? v.proposedStartTime() : v.startTime()).atZone(BillingTime.ZONE).toInstant();
            return new Visit(v.id(), at, v.listingTitle(), v.renterName(), v.status(),
                    "/dashboard/viewing-schedules?appointmentId=" + BranchOverviewService.encode(v.id()));
        }).filter(v -> !v.at().isBefore(realNow)).sorted(Comparator.comparing(Visit::at).thenComparing(Visit::id))
                .limit(VISIT_LIMIT).toList();
    }

    private List<Hold> holds(String ownerId, Instant now) {
        return details.holds(ownerId, now).stream().map(h -> {
            var next = onboarding.get(h.id(), ownerId).nextAction();
            return new Hold(h.id(), h.title(), h.renterName(), h.expiresAt(), next == null ? "NONE" : next.actor(),
                    "/dashboard/rental-requests?requestId=" + BranchOverviewService.encode(h.id()));
        }).toList();
    }

    private Membership quota(String ownerId, YearMonth month, Instant now) {
        var v = membership.view(ownerId);
        var orders = details.orders(ownerId, now, month.atDay(1).atStartOfDay(BillingTime.ZONE).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(BillingTime.ZONE).toInstant());
        return new Membership(v.tier(), v.remainingUses(), v.reservedUses(), v.expiresAt(), v.quotaResetsAt(),
                v.addonRemaining(), v.addonReserved(), v.addonExpiresAt(), v.unlimited(), orders.pending(),
                orders.expiresAt(), orders.failed(), orders.paid());
    }

    /* ---------- plumbing ---------- */

    private static <T> T block(String group, Map<String, Boolean> errors, Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException failed) {
            log.warn("Landlord dashboard group {} failed: {}", group, failed.getClass().getSimpleName());
            errors.put(group, true);
            return null;
        }
    }

    /** Runs the source once; a failure is replayed to every group that needs it. */
    private static final class Once<T> {
        private final Supplier<T> source;
        private boolean done;
        private T value;
        private RuntimeException failure;

        Once(Supplier<T> source) {
            this.source = source;
        }

        T get() {
            if (!done) {
                done = true;
                try {
                    value = source.get();
                } catch (RuntimeException failed) {
                    failure = failed;
                }
            }
            if (failure != null) throw failure;
            return value;
        }
    }
}
