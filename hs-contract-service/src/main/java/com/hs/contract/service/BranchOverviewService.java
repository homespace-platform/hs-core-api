package com.hs.contract.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.*;
import com.hs.contract.dto.branch.BranchOverviewResponse.*;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.model.BranchContractAssignment;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractAmendment;
import com.hs.contract.model.ContractClosure;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.listing.dto.response.BranchOverviewListingRow;
import com.hs.listing.model.BranchRoomListing;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.repository.BranchRoomListingRepository;
import com.hs.listing.repository.BranchSlotRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.listing.service.BranchRoomLinkService;
import com.hs.payment.model.PaymentLineItem;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentTimePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Room, money, meter and contract layers of one branch. Read-only: it never calls billing, lifecycle or onboarding services
 * (they lock and sync); every state is derived from batch reads of one snapshot.
 */
@Service
@RequiredArgsConstructor
public class BranchOverviewService {
    private static final int BATCH = 500;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DUE_SOON_WINDOW = Duration.ofHours(72);
    private static final String PAYMENT_EXTENSION = "PAYMENT_EXTENSION";
    static final Set<ContractStatus> PRE_EFFECTIVE = Set.of(ContractStatus.DRAFT,
            ContractStatus.PENDING_REVIEW, ContractStatus.LANDLORD_SIGNATURE_PENDING,
            ContractStatus.TENANT_SIGNATURE_PENDING);
    static final Set<PaymentStatus> OPEN_PAYMENT = Set.of(PaymentStatus.AWAITING_TRANSFER,
            PaymentStatus.REJECTED, PaymentStatus.OVERDUE, PaymentStatus.TRANSFER_REPORTED);

    private final PropertyBranchRepository branches;
    private final BranchSlotRepository slots;
    private final BranchRoomListingRepository roomListings;
    private final BranchContractAssignmentRepository assignments;
    private final MonthlyInvoiceRepository invoices;
    private final PaymentRequestRepository payments;
    private final PaymentLineItemRepository lineItems;
    private final BillingTime billingTime;

    /** A lease or an accepted request seen as someone staying (or about to stay) in a room. */
    record StayRow(String id, LocalDate moveInDate, String tenantName, Target target) {}

    private static final Comparator<StayRow> STAY_ORDER =
            Comparator.comparing(StayRow::moveInDate).thenComparing(StayRow::id);

    static final class RoomFacts {
        final List<StayRow> occupants = new ArrayList<>();
        final List<StayRow> holds = new ArrayList<>();
        final List<StayRow> upcoming = new ArrayList<>(); // signed leases that have not started
        final List<String> publicListings = new ArrayList<>();
        final List<BranchOverviewListingRow> linkedListings = new ArrayList<>();
        final List<Payment> payments = new ArrayList<>();
    }

    /** @param month null means the month of the billing clock. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchOverviewResponse overview(String branchId, String ownerId, YearMonth month) {
        if (branches.findByIdAndOwnerIdAndActiveTrue(branchId, ownerId).isEmpty()) {
            throw new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND);
        }
        Instant now = billingTime.now();
        Instant realNow = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, BillingTime.ZONE);
        YearMonth selected = month != null ? month : YearMonth.from(today);

        Map<String, BranchSlot> roomSlots = slots.findAllByBranchIdOrderByPosition(branchId).stream()
                .filter(s -> s.getRoomCode() != null)
                .collect(Collectors.toMap(BranchSlot::getId, Function.identity(), (a, b) -> a, TreeMap::new));
        Map<String, RoomFacts> facts = new HashMap<>();
        roomSlots.keySet().forEach(id -> facts.put(id, new RoomFacts()));

        // Listings of the branch and the room each one is linked to (a link into another branch is ignored).
        Map<String, BranchOverviewListingRow> listings = roomListings.findOverviewListings(branchId, ownerId).stream()
                .collect(Collectors.toMap(BranchOverviewListingRow::id, Function.identity()));
        Map<String, String> listingRooms = roomListings.findAllInBranch(branchId).stream()
                .filter(k -> listings.containsKey(k.getListingId()) && facts.containsKey(k.getRoomId()))
                .collect(Collectors.toMap(BranchRoomListing::getListingId, BranchRoomListing::getRoomId));
        listingRooms.forEach((listingId, roomId) -> {
            facts.get(roomId).linkedListings.add(listings.get(listingId));
            if (listings.get(listingId).publiclyListed(realNow)) facts.get(roomId).publicListings.add(listingId);
        });

        // Root leases managed here, on the same effective-room mapping as the assignments view.
        Map<String, Contract> roots = assignments.findManagedRoots(branchId, ownerId).stream()
                .collect(Collectors.toMap(Contract::getId, Function.identity()));
        Map<String, BranchLeaseRow> leases = batched(roots.keySet(), assignments::findLeaseRows).stream()
                .collect(Collectors.toMap(BranchLeaseRow::id, Function.identity()));
        Map<String, String> storedRooms = new HashMap<>();
        if (!roots.isEmpty()) {
            for (BranchContractAssignment a : assignments.findAllByManagementBranchId(branchId)) {
                if (roots.containsKey(a.getContractId()) && a.getRoomId() != null && facts.containsKey(a.getRoomId())) {
                    storedRooms.put(a.getContractId(), a.getRoomId());
                }
            }
        }
        Map<String, String> leaseRooms = BranchRoomSchedule.shownRooms(leases.values(), storedRooms, today);
        Map<String, BranchRentalRow> rentals = batched(
                roots.values().stream().map(Contract::getRentalRequestId).collect(Collectors.toSet()),
                ids -> assignments.findOwnerRentals(ids, ownerId)).stream()
                .collect(Collectors.toMap(BranchRentalRow::id, Function.identity()));
        List<BranchRentalRow> uncontracted = batched(listings.keySet(),
                ids -> assignments.findUncontractedRentals(ids, ownerId));

        addStays(roots, leases, leaseRooms, rentals, uncontracted, listingRooms, facts, today);

        List<Payment> unplaced = new ArrayList<>();
        Function<String, List<Payment>> bucket = roomId -> roomId == null ? unplaced : facts.get(roomId).payments;
        List<BranchInvoiceRow> invoiceRows = batched(roots.keySet(), invoices::findBranchRows);
        addMonthlyPayments(roots, leases, leaseRooms, invoiceRows, ownerId, selected, now, bucket);
        addInitialPayments(roots, leaseRooms, rentals, uncontracted, listingRooms, ownerId, selected, now, bucket);
        addSettlementBalances(roots, leaseRooms, ownerId, selected, now, bucket);
        addDepositAdjustments(roots, leaseRooms, ownerId, selected, now, bucket);

        Map<String, List<Meter>> meters = meterLayer(roots, leases, leaseRooms, invoiceRows, today);
        Map<String, List<Expiry>> expiries = expiryLayer(leases, leaseRooms, today);

        Comparator<Payment> order = paymentOrder(now, realNow);
        unplaced.sort(order);
        List<Room> rooms = roomSlots.values().stream().map(slot -> {
            RoomFacts room = facts.get(slot.getId());
            room.payments.sort(order);
            Room view = room(slot.getId(), BranchRoomLinkService.flagOf(slot), room.occupants, room.holds,
                    room.upcoming, room.publicListings, room.linkedListings, room.payments, today);
            return new Room(view.roomId(), view.status(), view.tenantName(), view.target(), view.nextStay(),
                    view.payments(), meters.getOrDefault(slot.getId(), List.of()),
                    expiries.getOrDefault(slot.getId(), List.of()), view.listingStatus());
        }).toList();
        return new BranchOverviewResponse(branchId, selected.toString(), now, realNow, rooms, unplaced);
    }

    /** Meter lines of the placed, billable leases, by room. Their period terms are read in one batch. */
    private Map<String, List<Meter>> meterLayer(Map<String, Contract> roots, Map<String, BranchLeaseRow> leases,
            Map<String, String> leaseRooms, List<BranchInvoiceRow> rows, LocalDate today) {
        Map<String, List<BranchInvoiceRow>> byContract = rows.stream()
                .collect(Collectors.groupingBy(BranchInvoiceRow::contractId));
        Map<String, List<BranchMonitoringService.MeterPeriod>> candidates = new HashMap<>();
        for (BranchLeaseRow lease : leases.values()) {
            if (!leaseRooms.containsKey(lease.id()) || !billable(lease)) continue;
            var found = BranchMonitoringService.candidates(
                    BranchMonitoringService.periods(lease, byContract.getOrDefault(lease.id(), List.of())), today);
            if (!found.isEmpty()) candidates.put(lease.id(), found);
        }
        BiFunction<String, LocalDate, String> revisionAt = revisionAt(roots, candidates.keySet());
        Map<BranchMonitoringService.MeterPeriod, String> revisionIds = new HashMap<>();
        candidates.values().forEach(periods -> periods.forEach(period -> {
            String id = period.row() != null && period.row().costTermsRevisionId() != null
                    ? period.row().costTermsRevisionId() : revisionAt.apply(period.contractId(), period.start());
            if (id != null) revisionIds.put(period, id);
        }));
        var metered = batched(new HashSet<>(revisionIds.values()), assignments::findRevisionCharges).stream()
                .filter(r -> BranchMonitoringService.metered(r.chargesSnapshot()))
                .collect(Collectors.toMap(r -> r.id(), Function.identity()));
        Map<String, List<Meter>> layer = new HashMap<>();
        candidates.forEach((contractId, periods) -> {
            Meter line = BranchMonitoringService.meter(periods,
                    p -> {
                        var charges = metered.get(revisionIds.get(p));
                        return charges != null && charges.belongsTo(contractId);
                    }, today);
            if (line != null) layer.computeIfAbsent(leaseRooms.get(contractId), k -> new ArrayList<>()).add(line);
        });
        layer.values().forEach(lines -> lines.sort(Comparator.comparing(Meter::status)
                .thenComparing(Meter::deadlineAt).thenComparing(Meter::contractId)));
        return layer;
    }

    private static Map<String, List<Expiry>> expiryLayer(Map<String, BranchLeaseRow> leases,
            Map<String, String> leaseRooms, LocalDate today) {
        Map<String, List<Expiry>> layer = new HashMap<>();
        for (BranchLeaseRow lease : leases.values()) {
            String roomId = leaseRooms.get(lease.id());
            Expiry line = roomId == null ? null : BranchMonitoringService.expiry(lease, today);
            if (line != null) layer.computeIfAbsent(roomId, k -> new ArrayList<>()).add(line);
        }
        layer.values().forEach(lines -> lines.sort(Comparator.comparing(Expiry::status)
                .thenComparingInt(Expiry::daysRemaining).thenComparing(Expiry::contractId)));
        return layer;
    }

    /** Same choice as billing: the amendment in force at the period start, else the root revision. */
    private BiFunction<String, LocalDate, String> revisionAt(Map<String, Contract> roots,
            Collection<String> contractIds) {
        Map<String, List<ContractAmendment>> amendments =
                batched(contractIds, assignments::findSignedAmendments).stream()
                        .collect(Collectors.groupingBy(ContractAmendment::getContractId));
        return (contractId, periodStart) ->
                ContractTermsResolver.selectAmendment(amendments.getOrDefault(contractId, List.of()), periodStart)
                        .map(ContractAmendment::getTermsRevisionId)
                        .orElseGet(() -> roots.get(contractId).getCurrentRevisionId());
    }

    /** Who is staying, holding or about to move into each room; {@code facts} has one entry per room. */
    static void addStays(Map<String, Contract> roots, Map<String, BranchLeaseRow> leases,
            Map<String, String> leaseRooms, Map<String, BranchRentalRow> rentals, List<BranchRentalRow> uncontracted,
            Map<String, String> listingRooms, Map<String, RoomFacts> facts, LocalDate today) {
        for (BranchLeaseRow lease : leases.values()) {
            String roomId = leaseRooms.get(lease.id());
            if (roomId == null || lease.moveInDate() == null || BranchRoomSchedule.historical(lease)) continue;
            RoomFacts room = facts.get(roomId);
            StayRow stay = new StayRow(lease.id(), lease.moveInDate(), lease.tenantName(), contractTarget(lease.id()));
            if (BranchRoomSchedule.living(lease, today)) {
                room.occupants.add(stay);
            } else if (lease.status() == ContractStatus.ACTIVE && lease.moveInDate().isAfter(today)) {
                room.upcoming.add(stay);
            } else if (PRE_EFFECTIVE.contains(lease.status())) {
                BranchRentalRow rental = rentals.get(roots.get(lease.id()).getRentalRequestId());
                if (rental != null && rental.activeAccepted()) room.holds.add(stay);
            }
        }
        for (BranchRentalRow rental : uncontracted) {
            String roomId = listingRooms.get(rental.listingId());
            if (roomId != null && rental.moveInDate() != null) {
                facts.get(roomId).holds.add(new StayRow(rental.id(), rental.moveInDate(), rental.renterName(),
                        rentalTarget(rental.id())));
            }
        }
    }

    /** Room status precedence: living tenant, hold reached, move-in ahead, manual flag, public listing, vacant (linked listing), unlinked. */
    static Room room(String roomId, RoomManualStatus flag, List<StayRow> occupants, List<StayRow> holds,
                     List<StayRow> upcoming, List<String> publicListings, List<BranchOverviewListingRow> linked,
                     List<Payment> items, LocalDate today) {
        PaymentLayer layer = new PaymentLayer(
                items.isEmpty() ? BranchOverviewResponse.PaymentStatus.NONE : items.getFirst().status(),
                items.size(), List.copyOf(items));
        List<StayRow> future = new ArrayList<>(upcoming);
        holds.stream().filter(h -> h.moveInDate().isAfter(today)).forEach(future::add);
        StayRow next = future.stream().min(STAY_ORDER).orElse(null);
        StayRow occupant = occupants.stream().min(STAY_ORDER).orElse(null);
        if (occupant != null) {
            return new Room(roomId, RoomStatus.OCCUPIED, occupant.tenantName(), occupant.target(),
                    next == null ? null : new Stay(next.tenantName(), next.moveInDate(), next.target()), layer);
        }
        StayRow reserved = holds.stream().filter(h -> !h.moveInDate().isAfter(today)).min(STAY_ORDER).orElse(null);
        StayRow primary = reserved != null ? reserved : next;
        if (primary != null) {
            return new Room(roomId, reserved != null ? RoomStatus.RESERVED : RoomStatus.MOVING_IN,
                    primary.tenantName(), primary.target(), null, layer);
        }
        if (flag == RoomManualStatus.EXTERNAL_RENT || flag == RoomManualStatus.REPAIR) {
            return new Room(roomId, RoomStatus.valueOf(flag.name()), null, null, null, layer);
        }
        String listingId = publicListings.stream().min(Comparator.naturalOrder()).orElse(null);
        if (listingId != null) {
            return new Room(roomId, RoomStatus.ADVERTISED, null, new Target(TargetKind.LISTING, listingId,
                    "/dashboard/properties/view?id=" + encode(listingId)), null, layer);
        }
        BranchOverviewListingRow first = linked.stream().min(Comparator.comparing(BranchOverviewListingRow::id)).orElse(null);
        if (first == null) return new Room(roomId, RoomStatus.UNLINKED, null, null, null, layer);
        return new Room(roomId, RoomStatus.VACANT, null, new Target(TargetKind.LISTING, first.id(),
                "/dashboard/properties/view?id=" + encode(first.id())), null, layer, List.of(), List.of(),
                first.operationalStatus().name());
    }

    // The add* methods are shared with the attention list; public so a call through the transactional proxy reaches
    // the real bean. A null month keeps every open item and builds no unissued ones.
    public void addMonthlyPayments(Map<String, Contract> roots, Map<String, BranchLeaseRow> leases,
            Map<String, String> leaseRooms, List<BranchInvoiceRow> rows, String ownerId, YearMonth selected,
            Instant now, Function<String, List<Payment>> bucket) {
        // Only rows that can reach the answer need their transfer: due in the month or earlier.
        Set<String> invoiceIds = rows.stream()
                .filter(i -> i.status() != MonthlyInvoiceStatus.DRAFT && i.status() != MonthlyInvoiceStatus.CANCELLED
                        && i.status() != MonthlyInvoiceStatus.ROLLED_OVER
                        && i.dueAt() != null && (selected == null || !monthOf(i.dueAt()).isAfter(selected)))
                .map(BranchInvoiceRow::id).collect(Collectors.toSet());
        Map<String, PaymentRequest> transfers = batched(invoiceIds, payments::findByActiveTrueAndInvoiceIdIn).stream()
                .collect(Collectors.toMap(PaymentRequest::getInvoiceId, Function.identity()));

        for (BranchInvoiceRow row : rows) {
            if (!invoiceIds.contains(row.id())) continue;
            Contract root = roots.get(row.contractId());
            PaymentRequest transfer = transfers.get(row.id());
            if (transfer != null && !matches(row, root, transfer, ownerId)) transfer = null;
            var status = invoiceStatus(row.status(), row.dueAt(), row.resolutionMode(),
                    transfer == null ? null : transfer.getStatus(), now);
            if (status == null) continue;
            Payment item = payment(row.id(), PaymentKind.MONTHLY, root.getId(), status, row.dueAt(), transfer,
                    invoiceTarget(root.getId(), row.id()));
            if (inMonth(item, selected, now, false)) bucket.apply(leaseRooms.get(root.getId())).add(item);
        }
        if (selected != null) addUnissued(roots, leases, leaseRooms, rows, selected, bucket);
    }

    /** A bill that does not exist yet: a stored draft, or a lease period without any row. */
    private record Unissued(String id, PaymentKind kind, String contractId, LocalDate periodEndExclusive,
                            String revisionId, Target target) {}

    /** Grey items of the selected month, dated by the signed terms of their period, for display only. */
    private void addUnissued(Map<String, Contract> roots, Map<String, BranchLeaseRow> leases,
            Map<String, String> leaseRooms, List<BranchInvoiceRow> rows, YearMonth selected,
            Function<String, List<Payment>> bucket) {
        List<String> billableIds = leases.values().stream().filter(BranchOverviewService::billable)
                .map(BranchLeaseRow::id).toList();
        BiFunction<String, LocalDate, String> revisionAt = revisionAt(roots, billableIds);

        List<Unissued> candidates = new ArrayList<>();
        for (BranchInvoiceRow row : rows) {
            if (row.status() == MonthlyInvoiceStatus.DRAFT && billable(leases.get(row.contractId()))) {
                candidates.add(new Unissued(row.id(), PaymentKind.MONTHLY, row.contractId(), row.periodEndExclusive(),
                        row.costTermsRevisionId() != null ? row.costTermsRevisionId()
                                : revisionAt.apply(row.contractId(), row.periodStart()),
                        invoiceTarget(row.contractId(), row.id())));
            }
        }
        // Any stored row, cancelled included, blocks projecting its period again.
        Set<String> storedPeriods = rows.stream().map(i -> i.contractId() + ':' + i.periodIndex())
                .collect(Collectors.toSet());
        for (String contractId : billableIds) {
            BranchLeaseRow lease = leases.get(contractId);
            for (var period : BranchBillingPeriods.of(lease.moveInDate(), lease.billingMonths(), lease.endExclusive())) {
                if (storedPeriods.contains(contractId + ':' + period.index())) continue;
                candidates.add(new Unissued("period:" + contractId + ":" + period.index(), PaymentKind.PROJECTED,
                        contractId, period.endExclusive(), revisionAt.apply(contractId, period.start()),
                        new Target(TargetKind.CONTRACT, contractId,
                                contractPath(contractId) + "?tab=payment&section=monthly")));
            }
        }
        Map<String, BranchRevisionTerms> terms = batched(
                candidates.stream().map(Unissued::revisionId).filter(Objects::nonNull).collect(Collectors.toSet()),
                assignments::findRevisionTerms).stream()
                .collect(Collectors.toMap(BranchRevisionTerms::id, Function.identity()));
        for (Unissued candidate : candidates) {
            Instant dueAt = expectedDueAt(candidate, terms);
            if (dueAt != null && monthOf(dueAt).equals(selected)) {
                bucket.apply(leaseRooms.get(candidate.contractId())).add(new Payment(candidate.id(), candidate.kind(),
                        candidate.contractId(), BranchOverviewResponse.PaymentStatus.UNISSUED, dueAt, null,
                        Clock.BILLING, candidate.target()));
            }
        }
    }

    /** A lease without any revision uses the system window; unreadable signed terms give no date at all. */
    private static Instant expectedDueAt(Unissued candidate, Map<String, BranchRevisionTerms> terms) {
        String revisionId = candidate.revisionId();
        LocalDate periodEndExclusive = candidate.periodEndExclusive();
        if (periodEndExclusive == null) return null;
        if (revisionId == null) return MonthlyBillingSchedule.dueAt(periodEndExclusive);
        BranchRevisionTerms revision = terms.get(revisionId);
        if (revision == null || !candidate.contractId().equals(revision.rootId())
                || revision.financialSnapshot() == null) return null;
        try {
            Map<String, Object> financial = JSON.readValue(revision.financialSnapshot(), new TypeReference<>() {});
            return financial == null ? null : MonthlyBillingSchedule.signedDueAt(financial, periodEndExclusive);
        } catch (JsonProcessingException | AppException unreadable) {
            return null;
        }
    }

    /** What the tenant still owes after handover under the accepted closure; once received it leaves the layer. */
    public void addSettlementBalances(Map<String, Contract> roots, Map<String, String> leaseRooms, String ownerId,
            YearMonth selected, Instant now, Function<String, List<Payment>> bucket) {
        Map<String, String> contractByPayment = batched(roots.keySet(), assignments::findBalanceClosures).stream()
                .collect(Collectors.toMap(ContractClosure::getBalancePaymentId, ContractClosure::getContractId));
        for (PaymentRequest balance : batched(contractByPayment.keySet(), payments::findAllById)) {
            Contract root = roots.get(contractByPayment.get(balance.getId()));
            if (!Boolean.TRUE.equals(balance.getActive()) || balance.getType() != PaymentType.OTHER_CHARGE
                    || balance.getInvoiceId() != null || !root.getId().equals(balance.getContractId())) {
                continue;
            }
            addTenantObligation(balance, root, PaymentKind.SETTLEMENT_BALANCE, leaseRooms, ownerId, selected, now,
                    bucket);
        }
    }

    /** The extra deposit a signed amendment makes the tenant transfer; once received it leaves the layer. */
    public void addDepositAdjustments(Map<String, Contract> roots, Map<String, String> leaseRooms, String ownerId,
            YearMonth selected, Instant now, Function<String, List<Payment>> bucket) {
        for (PaymentRequest extra : batched(roots.keySet(),
                ids -> payments.findByActiveTrueAndContractIdInAndType(ids, PaymentType.DEPOSIT_ADJUSTMENT))) {
            addTenantObligation(extra, roots.get(extra.getContractId()), PaymentKind.DEPOSIT_ADJUSTMENT, leaseRooms,
                    ownerId, selected, now, bucket);
        }
    }

    /** A transfer the tenant owes outside any invoice, coloured like an issued one. */
    private static void addTenantObligation(PaymentRequest owed, Contract root, PaymentKind kind,
            Map<String, String> leaseRooms, String ownerId, YearMonth selected, Instant now,
            Function<String, List<Payment>> bucket) {
        if (owed.getDirection() != PaymentDirection.TENANT_TO_LANDLORD || !ownerId.equals(owed.getPayeeId())
                || !Objects.equals(root.getTenantId(), owed.getPayerId()) || !OPEN_PAYMENT.contains(owed.getStatus())) {
            return;
        }
        var status = invoiceStatus(MonthlyInvoiceStatus.UNPAID, owed.getDueAt(), null, owed.getStatus(), now);
        if (status == null) return; // no due date
        Payment item = payment(owed.getId(), kind, root.getId(), status, owed.getDueAt(), owed,
                paymentTarget(owed.getId()));
        if (inMonth(item, selected, now, false)) bucket.apply(leaseRooms.get(root.getId())).add(item);
    }

    /** The first rent receipt: through the root lease when there is one, else through the request's listing. */
    public void addInitialPayments(Map<String, Contract> roots, Map<String, String> leaseRooms,
            Map<String, BranchRentalRow> rentals, List<BranchRentalRow> uncontracted,
            Map<String, String> listingRooms, String ownerId, YearMonth selected, Instant now,
            Function<String, List<Payment>> bucket) {
        // One receipt per request: a living root wins over a cancelled one of the same request.
        Map<String, Contract> rootByRental = new HashMap<>();
        roots.values().stream()
                .sorted(Comparator.<Contract, Boolean>comparing(c -> c.getStatus() == ContractStatus.CANCELLED)
                        .thenComparing(Contract::getId))
                .forEach(c -> rootByRental.putIfAbsent(c.getRentalRequestId(), c));
        Map<String, BranchRentalRow> sources = new HashMap<>();
        rootByRental.keySet().forEach(id -> {
            if (rentals.containsKey(id)) sources.put(id, rentals.get(id));
        });
        uncontracted.forEach(r -> sources.put(r.id(), r));

        List<PaymentRequest> receipts = batched(sources.keySet(),
                ids -> payments.findByActiveTrueAndRentalRequestIdInAndType(ids, PaymentType.INITIAL));
        Map<String, List<PaymentLineItem>> lines = batched(
                receipts.stream().map(PaymentRequest::getId).toList(),
                ids -> lineItems.findByPaymentRequestIdInAndTypeIn(ids, List.of("RENT", "SERVICE_FEE"))).stream()
                .collect(Collectors.groupingBy(PaymentLineItem::getPaymentRequestId));
        for (PaymentRequest receipt : receipts) {
            BranchRentalRow rental = sources.get(receipt.getRentalRequestId());
            Contract root = rootByRental.get(receipt.getRentalRequestId());
            if (rental.moveInDate() == null || receipt.getInvoiceId() != null || receipt.getDueAt() == null
                    || receipt.getDirection() != PaymentDirection.TENANT_TO_LANDLORD
                    || !ownerId.equals(receipt.getPayeeId())
                    || !Objects.equals(rental.renterId(), receipt.getPayerId())
                    || root != null && !Objects.equals(root.getTenantId(), receipt.getPayerId())
                    || !validFirstRent(receipt.getTotalAmount(), lines.getOrDefault(receipt.getId(), List.of()))) {
                continue;
            }
            var status = initialStatus(receipt.getStatus(), receipt.getDueAt(), now);
            if (status == null) continue;
            String id = "first-rent:" + receipt.getId();
            Target target;
            if (root == null) {
                target = rentalTarget(rental.id());
            } else if (PRE_EFFECTIVE.contains(root.getStatus()) || root.getStatus() == ContractStatus.CANCELLED) {
                target = new Target(TargetKind.CONTRACT, root.getId(), contractPath(root.getId()) + "?tab=payment");
            } else {
                target = invoiceTarget(root.getId(), id);
            }
            Payment item = payment(id, PaymentKind.INITIAL, root == null ? null : root.getId(), status,
                    receipt.getDueAt(), receipt, target);
            if (inMonth(item, selected, now, true)) {
                bucket.apply(root != null ? leaseRooms.get(root.getId()) : listingRooms.get(rental.listingId()))
                        .add(item);
            }
        }
    }

    /** Same evidence the first-rent invoice view requires: positive rent, rent plus fees within the receipt. */
    static boolean validFirstRent(BigDecimal total, List<PaymentLineItem> lines) {
        boolean rent = lines.stream().anyMatch(l -> "RENT".equals(l.getType()) && l.getAmount() != null
                && l.getAmount().signum() > 0);
        return rent && total != null && lines.stream()
                .filter(l -> "RENT".equals(l.getType()) || "SERVICE_FEE".equals(l.getType()))
                .map(l -> l.getAmount() == null ? BigDecimal.ZERO : l.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(total) <= 0;
    }

    /** An invoice and a transfer are one receivable only when ids and parties agree. */
    static boolean matches(BranchInvoiceRow row, Contract root, PaymentRequest transfer, String ownerId) {
        return transfer.getType() == PaymentType.MONTHLY_RENT
                && Objects.equals(row.contractId(), transfer.getContractId())
                && (row.paymentRequestId() == null || row.paymentRequestId().equals(transfer.getId()))
                && transfer.getDirection() == PaymentDirection.TENANT_TO_LANDLORD
                && ownerId.equals(transfer.getPayeeId())
                && Objects.equals(root.getTenantId(), transfer.getPayerId());
    }

    /** Status of an issued invoice; null when it is not a receivable that can be shown. */
    static BranchOverviewResponse.PaymentStatus invoiceStatus(MonthlyInvoiceStatus invoice, Instant dueAt,
            String resolutionMode, PaymentStatus transfer, Instant now) {
        if (dueAt == null || invoice == MonthlyInvoiceStatus.DRAFT || invoice == MonthlyInvoiceStatus.CANCELLED
                || invoice == MonthlyInvoiceStatus.ROLLED_OVER) return null;
        if (invoice == MonthlyInvoiceStatus.PAID || transfer == PaymentStatus.CONFIRMED) {
            return BranchOverviewResponse.PaymentStatus.PAID;
        }
        if (transfer == null || !OPEN_PAYMENT.contains(transfer)) return null;
        if (transfer == PaymentStatus.TRANSFER_REPORTED) {
            return BranchOverviewResponse.PaymentStatus.PENDING_CONFIRMATION;
        }
        boolean extended = PAYMENT_EXTENSION.equals(resolutionMode) && !now.isAfter(dueAt);
        if (now.isAfter(dueAt) || invoice == MonthlyInvoiceStatus.OVERDUE && now.equals(dueAt) && !extended) {
            return BranchOverviewResponse.PaymentStatus.OVERDUE;
        }
        return extended || dueSoon(dueAt, now) ? BranchOverviewResponse.PaymentStatus.DUE_SOON
                : BranchOverviewResponse.PaymentStatus.NOT_DUE;
    }

    static BranchOverviewResponse.PaymentStatus initialStatus(PaymentStatus transfer, Instant dueAt, Instant now) {
        if (transfer == PaymentStatus.CONFIRMED) return BranchOverviewResponse.PaymentStatus.PAID;
        if (!OPEN_PAYMENT.contains(transfer)) return null;
        if (transfer == PaymentStatus.TRANSFER_REPORTED) {
            return BranchOverviewResponse.PaymentStatus.PENDING_CONFIRMATION;
        }
        if (!now.isBefore(dueAt)) return BranchOverviewResponse.PaymentStatus.OVERDUE;
        return dueSoon(dueAt, now) ? BranchOverviewResponse.PaymentStatus.DUE_SOON
                : BranchOverviewResponse.PaymentStatus.NOT_DUE;
    }

    private static boolean dueSoon(Instant dueAt, Instant now) {
        return Duration.between(now, dueAt).compareTo(DUE_SOON_WINDOW) <= 0;
    }

    /** Items of the selected month, plus open ones of earlier months whose due time has passed. */
    static boolean inMonth(Payment item, YearMonth selected, Instant now, boolean overdueAtDueTime) {
        if (selected == null) return true;
        YearMonth due = monthOf(item.dueAt());
        if (due.equals(selected)) return true;
        if (due.isAfter(selected) || item.status() == BranchOverviewResponse.PaymentStatus.PAID
                || item.status() == BranchOverviewResponse.PaymentStatus.UNISSUED) return false;
        return item.status() == BranchOverviewResponse.PaymentStatus.OVERDUE || now.isAfter(item.dueAt())
                || overdueAtDueTime && now.equals(item.dueAt());
    }

    /** Status priority, then the least time left on the item's own clock (confirmation time for pending), then id. */
    static Comparator<Payment> paymentOrder(Instant billingNow, Instant realNow) {
        Function<Payment, Long> remaining = p -> {
            Instant at = p.status() == BranchOverviewResponse.PaymentStatus.PENDING_CONFIRMATION
                    ? p.confirmationDueAt() : p.dueAt();
            return at == null ? Long.MAX_VALUE
                    : Duration.between(p.clock() == Clock.BILLING ? billingNow : realNow, at).toMillis();
        };
        return Comparator.comparing(Payment::status).thenComparing(remaining).thenComparing(Payment::id);
    }

    private static Payment payment(String id, PaymentKind kind, String contractId,
            BranchOverviewResponse.PaymentStatus status, Instant dueAt, PaymentRequest transfer, Target target) {
        Clock clock = transfer == null || PaymentTimePolicy.usesBillingTime(transfer) ? Clock.BILLING : Clock.REAL;
        return new Payment(id, kind, contractId, status, dueAt,
                status == BranchOverviewResponse.PaymentStatus.PENDING_CONFIRMATION
                        ? transfer.getConfirmationDueAt() : null, clock, target);
    }

    /** The lease can still be billed: active or expired and the room not handed back. */
    static boolean billable(BranchLeaseRow lease) {
        return lease != null && lease.handedOverAt() == null
                && (lease.status() == ContractStatus.ACTIVE || lease.status() == ContractStatus.EXPIRED);
    }

    private static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(BillingTime.ZONE));
    }

    static Target contractTarget(String contractId) {
        return new Target(TargetKind.CONTRACT, contractId, contractPath(contractId) + "?tab=overview");
    }

    static Target invoiceTarget(String contractId, String invoiceId) {
        return new Target(TargetKind.INVOICE, invoiceId,
                contractPath(contractId) + "?tab=payment&section=monthly&invoice=" + encode(invoiceId));
    }

    static Target paymentTarget(String paymentId) {
        return new Target(TargetKind.PAYMENT, paymentId, "/dashboard/payments?paymentId=" + encode(paymentId));
    }

    static Target rentalTarget(String rentalId) {
        return new Target(TargetKind.RENTAL_REQUEST, rentalId,
                "/dashboard/rental-requests?requestId=" + encode(rentalId));
    }

    static String contractPath(String contractId) {
        return "/dashboard/landlord/contracts/" + encode(contractId);
    }

    static String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }

    /** One query per 500 ids; an empty id set runs none. */
    static <T> List<T> batched(Collection<String> ids, Function<List<String>, List<T>> query) {
        List<String> all = List.copyOf(ids);
        List<T> result = new ArrayList<>();
        for (int from = 0; from < all.size(); from += BATCH) {
            result.addAll(query.apply(all.subList(from, Math.min(from + BATCH, all.size()))));
        }
        return result;
    }
}
