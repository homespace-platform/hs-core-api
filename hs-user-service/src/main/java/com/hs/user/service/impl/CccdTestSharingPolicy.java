package com.hs.user.service.impl;

import com.hs.user.config.database.BootstrapAdminAccount;
import com.hs.user.config.database.BootstrapAdminProperties;
import com.hs.user.constant.RoleConstants;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import java.util.List;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Narrow dev-only exception for the two configured bootstrap admins sharing a SmartCA test identity. */
@Component
public class CccdTestSharingPolicy {

    private final UserRepository userRepository;
    private final List<BootstrapAdminAccount> admins;
    @Getter
    private final boolean enabled;

    public CccdTestSharingPolicy(UserRepository userRepository,
                                 BootstrapAdminProperties bootstrapProperties,
                                 Environment environment,
                                 @Value("${homespace.cccd-test-sharing.enabled:false}") boolean enabled) {
        this.userRepository = userRepository;
        this.admins = bootstrapProperties.enabledAdmins();
        this.enabled = enabled;
        if (enabled) {
            if (!environment.acceptsProfiles(Profiles.of("dev"))) {
                throw new IllegalStateException("Shared test CCCD is allowed only under the dev profile");
            }
            if (admins.size() != 2
                    || admins.get(0).username() == null || admins.get(1).username() == null
                    || admins.get(0).username().equals(admins.get(1).username())
                    || admins.get(0).cccd() == null
                    || !admins.get(0).cccd().matches("\\d{12}")
                    || !admins.get(0).cccd().equals(admins.get(1).cccd())
                    || !admins.get(0).username().matches("[A-Za-z0-9_.-]+")
                    || !admins.get(1).username().matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalStateException("Shared test CCCD requires exactly two distinct bootstrap admins with the same 12-digit CCCD");
            }
        }
    }

    public String sharedCccd() {
        return admins.get(0).cccd();
    }

    public String firstUsername() {
        return admins.get(0).username();
    }

    public String secondUsername() {
        return admins.get(1).username();
    }

    public boolean mayClaim(User claimant, String cccd) {
        if (enabled && sharedCccd().equals(cccd) && !isSharedTestAdmin(claimant, cccd)) {
            return false;
        }
        return userRepository.findAllByCccd(cccd).stream()
                .filter(holder -> !holder.getId().equals(claimant.getId()))
                .allMatch(holder -> isSharedTestAdmin(claimant, cccd)
                        && isSharedTestAdmin(holder, cccd)
                        && !holder.getUsername().equals(claimant.getUsername()));
    }

    private boolean isSharedTestAdmin(User user, String cccd) {
        return enabled
                && sharedCccd().equals(cccd)
                && user.getRole() != null
                && RoleConstants.ADMIN.equals(user.getRole().getName())
                && (firstUsername().equals(user.getUsername()) || secondUsername().equals(user.getUsername()));
    }
}
