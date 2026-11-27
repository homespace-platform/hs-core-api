package com.hs.user.dto.response;

import java.time.Instant;

/** Admin view of KYC: status only, never personal data. */
public record AdminKycStatusResponse(boolean verified, String provider, String providerLabel, Instant verifiedAt) {}
