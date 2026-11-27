package com.hs.contract.dto.branch;

import com.hs.listing.model.constant.RentalRequestStatus;

import java.time.LocalDate;

/** Rental request columns for the branch views; loading the entity would pull its listing graph. */
public record BranchRentalRow(String id, String listingId, String renterId, String renterName, LocalDate moveInDate,
                              RentalRequestStatus status, Boolean active) {
    public boolean activeAccepted() {
        return Boolean.TRUE.equals(active) && status == RentalRequestStatus.ACCEPTED;
    }
}
