package com.hs.contract.service;

import com.hs.contract.dto.branch.BranchHoldRow;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchRoomContract;
import com.hs.contract.repository.BranchContractAssignmentRepository;
import com.hs.listing.service.BranchContractLinkGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BranchAssignmentGuard implements BranchContractLinkGuard {

    private final BranchContractAssignmentRepository assignments;

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
    public void unplaceContracts(Collection<String> roomIds) {
        assignments.unplaceRooms(roomIds);
    }
}
