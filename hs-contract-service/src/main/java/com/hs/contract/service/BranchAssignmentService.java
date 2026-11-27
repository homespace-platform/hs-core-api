package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.branch.*;
import com.hs.contract.model.BranchContractAssignment;
import com.hs.contract.model.Contract;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.response.BranchListingRow;
import com.hs.listing.model.BranchRoomListing;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.repository.BranchRoomListingRepository;
import com.hs.listing.repository.BranchSlotRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.listing.service.BranchContractLinkGuard.ListingLease;
import com.hs.listing.service.BranchRoomLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Management links between root leases and rooms. Reads never write; writes touch only the link table. */
@Service
@Slf4j
@RequiredArgsConstructor
public class BranchAssignmentService {
    private static final int AUTO_LINK_ATTEMPTS = 3;

    private final BranchContractAssignmentRepository assignments;
    private final ContractRepository contracts;
    private final PropertyBranchRepository branches;
    private final BranchSlotRepository slots;
    private final BranchRoomListingRepository roomListings;
    private final BranchRoomLinkService rooms;
    private final BillingTime billingTime;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    // One snapshot for all reads, like the layout GET.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchAssignmentsResponse assignments(String branchId, String ownerId, int page, int size) {
        requirePage(page, size);
        requireOwnBranch(branchId, ownerId);
        List<BranchSlot> roomSlots = slots.findAllByBranchIdOrderByPosition(branchId).stream()
                .filter(s -> s.getRoomCode() != null).toList();
        Set<String> roomIds = roomSlots.stream().map(BranchSlot::getId).collect(Collectors.toSet());

        List<BranchListingRow> listings = roomListings.findBranchListings(branchId, ownerId);
        Map<String, String> listingRooms = listingRooms(branchId);
        Placement placement = placement(branchId, ownerId);
        Map<String, List<BranchHoldRow>> holds = roomIds.isEmpty() ? Map.of()
                : assignments.findHolds(roomIds).stream().collect(Collectors.groupingBy(BranchHoldRow::roomId));

        Map<String, List<BranchAssignmentsResponse.Listing>> listingsByRoom = new HashMap<>();
        List<BranchAssignmentsResponse.Item> unplaced = new ArrayList<>();
        for (BranchLeaseRow lease : placement.leases()) {
            if (!placement.rooms().containsKey(lease.id())) {
                unplaced.add(new BranchAssignmentsResponse.Item("CONTRACT", lease.id(), label(lease)));
            }
        }
        for (BranchListingRow listing : listings) { // already ordered by id
            String roomId = listingRooms.get(listing.id());
            if (roomId == null) {
                unplaced.add(new BranchAssignmentsResponse.Item("LISTING", listing.id(), listing.title()));
            } else {
                listingsByRoom.computeIfAbsent(roomId, k -> new ArrayList<>()).add(
                        new BranchAssignmentsResponse.Listing(listing.id(), listing.title(), listing.operationalStatus(),
                                listing.roomCode()));
            }
        }
        Map<String, List<BranchAssignmentsResponse.Contract>> contractsByRoom = new HashMap<>();
        Set<String> leasedRooms = new HashSet<>();
        for (BranchLeaseRow lease : placement.leases()) {
            String roomId = placement.rooms().get(lease.id());
            if (roomId != null) {
                if (!BranchRoomSchedule.historical(lease)) leasedRooms.add(roomId);
                contractsByRoom.computeIfAbsent(roomId, k -> new ArrayList<>()).add(
                        new BranchAssignmentsResponse.Contract(lease.id(), lease.status(), lease.moveInDate(),
                                lease.endExclusive(), lease.tenantName()));
            }
        }

        List<BranchAssignmentsResponse.Room> result = roomSlots.stream()
                // A rental accepted after the flag was set wins on screen (occupant, then hold, then flag).
                // The stored flag is untouched and shows again once nothing occupies the room.
                .map(s -> new BranchAssignmentsResponse.Room(s.getId(),
                        leasedRooms.contains(s.getId()) || holds.containsKey(s.getId())
                                ? RoomManualStatus.NONE : BranchRoomLinkService.flagOf(s),
                        listingsByRoom.getOrDefault(s.getId(), List.of()),
                        contractsByRoom.getOrDefault(s.getId(), List.of()),
                        holds.getOrDefault(s.getId(), List.of()).stream()
                                .map(h -> new BranchAssignmentsResponse.Hold(h.rentalRequestId(), h.moveInDate(), h.tenantName()))
                                .toList()))
                .toList();
        int from = (int) Math.min((long) page * size, unplaced.size());
        return new BranchAssignmentsResponse(result, new BranchAssignmentsResponse.Unplaced(
                unplaced.subList(from, Math.min(from + size, unplaced.size())), page, size, unplaced.size()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchCandidatesResponse candidates(String branchId, String ownerId, String kind, int page, int size) {
        requirePage(page, size);
        requireOwnBranch(branchId, ownerId);
        if ("LISTING".equals(kind)) {
            Page<BranchListingRow> found = roomListings.findBranchListingPage(branchId, ownerId, PageRequest.of(page, size));
            Map<String, String> listingRooms = listingRooms(branchId);
            return new BranchCandidatesResponse(found.getContent().stream()
                    .map(l -> new BranchCandidatesResponse.Item(l.id(), l.title(), listingRooms.get(l.id()), l.roomCode()))
                    .toList(), page, size, found.getTotalElements());
        }
        if ("CONTRACT".equals(kind)) {
            Page<String> ids = assignments.findCandidateIds(ownerId, branchId, PageRequest.of(page, size));
            Map<String, BranchLeaseRow> leases = leaseRows(ids.getContent());
            Map<String, String> placedRooms = placement(branchId, ownerId).rooms();
            return new BranchCandidatesResponse(ids.getContent().stream()
                    .map(id -> new BranchCandidatesResponse.Item(id, label(leases.get(id)), placedRooms.get(id), null))
                    .toList(), page, size, ids.getTotalElements());
        }
        throw invalid("kind phải là LISTING hoặc CONTRACT");
    }

    /** Attaches or moves a root lease; a null room puts it in the unplaced tray. */
    @Transactional
    public BranchContractLinkResponse attach(String branchId, String ownerId, String contractId, String roomId) {
        return attach(branchId, ownerId, contractId, roomId, false);
    }

    @Transactional
    public BranchContractLinkResponse addToTray(String branchId, String ownerId, String contractId) {
        return attach(branchId, ownerId, contractId, null, true);
    }

    private BranchContractLinkResponse attach(String branchId, String ownerId, String contractId, String roomId,
            boolean addOnly) {
        requireOwnRootLease(contractId, ownerId); // contract lock first, then the branch lock
        rooms.lockBranch(branchId, ownerId);
        BranchContractAssignment assignment = assignments.findById(contractId).orElse(null);
        if (assignment != null && !assignment.getManagementBranchId().equals(branchId)) {
            throw new AppException(ContractErrorCode.CONTRACT_IN_OTHER_BRANCH);
        }
        if (assignment != null && addOnly) {
            return new BranchContractLinkResponse(contractId, branchId, assignment.getRoomId());
        }
        if (roomId != null) {
            BranchSlot room = rooms.requireRoom(branchId, roomId);
            Fit fit = fit(room, contractId);
            if (fit == Fit.FLAGGED) throw new AppException(ListingErrorCode.BRANCH_ROOM_STATUS_CONFLICT);
            if (fit == Fit.BUSY) throw new AppException(ContractErrorCode.BRANCH_ROOM_SCHEDULE_CONFLICT);
        }
        if (assignment == null) {
            assignments.save(new BranchContractAssignment(contractId, branchId, roomId));
        } else {
            assignment.setRoomId(roomId);
        }
        return new BranchContractLinkResponse(contractId, branchId, roomId);
    }

    /**
     * Attaches the one effective root lease of a listing (ACTIVE or EXPIRED, moved in, not handed over) to the room
     * the listing was just linked to. A lease elsewhere, in another branch or clashing with the room is only reported.
     * Same lock order as attach: contract first, then the branch.
     */
    @Transactional
    public ListingLease attachListingLease(String branchId, String ownerId, String roomId, String listingId) {
        if (roomListings.findById(listingId).filter(k -> roomId.equals(k.getRoomId())).isEmpty()) {
            return ListingLease.NONE;
        }
        List<BranchLeaseRow> effective = effectiveLeases(listingId, ownerId);
        if (effective.isEmpty()) return ListingLease.NONE;
        if (effective.size() > 1) {
            return new ListingLease(null, "Tin này có nhiều hợp đồng đang hiệu lực, hãy gắn hợp đồng thủ công.");
        }
        String contractId = effective.getFirst().id();
        requireOwnRootLease(contractId, ownerId);
        rooms.lockBranch(branchId, ownerId);
        // The lease may have ended while we waited for the locks.
        if (leaseRows(List.of(contractId)).values().stream().noneMatch(this::effectiveNow)) return ListingLease.NONE;
        BranchContractAssignment assignment = assignments.findById(contractId).orElse(null);
        if (assignment != null && !assignment.getManagementBranchId().equals(branchId)) {
            return new ListingLease(null, "Hợp đồng đang hiệu lực của tin này thuộc chi nhánh khác, nên không thay đổi.");
        }
        if (assignment != null && roomId.equals(assignment.getRoomId())) return ListingLease.NONE;
        if (assignment != null && assignment.getRoomId() != null) {
            String code = slots.findById(assignment.getRoomId()).map(BranchSlot::getRoomCode).orElse("khác");
            return new ListingLease(null, "Hợp đồng đang hiệu lực của tin này đang ở phòng " + code
                    + ", nên không chuyển sang phòng này.");
        }
        Fit fit = fit(rooms.requireRoom(branchId, roomId), contractId);
        if (fit == Fit.FLAGGED) {
            return new ListingLease(null, "Phòng đang đặt trạng thái thủ công, nên chưa gắn hợp đồng đang hiệu lực.");
        }
        if (fit == Fit.BUSY) {
            return new ListingLease(null, "Trùng lịch với hợp đồng đang ở phòng này, nên không gắn hợp đồng của tin.");
        }
        if (assignment == null) assignments.save(new BranchContractAssignment(contractId, branchId, roomId));
        else assignment.setRoomId(roomId);
        return new ListingLease(contractId, null);
    }

    private List<BranchLeaseRow> effectiveLeases(String listingId, String ownerId) {
        List<String> ids = assignments.findEffectiveRootIds(listingId, ownerId);
        return ids.isEmpty() ? List.of() : leaseRows(ids).values().stream().filter(this::effectiveNow)
                .sorted(Comparator.comparing(BranchLeaseRow::id)).toList();
    }

    private boolean effectiveNow(BranchLeaseRow lease) {
        return BranchOverviewService.billable(lease) && BranchRoomSchedule.living(lease, billingTime.today());
    }

    @Transactional
    public void detach(String branchId, String ownerId, String contractId) {
        requireOwnRootLease(contractId, ownerId);
        rooms.lockBranch(branchId, ownerId);
        assignments.findById(contractId).ifPresent(assignment -> {
            if (!assignment.getManagementBranchId().equals(branchId)) {
                throw new AppException(ContractErrorCode.CONTRACT_IN_OTHER_BRANCH);
            }
            assignments.delete(assignment);
        });
    }

    /**
     * First-time link of a new lease from its listing's live branch. Expected mismatches are resolved before
     * writing: no usable branch means no link, an unusable room means the tray. Only reads the listing.
     * Each attempt is its own transaction: a listing that moved while we waited is followed with fresh locks,
     * never by holding two branch locks.
     */
    public void autoLink(String contractId) {
        for (int attempt = 0; attempt < AUTO_LINK_ATTEMPTS; attempt++) {
            if (!inNewTransaction(() -> autoLinkOnce(contractId))) return;
        }
        log.warn("BRANCH_AUTO_LINK_GAVE_UP contract={}; listing kept moving", contractId);
    }

    /** True when the listing moved to another branch meanwhile, so the link must be tried again. */
    private boolean autoLinkOnce(String contractId) {
        Contract contract = contracts.findByIdForUpdate(contractId).filter(this::rootLease).orElse(null);
        if (contract == null || assignments.existsById(contractId)) return false;
        String ownerId = contract.getLandlordId();
        String branchId = roomListings.findActiveListingBranchId(contract.getListingId(), ownerId).orElse(null);
        if (branchId == null) return false;
        branches.lockExclusive(branchId);
        PropertyBranch branch = branches.findByIdForUpdate(branchId)
                .filter(b -> b.getOwnerId().equals(ownerId)).orElse(null);
        // Read the listing again under the branch lock: it may have moved in the meantime.
        String current = roomListings.findActiveListingBranchId(contract.getListingId(), ownerId).orElse(null);
        if (current == null) return false;
        if (!current.equals(branchId)) return true;
        if (branch == null) return false;
        String roomId = roomListings.findById(contract.getListingId())
                .map(BranchRoomListing::getRoomId)
                .flatMap(slots::findById)
                .filter(s -> s.getBranch().getId().equals(branchId) && s.getRoomCode() != null)
                .filter(s -> fit(s, contractId) == Fit.OK)
                .map(BranchSlot::getId)
                .orElse(null);
        assignments.save(new BranchContractAssignment(contractId, branchId, roomId));
        return false;
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        if (transactionManager == null) return work.get(); // plain unit tests
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private enum Fit { OK, FLAGGED, BUSY }

    /** Call under the branch lock. Ended leases fit anywhere. */
    private Fit fit(BranchSlot room, String contractId) {
        Set<String> inRoom = assignments.findRoomContracts(List.of(room.getId())).stream()
                .map(BranchRoomContract::contractId).collect(Collectors.toSet());
        Set<String> ids = new HashSet<>(inRoom);
        ids.add(contractId);
        Map<String, BranchLeaseRow> leases = leaseRows(ids);
        BranchLeaseRow candidate = leases.get(contractId);
        if (candidate == null || BranchRoomSchedule.historical(candidate)) return Fit.OK;
        if (BranchRoomLinkService.flagOf(room) != RoomManualStatus.NONE) return Fit.FLAGGED;
        List<BranchLeaseRow> kept = BranchRoomSchedule.placed(
                inRoom.stream().map(leases::get).filter(Objects::nonNull).toList(), billingTime.today());
        if (kept.contains(candidate)) return Fit.OK; // already holds this room
        return kept.stream().anyMatch(other -> !other.id().equals(contractId)
                && BranchRoomSchedule.overlaps(candidate, other, billingTime.today())) ? Fit.BUSY : Fit.OK;
    }

    /** All leases of the branch (id order) and the room each one really shows in. */
    private record Placement(List<BranchLeaseRow> leases, Map<String, String> rooms) {}

    private Placement placement(String branchId, String ownerId) {
        Map<String, String> stored = new HashMap<>();
        List<BranchContractAssignment> linked = assignments.findAllByManagementBranchId(branchId);
        if (linked.isEmpty()) return new Placement(List.of(), Map.of());
        for (BranchContractAssignment a : linked) stored.put(a.getContractId(), a.getRoomId());
        List<BranchLeaseRow> leases = assignments.findLeaseRows(stored.keySet()).stream()
                .sorted(Comparator.comparing(BranchLeaseRow::id)).toList();
        return new Placement(leases, BranchRoomSchedule.shownRooms(leases, stored, billingTime.today()));
    }

    private Map<String, String> listingRooms(String branchId) {
        return roomListings.findAllInBranch(branchId).stream()
                .collect(Collectors.toMap(BranchRoomListing::getListingId, BranchRoomListing::getRoomId));
    }

    private Map<String, BranchLeaseRow> leaseRows(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return assignments.findLeaseRows(ids).stream()
                .collect(Collectors.toMap(BranchLeaseRow::id, Function.identity()));
    }

    private String label(BranchLeaseRow lease) {
        return lease.tenantName() == null ? lease.contractNumber() : lease.contractNumber() + " · " + lease.tenantName();
    }

    private boolean rootLease(Contract c) {
        return c.getParentContractId() == null && !"DEPOSIT".equals(c.getAgreementKind());
    }

    private void requireOwnRootLease(String contractId, String ownerId) {
        Contract contract = contracts.findByIdForUpdate(contractId)
                .filter(c -> c.getLandlordId().equals(ownerId))
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_NOT_FOUND));
        if (!rootLease(contract)) throw new AppException(ContractErrorCode.CONTRACT_NOT_ASSIGNABLE);
    }

    private void requireOwnBranch(String branchId, String ownerId) {
        if (branches.findByIdAndOwnerIdAndActiveTrue(branchId, ownerId).isEmpty()) {
            throw new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND);
        }
    }

    private void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("page từ 0, size từ 1 đến 100");
    }

    private AppException invalid(String message) {
        return new AppException(ListingErrorCode.BRANCH_ASSIGNMENT_INVALID, message);
    }
}
