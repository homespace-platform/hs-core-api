package com.hs.contract.dto.billing;

/** Explicit bilateral settlement consent; a viewed notice is not consent. */
public record AcceptTerminationRequest(boolean acceptEarlyTermination,
                                       boolean acceptDepositRetention,
                                       boolean acknowledgeOutstandingDebt) {}
