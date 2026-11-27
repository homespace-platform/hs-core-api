package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchRoomContract;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Linking a listing to a room also attaches the listing's effective root lease, never moving or overriding one. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BranchListingLeaseAttachTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 27);
    @Mock BranchContractAssignmentRepository links;
    @Mock ContractRepository contracts;
    @Mock PropertyBranchRepository branches;
    @Mock BranchSlotRepository slots;
    @Mock BranchRoomListingRepository listingLinks;
    @Mock BranchRoomLinkService rooms;
    @Mock BillingTime time;
    @InjectMocks BranchAssignmentService service;

    @BeforeEach
    void stubs() {
        when(time.today()).thenReturn(DAY);
        when(listingLinks.findById("l")).thenReturn(Optional.of(new BranchRoomListing("l", "r")));
        when(links.findEffectiveRootIds("l", "owner")).thenReturn(List.of("c"));
        when(contracts.findByIdForUpdate("c")).thenReturn(Optional.of(
                Contract.builder().id("c").landlordId("owner").listingId("l").build()));
        when(rooms.requireRoom("b", "r")).thenReturn(room(RoomManualStatus.NONE));
        when(slots.findById("r2")).thenReturn(Optional.of(room(RoomManualStatus.NONE)));
        leases(lease("c", ContractStatus.ACTIVE, DAY.minusMonths(1), null));
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"ACTIVE", "EXPIRED"})
    void effectiveLeaseWithoutALinkIsAttachedToTheRoom(ContractStatus status) {
        leases(lease("c", status, DAY.minusMonths(1), null));
        var result = service.attachListingLease("b", "owner", "r", "l");
        assertEquals("c", result.contractId());
        assertNull(result.note());
        var saved = ArgumentCaptor.forClass(BranchContractAssignment.class);
        verify(links).save(saved.capture());
        assertEquals("r", saved.getValue().getRoomId());
        assertEquals("b", saved.getValue().getManagementBranchId());
        var order = inOrder(contracts, rooms);
        order.verify(contracts).findByIdForUpdate("c"); // contract lock first, then the branch lock
        order.verify(rooms).lockBranch("b", "owner");
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"CLOSED", "TERMINATED", "CANCELLED", "DRAFT", "PENDING_REVIEW"})
    void endedOrNotYetEffectiveLeasesAreIgnored(ContractStatus status) {
        leases(lease("c", status, DAY.minusMonths(1), null));
        var result = service.attachListingLease("b", "owner", "r", "l");
        assertNull(result.contractId());
        assertNull(result.note());
        verify(links, never()).save(any());
        verifyNoInteractions(contracts);
    }

    @Test
    void handedOverAndFutureStartLeasesAreIgnored() {
        leases(lease("c", ContractStatus.EXPIRED, DAY.minusMonths(6), Instant.EPOCH));
        assertNull(service.attachListingLease("b", "owner", "r", "l").contractId());
        leases(lease("c", ContractStatus.ACTIVE, DAY.plusDays(3), null));
        assertNull(service.attachListingLease("b", "owner", "r", "l").contractId());
        verify(links, never()).save(any());
    }

    @Test
    void leaseInAnotherRoomOfTheBranchIsNotMovedButReported() {
        var assignment = new BranchContractAssignment("c", "b", "r2");
        when(links.findById("c")).thenReturn(Optional.of(assignment));
        var result = service.attachListingLease("b", "owner", "r", "l");
        assertNull(result.contractId());
        assertTrue(result.note().contains("101"), result.note());
        assertEquals("r2", assignment.getRoomId());
        verify(links, never()).save(any());
    }

    @Test
    void leaseInTheUnplacedTrayIsPlacedInTheRoom() {
        var assignment = new BranchContractAssignment("c", "b", null);
        when(links.findById("c")).thenReturn(Optional.of(assignment));
        var result = service.attachListingLease("b", "owner", "r", "l");
        assertEquals("c", result.contractId());
        assertEquals("r", assignment.getRoomId());
    }

    @Test
    void leaseOfAnotherBranchIsLeftAloneAndReported() {
        var assignment = new BranchContractAssignment("c", "other", "x");
        when(links.findById("c")).thenReturn(Optional.of(assignment));
        var result = service.attachListingLease("b", "owner", "r", "l");
        assertNull(result.contractId());
        assertNotNull(result.note());
        assertEquals("x", assignment.getRoomId());
        verify(links, never()).save(any());
    }

    @Test
    void scheduleConflictWithALeaseInTheRoomIsReportedAndNothingIsWritten() {
        var occupant = lease("o", ContractStatus.ACTIVE, DAY.minusMonths(1), null);
        leases(lease("c", ContractStatus.ACTIVE, DAY.minusMonths(1), null), occupant);
        when(links.findRoomContracts(List.of("r"))).thenReturn(List.of(new BranchRoomContract("r", "o")));
        var result = service.attachListingLease("b", "owner", "r", "l");
        assertNull(result.contractId());
        assertTrue(result.note().contains("Trùng lịch"), result.note());
        verify(links, never()).save(any());
    }

    @Test
    void flaggedRoomKeepsItsFlagAndLeaseIsNotAttached() {
        when(rooms.requireRoom("b", "r")).thenReturn(room(RoomManualStatus.REPAIR));
        var result = service.attachListingLease("b", "owner", "r", "l");
        assertNull(result.contractId());
        assertNotNull(result.note());
        verify(links, never()).save(any());
    }

    @Test
    void listingNotLinkedToThisRoomAttachesNothing() {
        when(listingLinks.findById("l")).thenReturn(Optional.of(new BranchRoomListing("l", "elsewhere")));
        assertNull(service.attachListingLease("b", "owner", "r", "l").contractId());
        verifyNoInteractions(contracts);
    }

    private void leases(BranchLeaseRow... rows) {
        when(links.findLeaseRows(anyCollection())).thenAnswer(call -> List.of(rows).stream()
                .filter(row -> call.<java.util.Collection<String>>getArgument(0).contains(row.id())).toList());
    }

    private static BranchLeaseRow lease(String id, ContractStatus status, LocalDate start, Instant handedOver) {
        return new BranchLeaseRow(id, id, status, null, null, null, start, 12, "Tenant", handedOver);
    }

    private static BranchSlot room(RoomManualStatus flag) {
        var slot = new BranchSlot();
        slot.setId("r");
        slot.setBranch(PropertyBranch.builder().id("b").ownerId("owner").build());
        slot.setRoomCode("101");
        slot.setManualStatus(flag);
        return slot;
    }
}
