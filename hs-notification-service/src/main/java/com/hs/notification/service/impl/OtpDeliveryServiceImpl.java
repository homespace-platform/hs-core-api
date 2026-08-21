package com.hs.notification.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.hs.common.advice.entity.AppException;
import com.hs.notification.advice.entity.enums.NotificationErrorCode;
import com.hs.notification.model.OtpChannel;
import com.hs.notification.model.OtpPurpose;
import com.hs.notification.model.NotificationDelivery;
import com.hs.notification.model.NotificationDeliveryStatus;
import com.hs.notification.repository.NotificationDeliveryRepository;
import com.hs.notification.service.OtpDeliveryService;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpDeliveryServiceImpl implements OtpDeliveryService {
    private static final String DEVELOPMENT_PROFILE = "dev";

    private final JavaMailSender mailSender;
    private final Environment environment;
    private final NotificationDeliveryRepository deliveryRepository;

    @Value("${notification.mail.from:}")
    private String from;

    @Value("${notification.mail.from-name:HomeSpace}")
    private String fromName;

    @Override
    public void send(String challengeId, OtpChannel channel, String destination, String code,
            OtpPurpose purpose, long expiresInMinutes) {
        if (environment.matchesProfiles(DEVELOPMENT_PROFILE)) {
            logDevelopmentOtp(channel, destination, code, purpose, expiresInMinutes);
            recordDelivery(challengeId, channel, purpose, destination,
                    NotificationDeliveryStatus.DEVELOPMENT_LOGGED, null);
            return;
        }

        if (channel == OtpChannel.EMAIL) {
            sendEmail(challengeId, destination, code, purpose, expiresInMinutes);
            return;
        }

        sendSms(challengeId, destination, code, purpose, expiresInMinutes);
    }

    private void sendEmail(String challengeId, String destination, String code,
            OtpPurpose purpose, long expiresInMinutes) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            message.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            message.setRecipients(MimeMessage.RecipientType.TO, destination);
            message.setSubject("HomeSpace verification code", StandardCharsets.UTF_8.name());
            message.setText(buildEmailHtml(code, purpose, expiresInMinutes),
                    StandardCharsets.UTF_8.name(), "html");
            mailSender.send(message);
            recordDelivery(challengeId, OtpChannel.EMAIL, purpose, destination,
                    NotificationDeliveryStatus.SENT, null);
        } catch (Exception exception) {
            recordDelivery(challengeId, OtpChannel.EMAIL, purpose, destination,
                    NotificationDeliveryStatus.FAILED, exception.getClass().getSimpleName());
            throw new AppException(NotificationErrorCode.NOTIFICATION_PROVIDER_UNAVAILABLE);
        }
    }

    private void sendSms(String challengeId, String destination, String code,
            OtpPurpose purpose, long expiresInMinutes) {
        // TODO Integrate the selected SMS vendor (AWS SNS, Twilio, SpeedSMS, eSMS, etc.).
        recordDelivery(challengeId, OtpChannel.SMS, purpose, destination,
                NotificationDeliveryStatus.FAILED, "SmsProviderNotConfigured");
        throw new AppException(NotificationErrorCode.NOTIFICATION_PROVIDER_UNAVAILABLE);
    }

    private void recordDelivery(String challengeId, OtpChannel channel, OtpPurpose purpose,
            String destination, NotificationDeliveryStatus status, String failureType) {
        try {
            deliveryRepository.save(new NotificationDelivery(null, challengeId, channel, purpose,
                    mask(destination), status, failureType, Instant.now()));
        } catch (RuntimeException exception) {
            // Audit persistence must never prevent a valid OTP from being delivered.
            log.warn("Unable to persist notification delivery audit: challengeId={} status={} error={}",
                    challengeId, status, exception.getClass().getSimpleName());
        }
    }

    private void logDevelopmentOtp(OtpChannel channel, String destination, String code,
            OtpPurpose purpose, long expiresInMinutes) {
        log.warn("DEV OTP | channel={} destination={} purpose={} code={} expiresInMinutes={}",
                channel, mask(destination), purpose, code, expiresInMinutes);
    }

    private String buildEmailHtml(String code, OtpPurpose purpose, long expiresInMinutes) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:560px;margin:auto">
                  <h2>HomeSpace verification code</h2>
                  <p>Use this code to complete <strong>%s</strong>:</p>
                  <div style="font-size:32px;font-weight:700;letter-spacing:8px">%s</div>
                  <p>This code expires in %d minutes. Do not share it with anyone.</p>
                </div>
                """.formatted(purpose.name().toLowerCase().replace('_', ' '), code, expiresInMinutes);
    }

    private String mask(String value) {
        if (value == null || value.length() < 5) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
