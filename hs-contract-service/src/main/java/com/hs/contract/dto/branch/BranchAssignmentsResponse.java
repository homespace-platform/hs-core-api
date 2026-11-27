package com.hs.contract.dto.branch;

import com.hs.contract.model.constant.ContractStatus;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RoomManualStatus;

import java.time.LocalDate;
import java.util.List;

public record BranchAssignmentsResponse(List<Room> rooms, Unplaced unplaced) {

    public record Room(String roomId, RoomManualStatus manualStatus, List<Listing> listings,
                       List<Contract> contracts, List<Hold> holds) {
    }

    public record Listing(String id, String title, ListingStatus operationalStatus, String roomCode) {
    }

    public record Contract(String id, ContractStatus status, LocalDate startDate, LocalDate endDateExclusive,
                           String tenantName) {
    }

    public record Hold(String rentalRequestId, LocalDate moveInDate, String tenantName) {
    }

    public record Unplaced(List<Item> items, int page, int size, long totalElements) {
    }

    public record Item(String kind, String id, String label) {
    }
}
