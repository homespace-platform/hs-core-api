package com.hs.user.security;

import com.hs.common.security.SecretCrypto;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Encrypts KYC PII at rest ("v1:" + AES-256-GCM); legacy plaintext is read through until migrated. */
@Converter
public class KycCryptoConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        return SecretCrypto.get().encryptKyc(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return SecretCrypto.isKycCiphertext(dbData) ? SecretCrypto.get().decryptKyc(dbData) : dbData;
    }
}
