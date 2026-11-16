package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.user.constant.RoleConstants;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.service.KycService;
import com.hs.user.utils.CurrentUserUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminKycServiceTest {
    @Test void adminCanReadStatusWithoutChangingVerification() {
        var actor=mock(CurrentUserUtils.class);
        var kyc=mock(KycService.class);
        var admin=new User();var role=new Role();role.setName(RoleConstants.ADMIN);admin.setRole(role);
        when(actor.getCurrentUser()).thenReturn(admin);
        var response=new KycStatusResponse(KycStatus.NOT_VERIFIED,null,KycProvider.VNPT,null,null,null);
        when(kyc.getCurrentStatus("alice")).thenReturn(response);
        assertSame(response,new AdminKycService(actor,kyc).status("alice"));
        verify(kyc).getCurrentStatus("alice");
    }

    @Test void ordinaryUserCannotReadAnotherUsersStatus() {
        var actor=mock(CurrentUserUtils.class);
        var kyc=mock(KycService.class);
        var user=new User();var role=new Role();role.setName(RoleConstants.USER);user.setRole(role);
        when(actor.getCurrentUser()).thenReturn(user);
        assertThrows(AppException.class,()->new AdminKycService(actor,kyc).status("alice"));
        verifyNoInteractions(kyc);
    }
}
