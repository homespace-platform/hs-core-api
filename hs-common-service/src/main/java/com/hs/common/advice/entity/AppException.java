package com.hs.common.advice.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatusCode;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AppException extends RuntimeException {
    int code;
    String errorMessage;
    HttpStatusCode statusCode;

    public AppException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage(), errorCode.getStatusCode());
    }

    public AppException(int code, String message, HttpStatusCode statusCode) {
        super(message);
        this.code = code;
        this.errorMessage = message;
        this.statusCode = statusCode;
    }

    public interface ErrorCode {
        int getCode();

        String getMessage();

        HttpStatusCode getStatusCode();
    }
}
