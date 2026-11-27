package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.SaveBranchLayoutRequest;
import com.hs.listing.dto.response.ListingBranchRef;
import com.hs.listing.model.BranchRoomListing;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.repository.BranchRoomListingRepository;
import com.hs.listing.repository.BranchSlotRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchRoomLinkServiceTest {
    @Mock PropertyBranchRepository branches;
    @Mock BranchSlotRepository slots;
    @Mock BranchRoomListingRepository links;
    @Mock BranchContractLinkGuard guard;
    @InjectMocks BranchRoomLinkService service;

    @Test
    void linksAreIdempotentAndUnlinkChecksExactRoom() {
        var branch = ownBranch();
        when(links.findOwnListing("l", "owner")).thenReturn(Optional.of(ref("b")));
        when(slots.findById("r")).thenReturn(Optional.of(slot(branch, "r", "101")));
        when(slots.findById("r2")).thenReturn(Optional.of(slot(branch, "r2", "102")));
        var link = new BranchRoomListing("l", "r");
        when(links.findById("l")).thenReturn(Optional.empty(), Optional.of(link));
        when(links.findLinkedBranchId("l")).thenReturn(Optional.of("b"));
        service.link("b", "owner", "r", "l");
        service.link("b", "owner", "r", "l");
        assertEquals(ListingErrorCode.LISTING_ALREADY_IN_ROOM.getCode(),
                assertThrows(AppException.class, () -> service.link("b", "owner", "r2", "l")).getCode());
        verify(links, times(1)).save(any(BranchRoomListing.class));
        service.unlink("b", "owner", "r2", "l");
        verify(links, never()).delete(any());
        service.unlink("b", "owner", "r", "l");
        verify(links).delete(link);
    }

    @Test
    void rejectsForeignOwnerCrossBranchAndAisle() {
        var branch = ownBranch();
        // A foreign owner's listing is not found by the owner-scoped query.
        when(links.findOwnListing("l", "owner")).thenReturn(Optional.empty(),
                Optional.of(ref("elsewhere")), Optional.of(ref("b")));
        assertEquals(404, assertThrows(AppException.class, () -> service.link("b", "owner", "r", "l"))
                .getStatusCode().value());
        verifyNoInteractions(slots);
        verify(links, never()).save(any());
        when(slots.findById("r")).thenReturn(Optional.of(slot(branch, "r", "101")), Optional.of(slot(branch, "r", null)));
        assertEquals(ListingErrorCode.LISTING_BRANCH_MISMATCH.getCode(),
                assertThrows(AppException.class, () -> service.link("b", "owner", "r", "l")).getCode());
        assertEquals(ListingErrorCode.BRANCH_ROOM_NOT_ASSIGNABLE.getCode(),
                assertThrows(AppException.class, () -> service.link("b", "owner", "r", "l")).getCode());
        verify(links, never()).findById(any());
        verify(links, never()).save(any());
    }

    @Test
    void flagAndLayoutCannotDisplaceOccupiedRoomAndRemovingHistoryUnplacesBeforeDelete() {
        ReflectionTestUtils.setField(service, "contractLinks", guard);
        var room = slot(ownBranch(), "r", "101");
        when(slots.findById("r")).thenReturn(Optional.of(room));
        when(guard.occupiedRooms(anyCollection())).thenReturn(Set.of("r"), Set.of("r"), Set.of());
        assertEquals(409, assertThrows(AppException.class,
                () -> service.setManualStatus("b", "owner", "r", RoomManualStatus.EXTERNAL_RENT)).getStatusCode().value());
        assertEquals(RoomManualStatus.NONE, BranchRoomLinkService.flagOf(room));
        assertThrows(AppException.class, () -> service.releaseRooms(List.of(room)));
        verify(guard, never()).unplaceContracts(anyCollection());
        verifyNoInteractions(links);
        service.releaseRooms(List.of(room));
        var ordered = inOrder(guard, links);
        ordered.verify(guard).unplaceContracts(Set.of("r"));
        ordered.verify(links).deleteByRoomIds(Set.of("r"));
    }

    @Test
    void acknowledgedDetachMustCoverEveryLinkOrNothingIsWritten() {
        ReflectionTestUtils.setField(service, "contractLinks", guard);
        var room = slot(null, "r", "101");
        when(links.findListingIdsByRoomIds(anyCollection())).thenReturn(List.of("l1", "l2"));
        when(guard.contractsInRooms(anyCollection())).thenReturn(Set.of("c1"));
        java.util.function.BiConsumer<List<String>, List<String>> save = (l, c) ->
                service.releaseRooms(List.of(room), new SaveBranchLayoutRequest.AcknowledgedDetach(l, c));
        for (var bad : List.of(List.of(List.of("l1"), List.of("c1")), List.of(List.of("l1", "l2"), List.<String>of()))) {
            assertEquals(ListingErrorCode.BRANCH_LAYOUT_LINKS_CHANGED.getCode(),
                    assertThrows(AppException.class, () -> save.accept(bad.get(0), bad.get(1))).getCode());
        }
        verify(guard, never()).unplaceContracts(anyCollection());
        verify(links, never()).deleteByRoomIds(anyCollection());
        save.accept(List.of("l1", "l2", "extra"), List.of("c1", "extra"));
        verify(guard).unplaceContracts(Set.of("r"));
        verify(links).deleteByRoomIds(Set.of("r"));
    }

    @Test
    void absentAcknowledgementKeepsOldBehaviourAndOccupiedRoomStill4075() {
        ReflectionTestUtils.setField(service, "contractLinks", guard);
        var room = slot(null, "r", "101");
        service.releaseRooms(List.of(room), null);
        verify(links, never()).findListingIdsByRoomIds(anyCollection());
        verify(links).deleteByRoomIds(Set.of("r"));
        when(guard.occupiedRooms(anyCollection())).thenReturn(Set.of("r"));
        var ack = new SaveBranchLayoutRequest.AcknowledgedDetach(List.of(), List.of());
        assertEquals(ListingErrorCode.BRANCH_ROOM_IN_USE.getCode(),
                assertThrows(AppException.class, () -> service.releaseRooms(List.of(room), ack)).getCode());
    }

    @Test
    void onlyAChangedLiveBranchDeletesListingLink() {
        when(links.findLinkedBranchId("l")).thenReturn(Optional.of("b"));
        service.dropLinkOutsideBranch("l", "b", "b");
        verifyNoInteractions(branches);
        verify(links, never()).deleteLink(any());
        clearInvocations(links);
        service.dropLinkOutsideBranch("l", "b", null);
        var ordered = inOrder(branches, links);
        // Source branch first, then the link lookup.
        ordered.verify(branches).lockForListingWrite("b");
        ordered.verify(links).findLinkedBranchId("l");
        ordered.verify(links).deleteLink("l");
        verifyNoInteractions(guard);
    }

    private PropertyBranch ownBranch() {
        var branch = PropertyBranch.builder().id("b").ownerId("owner").build();
        when(branches.findByIdForUpdate("b")).thenReturn(Optional.of(branch));
        return branch;
    }

    private static BranchSlot slot(PropertyBranch branch, String id, String code) {
        var slot = new BranchSlot();
        slot.setBranch(branch);
        slot.setId(id);
        slot.setRoomCode(code);
        return slot;
    }

    private static ListingBranchRef ref(String branch) {
        return new ListingBranchRef("l", branch);
    }
}
