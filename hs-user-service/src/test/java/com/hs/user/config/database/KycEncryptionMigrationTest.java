package com.hs.user.config.database;

import com.hs.common.security.SecretCrypto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KycEncryptionMigrationTest {

    @Test
    void encryptsPlaintextOnlyFillsHashAndStopsWhenNothingLeft() {
        SecretCrypto.configure("test-secret");
        SecretCrypto c = SecretCrypto.get();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", "u1");
        row.put("cccd", "001234567890");
        row.put("full_name", "Nguyen A");
        row.put("birth_date", c.encryptKyc("01/01/2000")); // already migrated: must be left alone
        row.put("gender", null);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row)).thenReturn(List.of());
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        new KycEncryptionMigration(jdbc, tm).run();

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).update(sql.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        Object[] a = args.getAllValues().toArray();
        assertFalse(sql.getValue().contains("birth_date"));
        assertEquals("001234567890", c.decryptKyc((String) a[0]));
        assertEquals("Nguyen A", c.decryptKyc((String) a[1]));
        assertEquals(c.cccdHash("001234567890"), a[2]);
        assertEquals("u1", a[3]);

        reset(jdbc); // second run: nothing left to migrate, nothing written
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        new KycEncryptionMigration(jdbc, tm).run();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
