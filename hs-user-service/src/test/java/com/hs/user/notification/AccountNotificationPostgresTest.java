package com.hs.user.notification;

import com.hs.user.model.User;
import com.hs.user.model.Role;
import com.hs.user.repository.*;
import com.hs.user.service.KeycloakUserService;
import com.hs.user.service.impl.UserServiceImpl;
import com.hs.user.utils.CurrentUserUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static com.hs.user.notification.AccountNotificationWriter.Event.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Hibernate create-drop is allowed ONLY in this new dedicated rollout test database.
@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER}",
    "spring.datasource.password=${HS_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'","spring.jpa.open-in-view=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({UserServiceImpl.class,AccountNotificationWriter.class})
class AccountNotificationPostgresTest {
    @SpringBootConfiguration @EntityScan({"com.hs.user.model","com.hs.user.notification"})
    @EnableJpaRepositories(basePackageClasses={UserRepository.class,AccountNotificationOutboxRepository.class})
    @EnableJpaAuditing static class Config {}
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccountNotificationOutboxRepository outbox;
    @Autowired UserServiceImpl service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean AccountNotificationWriter writer;
    @MockitoBean KeycloakUserService keycloak;
    @MockitoBean CurrentUserUtils identity;
    User target;
    @BeforeEach void fixture() {
        outbox.deleteAllInBatch();users.deleteAllInBatch();roles.deleteAllInBatch();
        var role=roles.saveAndFlush(Role.builder().name("TEST_USER").build());
        target=new User();target.setId(UUID.randomUUID().toString());target.setUsername("account-test");
        target.setEmail("account-test@example.invalid");target.setRole(role);target.setActive(true);target=users.saveAndFlush(target);
        when(identity.getCurrentUserId()).thenReturn("verified-admin");
    }
    List<AccountNotificationOutbox> rows() { return outbox.findAll().stream().sorted(Comparator.comparing(AccountNotificationOutbox::getId)).toList(); }
    long pending() { return jdbc.queryForObject("SELECT COUNT(*) FROM account_notification_outbox WHERE sent_at IS NULL",Long.class); }

    @Test void writerRequiresBusinessTransactionAndExplicitRollbackLeavesNoNotice() {
        assertThrows(IllegalTransactionStateException.class,()->writer.enqueue(target,"admin",ACCOUNT_DISABLED));
        new TransactionTemplate(manager).executeWithoutResult(tx->{writer.enqueue(target,"admin",ACCOUNT_DISABLED);tx.setRollbackOnly();});
        assertEquals(0,outbox.count());
    }
    @Test void statusSequenceCommitsThreeTransitionsAndSameValueReconciliationStaysSilent() {
        service.updateUserStatus(target.getId(),false);service.updateUserStatus(target.getId(),false);
        service.updateUserStatus(target.getId(),true);service.updateUserStatus(target.getId(),false);
        assertFalse(users.findById(target.getId()).orElseThrow().getActive());
        assertEquals(3,outbox.count());assertEquals(3,rows().stream().map(AccountNotificationOutbox::getEventId).distinct().count());
        assertTrue(rows().get(0).getPayload().contains("ACCOUNT_DISABLED"));assertTrue(rows().get(1).getPayload().contains("ACCOUNT_ENABLED"));
        verify(keycloak,times(4)).updateUserIfChanged(eq(target.getId()),any());
    }
    @Test void keycloakFailureRollsBackLocalStatusAndOutbox() {
        doThrow(new IllegalStateException("test unavailable")).when(keycloak).updateUserIfChanged(anyString(),any());
        assertThrows(IllegalStateException.class,()->service.updateUserStatus(target.getId(),false));
        assertTrue(users.findById(target.getId()).orElseThrow().getActive());assertEquals(0,outbox.count());
    }
    @Test void failureAfterOutboxInsertRollsBackLocalStateAndNotice() {
        AccountNotificationWriter targetWriter=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test failure after enqueue");})
            .when(targetWriter).enqueue(any(),anyString(),any());
        assertThrows(IllegalStateException.class,()->service.updateUserStatus(target.getId(),false));
        assertTrue(users.findById(target.getId()).orElseThrow().getActive());assertEquals(0,outbox.count());
        // A real Keycloak write cannot be rolled back by JPA; it is mocked and documented, never bypassed.
        verify(keycloak).updateUserIfChanged(eq(target.getId()),any());
    }
    @Test void concurrentIdenticalAdminChangesCreateExactlyOneNotice() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Void> change=()->{assertTrue(start.await(10,TimeUnit.SECONDS));service.updateUserStatus(target.getId(),false);return null;};
            var a=pool.submit(change);var b=pool.submit(change);start.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertFalse(users.findById(target.getId()).orElseThrow().getActive());assertEquals(1,outbox.count());
    }
    @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka() { return mock(KafkaTemplate.class); }
    @Test void failedKafkaSendRetriesSamePayloadInPerUserOrder() {
        service.updateUserStatus(target.getId(),false);service.updateUserStatus(target.getId(),true);var rows=rows();var kafka=kafka();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test broker")));
        new AccountNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(2,pending());
        verify(kafka).send(rows.getFirst().getTopic(),target.getId(),rows.getFirst().getPayload());clearInvocations(kafka);
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        new AccountNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(0,pending());
        var order=inOrder(kafka);for(var row:rows)order.verify(kafka).send(row.getTopic(),target.getId(),row.getPayload());order.verifyNoMoreInteractions();
    }
    @Test void ackThenDatabaseFailureReplaysExactIdentityAndPayload() {
        service.updateUserStatus(target.getId(),false);var row=rows().getFirst();var kafka=kafka();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var failing=spy(jdbc);doThrow(new IllegalStateException("test after ack")).when(failing)
            .update("UPDATE account_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?",row.getId());
        new AccountNotificationPublisher(failing,kafka,manager).publishPending();assertEquals(1,pending());
        new AccountNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(0,pending());
        verify(kafka,times(2)).send(row.getTopic(),target.getId(),row.getPayload());
        assertEquals(row.getEventId(),outbox.findById(row.getId()).orElseThrow().getEventId());
    }
    @Test void secondPublisherSkipsLockedUserWithoutOvertakingAndCanSendAnotherUser() throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            writer.enqueue(target,"admin",ACCOUNT_DISABLED);writer.enqueue(target,"admin",ACCOUNT_ENABLED);
            User other=new User();other.setId("another-user");writer.enqueue(other,"admin",ACCOUNT_ROLE_CHANGED);
        });
        var first=kafka();var second=kafka();var claimed=new CountDownLatch(1);
        var ack=new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
        when(first.send(anyString(),anyString(),anyString())).thenAnswer(call->{claimed.countDown();return ack;});
        when(second.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var work=pool.submit(()->new AccountNotificationPublisher(jdbc,first,manager).publishPending());
            try {
                assertTrue(claimed.await(10,TimeUnit.SECONDS));assertEquals(3,pending());
                new AccountNotificationPublisher(jdbc,second,manager).publishPending();
                verify(second).send(anyString(),eq("another-user"),anyString());verify(second,never()).send(anyString(),eq(target.getId()),anyString());
            } finally { ack.complete(null); }
            work.get(15,TimeUnit.SECONDS);
        }
        assertEquals(0,pending());verify(first,times(2)).send(anyString(),eq(target.getId()),anyString());
    }
}
