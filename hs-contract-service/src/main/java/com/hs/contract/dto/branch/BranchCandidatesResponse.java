package com.hs.contract.dto.branch;

import java.util.List;

public record BranchCandidatesResponse(List<Item> items, int page, int size, long totalElements) {

    public record Item(String id, String label, String roomId, String roomCode) {
    }
}
