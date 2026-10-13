package com.hs.contract.dto.billing;
public record AcceptClosurePayload(int proposalVersion, boolean handoverConfirmed, boolean deductionsAccepted) {}
