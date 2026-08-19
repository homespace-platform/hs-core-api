package com.hs.user.constant.base;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public enum ErrorCode {
    // 2xxx — user service
    // 20xx user / account
    USER_EXISTED(2001, "User existed", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(2002, "User not existed", HttpStatus.NOT_FOUND),
    USER_ALREADY_ONBOARDED(2003, "User already onboarded", HttpStatus.BAD_REQUEST),
    INVALID_OLD_PASSWORD(2004, "Invalid old password", HttpStatus.BAD_REQUEST),
    PASSWORD_REQUIRED(2005, "Password is required", HttpStatus.BAD_REQUEST),
    NEW_PASSWORD_REQUIRED(2006, "New password is required", HttpStatus.BAD_REQUEST),
    PASSWORD_TOO_SHORT(2007, "Password must be at least 8 characters", HttpStatus.BAD_REQUEST),
    PASSWORD_WEAK(2008, "Password must include at least 1 uppercase letter, 1 digit and 1 special character", HttpStatus.BAD_REQUEST),
    USERNAME_EXISTED(2009, "Username existed", HttpStatus.BAD_REQUEST),
    EMAIL_EXISTED(2010, "Email existed", HttpStatus.BAD_REQUEST),
    USER_ALREADY_HAS_ROLE(2012, "User already has this role", HttpStatus.BAD_REQUEST),
    PASSWORD_ALREADY_SET(2013, "Password has already been set", HttpStatus.CONFLICT),
    PHONE_EXISTED(2014, "Phone number existed", HttpStatus.CONFLICT),

    // 21xx role
    ROLE_EXISTED(2101, "Role existed", HttpStatus.BAD_REQUEST),
    ROLE_NOT_EXISTED(2102, "Role not existed", HttpStatus.NOT_FOUND),

    // 22xx permission
    PERMISSION_EXISTED(2201, "Permission existed", HttpStatus.BAD_REQUEST),
    PERMISSION_NOT_EXISTED(2202, "Permission not existed", HttpStatus.NOT_FOUND),

    // 23xx keycloak integration
    KEYCLOAK_USER_UPDATE_FAILED(2301, "Keycloak user update failed", HttpStatus.BAD_GATEWAY),
    KEYCLOAK_PASSWORD_UPDATE_FAILED(2302, "Keycloak password update failed", HttpStatus.BAD_GATEWAY),
    KEYCLOAK_CREDENTIAL_READ_FAILED(2303, "Could not read Keycloak credentials", HttpStatus.BAD_GATEWAY);

    ErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }

    int code;
    String message;
    HttpStatusCode statusCode;
}
