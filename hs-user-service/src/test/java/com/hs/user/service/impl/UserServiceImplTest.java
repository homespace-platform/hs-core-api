package com.hs.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.hs.common.advice.entity.AppException;
import com.hs.user.model.Address;
import com.hs.user.model.User;
import com.hs.user.model.Role;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.service.kyc.KycPolicy;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KeycloakUserService;
import com.hs.user.utils.CurrentUserUtils;

class UserServiceImplTest {

    @Test
    void adminProfileReportsActualVerificationWithoutAnExemption() {
        var users = mock(UserRepository.class);
        var verifications = mock(KycVerificationRepository.class);
        var actor = mock(CurrentUserUtils.class);
        var user = new User();
        user.setId("admin-1");
        user.setActive(true);
        var role = new Role();
        role.setName("ADMIN");
        user.setRole(role);
        when(actor.getCurrentUser()).thenReturn(user);
        when(users.findById("admin-1")).thenReturn(Optional.of(user));
        var service = new UserServiceImpl(users, mock(RoleRepository.class), mock(AddressRepository.class),
                verifications, mock(KeycloakUserService.class), actor, mock(AccountNotificationWriter.class));

        assertFalse(service.getUserProfile().kycVerified());
        assertFalse(service.getUserProfile().kycOptional());
        assertFalse(service.getPublicUserProfile("admin-1").kycVerified());

        when(verifications.existsByUserIdAndProviderInAndStatus(
                "admin-1", KycPolicy.ACCEPTED_PROVIDERS, KycStatus.VERIFIED)).thenReturn(true);
        assertTrue(service.getUserProfile().kycVerified());
        assertTrue(service.getPublicUserProfile("admin-1").kycVerified());
    }

    @Test
    void returnsOnlyPublicProfileFields() {
        UserRepository users = mock(UserRepository.class);
        KycVerificationRepository verifications = mock(KycVerificationRepository.class);
        User user = new User();
        user.setId("user-1");
        user.setUsername("nguyen-van-a");
        user.setFirstName("Văn A");
        user.setLastName("Nguyễn");
        user.setEmail("private@example.com");
        user.setPhone("0900000000");
        user.setActive(true);
        user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(verifications.existsByUserIdAndProviderInAndStatus(
                "user-1", KycPolicy.ACCEPTED_PROVIDERS, KycStatus.VERIFIED)).thenReturn(true);

        var service = new UserServiceImpl(
                users,
                mock(RoleRepository.class),
                mock(AddressRepository.class),
                verifications,
                mock(KeycloakUserService.class),
                mock(CurrentUserUtils.class),
                mock(AccountNotificationWriter.class));
        var profile = service.getPublicUserProfile("user-1");
        Set<String> fields = Arrays.stream(profile.getClass().getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals("nguyen-van-a", profile.username());
        assertTrue(profile.kycVerified());
        assertFalse(fields.contains("email"));
        assertFalse(fields.contains("phone"));
        assertFalse(fields.contains("cccd"));
        assertFalse(fields.contains("address"));
    }

    @Test
    void looksUpByUsernameCaseInsensitivelyWithProvinceName() {
        UserRepository users = mock(UserRepository.class);
        AddressRepository addresses = mock(AddressRepository.class);
        User user = new User();
        user.setId("user-1");
        user.setUsername("Nguyen-A");
        user.setActive(true);
        var address = new Address();
        address.setCity("Ha Noi");
        when(users.findByUsernameIgnoreCase("nguyen-a")).thenReturn(Optional.of(user));
        when(addresses.findByUser_IdAndActiveTrue("user-1")).thenReturn(Optional.of(address));
        var service = new UserServiceImpl(users, mock(RoleRepository.class), addresses,
                mock(KycVerificationRepository.class), mock(KeycloakUserService.class),
                mock(CurrentUserUtils.class), mock(AccountNotificationWriter.class));

        var profile = service.getPublicUserProfileByUsername("nguyen-a");

        assertEquals("user-1", profile.id());
        assertEquals("Ha Noi", profile.provinceName());
    }

    @Test
    void provinceNameIsNullWithoutActiveAddressAndInactiveOrUnknownUsersAreHidden() {
        UserRepository users = mock(UserRepository.class);
        AddressRepository addresses = mock(AddressRepository.class);
        User user = new User();
        user.setId("user-1");
        user.setActive(true);
        User inactive = new User();
        inactive.setId("user-2");
        inactive.setActive(false);
        when(users.findByUsernameIgnoreCase("a")).thenReturn(Optional.of(user));
        when(users.findByUsernameIgnoreCase("b")).thenReturn(Optional.of(inactive));
        when(addresses.findByUser_IdAndActiveTrue("user-1")).thenReturn(Optional.empty());
        var service = new UserServiceImpl(users, mock(RoleRepository.class), addresses,
                mock(KycVerificationRepository.class), mock(KeycloakUserService.class),
                mock(CurrentUserUtils.class), mock(AccountNotificationWriter.class));

        assertNull(service.getPublicUserProfileByUsername("a").provinceName());
        assertThrows(AppException.class, () -> service.getPublicUserProfileByUsername("b"));
        assertThrows(AppException.class, () -> service.getPublicUserProfileByUsername("missing"));
    }
}
