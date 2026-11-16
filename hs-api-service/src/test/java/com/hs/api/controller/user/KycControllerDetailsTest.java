package com.hs.api.controller.user;

import com.hs.user.service.KycService;
import com.hs.user.utils.CurrentUserUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class KycControllerDetailsTest {
    @Test void detailsAlwaysUseTheAuthenticatedUser() throws Exception {
        var kyc=mock(KycService.class);
        var actor=mock(CurrentUserUtils.class);
        when(actor.getCurrentUserId()).thenReturn("alice");
        var mvc=MockMvcBuilders.standaloneSetup(new KycController(kyc,actor)).build();
        assertEquals(200,mvc.perform(get("/users/me/kyc/details").param("userId","bob"))
                .andReturn().getResponse().getStatus());
        verify(kyc).getVerifiedDetails("alice");
        verifyNoMoreInteractions(kyc);
    }
}
