package com.hs.payment.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BankAccountCryptoConverterTest {

    private BankAccountCryptoConverter converter;

    @BeforeEach
    void setUp() {
        converter = new BankAccountCryptoConverter();
    }

    @Test
    @DisplayName("Encrypt and decrypt with default key returns original plaintext")
    void defaultKey_RoundTrip() {
        String accountNumber = "0987654321";
        String encrypted = converter.convertToDatabaseColumn(accountNumber);

        assertNotNull(encrypted);
        assertNotEquals(accountNumber, encrypted);

        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertEquals(accountNumber, decrypted);
    }

    @Test
    @DisplayName("Encrypt and decrypt with custom passphrase (arbitrary length) succeeds via SHA-256 derivation")
    void customPassphrase_RoundTrip() {
        converter.setConfiguredKey("arbitrary-length-passphrase-that-is-not-32-chars");

        String accountNumber = "9353999798";
        String encrypted = converter.convertToDatabaseColumn(accountNumber);

        assertNotNull(encrypted);
        assertNotEquals(accountNumber, encrypted);

        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertEquals(accountNumber, decrypted);
    }

    @Test
    @DisplayName("Encrypt and decrypt with Base64 encoded 256-bit key succeeds")
    void base64Key_RoundTrip() {
        byte[] rawKey = new byte[32];
        for (int i = 0; i < 32; i++) {
            rawKey[i] = (byte) (i + 1);
        }
        String b64Key = Base64.getEncoder().encodeToString(rawKey);
        converter.setConfiguredKey(b64Key);

        String accountNumber = "1234567890123456";
        String encrypted = converter.convertToDatabaseColumn(accountNumber);

        assertNotNull(encrypted);
        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertEquals(accountNumber, decrypted);
    }

    @Test
    @DisplayName("Null or blank string returns null or blank")
    void nullOrBlank_ReturnsAsIs() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertEquals("", converter.convertToDatabaseColumn(""));
        assertEquals("   ", converter.convertToDatabaseColumn("   "));

        assertNull(converter.convertToEntityAttribute(null));
        assertEquals("", converter.convertToEntityAttribute(""));
    }

    @Test
    @DisplayName("Plain text legacy data returns original text when decryption fails")
    void legacyPlainText_ReturnsOriginal() {
        String plainLegacy = "123456";
        String result = converter.convertToEntityAttribute(plainLegacy);
        assertEquals(plainLegacy, result);
    }
}
