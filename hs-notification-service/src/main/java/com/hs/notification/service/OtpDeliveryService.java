package com.hs.notification.service;

import com.hs.notification.model.OtpChannel;
import com.hs.notification.model.OtpPurpose;

/**
 * Delivers a generated OTP through the requested communication channel.
 * The implementation selects development logging, SMTP email, or the future
 * SMS integration according to the active profile and channel.
 */
public interface OtpDeliveryService {

    void send(String challengeId, OtpChannel channel, String destination, String code,
            OtpPurpose purpose, long expiresInMinutes);
}
