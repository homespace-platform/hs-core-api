package com.hs.payment.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Converter
@Component
public class BankAccountCryptoConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final String DEV_FALLBACK_KEY = "HomeSpaceDevSecretBankKey2026!#$*"; // Exactly 32 chars = 256 bits

    private static SecretKey secretKey;

    @Value("${homespace.security.bank-account.encryption-key:}")
    public void setConfiguredKey(String configuredKey) {
        initSecretKey(configuredKey);
    }

    private static synchronized void initSecretKey(String configuredKey) {
        String keyToUse = configuredKey;
        if (keyToUse == null || keyToUse.isBlank()) {
            keyToUse = System.getenv("HOMESPACE_BANK_ENCRYPTION_KEY");
        }
        if (keyToUse == null || keyToUse.isBlank()) {
            keyToUse = DEV_FALLBACK_KEY;
        }

        byte[] keyBytes = deriveKeyBytes(keyToUse);
        secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] deriveKeyBytes(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            rawKey = DEV_FALLBACK_KEY;
        }
        // If it's valid Base64 decoding to 16, 24, or 32 bytes, use decoded bytes
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

        // Always hash to 32 bytes (256 bits) using SHA-256 for any arbitrary length passphrase
        try {
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            return sha256.digest(rawBytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private SecretKey getSecretKey() {
        if (secretKey == null) {
            initSecretKey(null);
        }
        return secretKey;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), parameterSpec);

            byte[] cipherText = cipher.doFinal(attribute.trim().getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt bank account data", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(dbData);
            if (decoded.length <= IV_LENGTH_BYTE) {
                // Return as is if not encrypted (backward compatibility with plain text)
                return dbData;
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH_BYTE];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // If decryption fails (e.g. legacy plain text string in DB), return as-is
            return dbData;
        }
    }
}
