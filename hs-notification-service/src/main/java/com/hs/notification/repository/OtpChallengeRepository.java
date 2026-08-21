package com.hs.notification.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.hs.notification.model.OtpChallenge;
import com.hs.notification.model.OtpChannel;
import com.hs.notification.model.OtpPurpose;

import lombok.RequiredArgsConstructor;

/**
 * Redis-backed persistence component for short-lived OTP challenges,
 * resend cooldowns, and hourly request counters.
 */
@Repository
@RequiredArgsConstructor
public class OtpChallengeRepository {
    private static final String CHALLENGE_PREFIX = "notification:otp:challenge:";
    private static final String COOLDOWN_PREFIX = "notification:otp:cooldown:";
    private static final String HOURLY_PREFIX = "notification:otp:hourly:";

    private final StringRedisTemplate redisTemplate;

    public void save(OtpChallenge challenge, Duration ttl) {
        String key = CHALLENGE_PREFIX + challenge.id();
        redisTemplate.opsForHash().putAll(key, Map.of(
                "channel", challenge.channel().name(),
                "purpose", challenge.purpose().name(),
                "destination", challenge.destination(),
                "codeHash", challenge.codeHash(),
                "attempts", Integer.toString(challenge.attempts()),
                "maxAttempts", Integer.toString(challenge.maxAttempts()),
                "createdAt", challenge.createdAt().toString(),
                "expiresAt", challenge.expiresAt().toString()));
        redisTemplate.expire(key, ttl);
    }

    public Optional<OtpChallenge> findById(String challengeId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(CHALLENGE_PREFIX + challengeId);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OtpChallenge(
                challengeId,
                OtpChannel.valueOf(value(values, "channel")),
                OtpPurpose.valueOf(value(values, "purpose")),
                value(values, "destination"),
                value(values, "codeHash"),
                Integer.parseInt(value(values, "attempts")),
                Integer.parseInt(value(values, "maxAttempts")),
                Instant.parse(value(values, "createdAt")),
                Instant.parse(value(values, "expiresAt"))));
    }

    public void delete(String challengeId) {
        redisTemplate.delete(CHALLENGE_PREFIX + challengeId);
    }

    public boolean acquireCooldown(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(COOLDOWN_PREFIX + key, "1", ttl));
    }

    public long incrementHourlyCounter(String key, Duration ttl) {
        String redisKey = HOURLY_PREFIX + key;
        Long value = redisTemplate.opsForValue().increment(redisKey);
        if (value != null && value == 1L) {
            redisTemplate.expire(redisKey, ttl);
        }
        return value == null ? 0L : value;
    }

    private String value(Map<Object, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("Corrupted OTP challenge: missing " + name);
        }
        return value.toString();
    }
}
