package com.hs.notification.model;

/** Business operations for which an OTP challenge may be issued. */
public enum OtpPurpose {
    LOGIN,
    REGISTER,
    PASSWORD_RESET,
    VERIFY_EMAIL,
    VERIFY_PHONE
}
