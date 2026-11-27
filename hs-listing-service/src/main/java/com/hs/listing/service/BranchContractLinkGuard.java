package com.hs.listing.service;

import java.util.Collection;
import java.util.Set;

/** What listing needs to know about contract-side links; implemented by the contract module. */
public interface BranchContractLinkGuard {
    /** Any contract still linked to the branch, including unplaced and finished ones. */
    long countContractLinks(String branchId);

    /** Rooms that hold an effective lease or an accepted rental request still waiting for its lease. */
    Set<String> occupiedRooms(Collection<String> roomIds);

    /** Moves every contract of these rooms to the unplaced tray. */
    void unplaceContracts(Collection<String> roomIds);
}
