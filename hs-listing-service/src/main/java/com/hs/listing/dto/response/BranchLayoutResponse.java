package com.hs.listing.dto.response;

import java.util.List;

public record BranchLayoutResponse(long revision, List<Floor> floors) {

    public record Floor(String id, String label, List<Row> rows) {
    }

    public record Row(String id, String label, List<Slot> slots) {
    }

    public record Slot(String id, String roomCode) {
    }
}
