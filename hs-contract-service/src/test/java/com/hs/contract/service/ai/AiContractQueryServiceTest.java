package com.hs.contract.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.ai.ContractLeaseRow;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.DepositRecord;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.DepositStatus;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.DepositRecordRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fake data only. Repositories are mocked so each case controls exactly what the caller may see. */
class AiContractQueryServiceTest {
    static final String LANDLORD = "landlord-1", TENANT = "tenant-1", STRANGER = "stranger-1";
    static final String ROOT = "11111111-1111-1111-1111-111111111111";

    ContractRepository contracts = mock(ContractRepository.class);
    ContractRevisionRepository revisions = mock(ContractRevisionRepository.class);
    ContractAmendmentRepository amendments = mock(ContractAmendmentRepository.class);
    ContractRenewalRepository renewals = mock(ContractRenewalRepository.class);
    ContractClosureRepository closures = mock(ContractClosureRepository.class);
    MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    DepositRecordRepository deposits = mock(DepositRecordRepository.class);
    PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
    BillingTime time = mock(BillingTime.class);
    AiContractQueryService service;

    Contract root;
    RentalRequest rental;
    List<ContractAmendment> history = new ArrayList<>();
    Map<String, ContractRevision> revisionData = new HashMap<>();
    List<MonthlyInvoice> bills = new ArrayList<>();

    @BeforeEach
    void setup() {
        service = new AiContractQueryService(contracts, revisions, amendments, renewals, closures, invoices, rentals,
                deposits, payments, new ObjectMapper().findAndRegisterModules(), time);
        root = Contract.builder().id(ROOT).contractNumber("HD-DEMO-001").rentalRequestId("rental").landlordId(LANDLORD)
                .tenantId(TENANT).status(ContractStatus.ACTIVE).currentRevisionId("original")
                .signedAt(Instant.parse("2026-10-15T03:00:00Z")).build();
        rental = RentalRequest.builder().id("rental").moveInDate(LocalDate.of(2026, 10, 16)).leaseMonths(3).build();
        revisionData.put("original", revision("original", 1, 5000000, "Phòng gốc"));
        when(contracts.findById(ROOT)).thenReturn(Optional.of(root));
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        when(revisions.findById(anyString())).thenAnswer(x -> Optional.ofNullable(revisionData.get(x.getArgument(0))));
        when(amendments.findByContractIdOrderByCreatedAtDesc(ROOT)).thenAnswer(x -> history);
        when(renewals.findByContractIdOrderByRequestedAtDesc(anyString())).thenReturn(List.of());
        when(invoices.findByContractIdOrderByPeriodIndexDesc(ROOT)).thenAnswer(x -> bills);
        when(deposits.findByRentalRequestId(anyString())).thenReturn(Optional.empty());
        when(payments.findAllById(any())).thenReturn(List.of());
        when(closures.findById(anyString())).thenReturn(Optional.empty());
        clock("2026-11-16T10:00:00");
    }

    void clock(String local) {
        var now = LocalDateTime.parse(local).atZone(BillingTime.ZONE);
        when(time.now()).thenReturn(now.toInstant());
        when(time.today()).thenReturn(now.toLocalDate());
    }

    static ContractRevision revision(String id, int number, int rent, String note) {
        return ContractRevision.builder().id(id).revisionNumber(number).leaseSnapshot("{\"durationMonths\":3}")
                .financialSnapshot("{\"amountValue\":" + rent + "}").landlordSnapshot("{\"fullName\":\"Chủ nhà mẫu\"}")
                .tenantSnapshot("{\"fullName\":\"Khách mẫu\",\"idNumber\":\"000000000000\"}")
                .propertySnapshot("{\"fullAddress\":\"1 Đường Mẫu\"}").chargesSnapshot("[]").specialTerms(note).build();
    }

    ContractAmendment amendment(String id, String status, String effectiveOn, Instant signedAt, String revisionId) {
        return ContractAmendment.builder().id(id).contractId(ROOT).baseRevisionId("original").proposerId(LANDLORD)
                .recipientId(TENANT).status(status).effectiveOn(LocalDate.parse(effectiveOn)).signedAt(signedAt)
                .termsRevisionId(revisionId).changesSnapshot("{}").comparisonSnapshot("[]").reason("Điều chỉnh mẫu").build();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    ContractLeaseRow row(int n, ContractStatus status, String landlord, String tenant) {
        return new ContractLeaseRow("00000000-0000-0000-0000-" + String.format("%012d", n), "HD-" + n, status, landlord,
                tenant, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"), null,
                LocalDate.of(2026, 10, 16), 3, "original");
    }

    AppException failure(Runnable call) { return assertThrows(AppException.class, call::run); }

    // 1. total comes from the full set, not the first page; amendment/renewal children are excluded by the query itself
    @Test
    void summaryAndSearchCountEveryRootContractNotJustTheFirstPage() {
        List<ContractLeaseRow> rows = new ArrayList<>();
        for (int i = 1; i <= 23; i++) rows.add(row(i, i <= 20 ? ContractStatus.ACTIVE : ContractStatus.DRAFT, "x", TENANT));
        when(contracts.findLeaseRowsForUser(eq(TENANT), any())).thenReturn(rows);
        var summary = service.summarize(TENANT, null, null);
        assertEquals(20L, summary.get("total"), "tenant never sees the 3 landlord drafts");
        var page = service.search(TENANT, null, null, null, null, null, null, 2, 5);
        assertEquals(20L, page.get("total"));
        assertEquals(5, list(page.get("items")).size());
        assertEquals(true, page.get("hasMore"));
        assertEquals(20L, service.search(TENANT, null, null, null, null, null, null, 1, 20).get("total"));
        assertEquals(20L, map(summary.get("byLeasePhase")).get("IN_LEASE"));
    }

    @Test
    void repositoryQueryExcludesChildContractsAndTenantDrafts() throws Exception {
        String query = ContractRepository.class.getMethod("findLeaseRowsForUser", String.class,
                org.springframework.data.domain.Pageable.class).getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        assertTrue(query.contains("parentContractId is null"));
        assertTrue(query.contains("c.active = true"));
        assertTrue(query.contains("c.status <>"));
    }

    // 2. end date is exclusive
    @Test
    void tenantLeaseEndDateIsExclusiveAndLastRentalDayIsThePreviousDay() {
        clock("2027-01-15T10:00:00");
        var detail = service.detail(TENANT, ROOT);
        var lease = map(detail.get("lease"));
        assertEquals("2027-01-16", lease.get("endExclusive"));
        assertEquals("2027-01-15", lease.get("lastRentalDay"));
        assertEquals(1L, lease.get("daysRemainingIncludingToday"));
        assertEquals("IN_LEASE", detail.get("leasePhase"));
        clock("2027-01-16T00:00:00");
        var ended = service.detail(TENANT, ROOT);
        assertEquals("LEASE_ENDED", ended.get("leasePhase"));
        assertEquals(0L, map(ended.get("lease")).get("daysRemainingIncludingToday"));
        assertEquals(true, ended.get("statusSyncPending"), "scheduler lag is reported, not hidden");
    }

    // 3. user is both landlord of one contract and tenant of another
    @Test
    void oneAccountCanBeLandlordAndTenantAndRoleFilterSeparatesThem() {
        when(contracts.findLeaseRowsForUser(eq("both"), any())).thenReturn(List.of(
                row(1, ContractStatus.ACTIVE, "both", "t"), row(2, ContractStatus.ACTIVE, "l", "both")));
        assertEquals(2L, service.summarize("both", null, null).get("total"));
        assertEquals(1L, service.summarize("both", "LANDLORD", null).get("total"));
        var tenantOnly = service.search("both", "TENANT", null, null, null, null, null, null, null);
        assertEquals("TENANT", list(tenantOnly.get("items")).get(0).get("askerRole"));
    }

    // 5. text search narrows only already-authorised ids
    @Test
    void textSearchOnlyNarrowsAuthorisedContractsAndIsPaged() {
        List<ContractLeaseRow> rows = List.of(row(1, ContractStatus.ACTIVE, "l", TENANT), row(2, ContractStatus.ACTIVE, "l", TENANT));
        when(contracts.findLeaseRowsForUser(eq(TENANT), any())).thenReturn(rows);
        when(contracts.findIdsMatchingText(anyList(), anyString())).thenReturn(List.of(rows.get(1).id()));
        var result = service.search(TENANT, null, null, null, null, "hd-2", null, 1, 5);
        assertEquals(1L, result.get("total"));
        verify(contracts).findIdsMatchingText(eq(List.of(rows.get(0).id(), rows.get(1).id())), eq("%hd-2%"));
        assertEquals("provided", map(result.get("filters")).get("text"), "raw text is not echoed");
    }

    @Test
    void invalidParametersAreRejectedWithoutTouchingData() {
        for (Runnable bad : new Runnable[]{
                () -> service.search(TENANT, "ADMIN", null, null, null, null, null, 1, 5),
                () -> service.search(TENANT, null, List.of("NOPE"), null, null, null, null, 1, 5),
                () -> service.search(TENANT, null, null, null, null, null, "DROP TABLE", 1, 5),
                () -> service.search(TENANT, null, null, null, null, null, null, 1, 500),
                () -> service.search(TENANT, null, null, null, null, null, null, 0, 5),
                () -> service.summarize(TENANT, null, 9999),
                () -> service.terms(TENANT, ROOT, List.of("CCCD"), null),
                () -> service.terms(TENANT, ROOT, null, "not-a-date"),
                () -> service.financial(TENANT, ROOT, "EVERYTHING", null)}) {
            assertEquals(6069, failure(bad).getCode());
        }
    }

    // 6. cross-account, guessable ids and forged identity
    @Test
    void strangerAndMalformedIdsReceiveTheSameNotFoundAsMissingContracts() {
        String missing = "22222222-2222-2222-2222-222222222222";
        when(contracts.findById(missing)).thenReturn(Optional.empty());
        for (String id : new String[]{ROOT, missing, "../etc/passwd", "' or 1=1 --"}) {
            assertEquals(6003, failure(() -> service.detail(STRANGER, id)).getCode());
            assertEquals(6003, failure(() -> service.terms(STRANGER, id, null, null)).getCode());
            assertEquals(6003, failure(() -> service.amendments(STRANGER, id)).getCode());
            assertEquals(6003, failure(() -> service.lifecycle(STRANGER, id)).getCode());
            assertEquals(6003, failure(() -> service.financial(STRANGER, id, null, null)).getCode());
        }
        for (String forged : new String[]{null, "", "  ", "system"}) {
            assertEquals(1002, failure(() -> service.summarize(forged, null, null)).getCode());
            assertEquals(1002, failure(() -> service.detail(forged, ROOT)).getCode());
        }
    }

    @Test
    void childContractsAreNeverReadableAsAContractAndTenantCannotSeeDrafts() {
        Contract child = Contract.builder().id("33333333-3333-3333-3333-333333333333").contractNumber("HD-CHILD").landlordId(LANDLORD)
                .tenantId(TENANT).parentContractId(ROOT).status(ContractStatus.DRAFT).build();
        when(contracts.findById(child.getId())).thenReturn(Optional.of(child));
        assertEquals(6003, failure(() -> service.detail(LANDLORD, child.getId())).getCode());
        root.setStatus(ContractStatus.DRAFT);
        assertEquals(6003, failure(() -> service.detail(TENANT, ROOT)).getCode());
        assertEquals("DRAFT", service.detail(LANDLORD, ROOT).get("status"));
        root.setStatus(ContractStatus.ACTIVE);
        root.setActive(false);
        assertEquals(6003, failure(() -> service.detail(LANDLORD, ROOT)).getCode());
    }

    // 9. original -> PL1 (16/11) -> PL2 (16/12); boundaries and scheduler lag
    void twoAmendments() {
        revisionData.put("pl1", revision("pl1", 2, 5500000, "PL1"));
        revisionData.put("pl2", revision("pl2", 3, 6000000, "PL2"));
        history.add(amendment("pl2", "SCHEDULED", "2026-12-16", Instant.parse("2026-11-20T03:00:00Z"), "pl2"));
        history.add(amendment("pl1", "APPLIED", "2026-11-16", Instant.parse("2026-11-10T03:00:00Z"), "pl1"));
    }

    String rentOn(String local) {
        clock(local);
        var terms = service.terms(TENANT, ROOT, List.of("PRICING"), null);
        return String.valueOf(map(map(terms.get("groups")).get("PRICING")).get("amountValue")) + "|"
                + map(terms.get("applicableDocument")).get("source") + "|" + map(terms.get("applicableDocument")).get("amendmentId");
    }

    @Test
    void currentTermsFollowEffectiveDateNotNewestCreatedAmendment() {
        twoAmendments();
        assertEquals("5500000|AMENDMENT|pl1", rentOn("2026-11-16T00:00:00"));
        assertEquals("5500000|AMENDMENT|pl1", rentOn("2026-12-15T23:59:59"));
        assertEquals("6000000|AMENDMENT|pl2", rentOn("2026-12-16T00:00:00"), "applies on its date even if the scheduler has not relabelled it");
        clock("2026-11-16T10:00:00");
        var next = map(service.terms(TENANT, ROOT, null, null).get("nextSignedChange"));
        assertEquals("2026-12-16", next.get("effectiveOn"));
        assertEquals("pl2", next.get("amendmentId"));
        var states = new HashMap<String, Object>();
        list(service.amendments(TENANT, ROOT).get("items")).forEach(i -> states.put(String.valueOf(i.get("amendmentId")), i.get("state")));
        assertEquals("CURRENT", states.get("pl1"));
        assertEquals("SIGNED_PENDING_EFFECTIVE", states.get("pl2"));
    }

    @Test
    void beforeTheFirstAmendmentTheOriginalContractApplies() {
        twoAmendments();
        assertEquals("5000000|ORIGINAL_CONTRACT|null", rentOn("2026-11-15T23:59:59"));
    }

    // 10. never current: unsigned / superseded / rejected / cancelled
    @Test
    void unsignedSupersededRejectedAndCancelledAmendmentsAreNeverCurrentTerms() {
        revisionData.put("x", revision("x", 2, 9999999, "X"));
        history.add(amendment("unsigned", "SCHEDULED", "2026-10-20", null, "x"));
        history.add(amendment("superseded", "SUPERSEDED", "2026-10-20", Instant.parse("2026-10-18T00:00:00Z"), "x"));
        history.add(amendment("rejected", "REJECTED", "2026-10-20", Instant.parse("2026-10-18T00:00:00Z"), "x"));
        history.add(amendment("cancelled", "WITHDRAWN", "2026-10-20", Instant.parse("2026-10-18T00:00:00Z"), "x"));
        history.add(amendment("approved", "APPROVED", "2026-10-20", null, "x"));
        assertEquals("5000000|ORIGINAL_CONTRACT|null", rentOn("2026-11-16T10:00:00"));
        var states = new HashMap<String, Object>();
        list(service.amendments(TENANT, ROOT).get("items")).forEach(i -> states.put(String.valueOf(i.get("amendmentId")), i.get("state")));
        assertEquals("SUPERSEDED", states.get("superseded"));
        assertEquals("REJECTED", states.get("rejected"));
        assertEquals("WITHDRAWN", states.get("cancelled"));
        assertEquals("AWAITING_SIGNATURES", states.get("approved"));
        assertNull(service.amendments(TENANT, ROOT).get("currentAmendmentId"));
    }

    // 11. approved renewal is not an extension
    @Test
    void approvedRenewalDoesNotExtendTheLease() {
        ContractRenewal renewal = new ContractRenewal();
        renewal.setId("renewal"); renewal.setContractId(ROOT); renewal.setMonths(3); renewal.setStatus("APPROVED");
        renewal.setStartsOn(LocalDate.of(2027, 1, 16)); renewal.setEndExclusive(LocalDate.of(2027, 4, 16));
        renewal.setSignBy(Instant.parse("2027-01-15T00:00:00Z"));
        when(renewals.findByContractIdOrderByRequestedAtDesc(ROOT)).thenReturn(List.of(renewal));
        clock("2026-12-20T10:00:00");
        var result = service.lifecycle(TENANT, ROOT);
        assertEquals("2027-01-16", map(result.get("lease")).get("endExclusive"));
        assertEquals(false, list(result.get("renewals")).get(0).get("extendsLease"));
        var blockers = (List<?>) map(result.get("renewal")).get("blockers");
        assertTrue(blockers.contains("RENEWAL_ALREADY_REQUESTED"));
        assertEquals(true, map(result.get("renewal")).get("advisory"));
    }

    @Test
    void renewalWindowAndRoleBlockersAreComputedFromBusinessDate() {
        clock("2026-12-16T10:00:00");
        assertEquals(List.of("WINDOW_NOT_OPEN_YET"), map(service.lifecycle(TENANT, ROOT).get("renewal")).get("blockers").equals(null) ? null
                : map(service.lifecycle(TENANT, ROOT).get("renewal")).get("blockers"));
        clock("2026-12-17T00:00:00");
        assertEquals(true, map(service.lifecycle(TENANT, ROOT).get("renewal")).get("askerMayRequestNow"));
        assertTrue(((List<?>) map(service.lifecycle(LANDLORD, ROOT).get("renewal")).get("blockers")).contains("ONLY_TENANT_MAY_REQUEST"));
    }

    // 13/14. frozen invoices, rolled-over debts and refund state
    @Test
    void issuedInvoicesUseFrozenSnapshotsAndRolledOverDebtIsNotCountedTwice() {
        bills.add(MonthlyInvoice.builder().id("b2").contractId(ROOT).periodIndex(2).status(MonthlyInvoiceStatus.UNPAID)
                .periodStart(LocalDate.of(2026, 12, 16)).periodEndExclusive(LocalDate.of(2027, 1, 16))
                .baseAmount(new BigDecimal("5500000")).lateFeeAmount(BigDecimal.ZERO).totalAmount(new BigDecimal("5800000.00"))
                .issuedAt(Instant.parse("2026-12-10T00:00:00Z")).dueAt(Instant.parse("2026-12-20T00:00:00Z"))
                .lineItemsSnapshot("[{\"type\":\"RENT\",\"description\":\"Tiền thuê\",\"quantity\":1,\"unitPrice\":5500000.00,\"amount\":5500000.00}]").build());
        bills.add(MonthlyInvoice.builder().id("b1").contractId(ROOT).periodIndex(1).status(MonthlyInvoiceStatus.ROLLED_OVER)
                .periodStart(LocalDate.of(2026, 11, 16)).periodEndExclusive(LocalDate.of(2026, 12, 16))
                .totalAmount(new BigDecimal("5000000")).issuedAt(Instant.parse("2026-11-10T00:00:00Z")).build());
        bills.add(MonthlyInvoice.builder().id("b3").contractId(ROOT).periodIndex(3).status(MonthlyInvoiceStatus.DRAFT)
                .periodStart(LocalDate.of(2027, 1, 16)).periodEndExclusive(LocalDate.of(2027, 2, 16))
                .totalAmount(new BigDecimal("1")).build());
        var tenant = service.financial(TENANT, ROOT, null, null);
        var totals = map(tenant.get("totals"));
        assertEquals("5800000.00", totals.get("outstanding"), "exact decimal string, rolled-over invoice excluded");
        assertEquals(1L, totals.get("outstandingCount"));
        assertEquals(2, list(tenant.get("invoices")).size(), "tenant does not see the landlord's draft");
        assertEquals("5500000.00", list(list(tenant.get("invoices")).get(0).get("lines")).get(0).get("unitPrice"));
        var landlord = service.financial(LANDLORD, ROOT, null, null);
        assertEquals(3, list(landlord.get("invoices")).size());
        assertEquals(1L, map(landlord.get("totals")).get("notIssuedDraftCount"));
    }

    @Test
    void refundWithTransferReportedIsNotPresentedAsReceived() {
        ContractClosure close = new ContractClosure();
        close.setContractId(ROOT); close.setStatus("ACCEPTED"); close.setRefundAmount(new BigDecimal("20000000"));
        close.setAcceptedAt(Instant.parse("2027-01-20T00:00:00Z")); close.setRefundPaymentId("refund");
        when(closures.findById(ROOT)).thenReturn(Optional.of(close));
        PaymentRequest refund = PaymentRequest.builder().id("refund").status(PaymentStatus.TRANSFER_REPORTED)
                .totalAmount(new BigDecimal("20000000")).build();
        when(payments.findAllById(any())).thenReturn(List.of(refund));
        var settlement = map(service.financial(TENANT, ROOT, null, null).get("settlement"));
        assertEquals("REFUND_TRANSFER_REPORTED_AWAITING_RECIPIENT_CONFIRMATION", settlement.get("depositRefundState"));
        assertEquals(false, map(settlement.get("refundPayment")).get("recipientConfirmed"));
    }

    @Test
    void missingFactsAreNullNotZero() {
        rental.setMoveInDate(null);
        var lease = map(service.detail(TENANT, ROOT).get("lease"));
        assertEquals(false, lease.get("known"));
        assertEquals("UNKNOWN", service.detail(TENANT, ROOT).get("leasePhase"));
        assertNull(map(service.financial(TENANT, ROOT, null, null)).get("deposit"));
    }

    @Test
    void partyDataExposedToTheModelNeverContainsIdentityNumbers() {
        var terms = service.terms(TENANT, ROOT, List.of("OCCUPANCY", "PROPERTY"), null);
        assertFalse(terms.toString().contains("000000000000"));
        assertFalse(service.detail(TENANT, ROOT).toString().contains("000000000000"));
    }

    // 19. read path never writes or locks
    @Test
    void queriesNeverWriteOrLockAnything() {
        twoAmendments();
        when(contracts.findLeaseRowsForUser(eq(TENANT), any())).thenReturn(List.of(row(1, ContractStatus.ACTIVE, "l", TENANT)));
        service.summarize(TENANT, null, null);
        service.search(TENANT, null, null, null, null, null, null, null, null);
        service.detail(TENANT, ROOT); service.terms(TENANT, ROOT, null, "2026-12-20");
        service.amendments(TENANT, ROOT); service.lifecycle(TENANT, ROOT); service.financial(TENANT, ROOT, null, null);
        for (Object mock : new Object[]{contracts, revisions, amendments, renewals, closures, invoices, deposits, payments, rentals}) {
            for (var invocation : mockingDetails(mock).getInvocations()) {
                String name = invocation.getMethod().getName();
                assertFalse(name.startsWith("save") || name.startsWith("delete") || name.contains("ForUpdate")
                        || name.startsWith("flush"), "unexpected write/lock: " + name);
            }
        }
    }

    @Test
    void pastDateViewRejectsAmendmentsSignedAfterThatDay() {
        twoAmendments();
        clock("2026-12-20T10:00:00");
        var terms = service.terms(TENANT, ROOT, List.of("PRICING"), "2026-12-01");
        assertEquals("PAST", terms.get("asOfKind"));
        assertEquals("pl1", map(terms.get("applicableDocument")).get("amendmentId"));
        assertTrue(((List<?>) terms.get("warnings")).contains("PAST_VIEW_RECONSTRUCTED_FROM_SIGNED_SNAPSHOTS"));
    }
}
