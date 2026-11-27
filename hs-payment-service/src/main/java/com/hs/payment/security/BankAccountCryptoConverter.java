package com.hs.payment.security;

import com.hs.common.security.SecretCrypto;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Bank account encryption. Format (unchanged, no prefix): Base64(iv || AES-GCM ciphertext+tag).
 * Legacy plaintext rows are read through: a value that cannot be ciphertext (not Base64, or shorter
 * than iv + tag + 1 byte) is returned as-is. A value that has the ciphertext shape but fails to
 * decrypt (wrong HOMESPACE_ENCRYPTION_KEY, corruption) is never passed on as data: a warning without the
 * value is logged and the read fails.
 */
@Slf4j
@Converter
@Component
public class BankAccountCryptoConverter implements AttributeConverter<String, String> {

    private static final int MIN_CIPHERTEXT_BYTES = 12 + 16 + 1;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        return SecretCrypto.get().encryptBank(attribute.trim());
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || !looksLikeCiphertext(dbData)) {
            return dbData;
        }
        try {
            return SecretCrypto.get().decryptBank(dbData);
        } catch (GeneralSecurityException e) {
            log.warn("Bank account value has ciphertext format but could not be decrypted; check HOMESPACE_ENCRYPTION_KEY");
            throw new IllegalStateException("Cannot decrypt bank account data");
        }
    }

    private static boolean looksLikeCiphertext(String value) {
        try {
            return Base64.getDecoder().decode(value).length >= MIN_CIPHERTEXT_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
