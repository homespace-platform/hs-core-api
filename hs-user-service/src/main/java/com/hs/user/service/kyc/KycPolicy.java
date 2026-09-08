package com.hs.user.service.kyc;

import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Role;
import com.hs.user.model.User;

/**
 * KYC policy derived from role — no extra DB column.
 * ADMIN (by role name, regardless of how role_id was assigned) may skip KYC;
 * USER must verify when a business gate requires it.
 */
public final class KycPolicy {

    private KycPolicy() {
    }

    public static boolean isKycOptional(User user) {
        return isKycOptional(user != null ? user.getRole() : null);
    }

    public static boolean isKycOptional(Role role) {
        return role != null && RoleConstants.ADMIN.equals(role.getName());
    }

    /**
     * Use when a feature requires identity verification.
     * Admins pass without Didit; others need {@code kycVerified == true}.
     */
    public static boolean isIdentitySatisfied(User user, boolean kycVerified) {
        return isKycOptional(user) || kycVerified;
    }
}
