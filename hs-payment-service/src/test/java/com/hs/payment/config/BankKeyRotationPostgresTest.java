package com.hs.payment.config;

import com.hs.common.security.SecretCrypto;
import com.hs.payment.model.BankAccount;
import com.hs.payment.repository.BankAccountRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real-database check of the SKIP LOCKED batch rotation (own throwaway database only: the test uses create-drop). */
@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER}",
    "spring.datasource.password=${HS_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class BankKeyRotationPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs.payment.model") @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses=BankAccountRepository.class)
    static class Config {}

    static final String OLD = "old-key-for-rotation-test", NEW = "new-key-for-rotation-test", OTHER = "unrelated-key-for-test";
    @Autowired BankAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;

    @BeforeEach void clean() { accounts.deleteAllInBatch(); }
    @AfterEach void restore() { SecretCrypto.configure(NEW); }

    void save(String id, String number, String key) {
        SecretCrypto.configure(key);
        accounts.saveAndFlush(BankAccount.builder().id(id).userId("u").bankBin("970").bankCode("B").bankName("Bank")
                .accountNumber(number).accountHolderName("Holder").build());
    }
    String raw(String id) { return jdbc.queryForObject("select account_number from bank_accounts where id=?", String.class, id); }
    void rotate(String previous) { new BankKeyRotation(jdbc, tx, previous, NEW).run(); }

    @Test void rotatesOldKeyRowsOnlyAndIsIdempotent() throws Exception {
        save("a-old", "111", OLD); save("b-new", "222", NEW); save("c-bad", "333", OTHER);
        jdbc.update("update bank_accounts set account_number='1234567890' where id=?", "b-new"); // legacy plaintext stays
        save("d-new", "444", NEW);
        String newBefore = raw("d-new"), badBefore = raw("c-bad");
        rotate(OLD);
        var crypto = SecretCrypto.of(NEW);
        assertEquals("111", crypto.decryptBank(raw("a-old")));
        assertEquals(newBefore, raw("d-new"));
        assertEquals(badBefore, raw("c-bad"));
        assertEquals("1234567890", raw("b-new"));
        String rotated = raw("a-old");
        rotate(OLD);
        assertEquals(rotated, raw("a-old"));
        assertEquals(badBefore, raw("c-bad"));
    }

    @Test void multipleBatchesAndNoOpWhenPreviousBlank() throws Exception {
        for (int i = 0; i < BankKeyRotation.BATCH + 5; i++) save(String.format("r%04d", i), "n" + i, OLD);
        String before = raw("r0000");
        rotate("");
        assertEquals(before, raw("r0000"));
        rotate(OLD);
        var crypto = SecretCrypto.of(NEW);
        for (int i : new int[]{0, BankKeyRotation.BATCH - 1, BankKeyRotation.BATCH + 4})
            assertEquals("n" + i, crypto.decryptBank(raw(String.format("r%04d", i))));
    }
}
