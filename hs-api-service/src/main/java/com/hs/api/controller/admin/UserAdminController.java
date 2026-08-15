package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.request.UserRoleAssign;
import com.hs.user.service.UserService;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequestMapping("/admin/users")
@PreAuthorize("hasAuthority('ADMIN')")
public class UserAdminController {

    UserService userService;

    @PatchMapping("/{userId}/disable")
    public ApiResponse<@NonNull Void> disableUser(@PathVariable String userId) {
        userService.updateUserStatus(userId, false);
        return ApiResponse.<Void>builder()
                .message("User disabled successfully")
                .build();
    }

    @PatchMapping("/{userId}/enable")
    public ApiResponse<@NonNull Void> enableUser(@PathVariable String userId) {
        userService.updateUserStatus(userId, true);
        return ApiResponse.<Void>builder()
                .message("User enabled successfully")
                .build();
    }

    @PostMapping("/assign-role")
    public ApiResponse<Void> assignRole(@RequestBody @Valid UserRoleAssign request) {
        userService.assignRole(request);
        return ApiResponse.<Void>builder()
                .message("Role assigned successfully")
                .build();
    }
}


