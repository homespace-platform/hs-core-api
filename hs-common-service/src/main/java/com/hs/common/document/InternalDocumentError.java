package com.hs.common.document;

import com.hs.common.advice.entity.AppException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** Errors of the notification-only internal document API; the message is the stable token the caller branches on. */
@Getter
@RequiredArgsConstructor
public enum InternalDocumentError implements AppException.ErrorCode {
    NOT_READY(1031, "NOT_READY", HttpStatus.CONFLICT),
    NOT_FOUND(1032, "NOT_FOUND", HttpStatus.NOT_FOUND),
    RECIPIENT_FORBIDDEN(1033, "RECIPIENT_FORBIDDEN", HttpStatus.FORBIDDEN),
    INTEGRITY_FAILED(1034, "INTEGRITY_FAILED", HttpStatus.UNPROCESSABLE_ENTITY),
    TOO_LARGE(1035, "TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    public AppException exception() {
        return new AppException(this);
    }
}
