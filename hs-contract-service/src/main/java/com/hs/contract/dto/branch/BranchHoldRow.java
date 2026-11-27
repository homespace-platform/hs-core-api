package com.hs.contract.dto.branch;

import java.time.LocalDate;

/** An accepted rental request that has no root lease yet, seen through the room of its listing. */
public record BranchHoldRow(String roomId, String rentalRequestId, LocalDate moveInDate, String tenantName) {
}
