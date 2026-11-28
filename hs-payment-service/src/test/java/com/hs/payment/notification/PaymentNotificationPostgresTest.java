package com.hs.payment.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.listing.notification.*;
import com.hs.storage.repository.StorageObjectRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER}",
    "spring.datasource.password=${HS_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false",
    "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({PaymentRequestService.class,PaymentNotificationWriter.class,RentalRequestNotificationWriter.class,
    PaymentProofUploadSessionService.class})
class PaymentNotificationPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs") @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses={PaymentRequestRepository.class,com.hs.payment.receipt.PaymentReceiptRepository.class,PaymentNotificationOutboxRepository.class,
        com.hs.listing.repository.RentalRequestRepository.class,RentalRequestNotificationOutboxRepository.class,StorageObjectRepository.class,
        com.hs.user.repository.UserRepository.class})
    static class Config {@Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}}
    @Autowired PaymentRequestService service;
    @Autowired PaymentRequestRepository payments;
    @Autowired PaymentEventRepository events;
    @Autowired DisputeCaseRepository cases;
    @Autowired DisputeCaseEventRepository caseEvents;
    @Autowired PaymentEvidenceRepository evidence;
    @Autowired PaymentProofUploadSessionRepository sessions;
    @Autowired PaymentNotificationOutboxRepository outbox;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PaymentNotificationWriter writer;
    @MockitoBean BankAccountService banks;
    @MockitoBean VietQrProvider qr;
    @MockitoBean TransferReferenceGenerator refs;
    @MockitoSpyBean PaymentProofUploadSessionService proofs;
    @MockitoBean com.hs.storage.service.StorageService storage;
    @MockitoBean BillingTime time;
    PaymentRequest payment;
    final Instant now=Instant.parse("2026-11-14T00:00:00Z");
    @BeforeEach void fixture(){
        outbox.deleteAllInBatch();events.deleteAllInBatch();caseEvents.deleteAllInBatch();cases.deleteAllInBatch();
        evidence.deleteAllInBatch();sessions.deleteAllInBatch();payments.deleteAllInBatch();
        when(time.now()).thenReturn(now);
        payment=payments.saveAndFlush(PaymentRequest.builder().payerId("payer").payeeId("payee").type(PaymentType.MONTHLY_RENT)
            .direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.TRANSFER_REPORTED).totalAmount(new BigDecimal("3000000"))
            .transferReference("HS"+UUID.randomUUID().toString().replace("-","").substring(0,12)).dueAt(now.plusSeconds(3600)).build());
    }
    @Test void dueSoonQueryUsesPaymentClockAndActualDueDateMarkerWithoutReturningReportedTransfers() {
        var page=org.springframework.data.domain.PageRequest.of(0,100);
        var states=Set.of(PaymentStatus.AWAITING_TRANSFER,PaymentStatus.REJECTED);
        var realNow=now.minusSeconds(30*86400L);
        payment.setType(PaymentType.DEPOSIT_REFUND);payment.setStatus(PaymentStatus.AWAITING_TRANSFER);payment=payments.saveAndFlush(payment);
        assertEquals(List.of(payment.getId()),payments.findDueSoonIds(states,PaymentTimePolicy.BILLING_TYPES,now,now.plusSeconds(86400),realNow,realNow.plusSeconds(86400),null,page));
        payment.setReminderForDueAt(payment.getDueAt());payment=payments.saveAndFlush(payment);
        assertTrue(payments.findDueSoonIds(states,PaymentTimePolicy.BILLING_TYPES,now,now.plusSeconds(86400),realNow,realNow.plusSeconds(86400),null,page).isEmpty());
        payment.setDueAt(now.plusSeconds(7200));payment=payments.saveAndFlush(payment);
        assertEquals(List.of(payment.getId()),payments.findDueSoonIds(states,PaymentTimePolicy.BILLING_TYPES,now,now.plusSeconds(86400),realNow,realNow.plusSeconds(86400),null,page));
        payment.setType(PaymentType.UTILITY);payment=payments.saveAndFlush(payment);
        assertTrue(payments.findDueSoonIds(states,PaymentTimePolicy.BILLING_TYPES,now,now.plusSeconds(86400),realNow,realNow.plusSeconds(86400),null,page).isEmpty());
        payment.setType(PaymentType.DEPOSIT_REFUND);payment.setStatus(PaymentStatus.TRANSFER_REPORTED);payment=payments.saveAndFlush(payment);
        assertTrue(payments.findDueSoonIds(states,PaymentTimePolicy.BILLING_TYPES,now,now.plusSeconds(86400),realNow,realNow.plusSeconds(86400),null,page).isEmpty());
    }
    @Test void authorizedConfirmationCommitsOutcomeAndSingleNoticeAndReplayIsSilent() throws Exception{
        service.confirmReceipt(payment.getId(),"payee");service.confirmReceipt(payment.getId(),"payee");
        assertEquals(PaymentStatus.CONFIRMED,readStatus());assertEquals(1,outbox.count());
        var e=new ObjectMapper().readTree(outbox.findAll().getFirst().getPayload());
        assertEquals("PAYMENT_RECEIPT_CONFIRMED",e.get("eventType").asText());assertEquals("payer",e.get("recipients").get(0).asText());
    }
    @Test void deniedAndInvalidStatusLeaveNoOutcomeOrNotice(){
        assertThrows(AppException.class,()->service.confirmReceipt(payment.getId(),"stranger"));
        payment.setStatus(PaymentStatus.AWAITING_TRANSFER);payments.saveAndFlush(payment);
        assertThrows(AppException.class,()->service.confirmReceipt(payment.getId(),"payee"));
        assertEquals(PaymentStatus.AWAITING_TRANSFER,readStatus());assertEquals(0,outbox.count());assertEquals(0,events.count());
    }
    @Test void outboxFailureAfterInsertRollsBackPaymentAuditAndNotice(){
        PaymentNotificationWriter target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("test after enqueue");}).when(target).enqueue(any(),any(),anyString());
        assertThrows(IllegalStateException.class,()->service.confirmReceipt(payment.getId(),"payee"));
        assertEquals(PaymentStatus.TRANSFER_REPORTED,readStatus());assertEquals(0,outbox.count());assertEquals(0,events.count());
    }
    @Test void staleParallelConfirmationsRefreshUnderLockAndNotifyOnce() throws Exception{
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Void> action=()->new TransactionTemplate(manager).execute(tx->{
                assertEquals(PaymentStatus.TRANSFER_REPORTED,payments.findById(payment.getId()).orElseThrow().getStatus());
                ready.countDown();try{assertTrue(go.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                service.confirmReceipt(payment.getId(),"payee");return null;
            });
            var a=pool.submit(action);var b=pool.submit(action);assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertEquals(PaymentStatus.CONFIRMED,readStatus());assertEquals(1,outbox.count());assertEquals(1,events.count());
    }
    @Test void standaloneRefundConfirmationNotifiesOwnerPayerWithoutCancellationCompletion() throws Exception{
        payment.setType(PaymentType.INITIAL_PAYMENT_REFUND);payment.setDirection(PaymentDirection.LANDLORD_TO_TENANT);
        payment.setPayerId("owner");payment.setPayeeId("tenant");payments.saveAndFlush(payment);
        service.confirmReceipt(payment.getId(),"tenant");
        var e=new ObjectMapper().readTree(outbox.findAll().getFirst().getPayload());assertEquals("owner",e.get("recipients").get(0).asText());
        assertEquals(PaymentStatus.CONFIRMED,readStatus());
    }
    @Test void writerRequiresExistingBusinessTransaction(){
        assertThrows(IllegalTransactionStateException.class,()->writer.enqueue(payment,PaymentNotificationWriter.Event.PAYMENT_RECEIPT_CONFIRMED,"payer"));
        assertEquals(0,outbox.count());
    }
    com.hs.payment.model.PaymentProofUploadSession session(String token) throws Exception{
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return sessions.saveAndFlush(com.hs.payment.model.PaymentProofUploadSession.builder().paymentRequestId(payment.getId())
            .tenantUserId("payer").tokenHash(hash).status(UploadSessionStatus.CREATED).expiresAt(Instant.now().plusSeconds(600)).build());
    }
    @Test void parallelMobileUploadsRefreshStalePaymentAndStoreOnlyOneEvidenceAndNotice() throws Exception{
        payment.setStatus(PaymentStatus.AWAITING_TRANSFER);payments.saveAndFlush(payment);session("first-token");session("second-token");
        when(storage.uploadDirect(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(new com.hs.storage.dto.response.StorageObjectResponse(
            UUID.randomUUID().toString(),"proof.png","image/png",1L,"checksum","png","payer","PAYMENT_REQUEST",payment.getId(),
            com.hs.storage.model.constant.StoragePurpose.PAYMENT_PROOF,com.hs.storage.model.constant.StorageVisibility.PRIVATE,
            com.hs.storage.model.constant.StorageStatus.READY,now,null));
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            java.util.function.Function<String,Callable<Void>> upload=token->()->new TransactionTemplate(manager).execute(tx->{
                assertEquals(PaymentStatus.AWAITING_TRANSFER,payments.findById(payment.getId()).orElseThrow().getStatus());
                ready.countDown();try{assertTrue(go.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                proofs.handleMobileUpload(token,new byte[]{1},"proof.png","image/png",1L);return null;
            });
            var a=pool.submit(upload.apply("first-token"));var b=pool.submit(upload.apply("second-token"));
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertEquals(PaymentStatus.TRANSFER_REPORTED,readStatus());assertEquals(1,evidence.count());assertEquals(1,outbox.count());
        verify(storage).uploadDirect(any(),any(),any(),any(),any(),any(),any(),any());
        assertTrue(sessions.findAll().stream().allMatch(s->s.getStatus()==UploadSessionStatus.CONSUMED));
    }
    void enqueue(PaymentNotificationWriter.Event event){new TransactionTemplate(manager).executeWithoutResult(tx->writer.enqueue(payment,event,"payer"));}
    @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka(){return mock(KafkaTemplate.class);}
    @Test void brokerFailureRetainsImmutableFifoPayloadsUntilAck(){
        enqueue(PaymentNotificationWriter.Event.PAYMENT_TRANSFER_REPORTED);enqueue(PaymentNotificationWriter.Event.PAYMENT_RECEIPT_REJECTED);
        var rows=outbox.findAll().stream().sorted(Comparator.comparing(PaymentNotificationOutbox::getId)).toList();var kafka=kafka();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test broker")));
        new PaymentNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(2,pending());
        verify(kafka).send(rows.getFirst().getTopic(),payment.getId(),rows.getFirst().getPayload());clearInvocations(kafka);
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        new PaymentNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(0,pending());
        var order=inOrder(kafka);for(var row:rows)order.verify(kafka).send(row.getTopic(),payment.getId(),row.getPayload());
    }
    @Test void brokerAckBeforeDbFailureReplaysSameEventId(){
        enqueue(PaymentNotificationWriter.Event.PAYMENT_RECEIPT_CONFIRMED);var row=outbox.findAll().getFirst();var kafka=kafka();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var failing=spy(jdbc);doThrow(new IllegalStateException("test after ack")).when(failing)
            .update("UPDATE payment_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?",row.getId());
        new PaymentNotificationPublisher(failing,kafka,manager).publishPending();assertEquals(1,pending());
        new PaymentNotificationPublisher(jdbc,kafka,manager).publishPending();assertEquals(0,pending());
        verify(kafka,times(2)).send(row.getTopic(),payment.getId(),row.getPayload());
    }
    PaymentStatus readStatus(){return payments.findById(payment.getId()).orElseThrow().getStatus();}
    long pending(){return jdbc.queryForObject("SELECT COUNT(*) FROM payment_notification_outbox WHERE sent_at IS NULL",Long.class);}
}
