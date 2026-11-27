package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.response.ListingBranchRef;
import com.hs.listing.model.BranchRoomListing;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.Listing;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.repository.BranchRoomListingRepository;
import com.hs.listing.repository.BranchSlotRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Room links of listings and manual room flags. The branch row lock serialises every write to the layout and
 * its links; a locked contract is always locked before the branch. Listing rows are never locked here: parking
 * locks branch then listing while acceptance locks listing then branch (see PropertyBranchRepository).
 */
@Service
@RequiredArgsConstructor
public class BranchRoomLinkService {

    private final PropertyBranchRepository branchRepository;
    private final BranchSlotRepository slotRepository;
    private final BranchRoomListingRepository linkRepository;
    @Autowired(required = false)
    private BranchContractLinkGuard contractLinks;

    public PropertyBranch lockBranch(String branchId, String ownerId) {
        return branchRepository.findByIdForUpdate(branchId)
                .filter(b -> b.getOwnerId().equals(ownerId))
                .orElseThrow(() -> notFound("Không tìm thấy chi nhánh"));
    }

    /** Also blocks listings from moving into or out of the branch until commit. */
    private void lockBranchAgainstListingMoves(String branchId, String ownerId) {
        branchRepository.lockExclusive(branchId);
        lockBranch(branchId, ownerId);
    }

    /** Call only after the branch has been checked. */
    public BranchSlot requireRoom(String branchId, String roomId) {
        BranchSlot slot = slotRepository.findById(roomId)
                .filter(s -> s.getBranch().getId().equals(branchId))
                .orElseThrow(() -> notFound("Không tìm thấy phòng trong chi nhánh"));
        if (slot.getRoomCode() == null) throw new AppException(ListingErrorCode.BRANCH_ROOM_NOT_ASSIGNABLE);
        return slot;
    }

    @Transactional
    public void link(String branchId, String ownerId, String roomId, String listingId) {
        lockBranchAgainstListingMoves(branchId, ownerId);
        ListingBranchRef listing = requireListing(ownerId, listingId);
        BranchSlot room = requireRoom(branchId, roomId);
        if (!branchId.equals(listing.branchId())) {
            throw new AppException(ListingErrorCode.LISTING_BRANCH_MISMATCH);
        }
        BranchRoomListing existing = linkRepository.findById(listingId).orElse(null);
        if (existing != null) {
            if (existing.getRoomId().equals(roomId)) return;
            if (linkRepository.findLinkedBranchId(listingId).filter(branchId::equals).isPresent()) {
                throw new AppException(ListingErrorCode.LISTING_ALREADY_IN_ROOM);
            }
            existing.setRoomId(roomId); // leftover link into another branch: not visible anywhere, replace it
        } else {
            linkRepository.save(new BranchRoomListing(listingId, roomId));
        }
        // A flagged room cannot receive a listing that brings an accepted rental with it.
        if (flagOf(room) != RoomManualStatus.NONE && contractLinks != null) {
            linkRepository.flush();
            if (!contractLinks.occupiedRooms(List.of(roomId)).isEmpty()) {
                throw new AppException(ListingErrorCode.BRANCH_ROOM_STATUS_CONFLICT);
            }
        }
    }

    @Transactional
    public void unlink(String branchId, String ownerId, String roomId, String listingId) {
        lockBranchAgainstListingMoves(branchId, ownerId);
        requireListing(ownerId, listingId);
        requireRoom(branchId, roomId);
        linkRepository.findById(listingId)
                .filter(k -> k.getRoomId().equals(roomId))
                .ifPresent(linkRepository::delete);
    }

    @Transactional
    public RoomManualStatus setManualStatus(String branchId, String ownerId, String roomId, RoomManualStatus status) {
        lockBranch(branchId, ownerId);
        BranchSlot room = requireRoom(branchId, roomId);
        if (status != RoomManualStatus.NONE && contractLinks != null
                && !contractLinks.occupiedRooms(List.of(roomId)).isEmpty()) {
            throw new AppException(ListingErrorCode.BRANCH_ROOM_STATUS_CONFLICT);
        }
        room.setManualStatus(status);
        return status;
    }

    /** Link of a just-created listing, in the creation transaction. Any invalid room rejects the creation. */
    @Transactional
    public void linkNewListing(Listing listing, String ownerId, String roomId) {
        PropertyBranch branch = branchRepository.findByIdForUpdate(listing.getBranchId())
                .filter(b -> b.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_ROOM_INVALID));
        BranchSlot room = slotRepository.findById(roomId)
                .filter(s -> s.getBranch().getId().equals(branch.getId()) && s.getRoomCode() != null)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_ROOM_INVALID));
        linkRepository.save(new BranchRoomListing(listing.getId(), room.getId()));
    }

    /**
     * Called when a listing's live branch is written, with the listing row locked; contract links stay where they
     * are. The source branch is locked before looking for a link, even when there is none yet: a concurrent attach
     * or auto-link holds it FOR UPDATE, so it either commits first and is seen here or waits and sees the move.
     */
    @Transactional
    public void dropLinkOutsideBranch(String listingId, String previousBranchId, String newBranchId) {
        if (previousBranchId != null && !previousBranchId.equals(newBranchId)) {
            branchRepository.lockForListingWrite(previousBranchId);
        }
        String linkedBranchId = linkRepository.findLinkedBranchId(listingId).orElse(null);
        if (linkedBranchId == null || linkedBranchId.equals(newBranchId)) return;
        branchRepository.lockForListingWrite(linkedBranchId);
        linkRepository.deleteLink(listingId);
    }

    /** Rooms leaving the layout. Runs under the branch lock and throws before anything is written. */
    public void releaseRooms(Collection<BranchSlot> rooms) {
        Map<String, String> codes = rooms.stream()
                .collect(Collectors.toMap(BranchSlot::getId, BranchSlot::getRoomCode));
        if (contractLinks != null) {
            Set<String> occupied = contractLinks.occupiedRooms(codes.keySet());
            if (!occupied.isEmpty()) {
                String names = occupied.stream().map(codes::get).sorted().limit(10).collect(Collectors.joining(", "));
                throw new AppException(ListingErrorCode.BRANCH_ROOM_IN_USE, "Không thể bỏ phòng " + names
                        + " vì còn hợp đồng hiệu lực hoặc khách đang giữ chỗ. Hãy chuyển hoặc gỡ liên kết trước.");
            }
            contractLinks.unplaceContracts(codes.keySet());
        }
        linkRepository.deleteByRoomIds(codes.keySet());
    }

    public static RoomManualStatus flagOf(BranchSlot room) {
        return room.getManualStatus() == null ? RoomManualStatus.NONE : room.getManualStatus();
    }

    private ListingBranchRef requireListing(String ownerId, String listingId) {
        return linkRepository.findOwnListing(listingId, ownerId)
                .orElseThrow(() -> notFound("Không tìm thấy tin đăng"));
    }

    private AppException notFound(String message) {
        return new AppException(404, message, HttpStatus.NOT_FOUND);
    }
}
