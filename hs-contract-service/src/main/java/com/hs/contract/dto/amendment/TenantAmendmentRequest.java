package com.hs.contract.dto.amendment;

import java.util.List;

/** A wish to negotiate, never an executable change of contract terms. */
public record TenantAmendmentRequest(String requestId, String message, List<String> evidenceIds) {}
