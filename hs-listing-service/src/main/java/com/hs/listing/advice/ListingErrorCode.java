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
    LISTING_OWNER_NOT_FOUND(4010, "Listing owner not found", HttpStatus.NOT_FOUND),
    LISTING_PUBLICATION_WINDOW_ENDED(
            4011,
            "Publication period has ended, submit the listing for review again",
            HttpStatus.CONFLICT),
    APPOINTMENT_NOT_FOUND(4020, "Viewing appointment not found", HttpStatus.NOT_FOUND),
    APPOINTMENT_FORBIDDEN(4021, "You do not have permission to perform this appointment action", HttpStatus.FORBIDDEN),
    CANNOT_BOOK_OWN_LISTING(4022, "You cannot book a viewing appointment for your own listing", HttpStatus.BAD_REQUEST),
    APPOINTMENT_ALREADY_EXISTS(4023, "You already have an active viewing appointment for this listing", HttpStatus.CONFLICT),
    SLOT_ALREADY_BOOKED(4024, "This time slot has already been booked and confirmed", HttpStatus.CONFLICT),
    DAY_NOT_AVAILABLE(4025, "The owner does not accept viewings on this day of the week", HttpStatus.BAD_REQUEST),
    SLOT_NOT_AVAILABLE(4026, "The selected time is outside the owner's available viewing slots", HttpStatus.BAD_REQUEST),
    INVALID_APPOINTMENT_TIME(4027, "Invalid appointment time. Appointments must be booked in advance", HttpStatus.BAD_REQUEST),
    INVALID_APPOINTMENT_STATUS(4028, "Invalid appointment status for this action", HttpStatus.CONFLICT),
    RESCHEDULE_ALREADY_REQUESTED(4029, "A reschedule request is already pending review", HttpStatus.CONFLICT),
    LISTING_NOT_AVAILABLE_FOR_VIEWING(4030, "This listing is not currently available for viewing appointments", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatusCode statusCode;

    ListingErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
