package com.hs.contract.dto.amendment;

import java.time.*;
import java.util.*;

public record AmendmentResponse(Instant serverNow, boolean canPropose, String blockedReason,
        String currentTermsRevisionId, Map<String, Object> currentTerms, List<LocalDate> availableDates,
        List<Proposal> proposals, AmendmentPayload draft) {
    public record Proposal(String id, String previousProposalId, String proposerId, String recipientId,
            String status, LocalDate effectiveOn, boolean immediateEffect, String reason, String responseNote,
            Instant submittedAt, Instant respondBy, Instant signBy, Instant signedAt, Instant appliedAt,
            String agreementContractId, List<AmendmentComparison> comparison,
            Map<String, Object> changes, List<String> evidenceIds, String tenantRequestId, String agreementStatus, Instant decidedAt,
            boolean canReplace, String replacementBlockedReason) {}
}
