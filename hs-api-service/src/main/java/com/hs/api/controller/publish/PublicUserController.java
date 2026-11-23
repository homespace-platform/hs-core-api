package com.hs.api.controller.publish;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.response.PublicUserProfileResponse;
import com.hs.user.service.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/users")
@RequiredArgsConstructor
@Validated
public class PublicUserController {

    private final UserService userService;

    @GetMapping("/{username}")
    public ApiResponse<PublicUserProfileResponse> getByUsername(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[\\p{L}\\p{N}._@-]+") String username) {
        return ApiResponse.<PublicUserProfileResponse>builder()
                .result(userService.getPublicUserProfileByUsername(username))
                .build();
    }
}
