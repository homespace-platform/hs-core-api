package com.hs.user.config.vnpt;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renames vnpt_kyc_steps to the provider-neutral kyc_provider_steps before Hibernate validates/updates the schema.
 * Idempotent and data-preserving: existing rows become provider 'VNPT'; nothing is dropped.
 */
@Configuration
public class KycProviderStepsSchemaMigration {
    static final String BEAN = "kycProviderStepsMigrator";

    /** Makes the entity manager factory wait for the migrator, so the rename runs first. */
    @Bean static BeanFactoryPostProcessor kycStepsMigrationBeforeHibernate() {
        return factory -> {
            for (String name : factory.getBeanNamesForType(EntityManagerFactory.class, true, false)) {
                BeanDefinition definition = factory.getBeanDefinition(name);
                var deps = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                        definition.getDependsOn() == null ? new String[0] : definition.getDependsOn()));
                deps.add(BEAN);
                definition.setDependsOn(deps.toArray(String[]::new));
            }
        };
    }

    @Bean(BEAN) Migrator kycProviderStepsMigrator(JdbcTemplate jdbc) {
        var migrator = new Migrator(jdbc);
        migrator.migrate();
        return migrator;
    }

    @RequiredArgsConstructor
    public static class Migrator {
        private final JdbcTemplate jdbc;

        @Transactional
        public void migrate() {
            if (!exists("kyc_provider_steps") && exists("vnpt_kyc_steps")) {
                jdbc.execute("ALTER TABLE vnpt_kyc_steps RENAME TO kyc_provider_steps");
            }
            if (!exists("kyc_provider_steps")) return;
            jdbc.execute("ALTER TABLE kyc_provider_steps ADD COLUMN IF NOT EXISTS provider varchar(32) NOT NULL DEFAULT 'VNPT'");
            if (exists("idx_vnpt_step_attempt") && !exists("idx_kyc_step_attempt")) {
                jdbc.execute("ALTER INDEX idx_vnpt_step_attempt RENAME TO idx_kyc_step_attempt");
            }
        }

        private boolean exists(String relation) {
            return jdbc.queryForObject("select to_regclass(?)::text", String.class, relation) != null;
        }
    }
}
