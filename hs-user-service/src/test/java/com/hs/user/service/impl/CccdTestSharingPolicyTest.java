package com.hs.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.hs.user.config.database.BootstrapAdminAccount;
import com.hs.user.config.database.BootstrapAdminProperties;
import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

class CccdTestSharingPolicyTest {

    private static final String SHARED_CCCD = "075204022672";
    private UserRepository repository;
    private Environment environment;
    private BootstrapAdminProperties admins;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        admins = new BootstrapAdminProperties(List.of(
                new BootstrapAdminAccount(true, "homespace", null, null, null, null, null, SHARED_CCCD),
                new BootstrapAdminAccount(true, "homespace2", null, null, null, null, null, SHARED_CCCD)));
    }

    @Test
    void allowsOnlyTheTwoAdminsToShareConfiguredCccdInDev() {
        User first = user("1", "homespace", RoleConstants.ADMIN);
        User second = user("2", "homespace2", RoleConstants.ADMIN);
        when(repository.findAllByCccd(SHARED_CCCD)).thenReturn(List.of(first));

        var policy = new CccdTestSharingPolicy(repository, admins, environment, true);

        assertTrue(policy.mayClaim(second, SHARED_CCCD));
        assertFalse(policy.mayClaim(user("3", "another-admin", RoleConstants.ADMIN), SHARED_CCCD));
        assertFalse(policy.mayClaim(user("4", "tenant", RoleConstants.USER), SHARED_CCCD));
    }

    @Test
    void stillRejectsDuplicateCccdForOtherUsers() {
        User existing = user("1", "owner", RoleConstants.USER);
        when(repository.findAllByCccd("075204022789")).thenReturn(List.of(existing));

        var policy = new CccdTestSharingPolicy(repository, admins, environment, true);

        assertFalse(policy.mayClaim(user("2", "tenant", RoleConstants.USER), "075204022789"));
    }

    @Test
    void disabledModeRejectsEvenTheBootstrapAdminPair() {
        User first = user("1", "homespace", RoleConstants.ADMIN);
        when(repository.findAllByCccd(SHARED_CCCD)).thenReturn(List.of(first));

        var policy = new CccdTestSharingPolicy(repository, admins, environment, false);

        assertFalse(policy.mayClaim(user("2", "homespace2", RoleConstants.ADMIN), SHARED_CCCD));
    }

    @Test
    void refusesToEnableSharingOutsideDevProfile() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> new CccdTestSharingPolicy(repository, admins, environment, true));
    }

    private static User user(String id, String username, String roleName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        Role role = new Role();
        role.setName(roleName);
        user.setRole(role);
        return user;
    }
}
