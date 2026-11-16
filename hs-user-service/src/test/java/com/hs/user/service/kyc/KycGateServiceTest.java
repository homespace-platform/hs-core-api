package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KycGateServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "USER"})
    void everyRoleRequiresVerifiedEvidence(String roleName) {
        var users = mock(UserRepository.class);
        var verifications = mock(KycVerificationRepository.class);
        var user = new User();
        var role = new Role();
        role.setName(roleName);
        user.setRole(role);
        when(users.findById("actor")).thenReturn(Optional.of(user));
        var gate = new KycGateService(users, verifications);

        var error = assertThrows(AppException.class, () -> gate.requireVerifiedIdentity("actor"));
        assertEquals(UserErrorCode.KYC_REQUIRED.getCode(), error.getCode());

        when(verifications.existsByUserIdAndProviderInAndStatus(
                "actor", KycPolicy.ACCEPTED_PROVIDERS, KycStatus.VERIFIED)).thenReturn(true);
        assertDoesNotThrow(() -> gate.requireVerifiedIdentity("actor"));
    }
}
