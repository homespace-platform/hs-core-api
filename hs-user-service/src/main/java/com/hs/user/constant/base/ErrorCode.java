package com.hs.user.constant.base;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public enum ErrorCode {
    USER_EXISTED(1004, "User existed", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(1005, "User not existed", HttpStatus.NOT_FOUND),
    USER_ALREADY_ONBOARDED(1006, "User already onboarded", HttpStatus.BAD_REQUEST),
    INVALID_OLD_PASSWORD(1007, "Invalid old password", HttpStatus.BAD_REQUEST),
    PASSWORD_REQUIRED(1008, "Password is required", HttpStatus.BAD_REQUEST),
    NEW_PASSWORD_REQUIRED(1009, "New password is required", HttpStatus.BAD_REQUEST),
    PASSWORD_TOO_SHORT(1010, "Password must be at least 8 characters", HttpStatus.BAD_REQUEST),
    PASSWORD_WEAK(1011, "Password must include at least 1 uppercase letter, 1 digit and 1 special character", HttpStatus.BAD_REQUEST),
    USERNAME_EXISTED(1013, "Username existed", HttpStatus.BAD_REQUEST),
    EMAIL_EXISTED(1014, "Email existed", HttpStatus.BAD_REQUEST),
    EMAIL_ALREADY_VERIFIED(1015, "Email already verified", HttpStatus.BAD_REQUEST),
    USER_ALREADY_HAS_ROLE(1016, "User already has this role", HttpStatus.BAD_REQUEST),
    ROLE_EXISTED(1101, "Role existed", HttpStatus.BAD_REQUEST),
    ROLE_NOT_EXISTED(1102, "Role not existed", HttpStatus.NOT_FOUND),
    PERMISSION_EXISTED(1201, "Permission existed", HttpStatus.BAD_REQUEST),
    PERMISSION_NOT_EXISTED(1202, "Permission not existed", HttpStatus.NOT_FOUND),
    KEYCLOAK_USER_UPDATE_FAILED(1301, "Keycloak user update failed", HttpStatus.BAD_GATEWAY),
    KEYCLOAK_PASSWORD_UPDATE_FAILED(1302, "Keycloak password update failed", HttpStatus.BAD_GATEWAY);

    ErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }

    int code;
    String message;
    HttpStatusCode statusCode;
}
