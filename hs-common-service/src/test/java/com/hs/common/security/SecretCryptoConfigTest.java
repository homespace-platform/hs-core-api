package com.hs.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class SecretCryptoConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(SecretCryptoConfig.class);

    @Test
    void startupFailsWithoutTheKey() {
        runner.run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void startupFailsWithBlankKey() {
        runner.withPropertyValues("homespace.security.encryption-key=").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage().contains("HOMESPACE_ENCRYPTION_KEY"));
        });
    }

    @Test
    void startsWithKeyAndConfiguresCrypto() {
        runner.withPropertyValues("homespace.security.encryption-key=abc").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("x", SecretCrypto.get().decryptKyc(SecretCrypto.get().encryptKyc("x")));
        });
    }
}
