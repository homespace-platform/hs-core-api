package com.hs.user.service.kyc;

/** Accepted verification providers for every account, regardless of role. */
public final class KycPolicy {
    // Existing verified identities remain valid after retiring their provider integration.
    public static final java.util.List<com.hs.user.model.constant.KycProvider> ACCEPTED_PROVIDERS = java.util.List.of(
            com.hs.user.model.constant.KycProvider.DIDIT, com.hs.user.model.constant.KycProvider.VNPT,
            com.hs.user.model.constant.KycProvider.ADMIN);

    private KycPolicy() {
    }

}
