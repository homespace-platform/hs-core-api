package com.hs.api.controller.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hs.common.dto.ApiResponse;
import com.hs.user.dto.response.UserPermissionsResponse;
import com.hs.user.notification.UserEmailLookupService;
import com.hs.user.service.UserService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/users")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserInternalController {
    
    UserService userService;
    UserEmailLookupService emailLookup;

    @GetMapping("/{userId}/permissions")
    public ApiResponse<UserPermissionsResponse> getUserPermissions(@PathVariable String userId) {
        return ApiResponse.<UserPermissionsResponse>builder()
                .result(userService.getUserPermissions(userId))
                .build();
    }

    /** Notification-service only (/internal/** needs the gateway secret): addresses of active users, never exposed publicly. */
    @PostMapping("/emails")
    public ApiResponse<java.util.List<UserEmailLookupService.UserEmail>> getEmails(@RequestBody UserEmailLookupService.Request request) {
        return ApiResponse.<java.util.List<UserEmailLookupService.UserEmail>>builder()
                .result(emailLookup.findActive(request.userIds()))
                .build();
    }
}
