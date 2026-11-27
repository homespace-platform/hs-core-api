package com.hs.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretCryptoTest {

    // Produced by the pre-HOMESPACE_ENCRYPTION_KEY BankAccountCryptoConverter with this key and plaintext "0987654321".
    private static final String OLD_BANK_CIPHERTEXT = "Dl5oysTur7VD8pSMeAmB5EiB7nFz/Lta4Q+pIKidJHy8B+skoXE=";
    private static final String OLD_KEY = "my-hs-secret-value";

    @Test
    void oldBankCiphertextStillDecryptsWithSameSecret() throws Exception {
        assertEquals("0987654321", SecretCrypto.of(OLD_KEY).decryptBank(OLD_BANK_CIPHERTEXT));
    }

    @Test
    void oldBankCiphertextDoesNotDecryptWithAnotherSecret() {
        assertThrows(Exception.class, () -> SecretCrypto.of("other").decryptBank(OLD_BANK_CIPHERTEXT));
    }

    @Test
    void kycRoundTripIsVersionedAndRandomized() {
        SecretCrypto c = SecretCrypto.of("s");
        String a = c.encryptKyc("Nguyen Van A");
        assertTrue(a.startsWith("v1:"));
        assertNotEquals(a, c.encryptKyc("Nguyen Van A"));
        assertEquals("Nguyen Van A", c.decryptKyc(a));
    }

    @Test
    void kycDecryptFailureThrowsWithoutLeakingData() {
        String stored = SecretCrypto.of("s").encryptKyc("001234567890");
        var ex = assertThrows(IllegalStateException.class, () -> SecretCrypto.of("t").decryptKyc(stored));
        assertFalse(ex.getMessage().contains("0012"));
        assertThrows(IllegalStateException.class, () -> SecretCrypto.of("s").decryptKyc("v1:!!notbase64"));
    }

    @Test
    void kycKeyIsNotTheBankKey() {
        SecretCrypto c = SecretCrypto.of("s");
        String bankStyle = c.encryptBank("x");
        assertThrows(IllegalStateException.class, () -> c.decryptKyc("v1:" + bankStyle));
    }

    @Test
    void cccdHashIsNormalizedKeyedAndStable() {
        SecretCrypto c = SecretCrypto.of("s");
        assertEquals(c.cccdHash("001234567890"), c.cccdHash(" 0012 3456 7890 "));
        assertNotEquals(c.cccdHash("001234567890"), c.cccdHash("001234567891"));
        assertNotEquals(c.cccdHash("001234567890"), SecretCrypto.of("t").cccdHash("001234567890"));
        assertEquals(64, c.cccdHash("1").length());
    }

    @Test
    void missingOrBlankSecretFails() {
        assertThrows(IllegalStateException.class, () -> SecretCrypto.of(null));
        assertThrows(IllegalStateException.class, () -> SecretCrypto.of("  "));
        assertThrows(IllegalStateException.class, () -> SecretCrypto.configure(""));
    }
}
