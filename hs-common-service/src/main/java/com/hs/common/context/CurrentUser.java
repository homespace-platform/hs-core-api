package com.hs.common.context;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Verified caller identity (set by UserContextFilter from Gateway headers). Use instead of local copies. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** Caller id, or 401 when there is no verified identity. Never falls back to a synthetic id. */
    public static String requireUserId() {
        return optionalUserId().orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
    }

    public static Optional<String> optionalUserId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) return Optional.empty();
        return Optional.of(context.userId());
    }

    public static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) return true;
        }
        return false;
    }

    /** True only for an ADMIN that also holds the given permission. For service-level admin checks. */
    public static boolean isAdminWith(String permission) {
        return hasAuthority(com.hs.common.security.Permissions.ROLE_ADMIN) && hasAuthority(permission);
    }

    /** 403 unless the caller is an ADMIN holding the permission. */
    public static void requireAdminWith(String permission) {
        requireUserId();
        if (!isAdminWith(permission)) throw new AppException(ErrorCode.UNAUTHORIZED);
    }
}
