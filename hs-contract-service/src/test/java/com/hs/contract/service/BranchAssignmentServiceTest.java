package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.model.BranchContractAssignment;
import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.model.BranchRoomListing;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.repository.BranchRoomListingRepository;
import com.hs.listing.repository.BranchSlotRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.listing.service.BranchRoomLinkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchAssignmentServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 27);
    @Mock BranchContractAssignmentRepository links;
    @Mock ContractRepository contracts;
    @Mock PropertyBranchRepository branches;
    @Mock BranchSlotRepository slots;
    @Mock BranchRoomListingRepository listingLinks;
    @Mock BranchRoomLinkService rooms;
    @Mock BillingTime time;
    @InjectMocks BranchAssignmentService service;

    @Test
    void attachToTrayMoveAndDetachNeverSaveBusinessContract() {
        var contract = ownLease();
        when(contracts.findByIdForUpdate("c")).thenReturn(Optional.of(contract));
        assertNull(service.attach("b", "owner", "c", null).roomId());
        var captured = ArgumentCaptor.forClass(BranchContractAssignment.class);
        verify(links).save(captured.capture());
        var row = captured.getValue();
        assertEquals("b", row.getManagementBranchId());
        assertNull(row.getRoomId());
        when(links.findById("c")).thenReturn(Optional.of(row));
        when(rooms.requireRoom("b", "r")).thenReturn(room(RoomManualStatus.NONE));
        service.attach("b", "owner", "c", "r");
        assertEquals("r", row.getRoomId());
        service.detach("b", "owner", "c");
        verify(links).delete(row);
        var ordered = inOrder(contracts, rooms);
        ordered.verify(contracts).findByIdForUpdate("c");
        ordered.verify(rooms).lockBranch("b", "owner");
        assertEquals("parking-branch", contract.getBranchId());
        verify(contracts, never()).save(any());
    }

    @Test
    void addingToTrayKeepsTheSavedRoomEvenIfItIsHiddenInTheReadModel() {
        when(contracts.findByIdForUpdate("c")).thenReturn(Optional.of(ownLease()));
        assertNull(service.addToTray("b", "owner", "c").roomId());
        var captured = ArgumentCaptor.forClass(BranchContractAssignment.class);
        verify(links).save(captured.capture());
        var row = captured.getValue();
        row.setRoomId("saved-room");
        when(links.findById("c")).thenReturn(Optional.of(row));

        assertEquals("saved-room", service.addToTray("b", "owner", "c").roomId());
        assertEquals("saved-room", row.getRoomId());
        verify(links, times(1)).save(any());

        service.attach("b", "owner", "c", null);
        assertNull(row.getRoomId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "DEPOSIT", "child"})
    void attachRejectsForeignOwnerDepositAndChildBeforeLockingBranch(String type) {
        var contract = ownLease();
        if ("foreign".equals(type)) contract.setLandlordId("other");
        if ("DEPOSIT".equals(type)) contract.setAgreementKind(type);
        if ("child".equals(type)) contract.setParentContractId("root");
        when(contracts.findByIdForUpdate("c")).thenReturn(Optional.of(contract));
        assertEquals("foreign".equals(type) ? 404 : 400,
                assertThrows(AppException.class, () -> service.attach("b", "owner", "c", null)).getStatusCode().value());
        verifyNoInteractions(rooms, links);
    }

    @Test
    void attachAndDetachCannotTransferFromAnotherBranch() {
        when(contracts.findByIdForUpdate("c")).thenReturn(Optional.of(ownLease()));
        var elsewhere = new BranchContractAssignment("c", "other", "old");
        when(links.findById("c")).thenReturn(Optional.of(elsewhere));
        assertEquals(ContractErrorCode.CONTRACT_IN_OTHER_BRANCH.getCode(),
                assertThrows(AppException.class, () -> service.attach("b", "owner", "c", null)).getCode());
        assertThrows(AppException.class, () -> service.detach("b", "owner", "c"));
        verify(links, never()).save(any());
        verify(links, never()).delete(any());
        assertEquals("old", elsewhere.getRoomId());
    }

    @Test
    void autolinkUsesTrayWhenFlaggedAndSkipsWhenListingLeavesItsBranchWhileWaiting() {
        when(contracts.findByIdForUpdate("c")).thenReturn(Optional.of(ownLease()));
        when(branches.findByIdForUpdate("b")).thenReturn(Optional.of(branch()));
        when(listingLinks.findActiveListingBranchId("l", "owner")).thenReturn(
                Optional.of("b"), Optional.empty(), Optional.of("b"), Optional.of("b"));
        service.autoLink("c");
        verify(links, never()).save(any());

        when(listingLinks.findById("l")).thenReturn(Optional.of(new BranchRoomListing("l", "r")));
        when(slots.findById("r")).thenReturn(Optional.of(room(RoomManualStatus.REPAIR)));
        when(links.findLeaseRows(anyCollection())).thenReturn(List.of(lease("c", ContractStatus.DRAFT, DAY, 3, null)));
        service.autoLink("c");
        var captured = ArgumentCaptor.forClass(BranchContractAssignment.class);
        verify(links).save(captured.capture());
        assertEquals("b", captured.getValue().getManagementBranchId());
        assertNull(captured.getValue().getRoomId());
    }

    @Test
    void exclusiveBoundariesAllowNextTenantUntilAnOverdueOccupantBlocksTheDay() {
        var prior = lease("old", ContractStatus.EXPIRED, DAY.minusMonths(3), 3, null);
        var next = lease("c", ContractStatus.DRAFT, DAY, 3, null);
        assertFalse(BranchRoomSchedule.overlaps(prior, next, DAY.minusDays(1)));
        assertEquals(List.of(prior), BranchRoomSchedule.placed(List.of(next, prior), DAY));
        var handedOver = lease("old", ContractStatus.EXPIRED, DAY.minusMonths(3), 3, Instant.EPOCH);
        assertEquals(2, BranchRoomSchedule.placed(List.of(handedOver, next), DAY).size());
    }

    private static Contract ownLease() {
        return Contract.builder().id("c").landlordId("owner").listingId("l").branchId("parking-branch").build();
    }

    private static PropertyBranch branch() {
        return PropertyBranch.builder().id("b").ownerId("owner").build();
    }

    private static BranchSlot room(RoomManualStatus flag) {
        var slot = new BranchSlot();
        slot.setId("r");
        slot.setBranch(branch());
        slot.setRoomCode("101");
        slot.setManualStatus(flag);
        return slot;
    }

    private static BranchLeaseRow lease(String id, ContractStatus status, LocalDate start, int months, Instant accepted) {
        return new BranchLeaseRow(id, id, status, null, null, null, start, months, "Tenant", accepted);
    }
}
