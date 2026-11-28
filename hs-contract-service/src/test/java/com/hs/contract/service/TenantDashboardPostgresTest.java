package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.config.TenantDashboardIndexMigration;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.receipt.*;
import com.hs.payment.repository.*;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.repository.StorageObjectRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only, guarded to a uniquely named local disposable database. */
@EnabledIfEnvironmentVariable(named = "HS_TENANT_TEST_PG_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_tenant_dashboard_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TenantDashboardPostgresTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_TENANT_TEST_PG_URL}", "spring.datasource.username=tenant_test",
        "spring.datasource.password=tenant_test", "spring.profiles.active=test",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.hs.contract.service.TenantDashboardPostgresTest$SqlTrace"})
@Import({TenantDashboardService.class, TenantDashboardIndexMigration.class})
class TenantDashboardPostgresTest {
    @SpringBootConfiguration
    @EntityScan("com.hs")
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {ContractRepository.class, RentalRequestRepository.class,
            PaymentRequestRepository.class, StorageObjectRepository.class, PaymentReceiptRepository.class,
            com.hs.user.repository.UserRepository.class})
    static class Config {
        @Bean BillingTime time(Environment env) { return new BillingTime(NOW.toString(), env); }
        @Bean StorageProperties storage() {
            return new StorageProperties("test", "ap-southeast-1", Duration.ZERO, Duration.ZERO, null, null);
        }
        @Bean com.fasterxml.jackson.databind.ObjectMapper mapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        }
    }

    static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z"); // December 1 in Vietnam
    static final Instant REAL = Instant.parse("2026-11-29T02:00:30Z"); // November 29, 09:00:30
    static final String ME = "tenant-a", OTHER = "tenant-b", OWNER = "owner";
    @Autowired TenantDashboardService service;
    @Autowired TenantDashboardReadRepository reads;
    @Autowired TenantDashboardIndexMigration migration;
    @Autowired ListingRepository listings;
    @Autowired RentalRequestRepository rentals;
    @Autowired ContractRepository contracts;
    @Autowired PaymentRequestRepository payments;
    @Autowired PaymentReceiptRepository receipts;
    @Autowired DepositRecordRepository deposits;
    @Autowired DisputeCaseRepository disputes;
    @Autowired ContractClosureRepository closures;
    @Autowired ContractRenewalRepository renewals;
    @Autowired ContractAmendmentRepository amendments;
    @Autowired ViewingAppointmentRepository appointments;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RentalOnboardingService onboarding;

    @Test
    void contractsMoneyDepositsAndComplaintsAreIsolatedEvenForAnOwnerWhoAlsoRents() throws Exception {
        var a = lease(ME, ContractStatus.ACTIVE);
        var b = lease(ME, ContractStatus.ACTIVE);
        var c = lease(ME, ContractStatus.ACTIVE);
        var foreign = lease(OTHER, ContractStatus.ACTIVE);
        foreign.setLandlordId(ME); // My landlord role must not grant tenant scope.
        contracts.saveAndFlush(foreign);
        lease(ME, ContractStatus.CANCELLED);
        lease(ME, ContractStatus.TERMINATED);
        var annex = lease(ME, ContractStatus.ACTIVE);
        annex.setParentContractId(a.getId()); contracts.saveAndFlush(annex);
        var depositDocument = lease(ME, ContractStatus.ACTIVE);
        depositDocument.setAgreementKind("DEPOSIT"); contracts.saveAndFlush(depositDocument);
        var late = pay(a, PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, "100.25", NOW.minusSeconds(1));
        late.setPayeeBankAccountSnapshot("hidden-bank"); payments.saveAndFlush(late);
        var extended = pay(b, PaymentType.MONTHLY_RENT, PaymentStatus.OVERDUE, "20.10", NOW.plusSeconds(60));
        pay(c, PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, "30.20", NOW.plusSeconds(120));
        pay(a, PaymentType.MONTHLY_RENT, PaymentStatus.CANCELLED, "999", NOW.minusSeconds(1));
        pay(foreign, PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, "999", NOW.minusSeconds(1));
        var reported = pay(a, PaymentType.MONTHLY_RENT, PaymentStatus.TRANSFER_REPORTED, "77", NOW.minusSeconds(1));
        reported.setConfirmationDueAt(NOW.plusSeconds(60)); payments.saveAndFlush(reported);
        deposit(a, "70", DepositStatus.HELD);
        deposit(b, "0", DepositStatus.REFUNDED);
        deposit(foreign, "999", DepositStatus.HELD);
        complaint(a, ME, OWNER, DisputeCaseStatus.OPEN);
        complaint(foreign, OTHER, OWNER, DisputeCaseStatus.OPEN);
        complaint(a, ME, OWNER, DisputeCaseStatus.RESOLVED);
        em.flush(); em.clear();

        var result = service.summary(ME, 12, REAL);
        assertThat(result.leases().activeCount()).isEqualTo(3);
        assertThat(result.leases().items()).extracting(l -> l.contractId())
                .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(result.payables().overdueAmount()).isEqualByComparingTo("100.25");
        assertThat(result.payables().dueSoonAmount()).isEqualByComparingTo("50.30");
        assertThat(result.payables().next().paymentId()).isEqualTo(extended.getId());
        assertThat(result.payables().awaiting().amount()).isEqualByComparingTo("77");
        assertThat(result.deposits().heldTotal()).isEqualByComparingTo("70");
        assertThat(result.deposits().items()).hasSize(2);
        assertThat(result.complaints().openCount()).isEqualTo(1);
        assertThat(result.complaints().dueFromMeCount()).isEqualTo(1);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).doesNotContain("hidden-bank", "private complaint text", "BankAccountSnapshot", "qrImageUrl",
                "\"phone\"", "\"email\"", "\"idNumber\"", "\"reason\"", "\"decision\"", "complaintDecision");
        var stranger = service.summary("stranger", 12, REAL);
        assertThat(stranger.leases().items()).isEmpty();
        assertThat(stranger.payables().items()).isEmpty();
        assertThat(stranger.deposits().items()).isEmpty();
        assertThat(stranger.complaints().items()).isEmpty();
    }

    @Test
    void nativeSpendingUsesVietnamBoundariesConfirmedPayerAndDirectionAndPdfAging() {
        var a = lease(ME, ContractStatus.ACTIVE);
        receipt(pay(a, PaymentType.MONTHLY_RENT, PaymentStatus.CONFIRMED, "1.25", NOW),
                NOW.minusSeconds(1), PaymentReceipt.PdfStatus.FAILED);
        receipt(pay(a, PaymentType.INITIAL, PaymentStatus.CONFIRMED, "2.50", NOW),
                NOW, PaymentReceipt.PdfStatus.READY);
        receipt(pay(a, PaymentType.UTILITY, PaymentStatus.CONFIRMED, "3.75", NOW),
                NOW.minusSeconds(301), PaymentReceipt.PdfStatus.PENDING);
        receipt(pay(a, PaymentType.OTHER_CHARGE, PaymentStatus.CONFIRMED, "4", NOW),
                NOW.minusSeconds(300), PaymentReceipt.PdfStatus.PENDING);
        var refund = pay(a, PaymentType.DEPOSIT_REFUND, PaymentStatus.CONFIRMED, "999", NOW);
        refund.setDirection(PaymentDirection.LANDLORD_TO_TENANT); refund.setPayerId(OWNER); refund.setPayeeId(ME);
        payments.saveAndFlush(refund); receipt(refund, NOW, PaymentReceipt.PdfStatus.READY);
        receipt(pay(a, PaymentType.MONTHLY_RENT, PaymentStatus.CANCELLED, "999", NOW), NOW, PaymentReceipt.PdfStatus.READY);
        receipt(pay(lease(OTHER, ContractStatus.ACTIVE), PaymentType.MONTHLY_RENT, PaymentStatus.CONFIRMED, "999", NOW),
                NOW, PaymentReceipt.PdfStatus.READY);
        receipt(pay(a, PaymentType.MONTHLY_RENT, PaymentStatus.CONFIRMED, "999", NOW),
                Instant.parse("2026-12-31T17:00:00Z"), PaymentReceipt.PdfStatus.READY); // exclusive upper boundary
        receipt(pay(a, PaymentType.MONTHLY_RENT, PaymentStatus.CONFIRMED, "999", NOW),
                Instant.parse("2025-12-31T16:59:59Z"), PaymentReceipt.PdfStatus.READY); // before lower boundary
        em.flush(); em.clear();
        var spending = service.summary(ME, 12, REAL).spending();
        assertThat(spending.months()).hasSize(12);
        assertThat(spending.months().getFirst().total()).isEqualByComparingTo("0");
        assertThat(spending.months().get(10).total()).isEqualByComparingTo("9");
        assertThat(spending.months().getLast().byGroup().get("DEPOSIT_FIRST")).isEqualByComparingTo("2.5");
        assertThat(spending.total()).isEqualByComparingTo("11.5");
        assertThat(spending.receiptPdfIssues()).isEqualTo(2);
        assertThat(service.summary(OTHER, 12, REAL).spending().total()).isEqualByComparingTo("999");
    }

    @Test
    void visitsUseTheRealDateAndExactTimeAndExcludeCancelledAndOtherTenants() {
        var c = lease(ME, ContractStatus.ACTIVE);
        visit(c, AppointmentStatus.CONFIRMED, LocalTime.of(9, 0, 29));
        var next = visit(c, AppointmentStatus.PENDING, LocalTime.of(9, 0, 30));
        visit(c, AppointmentStatus.CANCELLED, LocalTime.of(10, 0));
        visit(c, AppointmentStatus.REJECTED, LocalTime.of(10, 0));
        visit(c, AppointmentStatus.EXPIRED, LocalTime.of(10, 0));
        visit(c, AppointmentStatus.COMPLETED, LocalTime.of(10, 0));
        visit(lease(OTHER, ContractStatus.ACTIVE), AppointmentStatus.CONFIRMED, LocalTime.of(10, 0));
        em.flush(); em.clear();
        var visits = service.summary(ME, 12, REAL).visits();
        assertThat(visits.upcomingCount()).isEqualTo(1);
        assertThat(visits.items()).extracting(v -> v.id()).containsExactly(next.getId());
    }

    @Test
    void closureResponseWaitingRenewalAndEarlyExitFollowRealStatusesAndDisappearWhenAnswered() {
        var active = lease(ME, ContractStatus.ACTIVE);
        var expired = lease(ME, ContractStatus.EXPIRED);
        deposit(expired, "70", DepositStatus.SETTLEMENT_PENDING);
        closures.saveAndFlush(ContractClosure.builder().contractId(expired.getId()).status("PROPOSED")
                .respondBy(NOW.plusSeconds(60)).refundAmount(new BigDecimal("65")).build());
        var renewal = renewals.saveAndFlush(ContractRenewal.builder().contractId(active.getId()).months(3)
                .status("REQUESTED").requestedAt(NOW.minusSeconds(60)).respondBy(NOW.plusSeconds(600)).build());
        var exit = new RentalWorkflowRecord();
        exit.setId(uuid()); exit.setContractId(active.getId()); exit.setRentalRequestId(active.getRentalRequestId());
        exit.setKind("EARLY_EXIT"); exit.setStatus("PROPOSED"); exit.setProposedBy(ME); exit.setRespondBy(NOW.plusSeconds(120));
        em.persist(exit); em.flush(); em.clear();
        var first = service.summary(ME, 12, REAL);
        assertThat(first.tasks().items()).extracting(t -> t.type()).contains("CLOSURE_RESPONSE");
        assertThat(first.waiting().items()).extracting(w -> w.type()).containsExactly("EARLY_EXIT_PROPOSED", "RENEWAL_REQUESTED");
        assertThat(first.deposits().items()).singleElement().satisfies(d -> {
            assertThat(d.closure().status()).isEqualTo("PROPOSED");
            assertThat(d.closure().refundAmount()).isEqualByComparingTo("65");
        });
        jdbc.update("update contract_closures set status = 'DISPUTED' where contract_id = ?", expired.getId());
        jdbc.update("update contract_renewals set status = 'REJECTED' where id = ?", renewal.getId());
        jdbc.update("update rental_workflow_records set status = 'CANCELLED' where id = ?", exit.getId());
        em.clear();
        var second = service.summary(ME, 12, REAL);
        assertThat(second.tasks().items()).extracting(t -> t.type()).doesNotContain("CLOSURE_RESPONSE");
        assertThat(second.waiting().items()).extracting(w -> w.type()).containsExactly("CLOSURE_DISPUTED");
    }

    @Test
    void readsStaySelectOnlyAndQueryCountDoesNotGrowFromThreeToThirtyLeasesAndMigrationIsIdempotent() {
        migration.migrate(); migration.migrate();
        for (int i = 0; i < 3; i++) lease(ME, ContractStatus.ACTIVE);
        em.flush(); em.clear();
        int small = trace(() -> service.summary(ME, 12, REAL));
        for (int i = 3; i < 30; i++) lease(ME, ContractStatus.ACTIVE);
        em.flush(); em.clear();
        int large = trace(() -> service.summary(ME, 12, REAL));
        assertThat(large).isEqualTo(small);
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname in "
                + "('idx_receipt_tenant_issued','idx_dispute_payer_status','idx_dispute_payee_status',"
                + "'idx_closure_status_refund_due','idx_sig_contract_status')", Integer.class)).isEqualTo(5);
    }

    @Test
    void moneyTotalsStayExactPastDetailCapsAndGlobalNextIsStillPresent() {
        var c = lease(ME, ContractStatus.ACTIVE);
        for (int i = 0; i < 205; i++) pay(c, PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, "1.25", NOW.minusSeconds(i + 1));
        var next = pay(c, PaymentType.MONTHLY_RENT, PaymentStatus.OVERDUE, "2.50", NOW.plusSeconds(1));
        for (int i = 0; i < 6; i++) pay(c, PaymentType.MONTHLY_RENT, PaymentStatus.TRANSFER_REPORTED, "3.75", NOW);
        var unopened = pay(c, PaymentType.MONTHLY_RENT, PaymentStatus.AWAITING_TRANSFER, "999", NOW.minusSeconds(1));
        unopened.setOpensAt(NOW.plusSeconds(60)); payments.saveAndFlush(unopened);
        var utility = pay(c, PaymentType.UTILITY, PaymentStatus.AWAITING_TRANSFER, "4", REAL.plusSeconds(1));
        for (int i = 0; i < 55; i++) {
            var refund = pay(c, PaymentType.DEPOSIT_REFUND, PaymentStatus.AWAITING_TRANSFER, "1.25", NOW.plusSeconds(60));
            refund.setDirection(PaymentDirection.LANDLORD_TO_TENANT); refund.setPayerId(OWNER); refund.setPayeeId(ME);
            payments.saveAndFlush(refund);
        }
        em.flush(); em.clear();
        var result = service.summary(ME, 12, REAL);
        assertThat(result.payables().overdueCount()).isEqualTo(205);
        assertThat(result.payables().overdueAmount()).isEqualByComparingTo("256.25");
        assertThat(result.payables().dueSoonAmount()).isEqualByComparingTo("6.50");
        assertThat(result.payables().items()).hasSize(20);
        assertThat(result.payables().next().paymentId()).isEqualTo(utility.getId());
        assertThat(result.payables().awaiting().count()).isEqualTo(6);
        assertThat(result.payables().awaiting().amount()).isEqualByComparingTo("22.50");
        assertThat(result.payables().awaiting().items()).hasSize(5);
        assertThat(result.payables().refundsIncoming().count()).isEqualTo(55);
        assertThat(result.payables().refundsIncoming().amount()).isEqualByComparingTo("68.75");
        assertThat(result.payables().refundsIncoming().items()).hasSize(5);
    }

    @Test
    void smartCaTasksRequireTheTenantCurrentRevisionPendingContractAndLatestRequest() {
        var pending = lease(ME, ContractStatus.TENANT_SIGNATURE_PENDING);
        pending.setCurrentRevisionId("revision"); pending.setTenantSigningDueAt(NOW.plusSeconds(600));
        contracts.saveAndFlush(pending);
        var failed = lease(ME, ContractStatus.TENANT_SIGNATURE_PENDING);
        failed.setCurrentRevisionId("revision"); failed.setTenantSigningDueAt(NOW.plusSeconds(600));
        contracts.saveAndFlush(failed);
        var foreign = lease(OTHER, ContractStatus.TENANT_SIGNATURE_PENDING);
        foreign.setCurrentRevisionId("revision"); contracts.saveAndFlush(foreign);
        signature(pending, ME, SignatureRequestStatus.PENDING_USER_CONFIRMATION, "revision");
        signature(failed, ME, SignatureRequestStatus.FAILED, "revision");
        signature(foreign, ME, SignatureRequestStatus.PENDING_USER_CONFIRMATION, "revision");
        signature(pending, ME, SignatureRequestStatus.FAILED, "old-revision");
        em.flush(); em.clear();
        var before = service.summary(ME, 12, REAL);
        assertThat(before.tasks().items()).filteredOn(t -> t.type().equals("SIGN_CONFIRM_APP"))
                .extracting(t -> t.contractId()).containsExactlyInAnyOrder(pending.getId(), failed.getId());
        jdbc.update("update signature_requests set status = 'SIGNED' where contract_id = ?", pending.getId());
        jdbc.update("update contracts set status = 'CANCELLED' where id = ?", failed.getId());
        em.clear();
        assertThat(service.summary(ME, 12, REAL).tasks().items()).extracting(t -> t.type()).doesNotContain("SIGN_CONFIRM_APP");
    }

    @Test
    void legacyDepositPrincipalIsReadFromConfirmedLineItemsWithoutClaimingItIsStillHeld() {
        var c = lease(ME, ContractStatus.ACTIVE);
        var p = pay(c, PaymentType.INITIAL, PaymentStatus.CONFIRMED, "100", NOW);
        em.persist(PaymentLineItem.builder().id(uuid()).paymentRequestId(p.getId()).type("DEPOSIT")
                .displayName("Deposit").amount(new BigDecimal("70")).build());
        em.flush(); em.clear();
        var result = service.summary(ME, 12, REAL);
        assertThat(result.deposits().heldTotal()).isEqualByComparingTo("0");
        assertThat(result.deposits().items()).singleElement().satisfies(d -> {
            assertThat(d.amount()).isEqualByComparingTo("70");
            assertThat(d.status()).isEqualTo("CONFIRMED_PAYMENT");
        });
    }

    @Test
    void annexSigningRequiresApprovedSourceAndActiveParent() {
        var root = lease(ME, ContractStatus.ACTIVE);
        var child = lease(ME, ContractStatus.TENANT_SIGNATURE_PENDING);
        child.setParentContractId(root.getId()); child.setRentalRequestId(root.getRentalRequestId());
        child.setAgreementKind("AMENDMENT"); contracts.saveAndFlush(child);
        var amendment = amendments.saveAndFlush(ContractAmendment.builder().contractId(root.getId())
                .agreementContractId(child.getId()).baseRevisionId("revision").proposerId(OWNER).recipientId(ME)
                .status("APPROVED").effectiveOn(LocalDate.of(2026, 12, 5)).changesSnapshot("{}")
                .comparisonSnapshot("{}").reason("Test approved amendment").signBy(NOW.plusSeconds(60)).build());
        em.flush(); em.clear();
        assertThat(service.summary(ME, 12, REAL).tasks().items()).filteredOn(t -> t.type().equals("SIGN_ANNEX"))
                .singleElement().satisfies(t -> assertThat(t.dueAt()).isEqualTo(NOW.plusSeconds(60)));
        jdbc.update("update contract_amendments set status = 'CANCELLED' where id = ?", amendment.getId()); em.clear();
        assertThat(service.summary(ME, 12, REAL).tasks().items()).extracting(t -> t.type()).doesNotContain("SIGN_ANNEX");
        jdbc.update("update contract_amendments set status = 'APPROVED' where id = ?", amendment.getId());
        jdbc.update("update contracts set status = 'CANCELLED' where id = ?", root.getId()); em.clear();
        assertThat(service.summary(ME, 12, REAL).tasks().items()).extracting(t -> t.type()).doesNotContain("SIGN_ANNEX");
    }

    private void signature(Contract c, String signer, SignatureRequestStatus status, String revision) {
        em.persist(SignatureRequest.builder().id(uuid()).contractId(c.getId()).revisionId(revision)
                .sourceDocumentId("document").signerUserId(signer).signerRole(SignerRole.TENANT)
                .docId(uuid()).status(status).expiresAt(REAL.plusSeconds(60)).build());
    }

    private Contract lease(String tenant, ContractStatus status) {
        var listing = listings.saveAndFlush(Listing.builder().ownerId(OWNER).title("Tenant listing").description("Test")
                .category(ListingCategory.ROOM).status(ListingStatus.RENTED).priceAmount(new BigDecimal("3000000"))
                .areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY)
                .availableFrom(LocalDate.of(2026, 1, 1)).minimumLeaseMonths(1).depositType(DepositType.NONE).build());
        var rental = rentals.saveAndFlush(RentalRequest.builder().id(uuid()).listing(listing).ownerId(OWNER).renterId(tenant)
                .renterName("Tenant").renterPhone("0900000000").status(RentalRequestStatus.COMPLETED)
                .moveInDate(LocalDate.of(2026, 1, 1)).leaseMonths(24).monthlyRentPrice(new BigDecimal("3000000"))
                .depositAmount(new BigDecimal("70")).build());
        return contracts.saveAndFlush(Contract.builder().contractNumber("P2-" + uuid()).rentalRequestId(rental.getId())
                .listingId(listing.getId()).landlordId(OWNER).tenantId(tenant).templateId("template")
                .templateVersionId("version").status(status).build());
    }

    private PaymentRequest pay(Contract c, PaymentType type, PaymentStatus status, String amount, Instant due) {
        return payments.saveAndFlush(PaymentRequest.builder().contractId(c.getId()).rentalRequestId(c.getRentalRequestId())
                .listingId(c.getListingId()).payerId(c.getTenantId()).payeeId(c.getLandlordId())
                .direction(PaymentDirection.TENANT_TO_LANDLORD).type(type).status(status).totalAmount(new BigDecimal(amount))
                .dueAt(due).transferReference(uuid().substring(0, 18)).build());
    }

    private void deposit(Contract c, String amount, DepositStatus status) {
        deposits.saveAndFlush(DepositRecord.builder().contractId(c.getId()).rentalRequestId(c.getRentalRequestId())
                .initialPaymentRequestId(uuid()).landlordId(c.getLandlordId()).tenantId(c.getTenantId())
                .originalAmount(new BigDecimal("70")).heldAmount(new BigDecimal(amount)).refundableAmount(new BigDecimal(amount))
                .status(status).build());
    }

    private void complaint(Contract c, String payer, String payee, DisputeCaseStatus status) {
        disputes.saveAndFlush(DisputeCase.builder().subjectType("RENTAL_COMPLAINT").subjectId(uuid())
                .rentalRequestId(c.getRentalRequestId()).contractId(c.getId()).payerId(payer).payeeId(payee)
                .openedBy(payer).reason("private complaint text").openedAt(NOW).status(status).amountSnapshot(BigDecimal.ONE)
                .currency("VND").initialEvidenceSnapshot("[]").responseDueFor(payer).respondBy(NOW.plusSeconds(60)).build());
    }

    private void receipt(PaymentRequest p, Instant issued, PaymentReceipt.PdfStatus status) {
        var r = new PaymentReceipt(); r.setId(uuid()); r.setPaymentRequestId(p.getId()); r.setReceiptNumber(uuid());
        r.setLandlordId(OWNER); r.setTenantId(p.getContractId() == null ? ME : contracts.findById(p.getContractId()).orElseThrow().getTenantId());
        r.setIssuedAt(issued); r.setSnapshotJson("{}"); r.setSnapshotHash("a".repeat(64)); r.setPdfStatus(status);
        receipts.saveAndFlush(r);
    }

    private ViewingAppointment visit(Contract c, AppointmentStatus status, LocalTime at) {
        return appointments.saveAndFlush(ViewingAppointment.builder().id(uuid()).listing(listings.findById(c.getListingId()).orElseThrow())
                .ownerId(c.getLandlordId()).renterId(c.getTenantId()).renterName("Tenant").renterPhone("0900000000")
                .appointmentDate(LocalDate.ofInstant(REAL, BillingTime.ZONE)).startTime(at).endTime(at.plusMinutes(30))
                .status(status).build());
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
    private static int trace(Supplier<?> action) {
        var statements = new ArrayList<String>(); SqlTrace.ACTIVE.set(statements);
        try { action.get(); } finally { SqlTrace.ACTIVE.remove(); }
        assertThat(statements).isNotEmpty().allSatisfy(s -> assertThat(s.toLowerCase(Locale.ROOT).strip())
                .matches("(?s)(select |with money as).*")
                .doesNotContainPattern("\\b(insert|update|delete|merge)\\b")
                .doesNotContain("for share", "pg_advisory"));
        return statements.size();
    }
    public static class SqlTrace implements StatementInspector {
        static final ThreadLocal<List<String>> ACTIVE = new ThreadLocal<>();
        @Override public String inspect(String sql) { if (ACTIVE.get() != null) ACTIVE.get().add(sql); return sql; }
    }
}
