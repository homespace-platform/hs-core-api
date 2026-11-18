package com.hs.api.controller.admin;

import com.hs.api.advice.handler.GlobalException;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.user.notification.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationBroadcastControllerTest {
    final BroadcastNotificationService service = mock(BroadcastNotificationService.class);
    final NotificationBroadcastController controller = new NotificationBroadcastController(service);
    static final String ID = "cc833761-2835-4bed-b8f1-339523a6c6ab";
    static final String BODY = "{\"broadcastId\":\"" + ID + "\",\"title\":\"Title\",\"body\":\"First line\\nsecond line\",\"priority\":\"HIGH\"}";
    @AfterEach void clear() { UserContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test void acceptedResponseIs202QueuedAndConflictingRetryIs409() throws Exception {
        UserContextHolder.set(new UserContext("verified-admin", null));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalException()).build();
        mvc.perform(post("/notifications/admin/broadcast").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.result.broadcastId").value(ID))
                .andExpect(jsonPath("$.result.status").value("QUEUED")).andExpect(jsonPath("$.result.recipientCount").doesNotExist());
        doThrow(new BroadcastNotificationService.IdConflict()).when(service).enqueueAdmin(any());
        mvc.perform(post("/notifications/admin/broadcast").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
    }

    @Test void validationRejectsMalformedIdContentPriorityAndUnsafePaths() throws Exception {
        UserContextHolder.set(new UserContext("verified-admin", null));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalException()).build();
        for (String body : List.of(BODY.replace(ID, "bad-id"), BODY.replace("Title", " "), BODY.replace("Title", "<script>"),
                BODY.replace("Title", "x".repeat(201)), BODY.replace("HIGH", "LOW"), BODY.replace("Title", "bad\\nline"),
                BODY.replace("First line\\nsecond line", "x".repeat(2001)), "{")) {
            mvc.perform(post("/notifications/admin/broadcast").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        for (String path : List.of("//evil.example", "/a/../admin", "/path://host", "relative", "/a b", "/" + "a".repeat(500))) {
            String body = BODY.substring(0, BODY.length() - 1) + ",\"actionPath\":\"" + path + "\"}";
            mvc.perform(post("/notifications/admin/broadcast").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void missingVerifiedIdentityNeverQueuesEvenForDirectControllerCall() {
        var request = new AdminBroadcastRequest(ID, "Title", "Body", AdminBroadcastRequest.Priority.NORMAL, null);
        assertThrows(AppException.class, () -> controller.broadcast(request)); verifyNoInteractions(service);
    }

    @Configuration @EnableMethodSecurity(proxyTargetClass = true) static class Security {
        @Bean BroadcastNotificationService service() { return mock(BroadcastNotificationService.class); }
        @Bean NotificationBroadcastController controller(BroadcastNotificationService service) { return new NotificationBroadcastController(service); }
    }
    @Test void methodSecurityRejectsUserAndAllowsAdminOnlyWithVerifiedContext() {
        try (var context = new AnnotationConfigApplicationContext(Security.class)) {
            var secured = context.getBean(NotificationBroadcastController.class);
            var target = context.getBean(BroadcastNotificationService.class);
            var request = new AdminBroadcastRequest(ID, "Title", "Body", AdminBroadcastRequest.Priority.NORMAL, null);
            UserContextHolder.set(new UserContext("verified-person", null));
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("person", "unused", List.of(new SimpleGrantedAuthority("USER"))));
            assertThrows(AccessDeniedException.class, () -> secured.broadcast(request)); verifyNoInteractions(target);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("person", "unused", List.of(new SimpleGrantedAuthority("ADMIN"), new SimpleGrantedAuthority("NOTIFICATION_BROADCAST"))));
            UserContextHolder.clear(); assertThrows(AppException.class, () -> secured.broadcast(request)); verifyNoInteractions(target);
            UserContextHolder.set(new UserContext("verified-admin", null)); secured.broadcast(request); verify(target).enqueueAdmin(request);
        }
    }
}
