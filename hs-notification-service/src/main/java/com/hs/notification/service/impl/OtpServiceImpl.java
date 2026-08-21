package com.hs.notification.service.impl;

import static com.hs.notification.advice.entity.enums.NotificationErrorCode.INVALID_DESTINATION;
import static com.hs.notification.advice.entity.enums.NotificationErrorCode.OTP_INVALID;
import static com.hs.notification.advice.entity.enums.NotificationErrorCode.OTP_MAX_ATTEMPTS;
import static com.hs.notification.advice.entity.enums.NotificationErrorCode.OTP_NOT_FOUND_OR_EXPIRED;
import static com.hs.notification.advice.entity.enums.NotificationErrorCode.OTP_RESEND_TOO_SOON;
import static com.hs.notification.advice.entity.enums.NotificationErrorCode.OTP_SEND_LIMIT_EXCEEDED;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.hs.common.advice.entity.AppException;
import com.hs.notification.dto.request.CreateOtpChallengeRequest;
import com.hs.notification.dto.response.OtpChallengeResponse;
import com.hs.notification.dto.response.OtpVerificationResponse;
import com.hs.notification.model.OtpChallenge;
import com.hs.notification.model.OtpChannel;
import com.hs.notification.dto.properties.OtpProperties;
import com.hs.notification.repository.OtpChallengeRepository;
import com.hs.notification.service.OtpDeliveryService;
import com.hs.notification.service.OtpService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIETNAMESE_PHONE_PATTERN = Pattern.compile(
            "^(?:\\+84|0)(?:3[2-9]|5[689]|7[06-9]|8[1-9]|9[0-9])[0-9]{7}$");
    private static final Duration HOURLY_WINDOW = Duration.ofHours(1);

    private final OtpChallengeRepository repository;
    private final OtpDeliveryService deliveryService;
    private final OtpProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public OtpChallengeResponse createChallenge(CreateOtpChallengeRequest request) {
        String destination = normalizeAndValidate(request.channel(), request.destination());
        return issue(request.channel(), request.purpose(), destination);
    }

    @Override
    public OtpChallengeResponse resend(String challengeId) {
        OtpChallenge existing = findChallenge(challengeId);
        OtpChallengeResponse response = issue(existing.channel(), existing.purpose(), existing.destination());
        repository.delete(challengeId);
        return response;
    }

    @Override
    public OtpVerificationResponse verify(String challengeId, String code) {
        OtpChallenge challenge = findChallenge(challengeId);
        if (challenge.attempts() >= challenge.maxAttempts()) {
            repository.delete(challengeId);
            throw new AppException(OTP_MAX_ATTEMPTS);
        }

        boolean matches = MessageDigest.isEqual(
                challenge.codeHash().getBytes(StandardCharsets.UTF_8),
                hash(challengeId, code).getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            int attempts = challenge.attempts() + 1;
            if (attempts >= challenge.maxAttempts()) {
                repository.delete(challengeId);
                throw new AppException(OTP_MAX_ATTEMPTS);
            }
            repository.save(challenge.withAttempts(attempts), remainingTtl(challenge));
            throw new AppException(OTP_INVALID);
        }

        repository.delete(challengeId);
        return new OtpVerificationResponse(true);
    }

    private OtpChallengeResponse issue(OtpChannel channel, com.hs.notification.model.OtpPurpose purpose,
            String destination) {
        String destinationKey = channel.name() + ":" + sha256(destination);
        if (!repository.acquireCooldown(destinationKey, properties.resendCooldown())) {
            throw new AppException(OTP_RESEND_TOO_SOON);
        }
        long sends = repository.incrementHourlyCounter(destinationKey, HOURLY_WINDOW);
        if (sends > properties.maxSendsPerHour()) {
            throw new AppException(OTP_SEND_LIMIT_EXCEEDED);
        }

        String challengeId = UUID.randomUUID().toString();
        String code = generateCode();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(properties.ttl());
        OtpChallenge challenge = new OtpChallenge(challengeId, channel, purpose, destination,
                hash(challengeId, code), 0, properties.maxAttempts(), createdAt, expiresAt);
        repository.save(challenge, properties.ttl());

        try {
            deliveryService.send(challengeId, channel, destination, code, purpose,
                    Math.max(1, properties.ttl().toMinutes()));
        } catch (RuntimeException exception) {
            repository.delete(challengeId);
            throw exception;
        }

        return response(challenge);
    }

    private OtpChallenge findChallenge(String challengeId) {
        OtpChallenge challenge = repository.findById(challengeId)
                .orElseThrow(() -> new AppException(OTP_NOT_FOUND_OR_EXPIRED));
        if (!challenge.expiresAt().isAfter(Instant.now())) {
            repository.delete(challengeId);
            throw new AppException(OTP_NOT_FOUND_OR_EXPIRED);
        }
        return challenge;
    }

    private String normalizeAndValidate(OtpChannel channel, String rawDestination) {
        String value = rawDestination.trim();
        if (channel == OtpChannel.EMAIL) {
            value = value.toLowerCase(Locale.ROOT);
            if (!EMAIL_PATTERN.matcher(value).matches()) {
                throw new AppException(INVALID_DESTINATION);
            }
            return value;
        }

        value = value.replace(" ", "").replace("-", "");
        if (!VIETNAMESE_PHONE_PATTERN.matcher(value).matches()) {
            throw new AppException(INVALID_DESTINATION);
        }
        return value.startsWith("0") ? "+84" + value.substring(1) : value;
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, properties.length());
        int minimum = bound / 10;
        int value = minimum + secureRandom.nextInt(bound - minimum);
        return Integer.toString(value);
    }

    private String hash(String challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (challengeId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Duration remainingTtl(OtpChallenge challenge) {
        Duration remaining = Duration.between(Instant.now(), challenge.expiresAt());
        return remaining.isNegative() || remaining.isZero() ? Duration.ofSeconds(1) : remaining;
    }

    private OtpChallengeResponse response(OtpChallenge challenge) {
        return new OtpChallengeResponse(challenge.id(), mask(challenge.destination()),
                challenge.expiresAt(), properties.resendCooldown().toSeconds());
    }

    private String mask(String destination) {
        int at = destination.indexOf('@');
        if (at > 1) {
            return destination.substring(0, 2) + "***" + destination.substring(at);
        }
        return destination.length() > 4
                ? "***" + destination.substring(destination.length() - 4)
                : "***";
    }
}
