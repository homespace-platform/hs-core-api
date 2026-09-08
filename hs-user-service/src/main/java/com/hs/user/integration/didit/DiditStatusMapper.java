package com.hs.user.integration.didit;

import com.hs.user.model.constant.KycStatus;

public final class DiditStatusMapper {

    private DiditStatusMapper() {}

    public static KycStatus toHomeSpaceStatus(String providerStatus) {
        if (providerStatus == null || providerStatus.isBlank()) {
            return KycStatus.PENDING;
        }
        return switch (providerStatus.trim()) {
            case "Approved" -> KycStatus.VERIFIED;
            case "Declined" -> KycStatus.REJECTED;
            case "In Review" -> KycStatus.REVIEW_REQUIRED;
            case "Expired", "Abandoned", "Kyc Expired" -> KycStatus.EXPIRED;
            case "Not Started", "In Progress", "Resubmitted", "Awaiting User" -> KycStatus.PENDING;
            default -> KycStatus.PENDING;
        };
    }
}
