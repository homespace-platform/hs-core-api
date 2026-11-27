package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.SaveBranchLayoutRequest;
import com.hs.listing.dto.request.SaveBranchLayoutRequest.Floor;
import com.hs.listing.dto.request.SaveBranchLayoutRequest.Row;
import com.hs.listing.dto.request.SaveBranchLayoutRequest.Slot;
import com.hs.listing.dto.response.BranchLayoutResponse;
import com.hs.listing.model.BranchFloor;
import com.hs.listing.model.BranchLayout;
import com.hs.listing.model.BranchRow;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchLayoutServiceTest {
    private static final String BRANCH = "branch-a";
    private static final String OWNER = "owner-a";

    private final PropertyBranchRepository branches = mock(PropertyBranchRepository.class);
    private final BranchLayoutRepository layouts = mock(BranchLayoutRepository.class);
    private final BranchFloorRepository floors = mock(BranchFloorRepository.class);
    private final BranchRowRepository rows = mock(BranchRowRepository.class);
    private final BranchSlotRepository slots = mock(BranchSlotRepository.class);
    private final BranchLayoutService service = new BranchLayoutService(branches, layouts, floors, rows, slots);
    private final PropertyBranch branch = PropertyBranch.builder().id(BRANCH).ownerId(OWNER).build();

    @Test
    void skippedLayoutReadsAsAnEmptyTreeWithoutWritingOrLocking() {
        when(branches.findByIdAndOwnerIdAndActiveTrue(BRANCH, OWNER)).thenReturn(Optional.of(branch));
        var empty = new BranchLayoutResponse(0, List.of());
        assertEquals(empty, service.getLayout(BRANCH, OWNER));
        assertEquals(empty, service.getLayout(BRANCH, OWNER));

        verify(branches, times(2)).findByIdAndOwnerIdAndActiveTrue(BRANCH, OWNER);
        verify(layouts, times(2)).findById(BRANCH);
        verify(floors, times(2)).findAllByBranchIdOrderByPosition(BRANCH);
        verify(rows, times(2)).findAllByFloorBranchIdOrderByPosition(BRANCH);
        verify(slots, times(2)).findAllByBranchIdOrderByPosition(BRANCH);
        verifyNoMoreInteractions(branches, layouts, floors, rows, slots);
    }

    @Test
    void anotherOwnerOrInactiveBranchCannotReadOrWriteTheTree() {
        when(branches.findByIdForUpdate(BRANCH)).thenReturn(Optional.of(branch), Optional.empty());

        for (String owner : List.of("owner-b", OWNER)) {
            var read = assertThrows(AppException.class, () -> service.getLayout(BRANCH, owner));
            var write = assertThrows(AppException.class,
                    () -> service.saveLayout(BRANCH, owner, request(0L, null, null, new Slot(null, "A1"))));
            assertEquals(HttpStatus.NOT_FOUND, read.getStatusCode());
            assertEquals(read.getStatusCode(), write.getStatusCode());
            assertEquals(read.getErrorMessage(), write.getErrorMessage());
        }
        verifyNoInteractions(layouts, floors, rows, slots);
    }

    @Test
    void firstSaveAllocatesIdsAndKeepsAisleSeparateFromRooms() {
        when(branches.findByIdForUpdate(BRANCH)).thenReturn(Optional.of(branch));
        when(floors.save(any())).thenAnswer(call -> {
            BranchFloor floor = call.getArgument(0);
            floor.setId("new-floor");
            return floor;
        });
        when(rows.save(any())).thenAnswer(call -> {
            BranchRow row = call.getArgument(0);
            assertEquals("new-floor", row.getFloor().getId());
            row.setId("new-row");
            return row;
        });
        when(slots.save(any())).thenAnswer(call -> {
            BranchSlot slot = call.getArgument(0);
            assertSame(branch, slot.getBranch());
            assertEquals("new-row", slot.getRow().getId());
            assertEquals(slot.getPosition() == 0 ? "A1" : null, slot.getNormalizedRoomCode());
            slot.setId("new-slot-" + slot.getPosition());
            return slot;
        });

        var saved = service.saveLayout(BRANCH, OWNER, request(0L, null, null, new Slot(null, " a1 "), new Slot(null, null)));

        assertEquals(1, saved.revision());
        var floor = saved.floors().getFirst();
        assertEquals("new-floor", floor.id());
        assertEquals("Floor", floor.label());
        assertEquals(List.of(new BranchLayoutResponse.Row("new-row", "Row", List.of(
                new BranchLayoutResponse.Slot("new-slot-0", "a1"),
                new BranchLayoutResponse.Slot("new-slot-1", null)))), floor.rows());
        var writes = inOrder(branches, layouts);
        writes.verify(branches).findByIdForUpdate(BRANCH);
        writes.verify(layouts).findById(BRANCH);
        writes.verify(layouts).save(argThat(header -> header.getBranch() == branch && header.getRevision() == 1));
    }

    @Test
    void acknowledgementIsCheckedAfterStaleRevisionAndValidationAndSizeCapped() {
        fixture();
        var ack = new SaveBranchLayoutRequest.AcknowledgedDetach(List.of("l"), List.of());
        var stale = new SaveBranchLayoutRequest(4L, List.of(), ack);
        assertEquals(ListingErrorCode.BRANCH_LAYOUT_STALE.getCode(),
                assertThrows(AppException.class, () -> service.saveLayout(BRANCH, OWNER, stale)).getCode());
        var tooMany = new SaveBranchLayoutRequest.AcknowledgedDetach(
                java.util.Collections.nCopies(5001, "l"), List.of());
        var nullId = new SaveBranchLayoutRequest.AcknowledgedDetach(List.of(), Arrays.asList("c", null));
        for (var bad : List.of(tooMany, nullId)) {
            assertEquals(ListingErrorCode.BRANCH_LAYOUT_INVALID.getCode(), assertThrows(AppException.class,
                    () -> service.saveLayout(BRANCH, OWNER, new SaveBranchLayoutRequest(5L, List.of(), bad))).getCode());
        }
        verifyNoInteractions(floors, rows, slots);
    }

    @Test
    void staleRevisionDoesNotReadOrModifyTheTree() {
        fixture();
        var error = assertThrows(AppException.class,
                () -> service.saveLayout(BRANCH, OWNER, request(4L, "floor-a", "row-a", new Slot("deleted", "A1"))));
        assertEquals(ListingErrorCode.BRANCH_LAYOUT_STALE.getCode(), error.getCode());
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(floors, rows, slots);
        verify(layouts).findById(BRANCH);
        verifyNoMoreInteractions(layouts);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTrees")
    void rejectsInvalidTreeBeforeAnyMutation(String description, SaveBranchLayoutRequest request) {
        var slot = fixture();
        var error = assertThrows(AppException.class, () -> service.saveLayout(BRANCH, OWNER, request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(ListingErrorCode.BRANCH_LAYOUT_INVALID.getCode(), error.getCode());
        assertEquals("A1", slot.getRoomCode());
        assertEquals("A1", slot.getNormalizedRoomCode());

        verify(layouts).findById(BRANCH);
        if (request.expectedRevision() != null) {
            verify(floors).findAllByBranchIdOrderByPosition(BRANCH);
            verify(rows).findAllByFloorBranchIdOrderByPosition(BRANCH);
            verify(slots).findAllByBranchIdOrderByPosition(BRANCH);
        }
        verifyNoMoreInteractions(layouts, floors, rows, slots);
    }

    private static Stream<Arguments> invalidTrees() {
        return Stream.of(
                Arguments.of("missing revision", request(null, null, null, new Slot(null, "A2"))),
                Arguments.of("no floors", new SaveBranchLayoutRequest(5L, List.of())),
                Arguments.of("no rows", new SaveBranchLayoutRequest(5L, List.of(new Floor("floor-a", "Floor", List.of())))),
                Arguments.of("only an aisle", existing(new Slot("slot-a", null))),
                Arguments.of("blank code", existing(new Slot("slot-a", " \t "))),
                Arguments.of("code too long", existing(new Slot("slot-a", "a".repeat(256)))),
                Arguments.of("duplicate normalized code", existing(new Slot("slot-a", " a1 "), new Slot(null, "A1"))),
                Arguments.of("foreign slot", existing(new Slot("another-branch-slot", "A1"))),
                Arguments.of("repeated slot", existing(new Slot("slot-a", "A1"), new Slot("slot-a", "A2"))),
                Arguments.of("wrong level id", existing(new Slot("floor-a", "A1"))),
                Arguments.of("null slot", existing(new Slot("slot-a", "A1"), null)),
                Arguments.of("foreign floor", request(5L, "another-branch-floor", "row-a", new Slot("slot-a", "A1"))),
                Arguments.of("blank row label", new SaveBranchLayoutRequest(5L, List.of(
                        new Floor("floor-a", "Floor", List.of(new Row("row-a", " ", List.of())))))));
    }

    private static SaveBranchLayoutRequest existing(Slot... cells) {
        return request(5L, "floor-a", "row-a", cells);
    }

    private static SaveBranchLayoutRequest request(Long revision, String floorId, String rowId, Slot... cells) {
        return new SaveBranchLayoutRequest(revision, List.of(new Floor(floorId, " Floor ",
                List.of(new Row(rowId, " Row ", Arrays.asList(cells))))));
    }

    private BranchSlot fixture() {
        var header = new BranchLayout();
        header.setBranch(branch);
        header.setRevision(5);
        var floor = new BranchFloor();
        floor.setId("floor-a");
        floor.setBranch(branch);
        var row = new BranchRow();
        row.setId("row-a");
        row.setFloor(floor);
        var slot = new BranchSlot();
        slot.setId("slot-a");
        slot.setRow(row);
        slot.setBranch(branch);
        slot.setRoomCode("A1");
        slot.setNormalizedRoomCode("A1");
        when(branches.findByIdForUpdate(BRANCH)).thenReturn(Optional.of(branch));
        when(layouts.findById(BRANCH)).thenReturn(Optional.of(header));
        when(floors.findAllByBranchIdOrderByPosition(BRANCH)).thenReturn(List.of(floor));
        when(rows.findAllByFloorBranchIdOrderByPosition(BRANCH)).thenReturn(List.of(row));
        when(slots.findAllByBranchIdOrderByPosition(BRANCH)).thenReturn(List.of(slot));
        return slot;
    }
}
