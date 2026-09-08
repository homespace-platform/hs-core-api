package com.hs.user.service.kyc;

import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Role;
import com.hs.user.model.User;

/**
 * KYC policy derived from role — no extra DB column.
 * ADMIN is treated as identity-verified by default (bypass Didit for business gates).
 * USER must complete Didit KYC when a feature requires it.
 */
public final class KycPolicy {

    private KycPolicy() {
    }

    /** Admin role — KYC not required; profile reports {@code kycVerified=true}. */
    public static boolean isAdminTrusted(User user) {
        return isAdminTrusted(user != null ? user.getRole() : null);
    }

    public static boolean isAdminTrusted(Role role) {
        return role != null && RoleConstants.ADMIN.equals(role.getName());
    }

    /** @deprecated prefer {@link #isAdminTrusted(User)} */
    public static boolean isKycOptional(User user) {
        return isAdminTrusted(user);
    }

    /** @deprecated prefer {@link #isAdminTrusted(Role)} */
    public static boolean isKycOptional(Role role) {
        return isAdminTrusted(role);
    }

    /**
     * Use when a feature requires identity verification.
     * Admins always pass; others need Didit {@code VERIFIED}.
     */
    public static boolean isIdentitySatisfied(User user, boolean diditVerified) {
        return isAdminTrusted(user) || diditVerified;
    }
}
