package com.hs.user.security;

import com.hs.common.security.SecretCrypto;
import com.hs.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KycCryptoConverterTest {

    private final KycCryptoConverter converter = new KycCryptoConverter();

    @BeforeEach
    void setUp() {
        SecretCrypto.configure("test-secret");
    }

    @Test
    void roundTripAndLegacyPlaintext() {
        String stored = converter.convertToDatabaseColumn("001234567890");
        assertTrue(stored.startsWith("v1:"));
        assertEquals("001234567890", converter.convertToEntityAttribute(stored));
        assertEquals("001234567890", converter.convertToEntityAttribute("001234567890"));
        assertNull(converter.convertToDatabaseColumn(null));
        assertEquals("", converter.convertToDatabaseColumn(""));
    }

    @Test
    void v1ValueThatCannotBeDecryptedThrows() {
        String stored = converter.convertToDatabaseColumn("001234567890");
        SecretCrypto.configure("different");
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute(stored));
    }

    @Test
    void userCccdFormulaValueIsDecryptedForCallers() throws Exception {
        User user = new User();
        var f = User.class.getDeclaredField("cccd");
        f.setAccessible(true);
        f.set(user, converter.convertToDatabaseColumn("001234567890"));
        assertEquals("001234567890", user.getCccd());
        f.set(user, "009999999999");
        assertEquals("009999999999", user.getCccd());
    }
}
