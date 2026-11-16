package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.user.model.*;
import com.hs.user.model.constant.*;
import com.hs.user.repository.*;
import com.hs.user.service.impl.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KycProviderSelectionTest {
    UserRepository users=mock(UserRepository.class);
    KycVerificationRepository records=mock(KycVerificationRepository.class);
    UserKycDetailsRepository details=mock(UserKycDetailsRepository.class);
    KycServiceImpl service=new KycServiceImpl(users,records,details);
    @BeforeEach void actor(){User u=new User();u.setId("alice");when(users.existsById("alice")).thenReturn(true);when(users.findByIdForUpdate("alice")).thenReturn(Optional.of(u));}
    KycVerification vnpt(){return KycVerification.builder().id("attempt").userId("alice").provider(KycProvider.VNPT).providerSessionId("WEB-SDK_test_123").status(KycStatus.PENDING).expiresAt(Instant.now().plusSeconds(60)).build();}
    @Test void newUsersDefaultToVnpt(){
        assertEquals(KycStatus.NOT_VERIFIED,service.getCurrentStatus("alice").status());
        assertEquals(KycProvider.VNPT,service.getCurrentStatus("alice").provider());
    }
    @Test void statusReturnsInternalAttemptIdAndHidesExpiredCapture(){
        var v=vnpt();when(records.findFirstByUserIdOrderByCreatedAtDesc("alice")).thenReturn(Optional.of(v));
        assertEquals("attempt",service.getCurrentStatus("alice").sessionId());assertEquals(KycProvider.VNPT,service.getCurrentStatus("alice").provider());
        v.setExpiresAt(Instant.now().minusSeconds(1));assertEquals(KycStatus.EXPIRED,service.getCurrentStatus("alice").status());assertNull(service.getCurrentStatus("alice").sessionUrl());
    }
    @Test void previousDiditVerifiedKeepsItsMeaning(){
        var v=KycVerification.builder().id("legacy").userId("alice").provider(KycProvider.DIDIT).providerSessionId("legacy-session").sessionUrl("https://example.test").status(KycStatus.VERIFIED).verifiedAt(Instant.now()).build();
        when(records.findFirstByUserIdAndStatusOrderByVerifiedAtDesc("alice",KycStatus.VERIFIED)).thenReturn(Optional.of(v));
        assertEquals(KycStatus.VERIFIED,service.getCurrentStatus("alice").status());assertEquals(KycProvider.DIDIT,service.getCurrentStatus("alice").provider());assertNull(service.getCurrentStatus("alice").sessionUrl());
        assertNull(service.getVerifiedDetails("alice"));
    }
    @Test void detailsAreReadOnlyForTheRequestedVerifiedUser(){
        var v=KycVerification.builder().id("attempt").userId("alice").provider(KycProvider.VNPT).status(KycStatus.VERIFIED).build();
        when(records.findFirstByUserIdAndStatusOrderByVerifiedAtDesc("alice",KycStatus.VERIFIED)).thenReturn(Optional.of(v));
        when(details.findById("alice")).thenReturn(Optional.of(UserKycDetails.from("alice",new com.hs.user.dto.response.KycVerifiedDetailsResponse("001234567890",null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null))));
        assertEquals("001234567890",service.getVerifiedDetails("alice").cccd());
        assertThrows(AppException.class,()->service.getVerifiedDetails("bob"));
        verify(records,never()).findFirstByUserIdAndStatusOrderByVerifiedAtDesc("bob",KycStatus.VERIFIED);
    }
    @Test void cancelWorksForVnptWithoutAClientSuppliedUserId(){
        var v=vnpt();when(records.findByUserIdAndStatusInOrderByCreatedAtDesc(eq("alice"),any())).thenReturn(List.of(v));when(records.lockById("attempt")).thenReturn(Optional.of(v));when(records.findFirstByUserIdOrderByCreatedAtDesc("alice")).thenReturn(Optional.of(v));
        assertEquals(KycStatus.EXPIRED,service.cancelPendingSession("alice").status());assertEquals(KycStatus.EXPIRED,v.getStatus());
    }
    @Test void retiredPendingSessionCannotResumeItsProviderUrl(){
        var v=vnpt();v.setProvider(KycProvider.DIDIT);v.setSessionUrl("https://example.test");
        when(records.findFirstByUserIdOrderByCreatedAtDesc("alice")).thenReturn(Optional.of(v));
        assertEquals(KycStatus.EXPIRED,service.getCurrentStatus("alice").status());assertNull(service.getCurrentStatus("alice").sessionUrl());
    }
    @Test void alreadyVerifiedIdentityCannotBeCancelled(){
        when(records.existsByUserIdAndProviderInAndStatus(eq("alice"),any(),eq(KycStatus.VERIFIED))).thenReturn(true);
        assertThrows(AppException.class,()->service.cancelPendingSession("alice"));verify(records,never()).save(any());
    }
}
