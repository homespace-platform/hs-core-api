package com.hs.contract.dto.branch;

/** Signed financial terms of one revision; {@code rootId} is its lease, directly or through an annex. */
public record BranchRevisionTerms(String id, String financialSnapshot, String rootId) {
}
