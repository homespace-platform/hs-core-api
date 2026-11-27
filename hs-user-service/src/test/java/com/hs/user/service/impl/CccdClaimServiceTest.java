package com.hs.user.service.impl;

import com.hs.common.security.SecretCrypto;
import com.hs.user.model.UserKycDetails;
import com.hs.user.repository.UserKycDetailsRepository;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CccdClaimServiceTest {

    @Test
    void claimLooksUpByHashAndRejectsCccdOwnedByAnotherUser() {
        SecretCrypto.configure("test-secret");
        String hash = SecretCrypto.get().cccdHash("001234567890");
        UserRepository users = mock(UserRepository.class);
        UserKycDetailsRepository details = mock(UserKycDetailsRepository.class);
        when(users.existsById("bob")).thenReturn(true);
        when(details.findById("bob")).thenReturn(Optional.empty());
        when(details.existsByCccdHashAndUserIdNot(hash, "bob")).thenReturn(true);

        var service = new CccdClaimService(users, details, mock(JdbcTemplate.class));

        assertFalse(service.claimInCurrentTransaction("bob", " 001234567890"));
        verify(details, never()).saveAndFlush(any());
    }

    @Test
    void hashIsFilledOnSaveHook() throws Exception {
        SecretCrypto.configure("test-secret");
        var d = new UserKycDetails();
        d.setCccd("001234567890");
        var m = UserKycDetails.class.getDeclaredMethod("syncCccdHash");
        m.setAccessible(true);
        m.invoke(d);
        assertEquals(SecretCrypto.get().cccdHash("001234567890"), d.getCccdHash());
    }
}
