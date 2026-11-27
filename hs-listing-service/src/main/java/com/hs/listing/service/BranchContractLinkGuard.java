package com.hs.listing.service;

import java.util.Collection;
import java.util.Set;

/** What listing needs to know about contract-side links; implemented by the contract module. */
public interface BranchContractLinkGuard {
    /** Any contract still linked to the branch, including unplaced and finished ones. */
    long countContractLinks(String branchId);

    /** Rooms that hold an effective lease or an accepted rental request still waiting for its lease. */
    Set<String> occupiedRooms(Collection<String> roomIds);

    /** Contracts that unplaceContracts would move to the tray. */
    Set<String> contractsInRooms(Collection<String> roomIds);

    /** Moves every contract of these rooms to the unplaced tray. */
    void unplaceContracts(Collection<String> roomIds);

    /** @param contractId the lease just attached, or null; @param note why none was attached (Vietnamese), or null */
    record ListingLease(String contractId, String note) {
        public static final ListingLease NONE = new ListingLease(null, null);
    }

    /**
     * Attaches the effective root lease of a listing already linked to this room. Never moves or overrides a link.
     * Runs in its own transaction, after the listing link committed, so the contract lock precedes the branch lock.
     */
    ListingLease attachListingLease(String branchId, String ownerId, String roomId, String listingId);
}
