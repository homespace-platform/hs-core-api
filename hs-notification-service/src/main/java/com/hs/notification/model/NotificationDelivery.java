package com.hs.notification.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB audit document for an OTP delivery attempt.
 * It intentionally stores neither the plain OTP nor the complete destination.
 */
@Document(collection = "notification_deliveries")
public record NotificationDelivery(
        /** MongoDB document identifier generated when the record is inserted. */
        @Id String id,

        /** Public OTP challenge identifier used to correlate delivery and verification logs. */
        String challengeId,

        /** Transport selected for the message, such as EMAIL or SMS. */
        OtpChannel channel,

        /** Business reason for issuing the OTP, such as LOGIN or PASSWORD_RESET. */
        OtpPurpose purpose,

        /** Partially hidden email address or phone number; full personal data is not persisted. */
        String maskedDestination,

        /** Outcome of the delivery attempt. */
        NotificationDeliveryStatus status,

        /** Sanitized technical failure category; null for successful attempts. */
        String failureType,

        /** UTC timestamp at which the delivery attempt finished. */
        Instant createdAt
) {
}
