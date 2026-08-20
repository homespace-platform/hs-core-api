package com.hs.storage.advice.entity.enums;

import com.hs.common.advice.entity.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum StorageErrorCode implements AppException.ErrorCode {
    STORAGE_OBJECT_NOT_FOUND(3001, "Storage object not found", HttpStatus.NOT_FOUND),
    STORAGE_ACCESS_DENIED(3002, "You do not have permission to access this object", HttpStatus.FORBIDDEN),
    STORAGE_INVALID_FILE_TYPE(3003, "File type is not allowed for this purpose", HttpStatus.BAD_REQUEST),
    STORAGE_FILE_TOO_LARGE(3004, "File exceeds the allowed size", HttpStatus.BAD_REQUEST),
    STORAGE_UPLOAD_NOT_FOUND(3005, "Uploaded object was not found in storage", HttpStatus.CONFLICT),
    STORAGE_UPLOAD_MISMATCH(3006, "Uploaded object metadata does not match", HttpStatus.CONFLICT),
    STORAGE_NOT_READY(3007, "Storage object is not ready", HttpStatus.CONFLICT),
    STORAGE_PROVIDER_ERROR(3008, "Storage provider request failed", HttpStatus.BAD_GATEWAY),
    STORAGE_UNAUTHENTICATED(3009, "Authentication is required", HttpStatus.UNAUTHORIZED),
    STORAGE_INVALID_PURPOSE(3010, "Storage object has an invalid purpose", HttpStatus.BAD_REQUEST),
    STORAGE_OBJECT_NOT_PUBLIC(3011, "Storage object is not public", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    StorageErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
