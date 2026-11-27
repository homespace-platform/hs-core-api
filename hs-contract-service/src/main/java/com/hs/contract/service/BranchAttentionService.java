package com.hs.contract.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.PaymentExtension;
import com.hs.contract.dto.billing.UnilateralTerminationNotice;
import com.hs.contract.dto.branch.*;
import com.hs.contract.dto.branch.BranchAttentionResponse.*;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.Payment;
import com.hs.contract.dto.branch.BranchOverviewResponse.PaymentKind;
import com.hs.contract.dto.branch.BranchOverviewResponse.RoomStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.Target;
import com.hs.contract.dto.branch.BranchOverviewResponse.TargetKind;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.BranchAttentionReadRepository;
import com.hs.contract.repository.BranchAttentionReadRepository.*;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.listing.dto.response.BranchOverviewListingRow;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.listing.service.BranchRoomLinkService;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentTimePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * "Cần chú ý": what a landlord can act on now. {@code collect} is the only place items are built, so the branch list,
 * the card counts and the daily digest (which adds up {@link #summary}) always agree. Read-only: no billing,
 * lifecycle or onboarding sync, no lock; an item exists only while its source condition holds.
 */
@Service
@RequiredArgsConstructor
public class BranchAttentionService {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Set<MonthlyInvoiceStatus> UNSETTLED =
            Set.of(MonthlyInvoiceStatus.UNPAID, MonthlyInvoiceStatus.OVERDUE);
    /** Open and not reported yet: the money has still to move. */
    private static final Set<PaymentStatus> NOT_REPORTED =
            Set.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.REJECTED, PaymentStatus.OVERDUE);
    private static final Set<PaymentType> ONBOARDING =
            Set.of(PaymentType.INITIAL, PaymentType.RESERVATION_DEPOSIT);
    private static final Set<PaymentType> REFUNDS = Set.of(PaymentType.DEPOSIT_REFUND,
            PaymentType.INITIAL_PAYMENT_REFUND, PaymentType.DEPOSIT_ADJUSTMENT);
    private static final Set<ContractStatus> BEFORE_LANDLORD_SIGNATURE = Set.of(ContractStatus.DRAFT,
            ContractStatus.PENDING_REVIEW, ContractStatus.LANDLORD_SIGNATURE_PENDING);
    private static final Set<String> AMENDMENT_STATUSES = Set.of("REQUESTED", "CHANGES_REQUESTED", "APPROVED",
            "SCHEDULED", "APPLIED", "TENANT_REQUESTED", "TENANT_ACCEPTED");
    private static final Set<WorkType> ALWAYS_URGENT =
            EnumSet.of(WorkType.DRAFT_MISSED, WorkType.METER_TODAY, WorkType.PAYMENT_OVERDUE);
    private static final Set<WorkType> AT_LEAST_SOON = EnumSet.of(WorkType.METER_UPCOMING, WorkType.LEASE_EXPIRING,
            WorkType.PAYMENT_CONFIRM, WorkType.PAYMENT_EXTENSION_REQUEST, WorkType.OWNER_REFUND, WorkType.OWNER_SIGN,
            WorkType.AMENDMENT_RESPONSE, WorkType.TERMINATION_RESPONSE, WorkType.PAYMENT_REVIEW,
            WorkType.CANCELLATION_DECISION, WorkType.SIGNING_REOPEN);

    private final PropertyBranchRepository branches;
    private final BranchAttentionReadRepository reads;
    private final BranchContractAssignmentRepository assignments;
    private final PaymentRequestRepository payments;
    private final BranchOverviewService overview;
    private final RentalOnboardingService onboarding;
    private final BillingTime billingTime;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchAttentionResponse attention(String branchId, String ownerId, int page, int size) {
        if (branches.findByIdAndOwnerIdAndActiveTrue(branchId, ownerId).isEmpty()) {
            throw new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND);
        }
        Instant now = billingTime.now();
        Instant realNow = Instant.now();
        List<Work> all = collect(ownerId, List.of(branchId), now, realNow).get(branchId);
        int from = (int) Math.min((long) page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new BranchAttentionResponse(branchId, now, realNow, List.copyOf(all.subList(from, to)), page, size,
                all.size());
    }

    /** Counts of every active branch of the owner. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary(String ownerId) {
        Instant now = billingTime.now();
        Instant realNow = Instant.now();
        Map<String, List<Work>> work = collect(ownerId, branches.findActiveIds(ownerId), now, realNow);
        return new Summary(now, realNow, cardOrder(work, now, realNow));
    }

    /** Most urgent first item, then more items, then id; branches without work come last. */
    static List<BranchCount> cardOrder(Map<String, List<Work>> work, Instant now, Instant realNow) {
        Comparator<Map.Entry<String, List<Work>>> order = Comparator
                .<Map.Entry<String, List<Work>>>comparingInt(e -> e.getValue().isEmpty()
                        ? Priority.values().length : e.getValue().getFirst().priority().ordinal())
                .thenComparingLong(e -> e.getValue().isEmpty()
                        ? Long.MAX_VALUE : remaining(e.getValue().getFirst(), now, realNow))
                .thenComparingInt(e -> -e.getValue().size())
                .thenComparing(Map.Entry::getKey);
        return work.entrySet().stream().sorted(order)
                .map(e -> new BranchCount(e.getKey(), e.getValue().size())).toList();
    }

    private static long remaining(Work work, Instant now, Instant realNow) {
        return remaining(work.dueAt(), work.clock(), now, realNow);
    }

    private static long remaining(Instant dueAt, Clock clock, Instant now, Instant realNow) {
        return dueAt == null ? Long.MAX_VALUE
                : Duration.between(clock == Clock.BILLING ? now : realNow, dueAt).toMillis();
    }

    static Priority priority(WorkType type, long remainingMillis) {
        if (ALWAYS_URGENT.contains(type) || remainingMillis <= Duration.ofHours(24).toMillis()) return Priority.URGENT;
        return AT_LEAST_SOON.contains(type) || remainingMillis <= Duration.ofDays(3).toMillis()
                ? Priority.SOON : Priority.NORMAL;
    }

    /** Everything one call loaded, plus the items found so far per branch (unique by key). */
    private static final class Scope {
        String ownerId;
        Instant now;
        Instant realNow;
        LocalDate today;
        final Map<String, Map<String, Work>> work = new LinkedHashMap<>();
        final Map<String, BranchSlot> rooms = new HashMap<>();
        final Map<String, String> roomBranches = new HashMap<>();
        Map<String, ListingRow> listings;
        final Map<String, String> listingRooms = new HashMap<>();
        Map<String, Contract> roots;
        final Map<String, String> rootBranches = new HashMap<>();
        Map<String, BranchLeaseRow> leases;
        Map<String, String> leaseRooms;
        /** Requests of managed roots and requests of branch listings. */
        final Map<String, RentalRow> rentals = new HashMap<>();
        /** The one managed lease of each request: a living root wins over a cancelled one. */
        final Map<String, Contract> rootByRental = new HashMap<>();
        /** Requests of the managed roots, and accepted requests without any root lease, as the money layer reads them. */
        Map<String, BranchRentalRow> rootRentals;
        List<BranchRentalRow> uncontracted;
        Map<String, Contract> depositDocuments;
        Map<String, List<MonthlyInvoice>> invoices;
        Map<String, PaymentRequest> transfers;
        Map<String, PaymentRequest> transfersByInvoice;
        Map<String, List<ContractAmendment>> amendments;
        List<RentalWorkflowRecord> earlyExits;

        /** Branch of the request: through its lease when there is one, else through its listing. */
        String rentalBranch(RentalRow rental) {
            Contract root = rootByRental.get(rental.id());
            if (root != null) return rootBranches.get(root.getId());
            ListingRow listing = listings.get(rental.listingId());
            return listing == null ? null : listing.branchId();
        }

        String rentalRoom(RentalRow rental) {
            Contract root = rootByRental.get(rental.id());
            return root != null ? leaseRooms.get(root.getId()) : listingRooms.get(rental.listingId());
        }

        /** Lease number, else listing title, else (in {@link #add}) the room code. */
        String rentalLabel(RentalRow rental) {
            Contract root = rootByRental.get(rental.id());
            if (root != null) return root.getContractNumber();
            ListingRow listing = listings.get(rental.listingId());
            return listing == null ? null : listing.title();
        }

        void add(String branchId, WorkType type, String sourceId, String roomId, String label, Integer periodIndex,
                 Instant dueAt, Clock clock, Target target) {
            Map<String, Work> items = work.get(branchId);
            if (items == null) return;
            BranchSlot room = roomId == null ? null : rooms.get(roomId);
            String code = room == null ? null : room.getRoomCode();
            String key = type + ":" + sourceId;
            items.putIfAbsent(key, new Work(key, type, room == null ? null : roomId, code,
                    label != null ? label : code, periodIndex, dueAt, clock,
                    priority(type, remaining(dueAt, clock, now, realNow)), target));
        }

        void addForRoot(String rootId, WorkType type, String sourceId, Integer periodIndex, Instant dueAt,
                        Clock clock, Target target) {
            add(rootBranches.get(rootId), type, sourceId, leaseRooms.get(rootId),
                    roots.get(rootId).getContractNumber(), periodIndex, dueAt, clock, target);
        }
    }

    /** The sorted work of each branch. The caller has verified that the branches are the owner's. */
    private Map<String, List<Work>> collect(String ownerId, List<String> branchIds, Instant now, Instant realNow) {
        Scope s = load(ownerId, branchIds, now, realNow);
        addMeterWork(s);
        Set<String> specificInvoices = addExtensionAndNoticeWork(s);
        Map<String, List<PaymentRequest>> receipts = onboardingReceipts(s);
        addOnboardingDecisions(s, receipts, specificInvoices);
        addMoneyWork(s, specificInvoices);
        addSigningWork(s, receipts);
        addLeaseResponses(s);
        addNewCustomerWork(s);
        addRoomAndDataWork(s);

        Comparator<Work> order = Comparator.comparing(Work::priority)
                .thenComparingLong(w -> remaining(w, now, realNow)).thenComparing(Work::key);
        Map<String, List<Work>> result = new LinkedHashMap<>();
        s.work.forEach((branchId, items) -> result.put(branchId, items.values().stream().sorted(order).toList()));
        return result;
    }

    private Scope load(String ownerId, List<String> branchIds, Instant now, Instant realNow) {
        Scope s = new Scope();
        s.ownerId = ownerId;
        s.now = now;
        s.realNow = realNow;
        s.today = LocalDate.ofInstant(now, BillingTime.ZONE);
        branchIds.forEach(id -> s.work.put(id, new HashMap<>()));

        for (Object[] row : batched(branchIds, reads::findSlots)) {
            BranchSlot slot = (BranchSlot) row[1];
            if (slot.getRoomCode() == null) continue;
            s.rooms.put(slot.getId(), slot);
            s.roomBranches.put(slot.getId(), (String) row[0]);
        }
        s.listings = batched(branchIds, ids -> reads.findListings(ids, ownerId)).stream()
                .collect(Collectors.toMap(ListingRow::id, Function.identity()));
        for (RoomLink link : batched(branchIds, reads::findRoomLinks)) {
            ListingRow listing = s.listings.get(link.listingId());
            if (listing != null && listing.branchId().equals(link.branchId()) && s.rooms.containsKey(link.roomId())) {
                s.listingRooms.put(link.listingId(), link.roomId());
            }
        }

        s.roots = batched(branchIds, ids -> reads.findManagedRoots(ids, ownerId)).stream()
                .collect(Collectors.toMap(Contract::getId, Function.identity()));
        Map<String, String> storedRooms = new HashMap<>();
        if (!s.roots.isEmpty()) {
            for (BranchContractAssignment a : batched(branchIds, reads::findAssignments)) {
                if (!s.roots.containsKey(a.getContractId())) continue;
                s.rootBranches.put(a.getContractId(), a.getManagementBranchId());
                if (a.getRoomId() != null && a.getManagementBranchId().equals(s.roomBranches.get(a.getRoomId()))) {
                    storedRooms.put(a.getContractId(), a.getRoomId());
                }
            }
        }
        s.leases = batched(s.roots.keySet(), assignments::findLeaseRows).stream()
                .collect(Collectors.toMap(BranchLeaseRow::id, Function.identity()));
        s.leaseRooms = BranchRoomSchedule.shownRooms(s.leases.values(), storedRooms, s.today);

        s.roots.values().stream()
                .sorted(Comparator.<Contract, Boolean>comparing(c -> c.getStatus() == ContractStatus.CANCELLED)
                        .thenComparing(Contract::getId))
                .forEach(c -> s.rootByRental.putIfAbsent(c.getRentalRequestId(), c));
        List<RentalRow> rootRentals = batched(s.rootByRental.keySet(), ids -> reads.findRentals(ids, ownerId));
        // A request whose lease is managed elsewhere only comes back here while it still waits for an answer.
        List<RentalRow> listingRentals = batched(s.listings.keySet(),
                ids -> reads.findListingRentals(ids, ownerId, BranchOverviewService.OPEN_PAYMENT));
        rootRentals.forEach(r -> s.rentals.put(r.id(), r));
        listingRentals.forEach(r -> s.rentals.putIfAbsent(r.id(), r));
        s.rootRentals = rootRentals.stream().map(BranchAttentionService::rentalRow)
                .collect(Collectors.toMap(BranchRentalRow::id, Function.identity()));
        s.uncontracted = listingRentals.stream()
                .filter(r -> !s.rootByRental.containsKey(r.id())).map(BranchAttentionService::rentalRow)
                .filter(BranchRentalRow::activeAccepted).toList();

        s.depositDocuments = batched(s.rentals.keySet(), ids -> reads.findDepositDocuments(ids, ownerId)).stream()
                .collect(Collectors.toMap(Contract::getRentalRequestId, Function.identity(), (a, b) -> a));
        s.invoices = batched(s.roots.keySet(), reads::findInvoices).stream()
                .collect(Collectors.groupingBy(MonthlyInvoice::getContractId));
        s.transfers = Stream.concat(
                        batched(s.rentals.keySet(), ids -> payments.findAttentionByRentals(ids,
                                BranchOverviewService.OPEN_PAYMENT, ONBOARDING)).stream(),
                        batched(s.roots.keySet(), ids -> payments.findAttentionByContracts(ids,
                                BranchOverviewService.OPEN_PAYMENT)).stream())
                .collect(Collectors.toMap(PaymentRequest::getId, Function.identity(), (a, b) -> a));
        s.transfersByInvoice = s.transfers.values().stream()
                .filter(p -> Boolean.TRUE.equals(p.getActive()) && p.getInvoiceId() != null)
                .collect(Collectors.toMap(PaymentRequest::getInvoiceId, Function.identity()));
        s.amendments = batched(s.roots.keySet(), ids -> reads.findAmendments(ids, AMENDMENT_STATUSES)).stream()
                .collect(Collectors.groupingBy(ContractAmendment::getContractId));
        s.earlyExits = batched(s.roots.keySet(), reads::findEarlyExits);
        return s;
    }

    /* ---------- utilities ---------- */

    private record MeterPeriod(String contractId, int index, MonthlyInvoice row, String revisionId, LocalDate end,
                               WorkType type) {}

    private void addMeterWork(Scope s) {
        List<MeterPeriod> readings = new ArrayList<>();
        for (BranchLeaseRow lease : s.leases.values()) {
            Contract root = s.roots.get(lease.id());
            List<MonthlyInvoice> regular = s.invoices.getOrDefault(lease.id(), List.of()).stream()
                    .filter(i -> !i.isFinalCostSupplement()).toList();
            boolean billable = BranchOverviewService.billable(lease);
            addAutoFilled(s, root, regular, billable);
            if (!billable) continue;
            for (MonthlyInvoice row : regular) {
                if (row.getStatus() == MonthlyInvoiceStatus.DRAFT
                        && !s.now.isBefore(startOfDay(row.getPeriodEndExclusive()))) {
                    s.addForRoot(lease.id(), WorkType.DRAFT_MISSED, row.getId(), row.getPeriodIndex(),
                            startOfDay(row.getPeriodEndExclusive()), Clock.BILLING,
                            BranchOverviewService.invoiceTarget(lease.id(), row.getId()));
                }
            }
            // Stored rows keep their own window; any stored row, cancelled included, blocks projecting its period.
            Map<Integer, MonthlyInvoice> stored = regular.stream()
                    .collect(Collectors.toMap(MonthlyInvoice::getPeriodIndex, Function.identity()));
            for (var period : BranchBillingPeriods.of(lease.moveInDate(), lease.billingMonths(), lease.endExclusive())) {
                MonthlyInvoice row = stored.get(period.index());
                if (row != null && (row.getStatus() != MonthlyInvoiceStatus.DRAFT
                        || row.getDraftExtraChargesSnapshot() != null)) continue;
                LocalDate start = row == null ? period.start() : row.getPeriodStart();
                LocalDate end = row == null ? period.endExclusive() : row.getPeriodEndExclusive();
                long days = ChronoUnit.DAYS.between(s.today, end.minusDays(1));
                if (days < 0 || days > 3) continue;
                String revisionId = row != null && row.getCostTermsRevisionId() != null ? row.getCostTermsRevisionId()
                        : revisionAt(s, root, start);
                if (revisionId != null) {
                    readings.add(new MeterPeriod(lease.id(), period.index(), row, revisionId, end,
                            days == 0 ? WorkType.METER_TODAY : WorkType.METER_UPCOMING));
                }
            }
        }
        Map<String, RevisionCharges> charges = batched(
                readings.stream().map(MeterPeriod::revisionId).collect(Collectors.toSet()), reads::findRevisionCharges)
                .stream().collect(Collectors.toMap(RevisionCharges::id, Function.identity()));
        for (MeterPeriod reading : readings) {
            RevisionCharges revision = charges.get(reading.revisionId());
            if (revision == null || !revision.belongsTo(reading.contractId())
                    || !BranchMonitoringService.metered(revision.chargesSnapshot())) continue;
            Target target = reading.row() != null
                    ? BranchOverviewService.invoiceTarget(reading.contractId(), reading.row().getId())
                    : new Target(TargetKind.CONTRACT, reading.contractId(),
                            BranchOverviewService.contractPath(reading.contractId()) + "?tab=payment&section=monthly");
            s.addForRoot(reading.contractId(), reading.type(), reading.contractId() + ":" + reading.index(),
                    reading.index(), startOfDay(reading.end()), Clock.BILLING, target);
        }
    }

    /** Only the newest period that reached its reading day, and only while its invoice is not collected. */
    private void addAutoFilled(Scope s, Contract root, List<MonthlyInvoice> regular, boolean billable) {
        regular.stream().filter(i -> !i.getPeriodEndExclusive().minusDays(1).isAfter(s.today))
                .max(Comparator.comparingInt(MonthlyInvoice::getPeriodIndex))
                .filter(i -> i.isElectricityAutoFilled() || i.isWaterAutoFilled())
                .filter(i -> i.getStatus() == MonthlyInvoiceStatus.DRAFT ? billable
                        : UNSETTLED.contains(i.getStatus()) && i.getDueAt() != null && openTransfer(s, root, i) != null)
                .ifPresent(i -> s.addForRoot(root.getId(), WorkType.METER_AUTOFILLED, i.getId(), i.getPeriodIndex(),
                        null, Clock.BILLING, BranchOverviewService.invoiceTarget(root.getId(), i.getId())));
    }

    private static String revisionAt(Scope s, Contract root, LocalDate date) {
        return ContractTermsResolver.selectAmendment(s.amendments.getOrDefault(root.getId(), List.of()), date)
                .map(ContractAmendment::getTermsRevisionId).orElse(root.getCurrentRevisionId());
    }

    /* ---------- money ---------- */

    /** The invoice's own open transfer (same pairing rule as the money layer), or null. */
    private static PaymentRequest openTransfer(Scope s, Contract root, MonthlyInvoice invoice) {
        PaymentRequest transfer = s.transfersByInvoice.get(invoice.getId());
        return transfer != null && BranchOverviewService.matches(row(invoice), root, transfer, s.ownerId)
                && BranchOverviewService.OPEN_PAYMENT.contains(transfer.getStatus()) ? transfer : null;
    }

    private static BranchInvoiceRow row(MonthlyInvoice i) {
        return new BranchInvoiceRow(i.getId(), i.getContractId(), i.getPeriodIndex(), i.getPeriodEndExclusive(),
                i.getStatus(), i.getDueAt(), i.getResolutionMode(), i.getPaymentRequestId(), i.getPeriodStart(),
                i.getCostTermsRevisionId());
    }

    /** Extension requests and termination reviews; returns the invoices whose overdue line they replace. */
    private Set<String> addExtensionAndNoticeWork(Scope s) {
        Set<String> replaced = new HashSet<>();
        for (BranchLeaseRow lease : s.leases.values()) {
            if (!BranchOverviewService.billable(lease)) continue;
            Contract root = s.roots.get(lease.id());
            List<UnilateralTerminationNotice> notices =
                    list(root.getUnilateralTerminationSnapshot(), new TypeReference<>() {});
            // Same test as MonthlyBillingService.activeTerminationProposal.
            boolean terminating = root.getMutualTerminationWorkflowId() != null
                    || root.getTerminationProposedAt() != null && root.getTerminationDeclinedAt() == null
                    && root.getTerminationCancelledAt() == null && root.getTerminationCompletedAt() == null;
            for (MonthlyInvoice invoice : s.invoices.getOrDefault(lease.id(), List.of())) {
                if (!UNSETTLED.contains(invoice.getStatus())) continue;
                PaymentRequest transfer = openTransfer(s, root, invoice);
                if (transfer == null || !NOT_REPORTED.contains(transfer.getStatus())) continue;
                Target target = BranchOverviewService.invoiceTarget(root.getId(), invoice.getId());

                UnilateralTerminationNotice notice = notices.stream()
                        .filter(n -> invoice.getId().equals(n.invoiceId())).reduce((a, b) -> b).orElse(null);
                if (notice != null && notice.status() == UnilateralTerminationNotice.Status.REVIEW_REQUESTED) {
                    // respondBy of a notice is the tenant's deadline, never the landlord's.
                    s.addForRoot(root.getId(), WorkType.TERMINATION_RESPONSE, notice.id(), null, null, Clock.BILLING,
                            target);
                    replaced.add(invoice.getId());
                }

                boolean extended = "PAYMENT_EXTENSION".equals(invoice.getResolutionMode())
                        && invoice.getDueAt() != null && !s.now.isAfter(invoice.getDueAt());
                if (invoice.getDueAt() == null || invoice.getDeferredAt() != null || extended || terminating
                        || invoice.getIssuedAt() != null && s.now.isBefore(invoice.getIssuedAt())) continue;
                List<PaymentExtension> requests =
                        list(invoice.getExtensionRequestsSnapshot(), new TypeReference<>() {});
                for (PaymentExtension request : requests) {
                    if (request.status() == PaymentExtension.Status.PENDING
                            && Objects.equals(root.getTenantId(), request.requestedBy())
                            && request.respondBy() != null && s.now.isBefore(request.respondBy())) {
                        s.addForRoot(root.getId(), WorkType.PAYMENT_EXTENSION_REQUEST, request.id(), null,
                                request.respondBy(), Clock.BILLING, target);
                        replaced.add(invoice.getId());
                    }
                }
            }
        }
        return replaced;
    }

    private void addMoneyWork(Scope s, Set<String> specificInvoices) {
        // The money layer of the overview for every month, with its pairing and status rules.
        List<Payment> items = new ArrayList<>();
        Function<String, List<Payment>> bucket = roomId -> items;
        List<BranchInvoiceRow> unsettled = s.invoices.values().stream().flatMap(List::stream)
                .filter(i -> UNSETTLED.contains(i.getStatus())).map(BranchAttentionService::row).toList();
        overview.addMonthlyPayments(s.roots, s.leases, s.leaseRooms, unsettled, s.ownerId, null, s.now, bucket);
        overview.addInitialPayments(s.roots, s.leaseRooms, s.rootRentals, s.uncontracted, s.listingRooms, s.ownerId,
                null, s.now, bucket);
        overview.addSettlementBalances(s.roots, s.leaseRooms, s.ownerId, null, s.now, bucket);
        overview.addDepositAdjustments(s.roots, s.leaseRooms, s.ownerId, null, s.now, bucket);

        for (Payment item : items) {
            boolean initial = item.kind() == PaymentKind.INITIAL;
            boolean overdue = item.status() == BranchOverviewResponse.PaymentStatus.OVERDUE;
            // Reported first-rent receipts are handled with the other onboarding receipts below.
            boolean reported = !initial
                    && item.status() == BranchOverviewResponse.PaymentStatus.PENDING_CONFIRMATION;
            if (!reported && !(overdue && !specificInvoices.contains(item.id()))) continue;
            String paymentId = initial ? item.id().substring("first-rent:".length())
                    : item.kind() == PaymentKind.MONTHLY ? s.transfersByInvoice.get(item.id()).getId() : item.id();
            String sourceId = overdue && item.kind() == PaymentKind.MONTHLY ? item.id() : paymentId;
            WorkType type = overdue ? WorkType.PAYMENT_OVERDUE : WorkType.PAYMENT_CONFIRM;
            Instant dueAt = overdue ? item.dueAt() : item.confirmationDueAt();
            if (item.contractId() != null) {
                s.addForRoot(item.contractId(), type, sourceId, null, dueAt, item.clock(), item.target());
            } else {
                RentalRow rental = s.rentals.get(item.target().id());
                s.add(s.rentalBranch(rental), type, sourceId, s.rentalRoom(rental), s.rentalLabel(rental), null, dueAt,
                        item.clock(), item.target());
            }
        }

        for (PaymentRequest payment : s.transfers.values()) {
            if (!Boolean.TRUE.equals(payment.getActive())) continue;
            if (ONBOARDING.contains(payment.getType()) && payment.getStatus() == PaymentStatus.TRANSFER_REPORTED) {
                addReceiptConfirmation(s, payment);
            } else if (REFUNDS.contains(payment.getType()) && NOT_REPORTED.contains(payment.getStatus())
                    && payment.getDirection() == PaymentDirection.LANDLORD_TO_TENANT
                    && s.ownerId.equals(payment.getPayerId()) && payment.getTotalAmount() != null
                    && payment.getTotalAmount().signum() > 0) {
                addRefund(s, payment);
            }
        }
    }

    private void addReceiptConfirmation(Scope s, PaymentRequest payment) {
        RentalRow rental = payment.getRentalRequestId() == null ? null : s.rentals.get(payment.getRentalRequestId());
        if (rental == null || !isReceiptOf(s, rental, payment)) return;
        Contract root = s.rootByRental.get(rental.id());
        Contract deposit = s.depositDocuments.get(rental.id());
        Target target;
        if (payment.getType() == PaymentType.RESERVATION_DEPOSIT) {
            target = deposit != null ? contractTab(deposit.getId(), "payment")
                    : BranchOverviewService.rentalTarget(rental.id());
        } else if (root == null) {
            target = BranchOverviewService.rentalTarget(rental.id());
        } else if (BranchOverviewService.PRE_EFFECTIVE.contains(root.getStatus())
                || root.getStatus() == ContractStatus.CANCELLED) {
            target = contractTab(root.getId(), "payment");
        } else {
            target = BranchOverviewService.invoiceTarget(root.getId(), "first-rent:" + payment.getId());
        }
        s.add(s.rentalBranch(rental), WorkType.PAYMENT_CONFIRM, payment.getId(), s.rentalRoom(rental),
                s.rentalLabel(rental), null, payment.getConfirmationDueAt(), clockOf(payment), target);
    }

    /** Money of this request: the tenant pays this landlord, and any named document is the request's own. */
    private static boolean isReceiptOf(Scope s, RentalRow rental, PaymentRequest payment) {
        Contract root = s.rootByRental.get(rental.id());
        return payment.getDirection() == PaymentDirection.TENANT_TO_LANDLORD
                && s.ownerId.equals(payment.getPayeeId()) && payment.getInvoiceId() == null
                && Objects.equals(rental.renterId(), payment.getPayerId())
                && (root == null || Objects.equals(root.getTenantId(), payment.getPayerId()))
                && (payment.getContractId() == null || isDocument(payment.getContractId(), root)
                || isDocument(payment.getContractId(), s.depositDocuments.get(rental.id())));
    }

    private void addRefund(Scope s, PaymentRequest payment) {
        Contract byContract = payment.getContractId() == null ? null : s.roots.get(payment.getContractId());
        String rentalId = payment.getRentalRequestId() != null ? payment.getRentalRequestId()
                : byContract == null ? null : byContract.getRentalRequestId();
        RentalRow rental = rentalId == null ? null : s.rentals.get(rentalId);
        if (rental == null || !Objects.equals(rental.renterId(), payment.getPayeeId())) return;
        Contract root = s.rootByRental.get(rental.id());
        if (byContract != null && !rental.id().equals(byContract.getRentalRequestId())
                || byContract == null && payment.getContractId() != null
                && !isDocument(payment.getContractId(), s.depositDocuments.get(rental.id()))
                || root != null && !Objects.equals(root.getTenantId(), payment.getPayeeId())) return;
        s.add(s.rentalBranch(rental), WorkType.OWNER_REFUND, payment.getId(), s.rentalRoom(rental),
                s.rentalLabel(rental), null, payment.getDueAt(), clockOf(payment),
                BranchOverviewService.paymentTarget(payment.getId()));
    }

    private static boolean isDocument(String contractId, Contract document) {
        return document != null && document.getId().equals(contractId);
    }

    private static Clock clockOf(PaymentRequest payment) {
        return PaymentTimePolicy.usesBillingTime(payment) ? Clock.BILLING : Clock.REAL;
    }

    private static BranchRentalRow rentalRow(RentalRow r) {
        return new BranchRentalRow(r.id(), r.listingId(), r.renterId(), r.renterName(), r.moveInDate(), r.status(),
                r.active());
    }

    /* ---------- before the tenancy ---------- */

    private static Map<String, List<PaymentRequest>> onboardingReceipts(Scope s) {
        return s.transfers.values().stream()
                .filter(p -> ONBOARDING.contains(p.getType()) && s.rentals.containsKey(p.getRentalRequestId())
                        && isReceiptOf(s, s.rentals.get(p.getRentalRequestId()), p))
                .collect(Collectors.groupingBy(PaymentRequest::getRentalRequestId));
    }

    /** A cancellation to decide, or a rejected proof to reconcile (which replaces that receipt's overdue line). */
    private void addOnboardingDecisions(Scope s, Map<String, List<PaymentRequest>> receipts, Set<String> replaced) {
        for (RentalRow rental : s.rentals.values()) {
            List<PaymentRequest> own = receipts.getOrDefault(rental.id(), List.of());
            PaymentRequest deposit = ofType(own, PaymentType.RESERVATION_DEPOSIT);
            PaymentRequest rent = ofType(own, PaymentType.INITIAL);
            RentalOnboardingService.landlordDecisionStep(rental.status() == RentalRequestStatus.ACCEPTED,
                    closing(rental), rental.cancellationRequestedAt() != null, deposit, rent, s.now).ifPresent(step -> {
                if (step.code() == RentalOnboardingService.NextActionCode.DECIDE_CANCELLATION) {
                    addForRental(s, rental, WorkType.CANCELLATION_DECISION, rental.id(), null);
                    return;
                }
                // An expired confirmation window is already listed as PAYMENT_CONFIRM.
                for (PaymentRequest payment : Arrays.asList(deposit, rent)) {
                    if (payment != null && payment.getStatus() == PaymentStatus.REJECTED) {
                        addForRental(s, rental, WorkType.PAYMENT_REVIEW, payment.getId(), null);
                        replaced.add("first-rent:" + payment.getId());
                    }
                }
            });
        }
    }

    private static boolean closing(RentalRow rental) {
        return rental.defaultCompletedAt() != null || rental.cancellationRefundApprovedAt() != null
                || rental.resolutionWorkflowId() != null;
    }

    /** The buttons of these steps are on the rental request page. */
    private static void addForRental(Scope s, RentalRow rental, WorkType type, String sourceId, Instant dueAt) {
        s.add(s.rentalBranch(rental), type, sourceId, s.rentalRoom(rental), s.rentalLabel(rental), null, dueAt,
                Clock.BILLING, BranchOverviewService.rentalTarget(rental.id()));
    }

    /* ---------- signing ---------- */

    private void addSigningWork(Scope s, Map<String, List<PaymentRequest>> receipts) {
        List<Contract> children = batched(s.roots.keySet(),
                ids -> reads.findChildDocuments(ids, s.ownerId, BEFORE_LANDLORD_SIGNATURE));
        List<String> documentIds = Stream.of(s.roots.values(), s.depositDocuments.values(), children)
                .flatMap(Collection::stream).filter(c -> BEFORE_LANDLORD_SIGNATURE.contains(c.getStatus()))
                .map(Contract::getId).toList();
        Set<String> signed = batched(documentIds, ids -> reads.findLandlordSignatures(ids, s.ownerId)).stream()
                .map(x -> x.contractId() + ':' + x.revisionId()).collect(Collectors.toSet());
        Predicate<Contract> unsigned = c -> c != null && BEFORE_LANDLORD_SIGNATURE.contains(c.getStatus())
                && c.getCurrentRevisionId() != null && !signed.contains(c.getId() + ':' + c.getCurrentRevisionId());

        // Lease and deposit agreement: only while onboarding says it is the landlord's turn.
        for (RentalRow rental : s.rentals.values()) {
            Contract lease = s.rootByRental.get(rental.id());
            Contract deposit = s.depositDocuments.get(rental.id());
            if (rental.status() != RentalRequestStatus.ACCEPTED || lease == null && deposit == null) continue;
            List<PaymentRequest> own = receipts.getOrDefault(rental.id(), List.of());
            ListingStatus listing = rental.listingStatus() == ListingStatus.PENDING_REVIEW
                    && rental.listingReviewStatus() != null ? rental.listingReviewStatus() : rental.listingStatus();
            var facts = new RentalOnboardingService.SigningFacts(true,
                    closing(rental) || rental.cancellationRequestedAt() != null,
                    listing == ListingStatus.RESERVED, rental.moveInDate(), rental.depositAmount(),
                    ofType(own, PaymentType.RESERVATION_DEPOSIT), ofType(own, PaymentType.INITIAL), deposit, lease);
            onboarding.landlordSigningOverdue(facts, s.now).ifPresent(overdue -> addForRental(s, rental,
                    WorkType.SIGNING_REOPEN, overdue.document().getId(), overdue.dueAt()));
            if (!unsigned.test(lease) && !unsigned.test(deposit)) continue;
            onboarding.landlordSigningStep(facts, s.now).ifPresent(step -> {
                Contract document = step.code() == RentalOnboardingService.NextActionCode.SIGN_DEPOSIT_LANDLORD
                        ? deposit : lease;
                if (unsigned.test(document)) {
                    s.add(s.rentalBranch(rental), WorkType.OWNER_SIGN, document.getId(), s.rentalRoom(rental),
                            s.rentalLabel(rental), null, step.dueAt(), Clock.BILLING,
                            contractTab(document.getId(), "sign"));
                }
            });
        }
        addChildSigning(s, children.stream().filter(unsigned).toList());
    }

    private static PaymentRequest ofType(List<PaymentRequest> receipts, PaymentType type) {
        return receipts.stream().filter(p -> p.getType() == type).findFirst().orElse(null);
    }

    /** An annex is signable only while the amendment, renewal or early exit it was prepared from is still approved. */
    private void addChildSigning(Scope s, List<Contract> children) {
        if (children.isEmpty()) return;
        Set<String> parents = children.stream().map(Contract::getParentContractId).collect(Collectors.toSet());
        List<ContractRenewal> openRenewals = batched(parents, reads::findOpenRenewals);
        Map<String, ContractRenewal> renewals = openRenewals.stream()
                .filter(r -> "APPROVED".equals(r.getStatus()) && r.getAgreementContractId() != null)
                .collect(Collectors.toMap(ContractRenewal::getAgreementContractId, Function.identity(), (a, b) -> a));
        Map<String, WorkflowSigningDocument> bindings = batched(
                children.stream().map(Contract::getId).toList(), reads::findWorkflowDocuments).stream()
                .collect(Collectors.toMap(WorkflowSigningDocument::getSigningContractId, Function.identity()));
        Map<String, RentalWorkflowRecord> exits = s.earlyExits.stream()
                .collect(Collectors.toMap(RentalWorkflowRecord::getId, Function.identity()));
        // An amendment waits while a renewal is open or an earlier deposit top-up is not confirmed.
        Set<String> amendmentBlocked = openRenewals.stream()
                .filter(r -> r.getSignBy() != null && s.now.isBefore(r.getSignBy()))
                .map(ContractRenewal::getContractId).collect(Collectors.toSet());
        batched(parents, ids -> payments.findByContractIdInAndType(ids, PaymentType.DEPOSIT_ADJUSTMENT)).stream()
                .filter(p -> p.getStatus() != PaymentStatus.CONFIRMED)
                .forEach(p -> amendmentBlocked.add(p.getContractId()));
        List<String> complaintKeys = bindings.isEmpty() ? List.of()
                : s.earlyExits.stream().filter(w -> s.rentals.containsKey(w.getRentalRequestId()))
                        .map(w -> complaintKey(w.getRentalRequestId())).toList();
        Set<String> complaints = new HashSet<>(batched(complaintKeys, reads::findActiveComplaintKeys));

        for (Contract child : children) {
            Contract root = s.roots.get(child.getParentContractId());
            if (!Objects.equals(root.getRentalRequestId(), child.getRentalRequestId())
                    || !Objects.equals(root.getTenantId(), child.getTenantId())) continue;
            ContractAmendment amendment = s.amendments.getOrDefault(root.getId(), List.of()).stream()
                    .filter(a -> "APPROVED".equals(a.getStatus()) && child.getId().equals(a.getAgreementContractId()))
                    .findFirst().orElse(null);
            ContractRenewal renewal = renewals.get(child.getId());
            Instant signBy = amendment != null ? amendment.getSignBy()
                    : renewal != null && root.getId().equals(renewal.getContractId()) ? renewal.getSignBy() : null;
            String href = "/dashboard/contracts/" + BranchOverviewService.encode(child.getId());
            if (signBy != null) {
                if (root.getStatus() != ContractStatus.ACTIVE || !s.now.isBefore(signBy)) continue;
                BranchLeaseRow lease = s.leases.get(root.getId());
                if (amendment != null
                        ? amendmentBlocked.contains(root.getId()) || !amendmentSignable(s, root, lease, amendment)
                        : !renewalSignable(root, lease, renewal)) {
                    continue;
                }
                href += "?tab=sign";
            } else if (!earlyExitSignable(s, root, child, bindings.get(child.getId()), exits, complaints)) {
                continue;
            }
            s.addForRoot(root.getId(), WorkType.OWNER_SIGN, child.getId(), null, signBy, Clock.BILLING,
                    new Target(TargetKind.CONTRACT, child.getId(), href));
        }
    }

    /**
     * The conditions of ContractAmendmentService.validateSigning that stored rows can prove. Not checked (they need
     * provider, storage, capacity or identity calls): signature mode, parking capacity, listing source, parties' KYC
     * and the PDF. A missing fact keeps the item: hiding due work is worse than a refused click.
     */
    private static boolean amendmentSignable(Scope s, Contract root, BranchLeaseRow lease, ContractAmendment a) {
        // Same test as blocked(): unlike billing, a completed proposal still blocks.
        if (a.isImmediateEffect() || root.getMutualTerminationWorkflowId() != null
                || root.getTerminationProposedAt() != null && root.getTerminationCancelledAt() == null
                && root.getTerminationDeclinedAt() == null
                || a.getBaseRevisionId() != null && !a.getBaseRevisionId().equals(revisionAt(s, root, s.today))) {
            return false;
        }
        LocalDate moveIn = lease.moveInDate();
        Integer months = lease.effectiveLeaseMonths() != null ? lease.effectiveLeaseMonths() : lease.leaseMonths();
        boolean termKnown = moveIn != null && months != null;
        if (termKnown && !s.today.isBefore(moveIn.plusMonths(months))) return false;
        LocalDate date = a.getEffectiveOn();
        if (date == null) return true;
        // ContractAmendmentService.dates: a future rent period start that no issued invoice has reached.
        if (termKnown && (!s.now.isBefore(startOfDay(date))
                || IntStream.range(1, months).noneMatch(n -> moveIn.plusMonths(n).equals(date)))) return false;
        if (s.invoices.getOrDefault(root.getId(), List.of()).stream()
                .anyMatch(b -> b.getStatus() != MonthlyInvoiceStatus.DRAFT && (reaches(b.getPeriodStart(), date)
                        || reaches(b.getPeriodEndExclusive(), date)
                        || b.getRentPeriodEndExclusive() != null && b.getRentPeriodEndExclusive().isAfter(date)))) {
            return false;
        }
        return replacementSignable(s, root, a, date);
    }

    private static boolean reaches(LocalDate boundary, LocalDate date) {
        return boundary != null && !boundary.isBefore(date);
    }

    /** A replacement keeps the date of the signed, not yet applied annex it replaces. */
    private static boolean replacementSignable(Scope s, Contract root, ContractAmendment a, LocalDate date) {
        Map<String, ContractAmendment> history = s.amendments.getOrDefault(root.getId(), List.of()).stream()
                .collect(Collectors.toMap(ContractAmendment::getId, Function.identity(), (x, y) -> x));
        Set<String> seen = new HashSet<>();
        String previousId = a.getPreviousProposalId();
        // shortcut: a link whose status is not loaded ends the walk (item kept); load those if it ever matters.
        while (previousId != null && seen.add(previousId) && history.containsKey(previousId)) {
            ContractAmendment old = history.get(previousId);
            if (old.getSignedAt() != null) {
                return "SCHEDULED".equals(old.getStatus()) && !old.isImmediateEffect()
                        && date.equals(old.getEffectiveOn()) && s.today.isBefore(date);
            }
            previousId = old.getPreviousProposalId();
        }
        return true;
    }

    /** The stored conditions of ContractLifecycleService.validateAgreementSigning for a renewal. */
    private static boolean renewalSignable(Contract root, BranchLeaseRow lease, ContractRenewal renewal) {
        boolean terminating = root.getMutualTerminationWorkflowId() != null
                || root.getTerminationProposedAt() != null && root.getTerminationCancelledAt() == null
                && root.getTerminationDeclinedAt() == null && root.getTerminationCompletedAt() == null;
        return !terminating && (renewal.getStartsOn() == null || lease.endExclusive() == null
                || lease.endExclusive().equals(renewal.getStartsOn()));
    }

    private static String complaintKey(String rentalId) {
        return "RENTAL:" + rentalId + ":TERMINATION";
    }

    /** Read-only form of WorkflowDocumentService.validateSigning for a source that is still approved. */
    private static boolean earlyExitSignable(Scope s, Contract root, Contract child, WorkflowSigningDocument binding,
            Map<String, RentalWorkflowRecord> exits, Set<String> complaints) {
        if (binding == null || !WorkflowDocumentService.applies(child)) return false;
        RentalWorkflowRecord source = exits.get(binding.getWorkflowId());
        if (source == null || !"APPROVED".equals(source.getStatus()) || source.getRevision() == 0
                || !root.getId().equals(source.getContractId())
                || !Objects.equals(root.getRentalRequestId(), source.getRentalRequestId())
                || !Objects.equals(root.getListingId(), child.getListingId()) || root.getSignedAt() == null
                || root.getStatus() != ContractStatus.ACTIVE && root.getStatus() != ContractStatus.EXPIRED
                || !Objects.equals(source.getSourceRevisionId(), revisionAt(s, root, s.today))
                || complaints.contains(complaintKey(source.getRentalRequestId()))) return false;
        try {
            if (!binding.getContentHash().equals(WorkflowDocumentService.hash(source))) return false;
        } catch (IllegalStateException unreadable) {
            return false;
        }
        LocalDate end = endExclusive(source.getPayloadJson());
        return end != null && !end.minusDays(1).isBefore(s.today);
    }

    /** Stored payloads hold the date as text or as a [year, month, day] array. */
    private static LocalDate endExclusive(String payload) {
        JsonNode node = tree(payload);
        if (node == null) return null;
        JsonNode end = node.path("endExclusive");
        try {
            return end.isArray() ? LocalDate.of(end.path(0).asInt(), end.path(1).asInt(), end.path(2).asInt())
                    : end.isTextual() ? LocalDate.parse(end.asText()) : null;
        } catch (DateTimeException unreadable) {
            return null;
        }
    }

    /* ---------- amendments, termination, expiry ---------- */

    private void addLeaseResponses(Scope s) {
        for (BranchLeaseRow lease : s.leases.values()) {
            Contract root = s.roots.get(lease.id());
            LocalDate end = lease.endExclusive();
            if (root.getStatus() != ContractStatus.ACTIVE || end == null) continue;
            if (s.today.isBefore(end)) {
                List<ContractAmendment> history = s.amendments.getOrDefault(lease.id(), List.of());
                for (ContractAmendment a : history) {
                    if (waitsForLandlord(a, s.ownerId) && a.getRespondBy() != null && s.now.isBefore(a.getRespondBy())
                            && history.stream().noneMatch(next -> a.getId().equals(next.getTenantRequestId())
                                    && ContractAmendmentService.PENDING.contains(next.getStatus()))) {
                        s.addForRoot(lease.id(), WorkType.AMENDMENT_RESPONSE, a.getId(), null, a.getRespondBy(),
                                Clock.BILLING, new Target(TargetKind.AMENDMENT, a.getId(),
                                        BranchOverviewService.contractPath(lease.id()) + "?tab=end&amendmentId="
                                                + BranchOverviewService.encode(a.getId())));
                    }
                }
            }
            // Same selector as the contract layer, limited to the 30/60-day bands still ahead.
            var expiry = BranchMonitoringService.expiry(lease, s.today);
            if (expiry != null && expiry.status() != ExpiryStatus.LONG && expiry.daysRemaining() >= 1) {
                s.addForRoot(lease.id(), WorkType.LEASE_EXPIRING, lease.id(), null, startOfDay(end), Clock.BILLING,
                        expiry.target());
            }
        }
        for (RentalWorkflowRecord exit : s.earlyExits) {
            Contract root = s.roots.get(exit.getContractId());
            RentalRow rental = s.rentals.get(root.getRentalRequestId());
            // An ended lease leaves nothing to answer.
            if ("PROPOSED".equals(exit.getStatus()) && rental != null
                    && rental.id().equals(exit.getRentalRequestId())
                    && Objects.equals(rental.renterId(), exit.getProposedBy())
                    && !BranchRoomSchedule.historical(s.leases.get(root.getId()))) {
                s.addForRoot(root.getId(), WorkType.TERMINATION_RESPONSE, exit.getId(), null, exit.getRespondBy(),
                        Clock.BILLING, new Target(TargetKind.WORKFLOW, exit.getId(),
                                BranchOverviewService.contractPath(root.getId()) + "?tab=end&focus=early-exit"));
            }
        }
    }

    private static boolean waitsForLandlord(ContractAmendment a, String ownerId) {
        return switch (a.getStatus()) {
            case "REQUESTED", "TENANT_REQUESTED", "TENANT_ACCEPTED" -> ownerId.equals(a.getRecipientId());
            case "CHANGES_REQUESTED" -> ownerId.equals(a.getProposerId());
            default -> false;
        };
    }

    /* ---------- new customers ---------- */

    private void addNewCustomerWork(Scope s) {
        for (RentalRow rental : s.rentals.values()) {
            ListingRow listing = s.listings.get(rental.listingId());
            if (listing != null && rental.status() == RentalRequestStatus.PENDING
                    && Boolean.TRUE.equals(rental.active())
                    && (rental.respondBy() == null || s.now.isBefore(rental.respondBy()))) {
                s.add(listing.branchId(), WorkType.RENTAL_REQUEST_PENDING, rental.id(),
                        s.listingRooms.get(listing.id()), listing.title(), null, rental.respondBy(), Clock.BILLING,
                        BranchOverviewService.rentalTarget(rental.id()));
            }
        }
        LocalDateTime real = LocalDateTime.ofInstant(s.realNow, BillingTime.ZONE);
        for (Appointment visit : batched(s.listings.keySet(), ids -> reads.findOpenAppointments(ids, s.ownerId))) {
            // Same expiry condition as findExpiredPendingAppointments, without waiting for its job.
            if (visit.status() == AppointmentStatus.PENDING
                    && LocalDateTime.of(visit.date(), visit.endTime()).isBefore(real)) continue;
            boolean proposed = Boolean.TRUE.equals(visit.rescheduleRequested()) && visit.proposedDate() != null
                    && visit.proposedStartTime() != null;
            Instant at = LocalDateTime.of(proposed ? visit.proposedDate() : visit.date(),
                    proposed ? visit.proposedStartTime() : visit.startTime()).atZone(BillingTime.ZONE).toInstant();
            ListingRow listing = s.listings.get(visit.listingId());
            s.add(listing.branchId(), WorkType.VIEWING_PENDING, visit.id(), s.listingRooms.get(listing.id()),
                    listing.title(), null, at, Clock.REAL, new Target(TargetKind.APPOINTMENT, visit.id(),
                            "/dashboard/viewing-schedules?appointmentId=" + BranchOverviewService.encode(visit.id())));
        }
    }

    /* ---------- rooms, listings, unplaced data ---------- */

    private void addRoomAndDataWork(Scope s) {
        Map<String, BranchOverviewService.RoomFacts> facts = new HashMap<>();
        s.rooms.keySet().forEach(id -> facts.put(id, new BranchOverviewService.RoomFacts()));
        s.listingRooms.forEach((listingId, roomId) -> {
            ListingRow listing = s.listings.get(listingId);
            facts.get(roomId).linkedListings.add(overviewRow(listing));
            if (overviewRow(listing).publiclyListed(s.realNow)) facts.get(roomId).publicListings.add(listingId);
        });
        BranchOverviewService.addStays(s.roots, s.leases, s.leaseRooms, s.rootRentals, s.uncontracted,
                s.listingRooms, facts, s.today);

        s.rooms.forEach((roomId, slot) -> {
            var room = facts.get(roomId);
            // Both "no listing linked" and "linked but not shown" count: the attention item set is unchanged.
            RoomStatus status = BranchOverviewService.room(roomId, BranchRoomLinkService.flagOf(slot), room.occupants,
                    room.holds, room.upcoming, room.publicListings, room.linkedListings, List.of(), s.today).status();
            if (status == RoomStatus.VACANT || status == RoomStatus.UNLINKED) {
                String branchId = s.roomBranches.get(roomId);
                s.add(branchId, WorkType.ROOM_VACANT_UNLISTED, roomId, roomId, null, null, null, Clock.REAL,
                        new Target(TargetKind.ROOM, roomId, "/dashboard/properties/new?branchId="
                                + BranchOverviewService.encode(branchId) + "&roomId="
                                + BranchOverviewService.encode(roomId)));
            }
        });

        // Listings that a lease of that same listing occupies today.
        Set<String> rentedListings = s.leases.values().stream()
                .filter(l -> !BranchRoomSchedule.historical(l) && BranchRoomSchedule.living(l, s.today))
                .map(l -> s.rootBranches.get(l.id()) + ':' + s.roots.get(l.id()).getListingId())
                .collect(Collectors.toSet());
        for (ListingRow listing : s.listings.values()) {
            String roomId = s.listingRooms.get(listing.id());
            if (roomId == null) {
                s.add(listing.branchId(), WorkType.UNPLACED_LISTING, listing.id(), null, listing.title(), null, null,
                        Clock.REAL, trayTarget(listing.branchId()));
            }
            ListingStatus status = listing.status() == ListingStatus.PENDING_REVIEW
                    && listing.reviewOperationalStatus() != null ? listing.reviewOperationalStatus() : listing.status();
            boolean expired = status == ListingStatus.EXPIRED || status == ListingStatus.PUBLISHED
                    && listing.expiresAt() != null && !s.realNow.isBefore(listing.expiresAt());
            boolean replaced = roomId != null
                    ? !facts.get(roomId).publicListings.isEmpty() || !facts.get(roomId).occupants.isEmpty()
                    : rentedListings.contains(listing.branchId() + ':' + listing.id());
            if ((expired || status == ListingStatus.REJECTED) && !replaced) {
                s.add(listing.branchId(), expired ? WorkType.LISTING_EXPIRED : WorkType.LISTING_REJECTED, listing.id(),
                        roomId, listing.title(), null, null, Clock.REAL, new Target(TargetKind.LISTING, listing.id(),
                                "/dashboard/properties/view?id=" + BranchOverviewService.encode(listing.id())));
            }
        }
        for (BranchLeaseRow lease : s.leases.values()) {
            if (!BranchRoomSchedule.historical(lease) && !s.leaseRooms.containsKey(lease.id())) {
                s.addForRoot(lease.id(), WorkType.UNPLACED_CONTRACT, lease.id(), null, null, Clock.BILLING,
                        trayTarget(s.rootBranches.get(lease.id())));
            }
        }
    }

    private static BranchOverviewListingRow overviewRow(ListingRow l) {
        return new BranchOverviewListingRow(l.id(), l.status(), l.reviewOperationalStatus(), l.expiresAt());
    }

    /* ---------- helpers ---------- */

    private static Target contractTab(String contractId, String tab) {
        return new Target(TargetKind.CONTRACT, contractId,
                BranchOverviewService.contractPath(contractId) + "?tab=" + tab);
    }

    private static Target trayTarget(String branchId) {
        return new Target(TargetKind.TRAY, branchId,
                "/dashboard/branches/" + BranchOverviewService.encode(branchId) + "?tab=map#unplaced");
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(BillingTime.ZONE).toInstant();
    }

    private static JsonNode tree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }

    /** An unreadable snapshot or row proves nothing, so it yields no item. */
    private static <T> List<T> list(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<T> values = JSON.readValue(json, type);
            return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
        } catch (JsonProcessingException unreadable) {
            return List.of();
        }
    }

    private static <T> List<T> batched(Collection<String> ids, Function<List<String>, List<T>> query) {
        return BranchOverviewService.batched(ids, query);
    }
}
