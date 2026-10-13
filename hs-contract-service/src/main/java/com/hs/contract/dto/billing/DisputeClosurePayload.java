package com.hs.contract.dto.billing;
import java.util.List;
public record DisputeClosurePayload(int proposalVersion, String reason, List<String> evidenceIds) {}
