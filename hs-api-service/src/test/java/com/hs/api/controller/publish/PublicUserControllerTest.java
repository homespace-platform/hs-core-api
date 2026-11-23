package com.hs.api.controller.publish;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;

import com.hs.user.dto.response.PublicUserProfileResponse;
import com.hs.user.service.UserService;

class PublicUserControllerTest {
    @Test
    void servesProfileByUsernameWithProvinceName() throws Exception {
        var users = mock(UserService.class);
        when(users.getPublicUserProfileByUsername("an")).thenReturn(PublicUserProfileResponse.builder()
                .id("u1").username("an").provinceName("Ha Noi").build());
        var mvc = standaloneSetup(new PublicUserController(users)).build();

        mvc.perform(get("/public/users/an")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.username").value("an"))
                .andExpect(jsonPath("$.result.provinceName").value("Ha Noi"))
                .andExpect(jsonPath("$.result.email").doesNotExist());
    }
}
