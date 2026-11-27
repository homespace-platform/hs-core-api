package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchOverviewResponse;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.MeterStatus;
import com.hs.contract.dto.branch.BranchOverviewResponse.Payment;
import com.hs.contract.dto.branch.BranchOverviewResponse.Room;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.notification.BranchDigestOutboxWriter;
import com.hs.contract.notification.ContractNotificationPublisher;
import com.hs.contract.repository.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.payment.model.PaymentLineItem;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.repository.StorageObjectRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static com.hs.contract.dto.branch.BranchOverviewResponse.PaymentStatus.*;
import static com.hs.contract.dto.branch.BranchOverviewResponse.RoomStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL",
        matches = "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/hs_branch_overview_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}",
        "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.hs.contract.service.BranchOverviewPostgresTest$SqlTrace",
        "spring.jpa.open-in-view=false", "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({BranchOverviewService.class, BranchDigestOutboxWriter.class})
class BranchOverviewPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {ContractRepository.class, RentalRequestRepository.class,
            PaymentRequestRepository.class, StorageObjectRepository.class})
    static class Config {
        @Bean BillingTime billingTime(Environment env) { return new BillingTime("2026-11-30T17:00:00Z", env); }
    }

    public static class SqlTrace implements StatementInspector {
        private static final ThreadLocal<List<String>> STATEMENTS = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            var trace = STATEMENTS.get();
            if (trace != null) trace.add(sql);
            return sql;
        }
    }

    private static final String OWNER = "owner-a";
    private static final String OTHER = "owner-b";
    private static final String TENANT = "tenant";
    private static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 10, 20);
    private static final BigDecimal RENT = BigDecimal.valueOf(3_000_000);
    private static final Pattern LOCK = Pattern.compile("\\b(?:for (?:no key )?update|for (?:key )?share|pg_(?:try_)?advisory_\\w+)\\b");

    @Autowired BranchOverviewService service;
    @Autowired PropertyBranchRepository branches;
    @Autowired BranchFloorRepository floors;
    @Autowired BranchRowRepository rows;
    @Autowired BranchSlotRepository slots;
    @Autowired BranchRoomListingRepository listingLinks;
    @Autowired ListingRepository listings;
    @Autowired RentalRequestRepository rentals;
    @Autowired ContractRepository contracts;
    @Autowired ContractRevisionRepository revisions;
    @Autowired BranchContractAssignmentRepository assignments;
    @Autowired ContractClosureRepository closures;
    @Autowired MonthlyInvoiceRepository invoices;
    @Autowired PaymentRequestRepository payments;
    @Autowired PaymentLineItemRepository lines;
    @Autowired JdbcTemplate jdbc;
    @Autowired BranchDigestOutboxWriter digest;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void repeatedReadsKeepOldDebtAndRunSameSelectsWithMorePopulatedRooms() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var debtRoom = room(row, "101");
        var debtRoot = lease(rental(listing(branch, OWNER), OWNER, "Returned tenant"), ContractStatus.EXPIRED);
        assign(branch, debtRoom, debtRoot);
        var overdue = invoice(debtRoot, 0, MonthlyInvoiceStatus.UNPAID, NOW.minusSeconds(1));
        var oldTransfer = payment(debtRoot, overdue.getId(), PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, OWNER, overdue.getDueAt());
        overdue.setPaymentRequestId(oldTransfer.getId());
        invoices.saveAndFlush(overdue);
        closures.saveAndFlush(ContractClosure.builder().contractId(debtRoot.getId()).status("BALANCE_PENDING").acceptedAt(NOW).build());

        var advertisedRoom = room(row, "102");
        var liveListing = listing(branch, OWNER);
        liveListing.setStatus(ListingStatus.PENDING_REVIEW);
        liveListing.setReviewOperationalStatus(ListingStatus.PUBLISHED);
        liveListing.setExpiresAt(Instant.now().plusSeconds(3600));
        listings.saveAndFlush(liveListing);
        listingLinks.saveAndFlush(new BranchRoomListing(liveListing.getId(), advertisedRoom.getId()));
        var firstRoom = room(row, "103");
        var first = populateActiveRoom(branch, firstRoom);
        var draft = invoice(contracts.findById(first.getContractId()).orElseThrow(), 1, MonthlyInvoiceStatus.DRAFT, null);
        var foreignListing = listing(branch, OTHER);
        var foreign = lease(rental(foreignListing, OTHER, "Other owner tenant"), ContractStatus.ACTIVE);
        assign(branch, debtRoom, foreign);
        var before = snapshot();

        Read initial = read(branch.getId());
        Read second = read(branch.getId());
        assertEquals(initial.sqlCount(), second.sqlCount());
        assertEquals(initial.result().rooms(), second.result().rooms());
        assertEquals(initial.result().unplacedPayments(), second.result().unplacedPayments());
        assertEquals(before, snapshot());
        assertEquals("2026-12", initial.result().month());
        assertEquals(NOW, initial.result().billingNow());
        assertEquals(ADVERTISED, findRoom(initial, advertisedRoom).status());
        var debt = findRoom(initial, debtRoom);
        assertEquals(VACANT, debt.status());
        assertThat(debt.payments().items()).extracting(Payment::id).containsExactly(overdue.getId());
        assertEquals(OVERDUE, debt.payments().status());
        var active = findRoom(initial, firstRoom);
        assertEquals(OCCUPIED, active.status());
        assertEquals(PENDING_CONFIRMATION, active.payments().status());
        assertEquals(3, active.payments().count());
        var items = active.payments().items();
        assertThat(items).extracting(Payment::status).containsExactly(PENDING_CONFIRMATION, UNISSUED, PAID);
        assertThat(items).extracting(Payment::id).doesNotHaveDuplicates();
        assertEquals(draft.getId(), items.get(1).id());
        assertEquals(MonthlyBillingSchedule.dueAt(draft.getPeriodEndExclusive()), items.get(1).dueAt());
        assertEquals("first-rent:" + first.getId(), items.getFirst().id());
        assertEquals(BranchOverviewResponse.Clock.BILLING, items.getFirst().clock());
        assertEquals(first.getConfirmationDueAt(), items.getFirst().confirmationDueAt());
        assertEquals("/dashboard/landlord/contracts/" + first.getContractId()
                + "?tab=payment&section=monthly&invoice=first-rent%3A" + first.getId(), items.getFirst().target().href());
        assertEquals(404, assertThrows(AppException.class, () -> service.overview(branch.getId(), OTHER, null)).getStatusCode().value());

        for (int i = 0; i < 25; i++) populateActiveRoom(branch, room(row, "extra-" + i));
        var expanded = read(branch.getId());
        assertEquals(28, expanded.result().rooms().size());
        assertEquals(25, expanded.result().rooms().stream().flatMap(r -> r.payments().items().stream())
                .filter(p -> p.kind() == BranchOverviewResponse.PaymentKind.PROJECTED).count());
        assertEquals(initial.sqlCount(), expanded.sqlCount(), "each room adds a lease with an invoice, transfer and first-rent lines");
    }

    @Test
    void preEffectiveFirstRentFollowsTheRentalOrManagedRootWithoutCountingForeignPayment() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var slot = room(row, "101");
        var booked = listing(branch, OWNER);
        listingLinks.saveAndFlush(new BranchRoomListing(booked.getId(), slot.getId()));
        var request = rental(booked, OWNER, "Reserved tenant");
        var receipt = payments.saveAndFlush(PaymentRequest.builder().rentalRequestId(request.getId())
                .listingId(booked.getId()).payerId(TENANT).payeeId(OWNER).type(PaymentType.INITIAL)
                .direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.AWAITING_TRANSFER)
                .dueAt(NOW.plusSeconds(3600)).totalAmount(RENT).transferReference(uuid().substring(0, 18)).build());
        lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(receipt.getId()).type("RENT").displayName("Rent").amount(RENT).build());

        var reservation = findRoom(read(branch.getId()), slot);
        assertEquals(RESERVED, reservation.status());
        assertEquals("/dashboard/rental-requests?requestId=" + request.getId(), reservation.target().href());
        assertThat(reservation.payments().items()).singleElement().satisfies(p -> {
            assertNull(p.contractId());
            assertEquals(reservation.target(), p.target());
            assertEquals(DUE_SOON, p.status());
        });

        var root = lease(request, ContractStatus.DRAFT);
        assignments.saveAndFlush(new BranchContractAssignment(root.getId(), branch.getId(), null));
        var foreignInvoice = invoice(root, 0, MonthlyInvoiceStatus.UNPAID, NOW.minusSeconds(1));
        payment(root, foreignInvoice.getId(), PaymentType.MONTHLY_RENT, PaymentStatus.TRANSFER_REPORTED, OTHER, foreignInvoice.getDueAt());

        var promoted = read(branch.getId());
        assertEquals(VACANT, findRoom(promoted, slot).status());
        assertTrue(findRoom(promoted, slot).payments().items().isEmpty());
        assertThat(promoted.result().unplacedPayments()).singleElement().satisfies(p -> {
            assertEquals("first-rent:" + receipt.getId(), p.id());
            assertEquals(root.getId(), p.contractId());
            assertEquals("/dashboard/landlord/contracts/" + root.getId() + "?tab=payment", p.target().href());
        });
    }

    @Test
    void q6LegacySignedDueDateDeterminesWhichMonthShowsTheDraft() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var slot = room(row, "101");
        var root = lease(rental(listing(branch, OWNER), OWNER, "Legacy tenant"), ContractStatus.ACTIVE);
        var legacyTerms = revisions.saveAndFlush(ContractRevision.builder().contract(root).revisionNumber(1)
                .templateVersionId(root.getTemplateVersionId()).landlordSnapshot("{}").tenantSnapshot("{}")
                .propertySnapshot("{}").leaseSnapshot("{}").financialSnapshot("{\"amountValue\":3000000}").build());
        root.setCurrentRevisionId(legacyTerms.getId());
        assign(branch, slot, contracts.saveAndFlush(root));
        var draft = invoice(root, 0, MonthlyInvoiceStatus.DRAFT, null); // closes Nov 20, signed due date Dec 5
        var expectedDue = Instant.parse("2026-12-05T16:59:59Z");

        var items = findRoom(read(branch.getId()), slot).payments().items();
        assertThat(items).filteredOn(p -> p.id().equals(draft.getId())).singleElement().satisfies(p -> {
            assertEquals(UNISSUED, p.status());
            assertEquals(expectedDue, p.dueAt());
        });
    }

    @Test
    void q7OutstandingSettlementBalanceRemainsVisibleAfterHandover() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var slot = room(row, "101");
        var root = lease(rental(listing(branch, OWNER), OWNER, "Returned tenant"), ContractStatus.EXPIRED);
        assign(branch, slot, root);
        var balance = payment(root, null, PaymentType.OTHER_CHARGE, PaymentStatus.AWAITING_TRANSFER, OWNER, NOW.minusSeconds(1));
        closures.saveAndFlush(ContractClosure.builder().contractId(root.getId()).status("BALANCE_PENDING")
                .acceptedAt(NOW.minusSeconds(86400)).balancePaymentId(balance.getId()).balanceAmount(RENT).build());

        var read = read(branch.getId());
        assertEquals(VACANT, findRoom(read, slot).status());
        assertThat(findRoom(read, slot).payments().items()).extracting(Payment::status).containsExactly(OVERDUE);
    }

    @Test
    void foreignRentalReferencedByLegacyContractDoesNotRevealTenantName() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var slot = room(row, "101");
        var foreign = rental(listing(branch, OTHER), OTHER, "Foreign rental name");
        var root = lease(foreign, ContractStatus.ACTIVE);
        root.setLandlordId(OWNER);
        assign(branch, slot, contracts.saveAndFlush(root));

        assertNull(findRoom(read(branch.getId()), slot).tenantName());
    }

    @Test
    void foreignRentalReferencedByLegacyContractDoesNotRevealFutureMoveInDate() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var slot = room(row, "101");
        var foreign = rental(listing(branch, OTHER), OTHER, "Foreign rental name");
        foreign.setMoveInDate(LocalDate.of(2026, 12, 17));
        rentals.saveAndFlush(foreign);
        var root = lease(foreign, ContractStatus.ACTIVE);
        root.setLandlordId(OWNER);
        assign(branch, slot, contracts.saveAndFlush(root));

        assertEquals(VACANT, findRoom(read(branch.getId()), slot).status());
    }

    @Test
    void p5LayersUseBillingMidnightSignedTermsAndLeaveEveryTableUntouched() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var upcoming = room(row, "101");
        var today = room(row, "102");
        var flat = room(row, "103");
        var renewable = monitoredLease(branch, upcoming, LocalDate.of(2026, 11, 5), "PER_KWH");
        var reading = monitoredLease(branch, today, LocalDate.of(2026, 11, 2), "PER_M3");
        monitoredLease(branch, flat, LocalDate.of(2026, 11, 2), "FIXED");
        var draft = invoices.saveAndFlush(MonthlyInvoice.builder().contractId(reading.getId()).periodIndex(0)
                .periodStart(LocalDate.of(2026, 11, 2)).periodEndExclusive(LocalDate.of(2026, 12, 2))
                .status(MonthlyInvoiceStatus.DRAFT).totalAmount(BigDecimal.ZERO).build());
        var before = snapshot();
        var overview = read(branch.getId());

        assertEquals(overview.result().rooms(), read(branch.getId()).result().rooms());
        assertEquals(before, snapshot());
        assertEquals(404, assertThrows(AppException.class, () -> service.overview(branch.getId(), OTHER, null))
                .getStatusCode().value());
        var proposed = findRoom(overview, upcoming);
        assertThat(proposed.meters()).singleElement().satisfies(m -> {
            assertEquals(MeterStatus.UPCOMING, m.status());
            assertEquals(LocalDate.of(2026, 12, 4), m.meterDate());
            assertEquals(Instant.parse("2026-12-04T17:00:00Z"), m.deadlineAt());
            assertTrue(m.target().href().endsWith("?tab=payment&section=monthly"));
        });
        assertThat(proposed.contracts()).singleElement().satisfies(e -> {
            assertEquals(ExpiryStatus.WITHIN_60, e.status());
            assertEquals(35, e.daysRemaining());
        });
        assertThat(findRoom(overview, today).meters()).singleElement().satisfies(m -> {
            assertEquals(MeterStatus.TODAY, m.status());
            assertEquals(draft.getId(), m.target().id());
        });
        assertThat(findRoom(overview, flat).meters()).isEmpty();

        draft.setDraftExtraChargesSnapshot("[]");
        invoices.saveAndFlush(draft);
        renewable.setEffectiveLeaseMonths(5);
        contracts.saveAndFlush(renewable);
        var updated = read(branch.getId());
        assertThat(findRoom(updated, today).meters()).extracting(m -> m.status()).containsExactly(MeterStatus.RECORDED);
        assertThat(findRoom(updated, upcoming).contracts()).isEmpty();
    }

    @Test
    void p5MeterDoesNotInferChargesFromAnotherOwnersLinkedRevision() {
        var row = branchRow();
        var branch = row.getFloor().getBranch();
        var slot = room(row, "101");
        var owned = monitoredLease(branch, slot, LocalDate.of(2026, 11, 2), "FIXED");
        var foreignBranch = branches.saveAndFlush(PropertyBranch.builder().id(uuid()).ownerId(OTHER)
                .name("Foreign branch").category(ListingCategory.ROOM).totalUnits(1).build());
        var foreign = lease(rental(listing(foreignBranch, OTHER), OTHER, "Other tenant"), ContractStatus.ACTIVE);
        var foreignMetered = revision(foreign, "PER_KWH");
        invoices.saveAndFlush(MonthlyInvoice.builder().contractId(owned.getId()).periodIndex(0)
                .periodStart(LocalDate.of(2026, 11, 2)).periodEndExclusive(LocalDate.of(2026, 12, 2))
                .costTermsRevisionId(foreignMetered.getId()).status(MonthlyInvoiceStatus.DRAFT)
                .totalAmount(BigDecimal.ZERO).build());

        assertThat(findRoom(read(branch.getId()), slot).meters()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void p5DigestDeduplicatesTwoTransactionsAndReplaysIdenticallyAfterAck() throws Exception {
        String owner = uuid(), other = uuid();
        var day = LocalDate.of(2026, 11, 27);
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> enqueue = () -> {
                start.await(5, TimeUnit.SECONDS);
                return digest.write(owner, day, 2);
            };
            var a = workers.submit(enqueue);
            var b = workers.submit(enqueue);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertTrue(digest.write(owner, day.plusDays(1), 3));
        assertTrue(digest.write(other, day, 1));
        var bodies = jdbc.queryForList("select payload from contract_notification_outbox order by id", String.class);
        assertThat(bodies).hasSize(3);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = json.readTree(bodies.getFirst());
        assertEquals(json.readTree("[\"%s\"]".formatted(owner)), first.get("recipients"));
        assertEquals(json.readTree("{\"attentionCount\":\"2\"}"), first.get("data"));
        assertEquals(json.readTree("[\"%s\"]".formatted(other)), json.readTree(bodies.getLast()).get("recipients"));
        var key = first.get("eventId").asText();
        var id = jdbc.queryForObject("select id from contract_notification_outbox where event_id = ?", Long.class, key);
        KafkaTemplate<String, String> kafka = mock();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var failing = spy(jdbc);
        doThrow(new IllegalStateException("after broker ack")).when(failing)
                .update("UPDATE contract_notification_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        try {
            new ContractNotificationPublisher(failing, kafka, transactions).publishDigests();
            new ContractNotificationPublisher(jdbc, kafka, transactions).publishDigests();
            verify(kafka, times(2)).send(anyString(), eq(key), eq(bodies.getFirst()));
            assertFalse(digest.write(owner, day, 4));
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from contract_notification_outbox where sent_at is null", Long.class));
        } finally {
            jdbc.update("delete from contract_notification_outbox where id >= ?", id);
        }
    }

    @Test
    void deferredDigestMustNotReorderContractNoticesWhenAnOlderInsertCommits() throws Exception {
        String oldKey = uuid(), digestKey = uuid(), newKey = uuid();
        KafkaTemplate<String, String> kafka = mock();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var inserted = new CountDownLatch(1);
        var commit = new CompletableFuture<Void>();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var producer = worker.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                insertNotice(oldKey, "INVOICE_ISSUED");
                inserted.countDown();
                commit.orTimeout(10, TimeUnit.SECONDS).join();
                insertNotice(newKey, "INVOICE_ISSUED");
            }));
            try {
                assertTrue(inserted.await(5, TimeUnit.SECONDS));
                insertNotice(digestKey, "BRANCH_DAILY_DIGEST");
                doAnswer(call -> {
                    commit.complete(null);
                    producer.get(5, TimeUnit.SECONDS);
                    return CompletableFuture.failedFuture(new IllegalStateException("digest send failed"));
                }).when(kafka).send(eq("events"), eq(digestKey), anyString());

                var publisher = new ContractNotificationPublisher(jdbc, kafka, transactions);
                publisher.publishDigests();
                publisher.publishPending();

                var order = inOrder(kafka);
                order.verify(kafka).send(eq("events"), eq(oldKey), anyString());
                order.verify(kafka).send(eq("events"), eq(newKey), anyString());
            } finally {
                commit.complete(null);
                try {
                    producer.get(10, TimeUnit.SECONDS);
                } finally {
                    jdbc.update("delete from contract_notification_outbox where aggregate_id in (?, ?, ?)",
                            oldKey, digestKey, newKey);
                }
            }
        }
    }

    private void insertNotice(String key, String eventType) {
        jdbc.update("""
                insert into contract_notification_outbox (event_id, aggregate_id, topic, payload, created_at)
                values (?, ?, 'events', ?, CURRENT_TIMESTAMP)
                """, key, key, "{\"eventType\":\"%s\"}".formatted(eventType));
    }

    private Contract monitoredLease(PropertyBranch branch, BranchSlot slot, LocalDate moveIn, String method) {
        var rental = rental(listing(branch, OWNER), OWNER, "Tenant");
        rental.setMoveInDate(moveIn);
        rental.setLeaseMonths(2);
        var root = lease(rentals.saveAndFlush(rental), ContractStatus.ACTIVE);
        root.setCurrentRevisionId(revision(root, method).getId());
        assign(branch, slot, contracts.saveAndFlush(root));
        return root;
    }

    private ContractRevision revision(Contract root, String method) {
        return revisions.saveAndFlush(ContractRevision.builder().contract(root).revisionNumber(1)
                .templateVersionId("version").landlordSnapshot("{}").tenantSnapshot("{}").propertySnapshot("{}")
                .leaseSnapshot("{}").financialSnapshot("{}")
                .chargesSnapshot("[{\"billingMethod\":\"%s\",\"includedInRent\":false}]".formatted(method)).build());
    }

    private Read read(String branchId) {
        var sql = new ArrayList<String>();
        SqlTrace.STATEMENTS.set(sql);
        BranchOverviewResponse result;
        try {
            result = service.overview(branchId, OWNER, null);
        } finally {
            SqlTrace.STATEMENTS.remove();
        }
        assertThat(sql).isNotEmpty().allSatisfy(statement -> {
            String normalized = statement.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
            assertThat(normalized).startsWith("select ").doesNotContainPattern(LOCK);
        });
        return new Read(result, sql.size());
    }

    private record Read(BranchOverviewResponse result, int sqlCount) {}

    private static Room findRoom(Read read, BranchSlot slot) {
        return read.result().rooms().stream().filter(r -> r.roomId().equals(slot.getId())).findFirst().orElseThrow();
    }

    private Map<String, String> snapshot() {
        var result = new TreeMap<String, String>();
        for (String table : jdbc.queryForList("select quote_ident(tablename) from pg_tables where schemaname = current_schema()", String.class)) {
            result.put(table, jdbc.queryForObject("select jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text from " + table + " t", String.class));
        }
        return result;
    }

    private BranchRow branchRow() {
        var branch = branches.saveAndFlush(PropertyBranch.builder().id(uuid()).ownerId(OWNER).name("P3 test branch")
                .category(ListingCategory.ROOM).totalUnits(30).build());
        var floor = new BranchFloor();
        floor.setBranch(branch);
        floor.setLabel("Floor");
        var row = new BranchRow();
        row.setFloor(floors.saveAndFlush(floor));
        row.setLabel("Row");
        return rows.saveAndFlush(row);
    }

    private BranchSlot room(BranchRow row, String code) {
        var slot = new BranchSlot();
        slot.setBranch(row.getFloor().getBranch());
        slot.setRow(row);
        slot.setRoomCode(code);
        slot.setNormalizedRoomCode(code.toUpperCase(Locale.ROOT));
        return slots.saveAndFlush(slot);
    }

    private Listing listing(PropertyBranch branch, String owner) {
        return listings.saveAndFlush(Listing.builder().branchId(branch.getId()).ownerId(owner).title("Test listing")
                .description("Test only").category(ListingCategory.ROOM).status(ListingStatus.RESERVED).priceAmount(RENT)
                .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
                .availableFrom(START).minimumLeaseMonths(1).depositType(DepositType.NONE).build());
    }

    private RentalRequest rental(Listing listing, String owner, String name) {
        return rentals.saveAndFlush(RentalRequest.builder().id(uuid()).listing(listing).ownerId(owner).renterId(TENANT)
                .renterName(name).renterPhone("0900000000").status(RentalRequestStatus.ACCEPTED).moveInDate(START)
                .leaseMonths(3).occupantCount(1).motorbikeCount(0).carCount(0).monthlyRentPrice(RENT).depositAmount(BigDecimal.ZERO).build());
    }

    private Contract lease(RentalRequest rental, ContractStatus status) {
        return contracts.saveAndFlush(Contract.builder().contractNumber("P3-" + uuid()).rentalRequestId(rental.getId())
                .listingId(rental.getListing().getId()).landlordId(rental.getOwnerId()).tenantId(rental.getRenterId())
                .templateId("template").templateVersionId("version").status(status).build());
    }

    private void assign(PropertyBranch branch, BranchSlot slot, Contract root) {
        assignments.saveAndFlush(new BranchContractAssignment(root.getId(), branch.getId(), slot.getId()));
    }

    private MonthlyInvoice invoice(Contract root, int index, MonthlyInvoiceStatus status, Instant due) {
        return invoices.saveAndFlush(MonthlyInvoice.builder().contractId(root.getId()).periodIndex(index)
                .periodStart(START.plusMonths(index)).periodEndExclusive(START.plusMonths(index + 1L))
                .status(status).totalAmount(RENT).dueAt(due).build());
    }

    private PaymentRequest payment(Contract root, String invoiceId, PaymentType type, PaymentStatus status, String payee, Instant due) {
        return payments.saveAndFlush(PaymentRequest.builder().contractId(root.getId()).rentalRequestId(root.getRentalRequestId())
                .listingId(root.getListingId()).invoiceId(invoiceId).payerId(TENANT).payeeId(payee)
                .direction(PaymentDirection.TENANT_TO_LANDLORD).type(type).status(status).dueAt(due)
                .confirmationDueAt(status == PaymentStatus.TRANSFER_REPORTED ? NOW.plusSeconds(3600) : null)
                .totalAmount(RENT).transferReference(uuid().substring(0, 18)).build());
    }

    private PaymentRequest populateActiveRoom(PropertyBranch branch, BranchSlot slot) {
        var root = lease(rental(listing(branch, OWNER), OWNER, "Active tenant"), ContractStatus.ACTIVE);
        assign(branch, slot, root);
        var issued = invoice(root, 0, MonthlyInvoiceStatus.UNPAID, NOW.plusSeconds(86400));
        var transfer = payment(root, issued.getId(), PaymentType.MONTHLY_RENT, PaymentStatus.CONFIRMED, OWNER, issued.getDueAt());
        issued.setPaymentRequestId(transfer.getId());
        invoices.saveAndFlush(issued);
        var first = payment(root, null, PaymentType.INITIAL, PaymentStatus.TRANSFER_REPORTED, OWNER, NOW.minusSeconds(1));
        lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(first.getId()).type("RENT").displayName("Rent").amount(RENT).build());
        return first;
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
}
