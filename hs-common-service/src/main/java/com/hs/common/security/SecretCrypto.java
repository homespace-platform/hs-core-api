package com.hs.common.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The single place that turns HOMESPACE_ENCRYPTION_KEY into keys and applies AES-256-GCM.
 * Bank data keeps its legacy key derivation (so stored rows still decrypt); KYC data and the
 * CCCD blind index use HMAC-SHA256 sub-keys with domain separation.
 */
public final class SecretCrypto {

    public static final String V1_PREFIX = "v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile SecretCrypto instance;

    private final byte[] bankKey;
    private final byte[] kycKey;
    private final byte[] indexKey;

    private SecretCrypto(String secret) {
        this.bankKey = deriveKeyBytes(secret);
        this.kycKey = hmac(secret.getBytes(StandardCharsets.UTF_8), "homespace/kyc-enc/v1");
        this.indexKey = hmac(secret.getBytes(StandardCharsets.UTF_8), "homespace/cccd-index/v1");
    }

    public static SecretCrypto of(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("HOMESPACE_ENCRYPTION_KEY (homespace.security.encryption-key) must be set and not blank");
        }
        return new SecretCrypto(secret);
    }

    public static void configure(String secret) {
        instance = of(secret);
    }

    public static SecretCrypto get() {
        SecretCrypto current = instance;
        if (current == null) {
            throw new IllegalStateException("HOMESPACE_ENCRYPTION_KEY (homespace.security.encryption-key) is not configured");
        }
        return current;
    }

    /** Legacy bank format: Base64(iv || ciphertext+tag), no prefix. */
    public String encryptBank(String plain) {
        return seal(bankKey, plain);
    }

    /** Throws GeneralSecurityException when the value is not valid ciphertext for this key. */
    public String decryptBank(String stored) throws GeneralSecurityException {
        return open(bankKey, stored);
    }

    public String encryptKyc(String plain) {
        return V1_PREFIX + seal(kycKey, plain);
    }

    public static boolean isKycCiphertext(String stored) {
        return stored != null && stored.startsWith(V1_PREFIX);
    }

    /** Decrypts a "v1:" value; never returns ciphertext as data. */
    public String decryptKyc(String stored) {
        try {
            return open(kycKey, stored.substring(V1_PREFIX.length()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt KYC value (wrong HOMESPACE_ENCRYPTION_KEY or corrupted data)");
        }
    }

    /** Hex HMAC-SHA256 of the normalized (trimmed, no inner spaces, upper-case) citizen ID. */
    public String cccdHash(String cccd) {
        String normalized = cccd.replaceAll("\s+", "").toUpperCase(Locale.ROOT);
        return HexFormat.of().formatHex(hmac(indexKey, normalized));
    }

    private static String seal(byte[] key, String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt data", e);
        }
    }

    private static String open(byte[] key, String b64) throws GeneralSecurityException {
        byte[] decoded = Base64.getDecoder().decode(b64);
        if (decoded.length <= IV_LENGTH_BYTE) {
            throw new GeneralSecurityException("too short");
        }
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_LENGTH_BIT, decoded, 0, IV_LENGTH_BYTE));
        return new String(cipher.doFinal(decoded, IV_LENGTH_BYTE, decoded.length - IV_LENGTH_BYTE), StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** Exactly the pre-existing bank derivation; changing it would orphan stored bank data. */
    static byte[] deriveKeyBytes(String rawKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(rawKey);
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return decoded;
            }
        } catch (Exception ignored) {
            // Not Base64 or wrong format, fall through
        }
        byte[] rawBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (rawBytes.length == 16 || rawBytes.length == 24 || rawBytes.length == 32) {
            return rawBytes;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(rawBytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
