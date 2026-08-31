package com.hs.listing.advice;

import com.hs.common.advice.entity.AppException;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum ListingErrorCode implements AppException.ErrorCode {
    LISTING_NOT_FOUND(4001, "Listing not found", HttpStatus.NOT_FOUND),
    LISTING_FORBIDDEN(4002, "You do not have permission to access this listing", HttpStatus.FORBIDDEN),
    INVALID_LISTING_STATUS_TRANSITION(4003, "Invalid listing status transition", HttpStatus.CONFLICT),
    LISTING_LOCKED_BY_VIOLATION(4004, "Listing is locked because of a violation", HttpStatus.CONFLICT),
    LISTING_HAS_ACTIVE_CONTRACT(4005, "Listing has an active contract", HttpStatus.CONFLICT),
    MODERATION_REASON_REQUIRED(4006, "A moderation reason is required", HttpStatus.BAD_REQUEST),
    LISTING_ALREADY_REVIEWED(4007, "Listing has already been reviewed", HttpStatus.CONFLICT),
    LISTING_VERSION_CONFLICT(4008, "Listing was changed by another request", HttpStatus.CONFLICT),
    LISTING_AUTHENTICATION_REQUIRED(4009, "Authentication is required", HttpStatus.UNAUTHORIZED),
    LISTING_OWNER_NOT_FOUND(4010, "Listing owner not found", HttpStatus.NOT_FOUND);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    ListingErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
