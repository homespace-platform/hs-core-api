package com.hs.contract.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hs.common.advice.entity.AppException;
import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartCaSignerIdentityTest {

    @Mock UserRepository userRepository;
    @Mock KycVerificationRepository kycVerificationRepository;
    @InjectMocks SmartCaSignatureServiceImpl service;

    @Test
    void adminWithCccdDoesNotRequireDiditKyc() {
        User admin = user("admin", RoleConstants.ADMIN, "075204022672");
        when(userRepository.findById("admin")).thenReturn(Optional.of(admin));

        assertSame(admin, service.requireUserWithCccd("admin"));
        verify(kycVerificationRepository, never()).existsByUserIdAndProviderAndStatus(
                "admin", KycProvider.DIDIT, KycStatus.VERIFIED);
    }

    @Test
    void regularUserStillRequiresDiditKyc() {
        User user = user("tenant", RoleConstants.USER, "075204022789");
        when(userRepository.findById("tenant")).thenReturn(Optional.of(user));

        assertThrows(AppException.class, () -> service.requireUserWithCccd("tenant"));
        verify(kycVerificationRepository).existsByUserIdAndProviderAndStatus(
                "tenant", KycProvider.DIDIT, KycStatus.VERIFIED);
    }

    @Test
    void adminStillNeedsCccd() {
        User admin = user("admin", RoleConstants.ADMIN, null);
        when(userRepository.findById("admin")).thenReturn(Optional.of(admin));

        assertThrows(AppException.class, () -> service.requireUserWithCccd("admin"));
        verify(kycVerificationRepository, never()).existsByUserIdAndProviderAndStatus(
                "admin", KycProvider.DIDIT, KycStatus.VERIFIED);
    }

    private static User user(String id, String roleName, String cccd) {
        User user = new User();
        user.setId(id);
        user.setCccd(cccd);
        Role role = new Role();
        role.setName(roleName);
        user.setRole(role);
        return user;
    }
}
