package com.hs.api.controller.contract;
import com.hs.api.controller.admin.ComplaintAdminController;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.common.dto.PageResponse;
import com.hs.contract.dto.complaint.*;
import com.hs.contract.service.ComplaintCaseService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ComplaintControllerTest {
    final ComplaintCaseService service=mock(ComplaintCaseService.class);
    final ComplaintController controller=new ComplaintController(service);
    @AfterEach void clear(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    @Test void partyIdentityAlwaysComesFromContext(){
        UserContextHolder.set(new UserContext("tenant",null));
        var p=new ComplaintOpen(ComplaintCategory.REPAIR,"Khóa cửa không đúng như thỏa thuận",List.of(),false);
        controller.open("rental",p);verify(service).open("rental","tenant",p);
        var action=new ComplaintAction(3L,"Phản hồi có căn cứ về hiện trạng",null,null,null,null);
        controller.act("case","COMMENT",action);verify(service).act("case","tenant","COMMENT",action);
    }
    @Test void noContextNeverReachesSourceOrComplaintService(){assertThrows(AppException.class,()->controller.list("rental"));verifyNoInteractions(service);}
    @Test void bindingRejectsMissingVersionShortReasonUnknownOutcomeAndOversizedEvidenceIds() throws Exception {
        UserContextHolder.set(new UserContext("tenant",null));var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/complaints/case/actions/COMMENT").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Đã đọc hồ sơ và phản hồi đầy đủ\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/complaints/case/actions/COMMENT").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"reason\":\"ngắn\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/complaints/case/actions/DECIDE").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"reason\":\"Đã đọc hồ sơ và phản hồi đầy đủ\",\"outcome\":\"CONFIRM_RECEIPT\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/complaints/case/actions/COMMENT").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1,\"reason\":\"Đã đọc hồ sơ và phản hồi đầy đủ\",\"evidenceIds\":[\""+"a".repeat(37)+"\"]}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Configuration @EnableMethodSecurity(proxyTargetClass=true) static class SecuredConfig {
        final ComplaintCaseService service=mock(ComplaintCaseService.class);
        @Bean ComplaintAdminController controller(){return new ComplaintAdminController(service);}
    }
    @Test void realMethodSecurityRejectsTenantAndAllowsAdminRead(){
        try(var context=new AnnotationConfigApplicationContext(SecuredConfig.class)){
            var secured=context.getBean(ComplaintAdminController.class);var target=context.getBean(SecuredConfig.class).service;
            UserContextHolder.set(new UserContext("person",null));
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("person","n/a",List.of(new SimpleGrantedAuthority("TENANT"))));
            assertThrows(AccessDeniedException.class,()->secured.list(null,null,null,1,20));verifyNoInteractions(target);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("person","n/a",List.of(new SimpleGrantedAuthority("ADMIN"))));
            when(target.adminList("person",null,null,null,1,20)).thenReturn(new PageResponse<>());
            assertNotNull(secured.list(null,null,null,1,20));verify(target).adminList("person",null,null,null,1,20);
        }
    }
}
