package com.hs.notification.advice.entity.enums;

import com.hs.common.advice.entity.AppException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public enum NotificationErrorCode implements AppException.ErrorCode {
    INVALID_DESTINATION(4001, "Invalid OTP destination", HttpStatus.BAD_REQUEST),
    OTP_NOT_FOUND_OR_EXPIRED(4002, "OTP challenge was not found or has expired", HttpStatus.BAD_REQUEST),
    OTP_INVALID(4003, "OTP code is invalid", HttpStatus.BAD_REQUEST),
    OTP_MAX_ATTEMPTS(4004, "Maximum OTP verification attempts exceeded", HttpStatus.TOO_MANY_REQUESTS),
    OTP_RESEND_TOO_SOON(4005, "Please wait before requesting another OTP", HttpStatus.TOO_MANY_REQUESTS),
    OTP_SEND_LIMIT_EXCEEDED(4006, "OTP send limit exceeded", HttpStatus.TOO_MANY_REQUESTS),
    NOTIFICATION_PROVIDER_UNAVAILABLE(4007, "Notification provider is unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    NotificationErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }

    int code;
    String message;
    HttpStatusCode statusCode;
}
