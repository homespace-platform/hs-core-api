package com.hs.contract.dto.billing;

/** The listing must not be republished until the owner attests to actual handover. */
public record CompleteTerminationRequest(boolean vacantPossessionConfirmed,
                                         boolean keysAndAssetsReturned) {}
