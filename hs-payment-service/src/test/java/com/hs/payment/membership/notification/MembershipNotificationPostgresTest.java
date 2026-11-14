package com.hs.payment.membership.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.membership.*;
import com.hs.payment.membership.*;
import com.hs.payment.repository.EthPaymentQuoteRepository;
import com.hs.payment.service.EthPaymentChainClient;
import com.hs.payment.service.EthPaymentService;
import com.hs.payment.service.EthVndPriceService;
import com.hs.payment.service.EthPaymentSignatures;
import com.hs.payment.repository.EthWalletChallengeRepository;
import com.hs.payment.model.EthPaymentQuote;
import com.hs.payment.dto.EthPaymentDtos;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER}",
    "spring.datasource.password=${HS_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false",
    "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'",
    "homespace.membership.sepay.merchant-id=test","homespace.membership.sepay.checkout-url=https://invalid.example",
    "homespace.membership.sepay.return-url=https://invalid.example","homespace.membership.sepay.ipn-secret=test-only"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({MembershipCheckoutService.class,MembershipService.class,MembershipNotificationWriter.class,EthPaymentService.class})
class MembershipNotificationPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs") @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses={MembershipOrderRepository.class,
        UserRepository.class,ListingMembershipRepository.class,EthPaymentQuoteRepository.class,
        com.hs.listing.repository.ListingStatusHistoryRepository.class})
    static class Config {@Bean SepaySigner signer(){return new SepaySigner("test-only");}}
    @Autowired UserRepository users;
    @Autowired MembershipOrderRepository orders;
    @Autowired ListingMembershipRepository memberships;
    @Autowired MembershipNotificationOutboxRepository outbox;
    @Autowired MembershipCheckoutService service;
    @Autowired EthPaymentService eth;
    @Autowired EthPaymentQuoteRepository quotes;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean MembershipNotificationWriter writer;
    @MockitoBean EthPaymentChainClient chain;
    @MockitoBean EthVndPriceService prices;
    @MockitoBean EthPaymentSignatures signatures;
    @MockitoBean EthWalletChallengeRepository challenges;
    final ObjectMapper json=new ObjectMapper();
    MembershipOrder order;
    @BeforeEach void fixture(){
        outbox.deleteAllInBatch();quotes.deleteAllInBatch();orders.deleteAllInBatch();memberships.deleteAllInBatch();users.deleteAllInBatch();
        User u=new User();u.setId(UUID.randomUUID().toString());u.setUsername("membership-test");u.setEmail("membership@example.invalid");users.saveAndFlush(u);
        order=new MembershipOrder();order.setId(UUID.randomUUID().toString().replace("-",""));order.setOwnerId(u.getId());
        order.setTier(MembershipTier.STARTER);order.setAmountVnd(49000);order.setStatus(MembershipOrder.Status.PENDING);
        order.setProvider(MembershipOrder.Provider.SEPAY);order.setCreatedAt(Instant.now());order.setExpiresAt(Instant.now().plusSeconds(900));orders.saveAndFlush(order);
    }
    com.fasterxml.jackson.databind.JsonNode callback(String tx){
        var root=json.createObjectNode().put("notification_type","ORDER_PAID");
        root.putObject("order").put("order_invoice_number",order.getId()).put("order_status","CAPTURED").put("order_currency","VND").put("order_amount","49000");
        root.putObject("transaction").put("transaction_id",tx).put("transaction_status","APPROVED").put("transaction_type","PAYMENT")
            .put("transaction_currency","VND").put("transaction_amount","49000");return root;
    }
    @Test void businessActivationOrderAndNoticeCommitOnceAndSecondPaymentTriggersOneReview(){
        service.sepayIpn(callback("first"));service.sepayIpn(callback("first"));
        assertEquals(MembershipOrder.Status.PAID,orders.findById(order.getId()).orElseThrow().getStatus());
        assertEquals(MembershipTier.STARTER,memberships.findById(order.getOwnerId()).orElseThrow().getTier());assertEquals(1,outbox.count());
        service.sepayIpn(callback("second"));service.sepayIpn(callback("second"));
        assertEquals(MembershipOrder.Status.REVIEW,orders.findById(order.getId()).orElseThrow().getStatus());assertEquals(2,outbox.count());
    }
    @Test void failedOutboxWriteRollsBackActivationOrderAndNotice(){
        MembershipNotificationWriter target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test after enqueue");})
            .when(target).enqueueTransition(any(),any());
        assertThrows(IllegalStateException.class,()->service.sepayIpn(callback("first")));
        assertEquals(MembershipOrder.Status.PENDING,orders.findById(order.getId()).orElseThrow().getStatus());
        assertTrue(memberships.findById(order.getOwnerId()).isEmpty());assertEquals(0,outbox.count());
    }
    @Test void parallelDuplicateCallbacksDoNotReactivateOrNotifyTwice() throws Exception{
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> call=()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return service.sepayIpn(callback("same"));};
            var a=pool.submit(call);var b=pool.submit(call);assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            assertTrue(a.get(15,TimeUnit.SECONDS));assertTrue(b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(MembershipOrder.Status.PAID,orders.findById(order.getId()).orElseThrow().getStatus());assertEquals(1,outbox.count());
    }
    @Test void cancelRacingVerifiedPaymentCannotOverwriteItsCommittedOutcome() throws Exception{
        var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var ipn=pool.submit(()->{assertTrue(go.await(10,TimeUnit.SECONDS));return service.sepayIpn(callback("paid-race"));});
            var cancel=pool.submit(()->{assertTrue(go.await(10,TimeUnit.SECONDS));return service.cancel(order.getOwnerId(),order.getId());});
            go.countDown();assertTrue(ipn.get(15,TimeUnit.SECONDS));cancel.get(15,TimeUnit.SECONDS);
        }
        var status=orders.findById(order.getId()).orElseThrow().getStatus();
        assertTrue(status==MembershipOrder.Status.PAID||status==MembershipOrder.Status.REVIEW);
        assertEquals(1,outbox.count());
        assertTrue(outbox.findAll().getFirst().getPayload().contains(status==MembershipOrder.Status.PAID?"MEMBERSHIP_ACTIVATED":"MEMBERSHIP_PAYMENT_REVIEW"));
    }
    EthPaymentQuote ethFixture(){
        order.setProvider(MembershipOrder.Provider.ETH);orders.saveAndFlush(order);
        var q=new EthPaymentQuote();q.setPaymentId("0x"+"1".repeat(64));q.setOrderId(order.getId());
        q.setPayerWallet("0x"+"a".repeat(40));q.setRecipientWallet("0x"+"b".repeat(40));q.setContractAddress("0x"+"c".repeat(40));
        q.setAmountVnd(java.math.BigDecimal.valueOf(49000));q.setEthVnd(java.math.BigDecimal.valueOf(80000000));q.setAmountWei("1000000000000");
        q.setPriceUpdatedAt(Instant.now());q.setExpiresAt(Instant.now().plusSeconds(900));q.setSignature("0x"+"2".repeat(130));return quotes.saveAndFlush(q);
    }
    @Test void concurrentVerifiedEthSettlementsCommitOnlyOneActivationAndOneNotice() throws Exception{
        var q=ethFixture();String hash="0x"+"d".repeat(64);var bothVerified=new CountDownLatch(2);Instant mined=Instant.now();
        when(chain.verify(any(),eq(hash))).thenAnswer(call->{bothVerified.countDown();assertTrue(bothVerified.await(10,TimeUnit.SECONDS));return mined;});
        var request=new EthPaymentDtos.Settle(q.getPaymentId(),hash);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<MembershipCheckoutService.OrderView> call=()->eth.settle(order.getId(),order.getOwnerId(),request);
            var a=pool.submit(call);var b=pool.submit(call);assertEquals("PAID",a.get(20,TimeUnit.SECONDS).status());assertEquals("PAID",b.get(20,TimeUnit.SECONDS).status());
        }
        assertEquals(1,outbox.count());assertEquals(hash,quotes.findById(q.getPaymentId()).orElseThrow().getTransactionHash());
        assertEquals(MembershipTier.STARTER,memberships.findById(order.getOwnerId()).orElseThrow().getTier());
    }
    @Test void ethFailureAfterNotificationInsertRollsBackQuoteOrderAndPlan(){
        var q=ethFixture();String hash="0x"+"e".repeat(64);when(chain.verify(any(),eq(hash))).thenReturn(Instant.now());
        MembershipNotificationWriter target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test after ETH enqueue");}).when(target).enqueueTransition(any(),any());
        assertThrows(IllegalStateException.class,()->eth.settle(order.getId(),order.getOwnerId(),new EthPaymentDtos.Settle(q.getPaymentId(),hash)));
        assertEquals(MembershipOrder.Status.PENDING,orders.findById(order.getId()).orElseThrow().getStatus());
        assertNull(quotes.findById(q.getPaymentId()).orElseThrow().getConfirmedAt());assertTrue(memberships.findById(order.getOwnerId()).isEmpty());assertEquals(0,outbox.count());
    }
    @Test void latePaymentOnlyRecordsReviewAndWriterRequiresTransaction(){
        assertThrows(IllegalTransactionStateException.class,()->writer.enqueueTransition(order,MembershipOrder.Status.PENDING));
        order.setExpiresAt(Instant.now().minusSeconds(1));orders.saveAndFlush(order);service.sepayIpn(callback("late"));
        assertEquals(MembershipOrder.Status.REVIEW,orders.findById(order.getId()).orElseThrow().getStatus());
        assertTrue(memberships.findById(order.getOwnerId()).isEmpty());assertEquals(1,outbox.count());
    }
    @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka(){return mock(KafkaTemplate.class);}
    @Test void kafkaFailureKeepsImmutableOrderedEventsForRetry(){
        service.sepayIpn(callback("first"));service.sepayIpn(callback("second"));
        var rows=outbox.findAll().stream().sorted(Comparator.comparing(MembershipNotificationOutbox::getId)).toList();var kafka=kafka();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test broker")));
        new MembershipNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(2,pending());
        verify(kafka).send(rows.getFirst().getTopic(),order.getId(),rows.getFirst().getPayload());clearInvocations(kafka);
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        new MembershipNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(0,pending());
        var sequence=inOrder(kafka);for(var row:rows)sequence.verify(kafka).send(row.getTopic(),order.getId(),row.getPayload());
    }
    @Test void brokerAckBeforeDbFailureReplaysSameEvent(){
        service.sepayIpn(callback("first"));var row=outbox.findAll().getFirst();var kafka=kafka();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var failing=spy(jdbc);doThrow(new IllegalStateException("test after ack")).when(failing)
            .update("UPDATE membership_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?",row.getId());
        new MembershipNotificationPublisher(failing,kafka,manager).publishPending();assertEquals(1,pending());
        new MembershipNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(0,pending());
        verify(kafka,times(2)).send(row.getTopic(),order.getId(),row.getPayload());
    }
    long pending(){return jdbc.queryForObject("SELECT COUNT(*) FROM membership_notification_outbox WHERE sent_at IS NULL",Long.class);}
}
