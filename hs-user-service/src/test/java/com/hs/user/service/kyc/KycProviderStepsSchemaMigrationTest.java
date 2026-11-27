package com.hs.user.service.kyc;

import com.hs.user.config.vnpt.KycProviderStepsSchemaMigration.Migrator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KycProviderStepsSchemaMigrationTest {
    private static JdbcTemplate db(java.util.Set<String> present) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenAnswer(i -> present.contains((String) i.getArguments()[2]) ? "x" : null);
        doAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.startsWith("ALTER TABLE vnpt_kyc_steps RENAME")) { present.remove("vnpt_kyc_steps"); present.add("kyc_provider_steps"); }
            return null;
        }).when(jdbc).execute(anyString());
        return jdbc;
    }

    @Test void legacyTableIsRenamedWithProviderColumnAndIndexKeepingData() {
        var jdbc = db(new java.util.HashSet<>(java.util.Set.of("vnpt_kyc_steps", "idx_vnpt_step_attempt")));
        new Migrator(jdbc).migrate();
        verify(jdbc).execute("ALTER TABLE vnpt_kyc_steps RENAME TO kyc_provider_steps");
        verify(jdbc).execute("ALTER TABLE kyc_provider_steps ADD COLUMN IF NOT EXISTS provider varchar(32) NOT NULL DEFAULT 'VNPT'");
        verify(jdbc).execute("ALTER INDEX idx_vnpt_step_attempt RENAME TO idx_kyc_step_attempt");
        verify(jdbc, never()).execute(contains("DROP"));
    }

    @Test void alreadyMigratedOnlyEnsuresColumnAndNeverRenames() {
        var jdbc = db(new java.util.HashSet<>(java.util.Set.of("kyc_provider_steps", "idx_kyc_step_attempt", "vnpt_kyc_steps")));
        new Migrator(jdbc).migrate();
        verify(jdbc, never()).execute(startsWith("ALTER TABLE vnpt_kyc_steps"));
        verify(jdbc, never()).execute(startsWith("ALTER INDEX"));
    }

    @Test void freshDatabaseDoesNothing() {
        var jdbc = db(new java.util.HashSet<>());
        new Migrator(jdbc).migrate();
        verify(jdbc, never()).execute(anyString());
    }
}
