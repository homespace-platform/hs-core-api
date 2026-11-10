package com.hs.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipOrderStatusSchemaMigrationTest {
    @Test
    void existingCheckGainsCancelledWithoutChangingFreshOrCurrentSchema() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("CHECK (status IN ('PENDING','PAID','FAILED','EXPIRED','REVIEW'))"));

        new MembershipOrderStatusSchemaMigration(jdbc).migrate();
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForList(anyString(), eq(String.class));
        order.verify(jdbc).execute("ALTER TABLE membership_orders DROP CONSTRAINT membership_orders_status_check");
        order.verify(jdbc).execute(argThat((String sql) -> sql.contains("'CANCELLED'") && sql.contains("'REVIEW'")));

        reset(jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        new MembershipOrderStatusSchemaMigration(jdbc).migrate();
        verify(jdbc, never()).execute(anyString());

        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of(
                "CHECK (status IN ('PENDING','PAID','FAILED','EXPIRED','CANCELLED','REVIEW'))"));
        new MembershipOrderStatusSchemaMigration(jdbc).migrate();
        verify(jdbc, never()).execute(anyString());
    }
}
