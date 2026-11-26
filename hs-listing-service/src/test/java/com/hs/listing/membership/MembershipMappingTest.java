package com.hs.listing.membership;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

/** Boots Hibernate with no database: a broken mapping or membership JPQL fails here instead of at startup. */
class MembershipMappingTest {
    @Test
    void entitiesAndQueriesAreValid() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try (SessionFactory factory = new MetadataSources(registry)
                .addAnnotatedClass(MembershipPeriod.class).addAnnotatedClass(MembershipEvent.class)
                .buildMetadata().buildSessionFactory();
             var session = factory.openSession()) {
            for (String query : new String[]{MembershipPeriodRepository.ACTIVE_PAID,
                    MembershipPeriodRepository.ACTIVE_ADDONS})
                session.createSelectionQuery(query, MembershipPeriod.class);
            session.createSelectionQuery(MembershipPeriodRepository.DUE_FOR_OWNER, MembershipPeriod.class);
            session.createSelectionQuery(MembershipPeriodRepository.DUE_OWNERS, String.class);
        }
    }
}
