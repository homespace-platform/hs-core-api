package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.SaveBranchLayoutRequest;
import com.hs.listing.dto.response.BranchLayoutResponse;
import com.hs.listing.model.BranchFloor;
import com.hs.listing.model.BranchLayout;
import com.hs.listing.model.BranchRow;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.repository.BranchFloorRepository;
import com.hs.listing.repository.BranchLayoutRepository;
import com.hs.listing.repository.BranchRowRepository;
import com.hs.listing.repository.BranchSlotRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BranchLayoutService {

    private static final int MAX_TEXT = 255;
    private static final int MAX_FLOORS = 30;
    private static final int MAX_ROWS_PER_FLOOR = 10;
    private static final int MAX_SLOTS_PER_ROW = 100;
    private static final int MAX_SLOTS = 1500;
    private static final int MAX_ACK_IDS = 5000;

    private final PropertyBranchRepository branchRepository;
    private final BranchLayoutRepository layoutRepository;
    private final BranchFloorRepository floorRepository;
    private final BranchRowRepository rowRepository;
    private final BranchSlotRepository slotRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BranchRoomLinkService roomLinks;

    // One snapshot for all reads, so a concurrent save cannot produce a half-old tree.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchLayoutResponse getLayout(String branchId, String ownerId) {
        branchRepository.findByIdAndOwnerIdAndActiveTrue(branchId, ownerId).orElseThrow(this::branchNotFound);
        long revision = layoutRepository.findById(branchId).map(BranchLayout::getRevision).orElse(0L);
        return toResponse(revision,
                floorRepository.findAllByBranchIdOrderByPosition(branchId),
                rowRepository.findAllByFloorBranchIdOrderByPosition(branchId),
                slotRepository.findAllByBranchIdOrderByPosition(branchId));
    }

    @Transactional
    public BranchLayoutResponse saveLayout(String branchId, String ownerId, SaveBranchLayoutRequest request) {
        // The branch lock serialises layout writes; take it before reading anything it protects.
        PropertyBranch branch = branchRepository.findByIdForUpdate(branchId)
                .filter(b -> b.getOwnerId().equals(ownerId))
                .orElseThrow(this::branchNotFound);

        BranchLayout layout = layoutRepository.findById(branchId).orElse(null);
        long revision = layout == null ? 0 : layout.getRevision();
        if (request.expectedRevision() == null) throw invalid("Thiếu expectedRevision");
        // Checked before the tree: a stale draft may carry ids that no longer exist and must get 409, not 400.
        if (request.expectedRevision() != revision) {
            throw new AppException(ListingErrorCode.BRANCH_LAYOUT_STALE);
        }

        checkSize(request);

        Map<String, BranchFloor> oldFloors = byId(
                floorRepository.findAllByBranchIdOrderByPosition(branchId), BranchFloor::getId);
        Map<String, BranchRow> oldRows = byId(
                rowRepository.findAllByFloorBranchIdOrderByPosition(branchId), BranchRow::getId);
        Map<String, BranchSlot> oldSlots = byId(
                slotRepository.findAllByBranchIdOrderByPosition(branchId), BranchSlot::getId);
        validate(request, oldFloors.keySet(), oldRows.keySet(), oldSlots.keySet());

        Set<String> keptFloors = new HashSet<>();
        Set<String> keptRows = new HashSet<>();
        Map<String, String> finalCodes = new HashMap<>();
        for (var floor : request.floors()) {
            keptFloors.add(floor.id());
            for (var row : floor.rows()) {
                keptRows.add(row.id());
                for (var slot : row.slots()) {
                    if (slot.id() != null) finalCodes.put(slot.id(), normalize(slot.roomCode()));
                }
            }
        }

        // Rooms that disappear or become aisles: checked and unlinked before the tree is written.
        List<BranchSlot> removedRooms = oldSlots.values().stream()
                .filter(s -> s.getRoomCode() != null && finalCodes.get(s.getId()) == null)
                .toList();
        if (roomLinks != null && !removedRooms.isEmpty()) roomLinks.releaseRooms(removedRooms, request.acknowledgedDetach());

        // Hibernate flushes inserts, then updates, then deletes, and PostgreSQL checks the unique room code
        // per statement. So first flush removed slots and release every code that changes; only then write
        // final codes. Otherwise a swap, or reusing a removed room's code, violates the constraint.
        for (BranchSlot slot : oldSlots.values()) {
            if (!finalCodes.containsKey(slot.getId())) {
                slotRepository.delete(slot);
            } else if (!Objects.equals(slot.getNormalizedRoomCode(), finalCodes.get(slot.getId()))) {
                slot.setNormalizedRoomCode(null);
            }
        }
        slotRepository.flush();

        List<BranchFloor> floors = new ArrayList<>();
        List<BranchRow> rows = new ArrayList<>();
        List<BranchSlot> slots = new ArrayList<>();
        int floorPosition = 0;
        for (var floorRequest : request.floors()) {
            BranchFloor floor = floorRequest.id() == null ? new BranchFloor() : oldFloors.get(floorRequest.id());
            floor.setBranch(branch);
            floor.setLabel(floorRequest.label().strip());
            floor.setPosition(floorPosition++);
            if (floorRequest.id() == null) floor = floorRepository.save(floor);
            floors.add(floor);

            int rowPosition = 0;
            for (var rowRequest : floorRequest.rows()) {
                BranchRow row = rowRequest.id() == null ? new BranchRow() : oldRows.get(rowRequest.id());
                row.setFloor(floor);
                row.setLabel(rowRequest.label().strip());
                row.setPosition(rowPosition++);
                if (rowRequest.id() == null) row = rowRepository.save(row);
                rows.add(row);

                int slotPosition = 0;
                for (var slotRequest : rowRequest.slots()) {
                    BranchSlot slot = slotRequest.id() == null ? new BranchSlot() : oldSlots.get(slotRequest.id());
                    slot.setBranch(branch);
                    slot.setRow(row);
                    slot.setPosition(slotPosition++);
                    slot.setRoomCode(slotRequest.roomCode() == null ? null : slotRequest.roomCode().strip());
                    slot.setNormalizedRoomCode(normalize(slotRequest.roomCode()));
                    if (slotRequest.roomCode() == null) slot.setManualStatus(null);
                    if (slotRequest.id() == null) slot = slotRepository.save(slot);
                    slots.add(slot);
                }
            }
        }
        // Moved rows and slots must be re-parented in the database before their old parent is deleted.
        slotRepository.flush();
        oldRows.values().stream().filter(r -> !keptRows.contains(r.getId())).forEach(rowRepository::delete);
        rowRepository.flush();
        oldFloors.values().stream().filter(f -> !keptFloors.contains(f.getId())).forEach(floorRepository::delete);

        if (layout == null) {
            layout = new BranchLayout();
            layout.setBranch(branch);
        }
        layout.setRevision(revision + 1);
        layoutRepository.save(layout);

        return toResponse(revision + 1, floors, rows, slots);
    }

    // Runs before any tree is loaded; null nodes are left for validate() to reject. Aisles count as slots.
    private void checkSize(SaveBranchLayoutRequest request) {
        var ack = request.acknowledgedDetach();
        if (ack != null) {
            for (var ids : java.util.Arrays.asList(ack.listingIds(), ack.contractIds())) {
                if (ids == null) continue;
                if (ids.size() > MAX_ACK_IDS) throw invalid("Danh sách liên kết xác nhận quá dài");
                if (ids.stream().anyMatch(java.util.Objects::isNull)) throw invalid("Mã liên kết xác nhận không hợp lệ");
            }
        }
        if (request.floors() == null) return;
        if (request.floors().size() > MAX_FLOORS) throw invalid("Sơ đồ tối đa " + MAX_FLOORS + " tầng/khu");
        int total = 0;
        for (var floor : request.floors()) {
            if (floor == null || floor.rows() == null) continue;
            if (floor.rows().size() > MAX_ROWS_PER_FLOOR) {
                throw invalid("Mỗi tầng/khu tối đa " + MAX_ROWS_PER_FLOOR + " dãy");
            }
            for (var row : floor.rows()) {
                if (row == null || row.slots() == null) continue;
                if (row.slots().size() > MAX_SLOTS_PER_ROW) {
                    throw invalid("Mỗi dãy tối đa " + MAX_SLOTS_PER_ROW + " ô (tính cả lối đi)");
                }
                total += row.slots().size();
                if (total > MAX_SLOTS) {
                    throw invalid("Sơ đồ chi nhánh tối đa " + MAX_SLOTS + " ô (tính cả lối đi)");
                }
            }
        }
    }

    private void validate(SaveBranchLayoutRequest request, Set<String> floorIds, Set<String> rowIds,
                          Set<String> slotIds) {
        if (request.floors() == null || request.floors().isEmpty()) throw invalid("Sơ đồ cần ít nhất một tầng/khu");
        // Stored ids are random UUIDs, so one "seen" set covers all three levels.
        Set<String> seenIds = new HashSet<>();
        Set<String> codes = new HashSet<>();
        int rowCount = 0;
        for (var floor : request.floors()) {
            if (floor == null || floor.rows() == null) throw invalid("Tầng/khu không hợp lệ");
            checkId(floor.id(), floorIds, seenIds);
            checkText(floor.label(), "Tên tầng/khu");
            for (var row : floor.rows()) {
                if (row == null || row.slots() == null) throw invalid("Dãy không hợp lệ");
                rowCount++;
                checkId(row.id(), rowIds, seenIds);
                checkText(row.label(), "Tên dãy");
                for (var slot : row.slots()) {
                    if (slot == null) throw invalid("Ô không hợp lệ");
                    checkId(slot.id(), slotIds, seenIds);
                    if (slot.roomCode() == null) continue;
                    checkText(slot.roomCode(), "Mã phòng");
                    if (!codes.add(normalize(slot.roomCode()))) {
                        throw invalid("Mã phòng bị trùng: " + slot.roomCode().strip());
                    }
                }
            }
        }
        if (rowCount == 0) throw invalid("Sơ đồ cần ít nhất một dãy");
        if (codes.isEmpty()) throw invalid("Sơ đồ cần ít nhất một phòng");
    }

    private void checkId(String id, Set<String> existing, Set<String> seen) {
        if (id != null && (!existing.contains(id) || !seen.add(id))) {
            throw invalid("Mã định danh trong sơ đồ không hợp lệ");
        }
    }

    private void checkText(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field + " không được để trống");
        if (value.strip().length() > MAX_TEXT) throw invalid(field + " tối đa " + MAX_TEXT + " ký tự");
    }

    private static String normalize(String roomCode) {
        return roomCode == null ? null : roomCode.strip().toUpperCase(Locale.ROOT);
    }

    private static <T> Map<String, T> byId(List<T> nodes, Function<T, String> id) {
        return nodes.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    // Inputs only need to be in position order within each parent.
    private BranchLayoutResponse toResponse(long revision, List<BranchFloor> floors, List<BranchRow> rows,
                                            List<BranchSlot> slots) {
        Map<String, List<BranchLayoutResponse.Slot>> slotsByRow = slots.stream().collect(Collectors.groupingBy(
                s -> s.getRow().getId(),
                Collectors.mapping(s -> new BranchLayoutResponse.Slot(s.getId(), s.getRoomCode()),
                        Collectors.toList())));
        Map<String, List<BranchLayoutResponse.Row>> rowsByFloor = rows.stream().collect(Collectors.groupingBy(
                r -> r.getFloor().getId(),
                Collectors.mapping(r -> new BranchLayoutResponse.Row(r.getId(), r.getLabel(),
                        slotsByRow.getOrDefault(r.getId(), List.of())), Collectors.toList())));
        return new BranchLayoutResponse(revision, floors.stream()
                .map(f -> new BranchLayoutResponse.Floor(f.getId(), f.getLabel(),
                        rowsByFloor.getOrDefault(f.getId(), List.of())))
                .toList());
    }

    private AppException invalid(String message) {
        return new AppException(ListingErrorCode.BRANCH_LAYOUT_INVALID, message);
    }

    private AppException branchNotFound() {
        return new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND);
    }
}
