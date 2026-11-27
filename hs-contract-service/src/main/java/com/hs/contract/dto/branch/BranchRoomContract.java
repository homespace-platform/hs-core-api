package com.hs.contract.dto.branch;

/**
 * Read as columns on purpose: a managed assignment entity would keep pointing at a room that the layout save
 * is about to delete after the bulk unplace, and the flush would fail.
 */
public record BranchRoomContract(String roomId, String contractId) {
}
