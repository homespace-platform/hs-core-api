package com.hs.contract.dto.amendment;

import java.time.LocalDate;
import java.util.*;

public record AmendmentPayload(String requestId, String baseRevisionId, String previousProposalId,
        LocalDate effectiveOn, String reason, Map<String, Object> changes, List<String> evidenceIds, String tenantRequestId) {
    public AmendmentPayload(String requestId, String baseRevisionId, String previousProposalId,
            LocalDate effectiveOn, String reason, Map<String, Object> changes, List<String> evidenceIds) {
        this(requestId, baseRevisionId, previousProposalId, effectiveOn, reason, changes, evidenceIds, null);
    }
}
