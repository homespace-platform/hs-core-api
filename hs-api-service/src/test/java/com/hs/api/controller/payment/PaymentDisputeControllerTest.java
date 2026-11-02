package com.hs.api.controller.payment;

import com.hs.api.controller.admin.PaymentDisputeAdminController;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.common.dto.PageResponse;
import com.hs.payment.dto.DisputeCaseAction;
import com.hs.payment.model.constant.*;
import com.hs.payment.service.PaymentDisputeService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentDisputeControllerTest {
    final PaymentDisputeService service=mock(PaymentDisputeService.class);
    final PaymentDisputeController controller=new PaymentDisputeController(service);
    @AfterEach void clear(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    @Test void actorComesFromAuthenticatedContextRatherThanPayload(){
        UserContextHolder.set(new UserContext("tenant",null));
        var action=new DisputeCaseAction(4L,"Phản hồi căn cứ chứng từ đầy đủ",DisputeResolution.REOPEN_TRANSFER,null);
        controller.act("case","PROPOSE",action);
        verify(service).act("case","tenant","PROPOSE",action);
    }
    @Test void missingUserContextNeverReachesCaseService(){
        assertThrows(AppException.class,()->controller.cases("payment"));verifyNoInteractions(service);
    }
    @Test void httpRejectsMissingVersionShortReasonAndUnknownReceiptDecision() throws Exception {
        UserContextHolder.set(new UserContext("tenant",null));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/payment-disputes/case/actions/PROPOSE").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Đã kiểm tra chứng cứ đầy đủ\",\"resolution\":\"REOPEN_TRANSFER\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/payment-disputes/case/actions/PROPOSE").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":1,\"reason\":\"ngắn\",\"resolution\":\"REOPEN_TRANSFER\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/payment-disputes/case/actions/DECIDE").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":1,\"reason\":\"Đã kiểm tra chứng cứ đầy đủ\",\"resolution\":\"CONFIRM_RECEIPT\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Configuration @EnableMethodSecurity(proxyTargetClass=true) static class SecuredConfig {
        final PaymentDisputeService service=mock(PaymentDisputeService.class);
        @Bean PaymentDisputeAdminController admin(){return new PaymentDisputeAdminController(service);}
    }
    @Test void realMethodSecurityRejectsTenantAndPermitsOnlyAdminAuthority(){
        try(var context=new AnnotationConfigApplicationContext(SecuredConfig.class)){
            var secured=context.getBean(PaymentDisputeAdminController.class);var target=context.getBean(SecuredConfig.class).service;
            UserContextHolder.set(new UserContext("person",null));
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("person","n/a",List.of(new SimpleGrantedAuthority("TENANT"))));
            assertThrows(AccessDeniedException.class,()->secured.list(DisputeCaseStatus.ESCALATED,1,20));verifyNoInteractions(target);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("person","n/a",List.of(new SimpleGrantedAuthority("ADMIN"))));
            when(target.adminList("person",DisputeCaseStatus.ESCALATED,1,20)).thenReturn(new PageResponse<>());
            assertNotNull(secured.list(DisputeCaseStatus.ESCALATED,1,20));verify(target).adminList("person",DisputeCaseStatus.ESCALATED,1,20);
        }
    }
}
