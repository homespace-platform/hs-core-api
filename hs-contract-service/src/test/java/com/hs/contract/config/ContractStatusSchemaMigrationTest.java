package com.hs.contract.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Arrays;
import com.hs.contract.model.constant.ContractStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContractStatusSchemaMigrationTest {
    @Test void existingEnumCheckGainsNewStatesWhileFreshOrUpToDateSchemaIsUnchanged() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("CHECK (status IN ('DRAFT','ACTIVE'))"));
        new ContractStatusSchemaMigration(jdbc).migrate();
        var order=inOrder(jdbc);
        order.verify(jdbc).queryForList(anyString(),eq(String.class));
        order.verify(jdbc).execute("ALTER TABLE contracts DROP CONSTRAINT contracts_status_check");
        order.verify(jdbc).execute(argThat((String sql)->sql.contains("'EXPIRED'")&&sql.contains("'CLOSED'")&&sql.contains("'TENANT_SIGNATURE_PENDING'")));
        reset(jdbc);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of());
        new ContractStatusSchemaMigration(jdbc).migrate();
        verify(jdbc,never()).execute(anyString());
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of(Arrays.toString(Arrays.stream(ContractStatus.values()).map(s->"'"+s.name()+"'").toArray())));
        new ContractStatusSchemaMigration(jdbc).migrate();
        verify(jdbc,never()).execute(anyString());
    }
}
