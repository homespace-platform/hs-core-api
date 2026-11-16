package com.hs.user.service.kyc;

import com.hs.user.config.vnpt.KycProviderSchemaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KycProviderSchemaMigrationTest {
    @Test void expandsTheExistingVnptConstraintWithoutDeletingHistory() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("CHECK (provider IN ('DIDIT','VNPT'))"));
        new KycProviderSchemaMigration(jdbc).migrate();
        verify(jdbc).execute("ALTER TABLE kyc_verifications ADD CONSTRAINT kyc_verifications_provider_check CHECK (provider IN ('DIDIT','VNPT','ADMIN'))");
    }
    @Test void currentConstraintNeedsNoDdl() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("CHECK (provider IN ('DIDIT','VNPT','ADMIN'))"));
        new KycProviderSchemaMigration(jdbc).migrate();
        verify(jdbc,never()).execute(anyString());
    }
}
