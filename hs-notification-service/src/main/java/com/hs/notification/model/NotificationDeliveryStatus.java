package com.hs.notification.model;

/** Final state of one attempt to deliver an OTP notification. */
public enum NotificationDeliveryStatus {
    SENT,
    FAILED,
    DEVELOPMENT_LOGGED
}
