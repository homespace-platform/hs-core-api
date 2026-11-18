package com.hs.api.controller.user;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hs.api.facade.OnboardingFacade;
import com.hs.api.facade.UserAvatarFacade;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.user.dto.response.UserPermissionsResponse;
import com.hs.user.service.UserService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserControllerPermissionsTest {
  final UserService service = mock(UserService.class);
  final UserController controller =
      new UserController(service, mock(UserAvatarFacade.class), mock(OnboardingFacade.class));

  @AfterEach
  void cleanup() {
    UserContextHolder.clear();
  }

  @Test
  void adminGetsOwnPermissionsFromVerifiedIdentity() throws Exception {
    UserContextHolder.set(new UserContext("admin-1", null));
    when(service.getUserPermissions("admin-1"))
        .thenReturn(new UserPermissionsResponse("a@x.vn", "ADMIN", Set.of("KYC_VIEW")));
    MockMvcBuilders.standaloneSetup(controller).build()
        .perform(get("/users/me/permissions").param("userId", "other"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.email").value("a@x.vn"))
        .andExpect(jsonPath("$.result.role").value("ADMIN"))
        .andExpect(jsonPath("$.result.permissions[0]").value("KYC_VIEW"));
    verify(service).getUserPermissions("admin-1");
    verifyNoMoreInteractions(service);
  }

  @Test
  void userRoleGetsItsRealPermissions() throws Exception {
    UserContextHolder.set(new UserContext("u-1", null));
    when(service.getUserPermissions("u-1"))
        .thenReturn(new UserPermissionsResponse("u@x.vn", "USER", Set.of("KYC_SUBMIT")));
    MockMvcBuilders.standaloneSetup(controller).build()
        .perform(get("/users/me/permissions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.role").value("USER"))
        .andExpect(jsonPath("$.result.permissions[0]").value("KYC_SUBMIT"));
  }

  @Test
  void missingIdentityIsUnauthenticatedAndNeverReachesService() {
    org.junit.jupiter.api.Assertions.assertThrows(AppException.class, controller::getMyPermissions);
    verifyNoInteractions(service);
  }
}
