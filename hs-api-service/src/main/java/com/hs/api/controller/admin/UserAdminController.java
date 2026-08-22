package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.user.dto.request.UserRoleAssign;
import com.hs.user.dto.request.AdminCreateUserRequest;
import com.hs.user.dto.response.AdminCreateUserResponse;
import com.hs.user.dto.response.UserResponse;
import com.hs.user.service.UserService;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequestMapping("/admin/users")
@PreAuthorize("hasAuthority('ADMIN')")
public class UserAdminController {

    UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ApiResponse<AdminCreateUserResponse> createUser(
            @RequestBody @Valid AdminCreateUserRequest request) {
        return ApiResponse.<AdminCreateUserResponse>builder()
                .message("User created successfully and queued for synchronization")
                .result(userService.createUser(request))
                .build();
    }

    @GetMapping
    public ApiResponse<PageResponse<UserResponse>> findAllUsers(
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        PageResponse<UserResponse> page = new PageResponse<>(userService.findAllUsers(pageable));
        return ApiResponse.<PageResponse<UserResponse>>builder()
                .result(page)
                .build();
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserResponse> findUserById(@PathVariable String userId) {
        return ApiResponse.<UserResponse>builder()
                .result(userService.findUserById(userId))
                .build();
    }

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


