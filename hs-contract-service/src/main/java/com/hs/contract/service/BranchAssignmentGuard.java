package com.hs.contract.service;

import com.hs.contract.dto.branch.BranchHoldRow;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchRoomContract;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.listing.service.BranchContractLinkGuard;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BranchAssignmentGuard implements BranchContractLinkGuard {

    private final BranchContractAssignmentRepository assignments;
    private final BranchAssignmentService assignmentService;

    // Lazy: the assignment service needs the room link service, which holds this guard.
    public BranchAssignmentGuard(BranchContractAssignmentRepository assignments,
                                 @Lazy BranchAssignmentService assignmentService) {
        this.assignments = assignments;
        this.assignmentService = assignmentService;
    }

    @Override
    public ListingLease attachListingLease(String branchId, String ownerId, String roomId, String listingId) {
        return assignmentService.attachListingLease(branchId, ownerId, roomId, listingId);
    }

    @Override
    public long countContractLinks(String branchId) {
        return assignments.countByManagementBranchId(branchId);
    }

    @Override
    public Set<String> occupiedRooms(Collection<String> roomIds) {
        Set<String> occupied = assignments.findHolds(roomIds).stream().map(BranchHoldRow::roomId)
                .collect(Collectors.toCollection(HashSet::new));
        List<BranchRoomContract> placed = assignments.findRoomContracts(roomIds);
        if (!placed.isEmpty()) {
            Map<String, BranchLeaseRow> leases = assignments
                    .findLeaseRows(placed.stream().map(BranchRoomContract::contractId).toList()).stream()
                    .collect(Collectors.toMap(BranchLeaseRow::id, Function.identity()));
            for (BranchRoomContract link : placed) {
                BranchLeaseRow lease = leases.get(link.contractId());
                if (lease != null && !BranchRoomSchedule.historical(lease)) occupied.add(link.roomId());
            }
        }
        return occupied;
    }

    @Override
    public Set<String> contractsInRooms(Collection<String> roomIds) {
        return assignments.findRoomContracts(roomIds).stream().map(BranchRoomContract::contractId)
                .collect(Collectors.toSet());
    }

    @Override
    public void unplaceContracts(Collection<String> roomIds) {
        assignments.unplaceRooms(roomIds);
    }
}
