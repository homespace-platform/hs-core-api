package com.hs.payment.security;

import com.hs.common.security.SecretCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BankAccountCryptoConverterTest {

    private final BankAccountCryptoConverter converter = new BankAccountCryptoConverter();

    @BeforeEach
    void setUp() {
        SecretCrypto.configure("my-hs-secret-value");
    }

    @Test
    void roundTrip() {
        String encrypted = converter.convertToDatabaseColumn("0987654321");
        assertNotEquals("0987654321", encrypted);
        assertEquals("0987654321", converter.convertToEntityAttribute(encrypted));
    }

    @Test
    void ciphertextWrittenByOldCodeWithSameKeyStillDecrypts() {
        assertEquals("0987654321",
                converter.convertToEntityAttribute("Dl5oysTur7VD8pSMeAmB5EiB7nFz/Lta4Q+pIKidJHy8B+skoXE="));
    }

    @Test
    void nullAndBlankPassThrough() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertEquals(" ", converter.convertToDatabaseColumn(" "));
        assertNull(converter.convertToEntityAttribute(null));
        assertEquals("", converter.convertToEntityAttribute(""));
    }

    @Test
    void legacyPlaintextIsReadThrough() {
        assertEquals("123456", converter.convertToEntityAttribute("123456"));
        assertEquals("Vietcombank 0123", converter.convertToEntityAttribute("Vietcombank 0123"));
    }

    @Test
    void realCiphertextThatCannotBeDecryptedFailsInsteadOfLeakingCiphertext() {
        String encrypted = converter.convertToDatabaseColumn("0987654321");
        SecretCrypto.configure("another-secret");
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute(encrypted));
    }

    @Test
    void missingSecretFailsClearly() {
        assertThrows(IllegalStateException.class, () -> SecretCrypto.configure(null));
    }
}
