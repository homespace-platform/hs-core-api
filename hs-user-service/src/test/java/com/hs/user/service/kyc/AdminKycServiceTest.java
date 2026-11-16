package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.constant.RoleConstants;
import com.hs.user.model.*;
import com.hs.user.model.constant.*;
import com.hs.user.repository.*;
import com.hs.user.service.KycService;
import com.hs.user.service.impl.CccdClaimService;
import com.hs.user.utils.CurrentUserUtils;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminKycServiceTest {
    UserRepository users=mock(UserRepository.class);
    KycVerificationRepository records=mock(KycVerificationRepository.class);
    CccdClaimService claims=mock(CccdClaimService.class);
    CurrentUserUtils actor=mock(CurrentUserUtils.class);
    KycService kyc=mock(KycService.class);
    AdminKycService service=new AdminKycService(users,records,claims,actor,kyc);
    User admin=new User(), target=new User();

    @BeforeEach void setup() {
        var role=new Role();role.setName(RoleConstants.ADMIN);admin.setId("admin");admin.setRole(role);
        target.setId("alice");target.setActive(true);
        when(actor.getCurrentUser()).thenReturn(admin);
        when(users.findByIdForUpdate("alice")).thenReturn(Optional.of(target));
        when(claims.claimInCurrentTransaction("alice","001234567890")).thenReturn(true);
    }

    @Test void recordsAdminAuditPreservesLeadingZeroAndClosesPendingVnpt() {
        var pending=KycVerification.builder().id("pending").provider(KycProvider.VNPT).status(KycStatus.PENDING).build();
        when(records.findByUserIdAndStatusInOrderByCreatedAtDesc(eq("alice"),any())).thenReturn(List.of(pending));
        when(records.lockById("pending")).thenReturn(Optional.of(pending));
        var result=service.verify("alice","001234567890");
        assertEquals(KycStatus.VERIFIED,result.status());assertEquals(KycProvider.ADMIN,result.provider());
        var saved=ArgumentCaptor.forClass(KycVerification.class);verify(records,times(2)).save(saved.capture());
        var verified=saved.getAllValues().get(1);
        assertEquals("admin",verified.getCreatedBy());assertEquals("alice",verified.getUserId());assertNotNull(verified.getVerifiedAt());
        assertEquals(KycStatus.EXPIRED,pending.getStatus());assertTrue(KycPolicy.ACCEPTED_PROVIDERS.contains(KycProvider.ADMIN));
        var order=inOrder(claims,users,records);
        order.verify(claims).lockVerification("alice");order.verify(users).findByIdForUpdate("alice");
        order.verify(claims).claimInCurrentTransaction("alice","001234567890");
        order.verify(records).lockById("pending");
    }

    @Test void duplicateIdentifierNeverCreatesVerificationOrClosesPendingSession() {
        when(claims.claimInCurrentTransaction("alice","001234567890")).thenReturn(false);
        assertThrows(AppException.class,()->service.verify("alice","001234567890"));
        verify(records,never()).save(any());verify(records,never()).lockById(anyString());
    }

    @Test void ordinaryOrDisabledActorCannotVerifyOrReadOthersKyc() {
        admin.getRole().setName(RoleConstants.USER);
        assertThrows(AppException.class,()->service.verify("alice","001234567890"));
        assertThrows(AppException.class,()->service.status("alice"));
        when(actor.getCurrentUser()).thenThrow(new AppException(UserErrorCode.USER_DISABLED));
        assertThrows(AppException.class,()->service.verify("alice","001234567890"));
        verifyNoInteractions(claims,records,kyc,users);
    }

    @Test void invalidIdentifierNeverLocksOrClaimsAnything() {
        for(String invalid:List.of("", "12345678901", "1234567890123", "00123456789a", " 001234567890"))
            assertThrows(AppException.class,()->service.verify("alice",invalid));
        verifyNoInteractions(claims,records,users);
    }

    @Test void missingDisabledAndAlreadyVerifiedUsersCannotBeChanged() {
        when(users.findByIdForUpdate("alice")).thenReturn(Optional.empty());
        assertThrows(AppException.class,()->service.verify("alice","001234567890"));
        when(users.findByIdForUpdate("alice")).thenReturn(Optional.of(target));target.setActive(false);
        assertThrows(AppException.class,()->service.verify("alice","001234567890"));
        target.setActive(true);
        when(records.existsByUserIdAndProviderInAndStatus("alice",KycPolicy.ACCEPTED_PROVIDERS,KycStatus.VERIFIED)).thenReturn(true);
        assertThrows(AppException.class,()->service.verify("alice","001234567890"));
        verify(claims,never()).claimInCurrentTransaction(anyString(),anyString());verify(records,never()).save(any());
    }
}
