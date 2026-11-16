package com.hs.contract.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hs.common.advice.entity.AppException;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.service.kyc.KycPolicy;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartCaSignerIdentityTest {
    @Mock UserRepository userRepository;
    @Mock KycVerificationRepository kycVerificationRepository;
    @InjectMocks SmartCaSignatureServiceImpl service;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "USER"})
    void citizenIdWithoutVerifiedKycCannotSign(String role) {
        User user = user(role, "001234567890");
        when(userRepository.findById("actor")).thenReturn(Optional.of(user));
        assertThrows(AppException.class, () -> service.requireUserWithCccd("actor"));
        verify(kycVerificationRepository).existsByUserIdAndProviderInAndStatus(
                "actor", KycPolicy.ACCEPTED_PROVIDERS, KycStatus.VERIFIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "USER"})
    void missingCitizenIdCannotSign(String role) {
        User user = user(role, null);
        when(userRepository.findById("actor")).thenReturn(Optional.of(user));
        assertThrows(AppException.class, () -> service.requireUserWithCccd("actor"));
        verifyNoInteractions(kycVerificationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "USER"})
    void verifiedIdentityMayUseItsOwnKycCitizenId(String role) {
        User user = user(role, "001234567890");
        when(userRepository.findById("actor")).thenReturn(Optional.of(user));
        when(kycVerificationRepository.existsByUserIdAndProviderInAndStatus(
                "actor", KycPolicy.ACCEPTED_PROVIDERS, KycStatus.VERIFIED)).thenReturn(true);
        assertSame(user, service.requireUserWithCccd("actor"));
    }

    private static User user(String roleName, String cccd) {
        User user = spy(new User());
        user.setId("actor");
        doReturn(cccd).when(user).getCccd();
        Role role = new Role();role.setName(roleName);user.setRole(role);
        return user;
    }
}
