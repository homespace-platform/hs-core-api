package com.hs.contract.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.IssueMonthlyInvoiceRequest;
import com.hs.contract.dto.billing.PaymentExtensionPayload;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractPaymentStatus;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.notification.ContractNotificationOutbox;
import com.hs.contract.notification.ContractNotificationOutboxRepository;
import com.hs.contract.notification.ContractNotificationPublisher;
import com.hs.contract.notification.InvoiceNotificationWriter;
import com.hs.contract.notification.InvoiceNotificationWriter.Event;
import com.hs.contract.repository.*;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ListingStatusService;
import com.hs.listing.service.ParkingReservationService;
import com.hs.payment.model.BankAccount;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.storage.repository.StorageObjectRepository;

/**
 * Real-PostgreSQL proof for the Phase 4 invoice outbox: one notice per actual transition under concurrent lazy reads,
 * the scheduler and manual issue; atomic rollback with the invoice/payment writes; FIFO/ACK publisher behaviour.
 * Opt-in and restricted to the dedicated disposable rollout database name; the schema is create-drop.
 */
@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL", matches = "jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql = false) @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.datasource.url=${HS_TEST_PG_URL}", "spring.datasource.username=${HS_TEST_PG_USER}",
    "spring.datasource.password=${HS_TEST_PG_PASSWORD}", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
    "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '10s'", "spring.profiles.active=test"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({com.hs.contract.notification.MoveInHandoverNotificationWriter.class,com.hs.contract.notification.AgreementSigningNotificationWriter.class,com.hs.contract.notification.RenewalNotificationWriter.class,com.hs.contract.notification.TerminationNotificationWriter.class,InvoiceNotificationWriter.class, com.hs.listing.notification.RentalRequestNotificationWriter.class,
    com.hs.payment.notification.PaymentNotificationWriter.class, ContractLifecycleService.class, MonthlyBillingService.class,
    PaymentRequestService.class, DepositLedgerService.class, PaymentDisputeService.class})
class MonthlyInvoiceNotificationPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs") @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {ContractNotificationOutboxRepository.class,
        com.hs.listing.notification.RentalRequestNotificationOutboxRepository.class,
        com.hs.payment.notification.PaymentNotificationOutboxRepository.class, ContractRepository.class,
        RentalRequestRepository.class, PaymentRequestRepository.class, StorageObjectRepository.class})
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean MutableTime time(Environment env) { return new MutableTime(env); }
    }
    static class MutableTime extends BillingTime {
        volatile Instant instant;
        MutableTime(Environment env) { super("2026-12-13T10:00:00", env); }
        @Override public Instant now() { return instant; }
        @Override public LocalDate today() { return now().atZone(BillingTime.ZONE).toLocalDate(); }
    }

    @MockitoBean ContractAmendmentService amendments;
    @MockitoBean ContractTermsResolver terms;
    @MockitoBean ListingStatusService listings;
    @MockitoBean ParkingReservationService parking;
    @MockitoBean BankAccountService banks;
    @MockitoBean VietQrProvider qr;
    @MockitoBean TransferReferenceGenerator references;
    @MockitoBean PaymentProofUploadSessionService uploadSessions;
    @MockitoSpyBean InvoiceNotificationWriter writer;
    @Autowired MonthlyBillingService billing;
    @Autowired PaymentRequestService payments;
    @Autowired ContractRepository contracts;
    @Autowired ContractRevisionRepository revisions;
    @Autowired RentalRequestRepository rentals;
    @Autowired ListingRepository properties;
    @Autowired PaymentRequestRepository paymentRepo;
    @Autowired MonthlyInvoiceRepository invoices;
    @Autowired ContractNotificationOutboxRepository outbox;
    @Autowired MutableTime time;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;

    record Fx(String contractId, String rentalId, String listingId) {}
    static final ObjectMapper JSON = new ObjectMapper();
    Fx fx;
    final AtomicBoolean failAfterIssuedEnqueue = new AtomicBoolean();

    void clock(String local) { time.instant = LocalDateTime.parse(local).atZone(BillingTime.ZONE).toInstant(); }
    void tx(Runnable call) { new TransactionTemplate(manager).executeWithoutResult(t -> call.run()); }

    @BeforeEach void fixture() {
        // Only this dedicated, disposable database may ever run this class.
        assertTrue(jdbc.queryForObject("select current_database()", String.class).matches("core_notification_rollout_test_[A-Za-z0-9]+"));
        // Leftover contracts of earlier tests must not be synchronised by the scheduler of this one.
        contracts.findAll().forEach(c -> { c.setStatus(ContractStatus.CLOSED); contracts.saveAndFlush(c); });
        outbox.deleteAllInBatch(); // disposable database: every test starts with an empty outbox
        failAfterIssuedEnqueue.set(false);
        InvoiceNotificationWriter target = AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(call -> {
            call.callRealMethod();
            if (call.getArgument(2) == Event.INVOICE_ISSUED && failAfterIssuedEnqueue.get()) throw new IllegalStateException("test after enqueue");
            return null;
        }).when(target).enqueueTenant(any(), any(), any());
        var bank = BankAccount.builder().bankBin("970436").bankCode("VCB").bankName("Test").accountNumber("123456").accountHolderName("TEST").build();
        when(banks.getDefaultIncomingAccount(anyString())).thenReturn(bank);
        when(banks.getDefaultRefundAccount(anyString())).thenReturn(bank);
        when(references.generateUniqueReference()).thenAnswer(call -> "HS" + UUID.randomUUID().toString().replace("-", "").substring(0, 15));
        when(qr.generateQrImageUrl(anyString(), anyString(), anyString(), any(), anyString()))
            .thenAnswer(call -> "test-qr-" + ((BigDecimal) call.getArgument(3)).stripTrailingZeros().toPlainString());
        clock("2026-11-12T00:01:00");
        fx = fixture("{\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}");
    }

    Fx fixture(String policies) {
        var listing = properties.saveAndFlush(Listing.builder().ownerId("owner").title("Invoice notice test").description("Test only")
            .status(ListingStatus.RENTED).category(ListingCategory.ROOM).priceAmount(new BigDecimal("5000000"))
            .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
            .depositType(DepositType.NONE).availableFrom(LocalDate.of(2026, 10, 1)).minimumLeaseMonths(1).build());
        var rental = rentals.saveAndFlush(RentalRequest.builder().id(UUID.randomUUID().toString()).listing(listing).ownerId("owner")
            .renterId("tenant").renterName("Test").renterPhone("0900000000").status(RentalRequestStatus.COMPLETED)
            .moveInDate(LocalDate.of(2026, 10, 12)).leaseMonths(3).monthlyRentPrice(new BigDecimal("5000000"))
            .depositAmount(new BigDecimal("5000000")).build());
        var contract = contracts.saveAndFlush(Contract.builder().contractNumber("INV-" + UUID.randomUUID()).rentalRequestId(rental.getId())
            .listingId(listing.getId()).landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("template-v1")
            .status(ContractStatus.ACTIVE).paymentStatus(ContractPaymentStatus.PAID).build());
        var revision = revisions.saveAndFlush(ContractRevision.builder().contract(contract).revisionNumber(1).templateVersionId("template-v1")
            .landlordSnapshot("{}").tenantSnapshot("{}").propertySnapshot("{}").leaseSnapshot("{\"durationMonths\":3}")
            .financialSnapshot("{\"amountValue\":\"5000000\",\"amountNumber\":\"5.000.000 VNĐ/tháng\",\"paymentDueOffsetDays\":4}")
            .chargesSnapshot("[]").policiesSnapshot(policies).build());
        contract.setCurrentRevisionId(revision.getId()); contracts.saveAndFlush(contract);
        when(terms.at(any(), any())).thenAnswer(call -> revisions.findById(((Contract) call.getArgument(0)).getCurrentRevisionId()).orElseThrow());
        return new Fx(contract.getId(), rental.getId(), listing.getId());
    }

    /** Index i covers [moveIn+i, moveIn+i+1); due = period end + 4 days 23:59:59 (Asia/Ho_Chi_Minh). */
    MonthlyInvoice bill(int index, MonthlyInvoiceStatus status) {
        var start = LocalDate.of(2026, 10, 12).plusMonths(index);
        return invoices.saveAndFlush(MonthlyInvoice.builder().contractId(fx.contractId()).periodIndex(index).periodStart(start)
            .periodEndExclusive(start.plusMonths(1)).status(status).totalAmount(BigDecimal.ZERO).build());
    }
    /** Issued invoice with a real payment row created by the production payment service. */
    MonthlyInvoice issuedBill(int index, MonthlyInvoiceStatus status) {
        var bill = bill(index, status);
        var due = bill.getPeriodEndExclusive().plusDays(4).atTime(23, 59, 59).atZone(BillingTime.ZONE).toInstant();
        String lines = "[{\"type\":\"RENT\",\"description\":\"Tiền thuê\",\"quantity\":1,\"unitPrice\":5000000,\"amount\":5000000}]";
        tx(() -> {
            var b = invoices.findById(bill.getId()).orElseThrow();
            var p = payments.createMonthlyPayment(b.getId(), fx.contractId(), fx.rentalId(), fx.listingId(), "tenant", "owner",
                new BigDecimal("5000000"), lines, due);
            b.setPaymentRequestId(p.getId()); b.setDueAt(due); b.setOriginalDueAt(due); b.setIssuedAt(due.minusSeconds(86400 * 5L));
            b.setBaseAmount(new BigDecimal("5000000")); b.setTotalAmount(new BigDecimal("5000000")); b.setLateFeeAmount(BigDecimal.ZERO);
            b.setLineItemsSnapshot(lines); invoices.saveAndFlush(b);
        });
        return invoices.findById(bill.getId()).orElseThrow();
    }

    List<String> events() { return eventsOf(fx.contractId()); }
    List<String> eventsOf(String contractId) {
        return outbox.findAll().stream().sorted(Comparator.comparing(ContractNotificationOutbox::getId)).map(row -> {
            try { JsonNode e = JSON.readTree(row.getPayload());
                return e.get("data").get("contractId").asText().equals(contractId)
                    ? e.get("eventType").asText().replace("INVOICE_", "") + ":" + e.get("recipients").get(0).asText() + ":" + e.get("data").get("invoiceId").asText() : null;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }).filter(Objects::nonNull).toList();
    }
    static String ev(String type, String who, String invoiceId) { return type + ":" + who + ":" + invoiceId; }

    /** Starts every action at the same instant; returns the failures (null entries are successes). */
    List<Throwable> race(Runnable... actions) throws Exception {
        var ready = new CountDownLatch(actions.length); var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(actions.length)) {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (var action : actions) futures.add(pool.submit(() -> {
                ready.countDown(); go.await(10, TimeUnit.SECONDS);
                try { action.run(); return null; } catch (Throwable failure) { return failure; }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            var result = new ArrayList<Throwable>();
            for (var future : futures) result.add(future.get(60, TimeUnit.SECONDS));
            return result;
        }
    }

    // ---- DRAFT_READY ----

    @Test void parallelLazyReadsAndSchedulerCreateOneDraftAndExactlyOneLandlordNotice() throws Exception {
        var failures = race(() -> billing.list(fx.contractId(), "tenant"), () -> billing.list(fx.contractId(), "owner"),
            () -> billing.syncContract(fx.contractId()), () -> billing.syncContract(fx.contractId()));
        assertTrue(failures.stream().allMatch(Objects::isNull), failures.toString());
        var all = invoices.findByContractIdOrderByPeriodIndexDesc(fx.contractId());
        assertEquals(1, all.size());
        assertEquals(List.of(ev("DRAFT_READY", "owner", all.getFirst().getId())), events());
        billing.syncContract(fx.contractId());
        assertEquals(1, events().size());
    }

    @Test void catchUpAnnouncesEachMissedPeriodAsItsOwnDraftAndNeverTheTenant() {
        clock("2026-12-30T10:00:00");
        billing.syncContract(fx.contractId()); billing.syncContract(fx.contractId());
        var drafts = invoices.findByContractIdOrderByPeriodIndexDesc(fx.contractId());
        assertEquals(2, drafts.size());
        assertEquals(drafts.stream().sorted(Comparator.comparingInt(MonthlyInvoice::getPeriodIndex))
            .map(d -> ev("DRAFT_READY", "owner", d.getId())).toList(), events());
    }

    // ---- ISSUED / ROLLED_OVER / atomicity ----

    @Test void manualIssueRacingTheSchedulerNotifiesTheTenantExactlyOnce() throws Exception {
        bill(0, MonthlyInvoiceStatus.PAID); var draft = bill(1, MonthlyInvoiceStatus.DRAFT);
        clock("2026-12-12T00:01:00");
        var failures = race(() -> billing.issue(draft.getId(), "owner", new IssueMonthlyInvoiceRequest(null, null, List.of())),
            () -> billing.scheduledSync());
        assertTrue(failures.stream().allMatch(f -> f == null || f instanceof AppException), failures.toString());
        assertEquals(MonthlyInvoiceStatus.UNPAID, invoices.findById(draft.getId()).orElseThrow().getStatus());
        assertEquals(List.of(ev("ISSUED", "tenant", draft.getId())), events());
        assertEquals(1, paymentRepo.findAll().stream().filter(p -> draft.getId().equals(p.getInvoiceId())).count());
    }

    @Test void issueFailureAfterTheNoticeRollsBackRolloverPaymentCancellationInvoiceAndOutboxTogether() {
        var old = issuedBill(0, MonthlyInvoiceStatus.OVERDUE);
        tx(() -> { var b = invoices.findById(old.getId()).orElseThrow(); b.setDeferredAt(Instant.parse("2026-11-27T00:00:00Z")); b.setResolutionMode("ROLLOVER"); invoices.saveAndFlush(b); });
        var draft = bill(1, MonthlyInvoiceStatus.DRAFT);
        clock("2026-12-12T00:01:00");
        failAfterIssuedEnqueue.set(true);
        assertThrows(IllegalStateException.class, () -> billing.issue(draft.getId(), "owner", new IssueMonthlyInvoiceRequest(null, null, List.of())));
        assertEquals(List.of(), events());
        assertEquals(MonthlyInvoiceStatus.OVERDUE, invoices.findById(old.getId()).orElseThrow().getStatus());
        assertNull(invoices.findById(old.getId()).orElseThrow().getRolledToInvoiceId());
        assertNotEquals(PaymentStatus.CANCELLED, paymentRepo.findById(old.getPaymentRequestId()).orElseThrow().getStatus());
        var rolledBack = invoices.findById(draft.getId()).orElseThrow();
        assertEquals(MonthlyInvoiceStatus.DRAFT, rolledBack.getStatus()); assertNull(rolledBack.getPaymentRequestId());
        failAfterIssuedEnqueue.set(false);
        billing.issue(draft.getId(), "owner", new IssueMonthlyInvoiceRequest(null, null, List.of()));
        assertEquals(List.of(ev("ROLLED_OVER", "tenant", old.getId()), ev("ISSUED", "tenant", draft.getId())), events());
        assertEquals(MonthlyInvoiceStatus.ROLLED_OVER, invoices.findById(old.getId()).orElseThrow().getStatus());
        assertEquals(PaymentStatus.CANCELLED, paymentRepo.findById(old.getPaymentRequestId()).orElseThrow().getStatus());
    }

    // ---- OVERDUE / AMOUNT_UPDATED / held money ----

    @Test void concurrentLazyReadersNotifyEachPartyOnceForOverdueAndOnlyOnceForTheNextRealFeeIncrease() throws Exception {
        var bill = issuedBill(0, MonthlyInvoiceStatus.UNPAID);
        clock("2026-11-17T10:00:00");
        var failures = race(() -> billing.list(fx.contractId(), "tenant"), () -> billing.list(fx.contractId(), "owner"),
            () -> billing.syncContract(fx.contractId()), () -> billing.syncContract(fx.contractId()));
        assertTrue(failures.stream().allMatch(Objects::isNull), failures.toString());
        assertEquals(List.of(ev("OVERDUE", "owner", bill.getId()), ev("OVERDUE", "tenant", bill.getId())), events());
        assertEquals(0, new BigDecimal("5100000").compareTo(paymentRepo.findById(bill.getPaymentRequestId()).orElseThrow().getTotalAmount()));
        clock("2026-11-18T10:00:00");
        failures = race(() -> billing.syncContract(fx.contractId()), () -> billing.list(fx.contractId(), "tenant"), () -> billing.syncContract(fx.contractId()));
        assertTrue(failures.stream().allMatch(Objects::isNull), failures.toString());
        assertEquals(3, events().size());
        assertEquals(ev("AMOUNT_UPDATED", "tenant", bill.getId()), events().getLast());
        assertEquals("test-qr-5200000", paymentRepo.findById(bill.getPaymentRequestId()).orElseThrow().getQrImageUrl());
        billing.syncContract(fx.contractId());
        assertEquals(3, events().size());
    }

    @Test void reportedTransferFreezesOverdueAndFeesWithoutAnyNotice() {
        var bill = issuedBill(0, MonthlyInvoiceStatus.UNPAID);
        tx(() -> { var p = paymentRepo.findById(bill.getPaymentRequestId()).orElseThrow(); p.setStatus(PaymentStatus.TRANSFER_REPORTED); paymentRepo.saveAndFlush(p); });
        clock("2026-11-20T10:00:00"); billing.syncContract(fx.contractId());
        assertEquals(MonthlyInvoiceStatus.UNPAID, invoices.findById(bill.getId()).orElseThrow().getStatus());
        assertEquals(List.of(), events());
    }

    // ---- Extensions ----

    @Test void extensionRequestApprovalAndReplayWriteEachNoticeOnceAndDeniedAttemptsWriteNone() {
        var bill = issuedBill(0, MonthlyInvoiceStatus.UNPAID);
        clock("2026-11-15T10:00:00");
        assertThrows(AppException.class, () -> billing.requestPaymentExtension(bill.getId(), "stranger", new PaymentExtensionPayload(1, "Xin gia hạn thêm một ngày")));
        var request = billing.requestPaymentExtension(bill.getId(), "tenant", new PaymentExtensionPayload(1, "Xin gia hạn thêm một ngày")).paymentExtensions().getFirst();
        assertThrows(AppException.class, () -> billing.decidePaymentExtension(bill.getId(), request.id(), "tenant", true));
        billing.decidePaymentExtension(bill.getId(), request.id(), "owner", true);
        assertThrows(AppException.class, () -> billing.decidePaymentExtension(bill.getId(), request.id(), "owner", true));
        assertEquals(List.of(ev("EXTENSION_REQUESTED", "owner", bill.getId()), ev("EXTENSION_APPROVED", "tenant", bill.getId())), events());
    }

    @Test void parallelReadersExpireARequestOnceAndExpiryWinsTheTenantNoticeOverOverdueAndFee() throws Exception {
        var bill = issuedBill(0, MonthlyInvoiceStatus.UNPAID);
        clock("2026-11-15T10:00:00");
        billing.requestPaymentExtension(bill.getId(), "tenant", new PaymentExtensionPayload(7, "Xin gia hạn thêm bảy ngày"));
        clock("2026-11-17T11:00:00"); // respondBy 2026-11-17T10:00 passed, due 2026-11-16 23:59:59 passed
        var failures = race(() -> billing.list(fx.contractId(), "tenant"), () -> billing.list(fx.contractId(), "owner"), () -> billing.syncContract(fx.contractId()));
        assertTrue(failures.stream().allMatch(Objects::isNull), failures.toString());
        assertEquals(List.of(ev("EXTENSION_REQUESTED", "owner", bill.getId()), ev("EXTENSION_EXPIRED", "tenant", bill.getId()),
            ev("OVERDUE", "owner", bill.getId())), events());
    }

    // ---- Overdue actions / deferral / action window ----

    @Test void landlordActionsAcknowledgementDeferralAndActionWindowNotifyOnceEach() throws Exception {
        var bill = issuedBill(0, MonthlyInvoiceStatus.UNPAID);
        clock("2026-11-22T10:00:00"); // overdue day 6: the action window is open
        billing.syncContract(fx.contractId()); billing.syncContract(fx.contractId());
        assertEquals(List.of(ev("OVERDUE", "owner", bill.getId()), ev("OVERDUE", "tenant", bill.getId()),
            ev("OVERDUE_ACTION_REQUIRED", "owner", bill.getId())), events());
        var action = billing.recordOverdueAction(bill.getId(), "owner", new com.hs.contract.dto.billing.CreateOverdueActionRequest(
            com.hs.contract.dto.billing.OverdueAction.Type.PAYMENT_REQUEST, "Đề nghị kiểm tra và thanh toán khoản nợ.", null)).overdueActions().getFirst();
        var failures = race(() -> billing.acknowledgeOverdueAction(bill.getId(), action.id(), "tenant",
                new com.hs.contract.dto.billing.AcknowledgeOverdueActionRequest("Tôi đã xem.")),
            () -> billing.acknowledgeOverdueAction(bill.getId(), action.id(), "tenant", new com.hs.contract.dto.billing.AcknowledgeOverdueActionRequest("Tôi đã xem.")));
        assertEquals(1, failures.stream().filter(Objects::nonNull).count());
        billing.deferToNextPeriod(bill.getId(), "owner"); billing.deferToNextPeriod(bill.getId(), "owner");
        assertEquals(List.of(ev("OVERDUE", "owner", bill.getId()), ev("OVERDUE", "tenant", bill.getId()),
            ev("OVERDUE_ACTION_REQUIRED", "owner", bill.getId()), ev("OVERDUE_ACTION_RECORDED", "tenant", bill.getId()),
            ev("OVERDUE_ACTION_ACKNOWLEDGED", "owner", bill.getId()), ev("DEFERRED", "tenant", bill.getId())), events());
    }

    // ---- Writer + publisher ----

    @Test void writerRefusesToRunWithoutAnExistingBusinessTransactionAndEventIdsAreUnique() {
        var contract = contracts.findById(fx.contractId()).orElseThrow(); var bill = bill(0, MonthlyInvoiceStatus.DRAFT);
        assertThrows(IllegalTransactionStateException.class, () -> writer.enqueueTenant(contract, bill, Event.INVOICE_ISSUED));
        assertEquals(List.of(), events());
        tx(() -> writer.enqueueBoth(contract, bill, Event.INVOICE_OVERDUE));
        var rows = outbox.findAll();
        assertEquals(2, rows.size());
        assertThrows(Exception.class, () -> outbox.saveAndFlush(new ContractNotificationOutbox(rows.getFirst().getEventId(), bill.getId(), "t", "{}", Instant.now())));
    }

    @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka() { return mock(KafkaTemplate.class); }
    long pending() { return jdbc.queryForObject("SELECT COUNT(*) FROM contract_notification_outbox WHERE sent_at IS NULL", Long.class); }

    @Test void brokerFailureKeepsFifoImmutablePayloadsUntilAckKeyedByInvoice() {
        var contract = contracts.findById(fx.contractId()).orElseThrow(); var a = bill(0, MonthlyInvoiceStatus.DRAFT); var b = bill(1, MonthlyInvoiceStatus.DRAFT);
        tx(() -> { writer.enqueueTenant(contract, a, Event.INVOICE_ISSUED); writer.enqueueTenant(contract, a, Event.INVOICE_AMOUNT_UPDATED); writer.enqueueTenant(contract, b, Event.INVOICE_ISSUED); });
        var rows = outbox.findAll().stream().sorted(Comparator.comparing(ContractNotificationOutbox::getId)).toList(); var kafka = kafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test broker")));
        new ContractNotificationPublisher(jdbc, kafka, manager).publishPending();
        assertEquals(3, pending());
        verify(kafka).send(rows.get(0).getTopic(), a.getId(), rows.get(0).getPayload()); verifyNoMoreInteractions(kafka); clearInvocations(kafka);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        new ContractNotificationPublisher(jdbc, kafka, manager).publishPending();
        assertEquals(0, pending());
        var order = inOrder(kafka);
        order.verify(kafka).send(rows.get(0).getTopic(), a.getId(), rows.get(0).getPayload());
        order.verify(kafka).send(rows.get(1).getTopic(), a.getId(), rows.get(1).getPayload());
        order.verify(kafka).send(rows.get(2).getTopic(), b.getId(), rows.get(2).getPayload());
    }

    @Test void brokerAckBeforeDatabaseFailureReplaysTheSameEventId() {
        var contract = contracts.findById(fx.contractId()).orElseThrow(); var a = bill(0, MonthlyInvoiceStatus.DRAFT);
        tx(() -> writer.enqueueTenant(contract, a, Event.INVOICE_ISSUED));
        var row = outbox.findAll().getFirst(); var kafka = kafka();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var failing = spy(jdbc);
        doThrow(new IllegalStateException("test after ack")).when(failing)
            .update("UPDATE contract_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", row.getId());
        new ContractNotificationPublisher(failing, kafka, manager).publishPending();
        assertEquals(1, pending());
        new ContractNotificationPublisher(jdbc, kafka, manager).publishPending();
        assertEquals(0, pending());
        verify(kafka, times(2)).send(row.getTopic(), a.getId(), row.getPayload());
    }
}
