package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.service.KycService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminKycServiceTest {
    @AfterEach void clear() { UserContextHolder.clear(); SecurityContextHolder.clearContext(); }

    private static void login(String... authorities) {
        UserContextHolder.set(new UserContext("actor", null));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "actor", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test void adminWithKycViewCanReadStatusWithoutChangingVerification() {
        var kyc = mock(KycService.class);
        login("ADMIN", "KYC_VIEW");
        var response = new KycStatusResponse(KycStatus.NOT_VERIFIED, null, KycProvider.VNPT, null, null, null);
        when(kyc.getCurrentStatus("alice")).thenReturn(response);
        assertSame(response, new AdminKycService(kyc).status("alice"));
        verify(kyc).getCurrentStatus("alice");
    }

    @Test void ordinaryUserAndAdminWithoutKycViewCannotReadAnotherUsersStatus() {
        var kyc = mock(KycService.class);
        login("USER", "KYC_VIEW");
        assertThrows(AppException.class, () -> new AdminKycService(kyc).status("alice"));
        login("ADMIN");
        assertThrows(AppException.class, () -> new AdminKycService(kyc).status("alice"));
        verifyNoInteractions(kyc);
    }
}
