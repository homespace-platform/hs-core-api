package com.hs.api.controller.admin;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.user.notification.AdminBroadcastRequest;
import com.hs.user.notification.BroadcastNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('NOTIFICATION_BROADCAST')")
@RequestMapping("/notifications/admin/broadcast")
public class NotificationBroadcastController {
    private final BroadcastNotificationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<QueuedBroadcast> broadcast(@Valid @RequestBody AdminBroadcastRequest request) {
        var actor = UserContextHolder.get();
        if (actor == null || actor.userId() == null || actor.userId().isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);
        service.enqueueAdmin(request);
        return ApiResponse.<QueuedBroadcast>builder().result(new QueuedBroadcast(request.broadcastId(), "QUEUED")).build();
    }

    public record QueuedBroadcast(String broadcastId, String status) {}

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalidBody() {
        return ApiResponse.<Void>builder().code(ErrorCode.INVALID_REQUEST.getCode()).message("Invalid broadcast request").build();
    }
}
