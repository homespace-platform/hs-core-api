package com.hs.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.hs.user.model.User;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KeycloakUserService;
import com.hs.user.utils.CurrentUserUtils;

class UserServiceImplTest {

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
        when(verifications.existsByUserIdAndProviderAndStatus(
                "user-1", KycProvider.DIDIT, KycStatus.VERIFIED)).thenReturn(true);

        var service = new UserServiceImpl(
                users,
                mock(RoleRepository.class),
                mock(AddressRepository.class),
                verifications,
                mock(KeycloakUserService.class),
                mock(CurrentUserUtils.class));
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
}
