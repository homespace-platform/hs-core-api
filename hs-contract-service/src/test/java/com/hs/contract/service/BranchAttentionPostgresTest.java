package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.dto.branch.BranchAttentionResponse.*;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static com.hs.contract.dto.branch.BranchAttentionResponse.WorkType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "HS_TEST_PG_URL",
        matches = "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/hs_branch_attention_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = BranchOverviewPostgresTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TEST_PG_URL}",
        "spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.hs.contract.service.BranchAttentionPostgresTest$SqlTrace",
        "spring.jpa.open-in-view=false", "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({BranchAttentionService.class, BranchOverviewService.class})
class BranchAttentionPostgresTest {
    private static final String OWNER = "attention-owner";
    private static final String OTHER = "attention-other";
    private static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final BigDecimal RENT = BigDecimal.valueOf(3_000_000);
    private static final String LOCK = "\\b(?:for (?:no key )?update|for (?:key )?share|pg_(?:try_)?advisory_\\w+)\\b";

    @Autowired BranchAttentionService service;
    @Autowired PropertyBranchRepository branches;
    @Autowired BranchFloorRepository floors;
    @Autowired BranchRowRepository rows;
    @Autowired BranchSlotRepository slots;
    @Autowired BranchRoomListingRepository links;
    @Autowired ListingRepository listings;
    @Autowired RentalRequestRepository rentals;
    @Autowired ContractRepository contracts;
    @Autowired ContractRevisionRepository revisions;
    @Autowired BranchContractAssignmentRepository assignments;
    @Autowired MonthlyInvoiceRepository invoices;
    @Autowired PaymentRequestRepository payments;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RentalOnboardingService onboarding;

    @Test
    void realQueriesShowTodayAndExpiredOwnersOnlyWithoutWritesLocksOrQueriesPerRoom() {
        var branch = branch(OWNER);
        var empty = branch(OWNER);
        var foreign = branch(OTHER);
        var floor = new BranchFloor();
        floor.setBranch(branch);
        floor.setLabel("Floor");
        var row = new BranchRow();
        row.setFloor(floors.saveAndFlush(floor));
        row.setLabel("Row");
        rows.saveAndFlush(row);
        var ownRoot = populate(row, "101", OWNER);
        var adjustment = depositTopUp(ownRoot);
        var expired = listing(branch, OWNER, ListingStatus.PUBLISHED, Instant.now().minusSeconds(3600));
        var foreignListing = listing(branch, OTHER, ListingStatus.EXPIRED, null);
        var foreignRoot = populate(row, "102", OTHER);
        var before = snapshot();

        var detail = trace(() -> service.attention(branch.getId(), OWNER, 0, 20));
        var summary = trace(() -> service.summary(OWNER));
        var detailAgain = trace(() -> service.attention(branch.getId(), OWNER, 0, 20));
        var summaryAgain = trace(() -> service.summary(OWNER));
        assertEquals(NOW, detail.result().billingNow());
        assertEquals(detail.result().items(), detailAgain.result().items());
        assertEquals(detail.result().totalElements(), detailAgain.result().totalElements());
        assertEquals(summary.result().branches(), summaryAgain.result().branches());
        assertEquals(before, snapshot());
        assertEquals(detail.queries(), detailAgain.queries());
        assertEquals(summary.queries(), summaryAgain.queries());

        var items = detail.result().items();
        assertThat(items).extracting(Work::key).doesNotHaveDuplicates().noneMatch(key ->
                key.endsWith(foreignListing.getId()) || key.contains(foreignRoot.getId()));
        assertThat(items).filteredOn(w -> w.type() == METER_TODAY).singleElement().satisfies(w -> {
            assertEquals(Clock.BILLING, w.clock());
            assertEquals(1, w.periodIndex());
            assertEquals(NOW.plusSeconds(86400), w.dueAt());
            assertEquals("101", w.roomCode());
        });
        assertThat(items).filteredOn(w -> w.type() == LISTING_EXPIRED).singleElement().satisfies(w -> {
            assertEquals(expired.getId(), w.target().id());
            assertEquals(Clock.REAL, w.clock());
        });
        assertThat(items).filteredOn(w -> w.type() == PAYMENT_OVERDUE).hasSize(1);
        assertThat(items).filteredOn(w -> w.type() == PAYMENT_CONFIRM).extracting(Work::key)
                .containsExactly("PAYMENT_CONFIRM:" + adjustment.getId());
        assertThat(summary.result().branches()).containsExactly(
                new BranchCount(branch.getId(), detail.result().totalElements()), new BranchCount(empty.getId(), 0));
        assertThat(trace(() -> service.summary(OTHER)).result().branches())
                .containsExactly(new BranchCount(foreign.getId(), 0));
        assertEquals(404, assertThrows(AppException.class, () -> service.attention(branch.getId(), OTHER, 0, 20))
                .getStatusCode().value());

        for (int i = 0; i < 25; i++) populate(row, "extra-" + i, OWNER);
        var expanded = trace(() -> service.attention(branch.getId(), OWNER, 0, 100));
        var expandedSummary = trace(() -> service.summary(OWNER));
        assertThat(expanded.result().items()).filteredOn(w -> w.type() == METER_TODAY).hasSize(26);
        assertThat(expanded.result().items()).filteredOn(w -> w.type() == PAYMENT_OVERDUE).hasSize(26);
        assertEquals(expanded.result().totalElements(), expandedSummary.result().branches().getFirst().count());
        assertEquals(detail.queries(), expanded.queries());
        assertEquals(summary.queries(), expandedSummary.queries());

        expired.setStatus(ListingStatus.PUBLISHED);
        expired.setExpiresAt(Instant.now().plusSeconds(3600));
        listings.saveAndFlush(expired);
        adjustment.setStatus(PaymentStatus.CONFIRMED);
        payments.saveAndFlush(adjustment);
        assertThat(trace(() -> service.attention(branch.getId(), OWNER, 0, 100)).result().items())
                .extracting(Work::type).doesNotContain(LISTING_EXPIRED, PAYMENT_CONFIRM);
    }

    private PropertyBranch branch(String owner) {
        return branches.saveAndFlush(PropertyBranch.builder().id(uuid()).ownerId(owner).name("Attention")
                .category(ListingCategory.ROOM).totalUnits(30).build());
    }

    private Listing listing(PropertyBranch branch, String owner, ListingStatus status, Instant expires) {
        return listings.saveAndFlush(Listing.builder().branchId(branch.getId()).ownerId(owner).title("Listing")
                .description("Test").category(ListingCategory.ROOM).status(status).expiresAt(expires).priceAmount(RENT)
                .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
                .availableFrom(START).minimumLeaseMonths(1).depositType(DepositType.NONE).build());
    }

    private Contract populate(BranchRow row, String code, String owner) {
        var branch = row.getFloor().getBranch();
        var slot = new BranchSlot();
        slot.setBranch(branch);
        slot.setRow(row);
        slot.setRoomCode(code);
        slot.setNormalizedRoomCode(code.toUpperCase(Locale.ROOT));
        slots.saveAndFlush(slot);
        var listing = listing(branch, owner, ListingStatus.RESERVED, null);
        links.saveAndFlush(new BranchRoomListing(listing.getId(), slot.getId()));
        var rental = rentals.saveAndFlush(RentalRequest.builder().id(uuid()).listing(listing).ownerId(owner).renterId("tenant")
                .renterName("Tenant").renterPhone("0900000000").status(RentalRequestStatus.ACCEPTED).moveInDate(START)
                .leaseMonths(3).occupantCount(1).motorbikeCount(0).carCount(0).monthlyRentPrice(RENT)
                .depositAmount(BigDecimal.ZERO).build());
        var root = contracts.saveAndFlush(Contract.builder().contractNumber("P4-" + uuid()).rentalRequestId(rental.getId())
                .listingId(listing.getId()).landlordId(owner).tenantId(rental.getRenterId()).templateId("template")
                .templateVersionId("version").status(ContractStatus.ACTIVE).build());
        var revision = revisions.saveAndFlush(ContractRevision.builder().contract(root).revisionNumber(1)
                .templateVersionId("version").landlordSnapshot("{}").tenantSnapshot("{}").propertySnapshot("{}")
                .leaseSnapshot("{}").financialSnapshot("{}").chargesSnapshot("""
                        [{"billingMethod":"PER_M3","includedInRent":false}]
                        """).build());
        root.setCurrentRevisionId(revision.getId());
        contracts.saveAndFlush(root);
        assignments.saveAndFlush(new BranchContractAssignment(root.getId(), branch.getId(), slot.getId()));
        var due = NOW.minusSeconds(1);
        var invoice = invoices.saveAndFlush(MonthlyInvoice.builder().contractId(root.getId()).periodIndex(0)
                .periodStart(START).periodEndExclusive(START.plusMonths(1)).status(MonthlyInvoiceStatus.UNPAID)
                .totalAmount(RENT).dueAt(due).build());
        payments.saveAndFlush(PaymentRequest.builder().contractId(root.getId()).rentalRequestId(rental.getId())
                .listingId(listing.getId()).invoiceId(invoice.getId()).payerId(rental.getRenterId()).payeeId(owner)
                .direction(PaymentDirection.TENANT_TO_LANDLORD).type(PaymentType.MONTHLY_RENT)
                .status(PaymentStatus.AWAITING_TRANSFER).dueAt(due).totalAmount(RENT)
                .transferReference(uuid().substring(0, 18)).build());
        return root;
    }

    private Map<String, String> snapshot() {
        var result = new TreeMap<String, String>();
        for (String table : jdbc.queryForList("select quote_ident(tablename) from pg_tables where schemaname = current_schema()", String.class)) {
            result.put(table, jdbc.queryForObject("select jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text from "
                    + table + " t", String.class));
        }
        return result;
    }

    private PaymentRequest depositTopUp(Contract root) {
        return payments.saveAndFlush(PaymentRequest.builder().contractId(root.getId())
                .rentalRequestId(root.getRentalRequestId()).listingId(root.getListingId())
                .payerId(root.getTenantId()).payeeId(root.getLandlordId()).direction(PaymentDirection.TENANT_TO_LANDLORD)
                .type(PaymentType.DEPOSIT_ADJUSTMENT).status(PaymentStatus.TRANSFER_REPORTED).dueAt(NOW.minusSeconds(1))
                .confirmationDueAt(NOW.plusSeconds(3600)).totalAmount(RENT)
                .transferReference(uuid().substring(0, 18)).build());
    }

    private record Read<T>(T result, int queries) {}

    private static <T> Read<T> trace(Supplier<T> action) {
        var sql = new ArrayList<String>();
        SqlTrace.STATEMENTS.set(sql);
        T result;
        try {
            result = action.get();
        } finally {
            SqlTrace.STATEMENTS.remove();
        }
        assertThat(sql).isNotEmpty().allSatisfy(statement -> assertThat(
                statement.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " "))
                .startsWith("select ").doesNotContainPattern(LOCK));
        return new Read<>(result, sql.size());
    }

    public static class SqlTrace implements StatementInspector {
        private static final ThreadLocal<List<String>> STATEMENTS = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            var trace = STATEMENTS.get();
            if (trace != null) {
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                trace.add(sql);
            }
            return sql;
        }
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
}
