package com.hs.contract.dto.billing;

/** Owner attests to the signed clause and physical handover before inventory is reopened. */
public record ForceTerminationRequest(boolean signedClauseAcknowledged,
                                      boolean tenantNotified,
                                      boolean vacantPossessionConfirmed,
                                      boolean keysAndAssetsReturned) {}
