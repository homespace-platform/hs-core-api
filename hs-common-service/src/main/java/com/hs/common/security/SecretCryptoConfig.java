package com.hs.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fails startup when HOMESPACE_ENCRYPTION_KEY is missing or blank. */
@Component
class SecretCryptoConfig {
    SecretCryptoConfig(@Value("${homespace.security.encryption-key}") String secret) {
        SecretCrypto.configure(secret);
    }
}
