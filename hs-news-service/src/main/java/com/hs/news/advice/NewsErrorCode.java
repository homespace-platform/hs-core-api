package com.hs.news.advice;

import com.hs.common.advice.entity.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum NewsErrorCode implements AppException.ErrorCode {
    NEWS_NOT_FOUND(5001, "News article not found", HttpStatus.NOT_FOUND),
    NEWS_SLUG_EXISTS(5002, "News slug already exists", HttpStatus.CONFLICT),
    NEWS_INVALID_CONTENT(5003, "News content is invalid", HttpStatus.BAD_REQUEST),
    NEWS_INVALID_MEDIA(5004, "News image is invalid", HttpStatus.BAD_REQUEST),
    NEWS_MEDIA_FORBIDDEN(5005, "News image belongs to another user", HttpStatus.FORBIDDEN),
    NEWS_AUTHENTICATION_REQUIRED(5006, "Authentication is required", HttpStatus.UNAUTHORIZED);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    NewsErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
