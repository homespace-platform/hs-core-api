package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchAttentionResponse.*;
import com.hs.contract.dto.branch.BranchLeaseRow;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.contract.repository.BranchAttentionReadRepository.*;
import com.hs.listing.model.BranchSlot;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.hs.contract.dto.branch.BranchAttentionResponse.WorkType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchAttentionTest {
    private static final String BRANCH = "b";
    private static final String OWNER = "owner";
    private static final String TENANT = "tenant";
    private static final List<String> BRANCHES = List.of(BRANCH, "empty");
    private static final LocalDate TODAY = LocalDate.of(2026, 12, 1);
    private static final Instant NOW = midnight(TODAY);

    private final PropertyBranchRepository branches = mock();
    private final BranchAttentionReadRepository reads = mock();
    private final BranchContractAssignmentRepository assignments = mock();
    private final PaymentRequestRepository payments = mock();
    private final PaymentLineItemRepository lines = mock();
    private final BillingTime time = mock();
    private final BranchOverviewService overview = new BranchOverviewService(branches, null, null, assignments,
            null, payments, lines, time);
    private final RentalOnboardingService onboarding = mock();
    private final BranchAttentionService service = new BranchAttentionService(branches, reads, assignments, payments,
            overview, onboarding, time);
    private final Contract root = Contract.builder().id("c").contractNumber("HD-1").landlordId(OWNER)
            .tenantId(TENANT).rentalRequestId("rental").listingId("listing").currentRevisionId("rev")
            .status(ContractStatus.ACTIVE).build();

    BranchAttentionTest() {
        when(time.now()).thenReturn(NOW);
        when(branches.findByIdAndOwnerIdAndActiveTrue(BRANCH, OWNER)).thenReturn(Optional.of(new PropertyBranch()));
        when(branches.findActiveIds(OWNER)).thenReturn(BRANCHES);
    }

    @Test
    void meterWindowUsesVietnamMidnightAndPreparedOrCancelledDraftBlocksProjection() {
        managed();
        lease(TODAY.plusDays(4), null);
        var draft = invoice("meter", 0, MonthlyInvoiceStatus.DRAFT);
        draft.setPeriodEndExclusive(TODAY.plusDays(4));
        draft.setCostTermsRevisionId("annex-rev");
        when(reads.findInvoices(any())).thenReturn(List.of(draft));
        var charges = "[{\"billingMethod\":\"PER_KWH\",\"includedInRent\":false}]";
        when(reads.findRevisionCharges(any())).thenReturn(List.of(new RevisionCharges("annex-rev", charges, "other")));
        absent(METER_UPCOMING);
        when(reads.findRevisionCharges(any())).thenReturn(List.of(new RevisionCharges("annex-rev", charges, root.getId())));

        var upcoming = one(METER_UPCOMING);
        assertEquals(0, upcoming.periodIndex());
        assertEquals(midnight(TODAY.plusDays(4)), upcoming.dueAt());
        assertEquals(Clock.BILLING, upcoming.clock());
        assertEquals("/dashboard/landlord/contracts/c?tab=payment&section=monthly&invoice=meter", upcoming.target().href());
        when(time.now()).thenReturn(midnight(TODAY.plusDays(3)));
        assertEquals(Priority.URGENT, one(METER_TODAY).priority());
        absent(METER_UPCOMING);
        draft.setDraftExtraChargesSnapshot("[]");
        absent(METER_TODAY);
        when(time.now()).thenReturn(midnight(TODAY.plusDays(4)));
        assertEquals("DRAFT_MISSED:meter", one(DRAFT_MISSED).key());
        draft.setStatus(MonthlyInvoiceStatus.CANCELLED);
        absent(METER_TODAY, METER_UPCOMING, DRAFT_MISSED);
    }

    @Test
    void onlyNewestReachedInvoiceFlagsAutofillOnceAndStopsAfterCollecting() {
        managed();
        lease(TODAY.plusDays(15), null);
        var older = invoice("older", 0, MonthlyInvoiceStatus.DRAFT);
        var latest = invoice("latest", 1, MonthlyInvoiceStatus.DRAFT);
        older.setElectricityAutoFilled(true);
        latest.setElectricityAutoFilled(true);
        latest.setWaterAutoFilled(true);
        when(reads.findInvoices(any())).thenReturn(List.of(older, latest));
        assertEquals("METER_AUTOFILLED:latest", one(METER_AUTOFILLED).key());
        latest.setStatus(MonthlyInvoiceStatus.PAID);
        absent(METER_AUTOFILLED);
    }

    @Test
    void monthlyExtensionAndNoticeReplaceOverdueUntilResolvedAndConfirmedReceiptDisappears() {
        managed();
        lease(TODAY.plusDays(15), null);
        var bill = invoice("invoice", 0, MonthlyInvoiceStatus.OVERDUE);
        var p = incoming("pay", PaymentType.MONTHLY_RENT, PaymentStatus.OVERDUE);
        p.setInvoiceId(bill.getId());
        bill.setPaymentRequestId(p.getId());
        when(reads.findInvoices(any())).thenReturn(List.of(bill));
        when(payments.findByActiveTrueAndInvoiceIdIn(any())).thenReturn(List.of(p));
        when(payments.findAttentionByContracts(any(), any())).thenReturn(List.of(p));

        assertEquals("PAYMENT_OVERDUE:invoice", one(PAYMENT_OVERDUE).key());
        bill.setExtensionRequestsSnapshot("""
                [{"id":"ext","status":"PENDING","requestedBy":"tenant","respondBy":"2026-12-01T17:00:00Z"}]
                """);
        assertEquals("PAYMENT_EXTENSION_REQUEST:ext", one(PAYMENT_EXTENSION_REQUEST).key());
        absent(PAYMENT_OVERDUE);
        bill.setExtensionRequestsSnapshot(bill.getExtensionRequestsSnapshot().replace("tenant", OWNER));
        absent(PAYMENT_EXTENSION_REQUEST);
        root.setUnilateralTerminationSnapshot("""
                [{"id":"notice","invoiceId":"invoice","status":"REVIEW_REQUESTED","respondBy":"2026-12-01T17:00:00Z"}]
                """);
        assertNull(one(TERMINATION_RESPONSE).dueAt());
        absent(PAYMENT_OVERDUE);
        p.setStatus(PaymentStatus.TRANSFER_REPORTED);
        assertEquals("PAYMENT_CONFIRM:pay", one(PAYMENT_CONFIRM).key());
        absent(PAYMENT_OVERDUE, TERMINATION_RESPONSE);
        p.setStatus(PaymentStatus.CONFIRMED);
        absent(PAYMENT_CONFIRM, PAYMENT_OVERDUE);
    }

    @Test
    void malformedExtensionEntryDoesNotHideOtherWorkOnTheBranch() {
        managed();
        lease(TODAY.plusDays(15), null);
        var bill = invoice("invoice", 0, MonthlyInvoiceStatus.OVERDUE);
        var transfer = incoming("pay", PaymentType.MONTHLY_RENT, PaymentStatus.OVERDUE);
        transfer.setInvoiceId(bill.getId());
        bill.setExtensionRequestsSnapshot("[null]");
        when(reads.findInvoices(any())).thenReturn(List.of(bill));
        when(payments.findByActiveTrueAndInvoiceIdIn(any())).thenReturn(List.of(transfer));
        when(payments.findAttentionByContracts(any(), any())).thenReturn(List.of(transfer));
        assertEquals("PAYMENT_OVERDUE:invoice", one(PAYMENT_OVERDUE).key());
    }

    @Test
    void settlementAndDepositTopUpFollowTheSameMoneyRulesAndOnlyOwnerRefundsAreWork() {
        managed();
        lease(TODAY.minusDays(1), NOW);
        var balance = incoming("balance", PaymentType.OTHER_CHARGE, PaymentStatus.TRANSFER_REPORTED);
        var extra = incoming("extra", PaymentType.DEPOSIT_ADJUSTMENT, PaymentStatus.OVERDUE);
        var refund = incoming("refund", PaymentType.DEPOSIT_ADJUSTMENT, PaymentStatus.AWAITING_TRANSFER);
        refund.setDirection(PaymentDirection.LANDLORD_TO_TENANT);
        refund.setPayerId(OWNER);
        refund.setPayeeId(TENANT);
        when(assignments.findBalanceClosures(any())).thenReturn(List.of(ContractClosure.builder()
                .contractId(root.getId()).balancePaymentId(balance.getId()).build()));
        when(payments.findAllById(any())).thenReturn(List.of(balance));
        when(payments.findByActiveTrueAndContractIdInAndType(any(), eq(PaymentType.DEPOSIT_ADJUSTMENT)))
                .thenReturn(List.of(extra, refund));
        when(payments.findAttentionByContracts(any(), any())).thenReturn(List.of(extra, refund, balance));

        assertEquals("/dashboard/payments?paymentId=balance", one(PAYMENT_CONFIRM).target().href());
        assertEquals("PAYMENT_OVERDUE:extra", one(PAYMENT_OVERDUE).key());
        assertEquals("OWNER_REFUND:refund", one(OWNER_REFUND).key());
        extra.setStatus(PaymentStatus.TRANSFER_REPORTED);
        assertThat(work()).filteredOn(w -> w.type() == PAYMENT_CONFIRM).extracting(Work::key)
                .containsExactly("PAYMENT_CONFIRM:balance", "PAYMENT_CONFIRM:extra");
        balance.setStatus(PaymentStatus.CONFIRMED);
        refund.setStatus(PaymentStatus.TRANSFER_REPORTED);
        extra.setPayeeId("other");
        absent(PAYMENT_CONFIRM, PAYMENT_OVERDUE, OWNER_REFUND);
    }

    @Test
    void onboardingReceiptsUseCorrectPartyAndAreDeduplicatedAcrossQuerySources() {
        managed();
        root.setStatus(ContractStatus.DRAFT);
        lease(TODAY.plusDays(15), null);
        var receipt = incoming("first", PaymentType.INITIAL, PaymentStatus.TRANSFER_REPORTED);
        when(payments.findAttentionByRentals(any(), any(), any())).thenReturn(List.of(receipt));
        when(payments.findAttentionByContracts(any(), any())).thenReturn(List.of(receipt));
        var work = one(PAYMENT_CONFIRM);
        assertEquals("PAYMENT_CONFIRM:first", work.key());
        assertEquals(Clock.BILLING, work.clock());
        assertEquals(receipt.getConfirmationDueAt(), work.dueAt());
        assertTrue(work.target().href().endsWith("/c?tab=payment"));
        receipt.setPayerId("other");
        absent(PAYMENT_CONFIRM);
    }

    @Test
    void rejectedReservationDepositStillNeedsOwnerReviewBeforeItsTransferDeadline() {
        listing(ListingStatus.RESERVED, null);
        slot();
        when(reads.findRoomLinks(any())).thenReturn(List.of(new RoomLink("listing", "room", BRANCH)));
        when(reads.findListingRentals(any(), eq(OWNER), any())).thenReturn(List.of(
                rental("rental", RentalRequestStatus.ACCEPTED, null)));
        var deposit = incoming("deposit", PaymentType.RESERVATION_DEPOSIT, PaymentStatus.REJECTED);
        deposit.setContractId(null);
        deposit.setDueAt(NOW.plusSeconds(3600));
        when(payments.findAttentionByRentals(any(), any(), any())).thenReturn(List.of(deposit));

        assertThat(work()).singleElement().satisfies(w ->
                assertEquals("/dashboard/rental-requests?requestId=rental", w.target().href()));
    }

    @Test
    void leaseSigningNeverUsesAConfirmedReceiptForAnotherOwner() {
        managed();
        root.setStatus(ContractStatus.DRAFT);
        lease(TODAY.plusDays(15), null);
        doCallRealMethod().when(onboarding).landlordSigningStep(any(), any());
        var receipt = incoming("first", PaymentType.INITIAL, PaymentStatus.CONFIRMED);
        receipt.setConfirmedAt(NOW.minusSeconds(60));
        receipt.setContractDueAt(NOW.plusSeconds(3600));
        when(payments.findAttentionByRentals(any(), any(), any())).thenReturn(List.of(receipt));
        assertEquals("OWNER_SIGN:c", one(OWNER_SIGN).key());
        receipt.setPayeeId("other");
        absent(OWNER_SIGN);
    }

    @Test
    void tenantResponsesAndLeaseExpiryStopWhenPartyOrEffectiveEndChanges() {
        managed();
        lease(TODAY.plusDays(60), null);
        var amendment = ContractAmendment.builder().id("amend / 1").contractId(root.getId()).status("TENANT_REQUESTED")
                .recipientId(OWNER).proposerId(TENANT).respondBy(NOW.plusSeconds(3600)).build();
        var exit = new RentalWorkflowRecord();
        exit.setId("exit");
        exit.setContractId(root.getId());
        exit.setRentalRequestId(root.getRentalRequestId());
        exit.setStatus("PROPOSED");
        exit.setProposedBy(TENANT);
        when(reads.findAmendments(any(), any())).thenReturn(List.of(amendment));
        when(reads.findEarlyExits(any())).thenReturn(List.of(exit));
        assertTrue(one(AMENDMENT_RESPONSE).target().href().endsWith("?tab=end&amendmentId=amend%20%2F%201"));
        assertTrue(one(TERMINATION_RESPONSE).target().href().endsWith("?tab=end&focus=early-exit"));
        assertEquals(midnight(TODAY.plusDays(60)), one(LEASE_EXPIRING).dueAt());
        amendment.setRecipientId(TENANT);
        exit.setProposedBy(OWNER);
        lease(TODAY.plusDays(61), null);
        absent(AMENDMENT_RESPONSE, TERMINATION_RESPONSE, LEASE_EXPIRING);
        lease(TODAY.plusDays(30), null);
        assertEquals(midnight(TODAY.plusDays(30)), one(LEASE_EXPIRING).dueAt());
    }

    @Test
    void ownerSignatureUsesCurrentRevisionAndFutureDeadline() {
        var child = amendmentReadyForSigning();
        assertEquals("/dashboard/contracts/annex?tab=sign", one(OWNER_SIGN).target().href());
        when(reads.findLandlordSignatures(any(), eq(OWNER))).thenReturn(List.of(new Signature(child.getId(), "old")));
        assertEquals("OWNER_SIGN:annex", one(OWNER_SIGN).key());
        when(reads.findLandlordSignatures(any(), eq(OWNER))).thenReturn(List.of(new Signature(child.getId(), "annex-rev")));
        absent(OWNER_SIGN);
        when(reads.findLandlordSignatures(any(), eq(OWNER))).thenReturn(List.of());
        when(time.now()).thenReturn(NOW.plusSeconds(3600));
        absent(OWNER_SIGN);
    }

    @Test
    void doesNotPromptForApprovedAnnexAfterBillingClosesItsEffectivePeriod() {
        amendmentReadyForSigning();
        var firstBoundary = TODAY.minusDays(25).plusMonths(1);
        when(time.now()).thenReturn(midnight(firstBoundary).minusSeconds(3600));
        when(reads.findAmendments(any(), any())).thenReturn(List.of(ContractAmendment.builder().id("amend")
                .contractId(root.getId()).status("APPROVED").agreementContractId("annex").baseRevisionId("rev")
                .effectiveOn(firstBoundary).signBy(midnight(firstBoundary)).build()));
        assertEquals("OWNER_SIGN:annex", one(OWNER_SIGN).key());
        var issued = invoice("already-issued", 0, MonthlyInvoiceStatus.PAID);
        issued.setPeriodEndExclusive(firstBoundary);
        when(reads.findInvoices(any())).thenReturn(List.of(issued));
        absent(OWNER_SIGN);
    }

    @Test
    void passedLeaseSigningWindowAndPendingCancellationPointAtTheRentalRequest() {
        managed();
        root.setStatus(ContractStatus.DRAFT);
        lease(TODAY.plusDays(15), null);
        doCallRealMethod().when(onboarding).landlordSigningOverdue(any(), any());
        var receipt = incoming("first", PaymentType.INITIAL, PaymentStatus.CONFIRMED);
        receipt.setConfirmedAt(NOW.minusSeconds(60));
        receipt.setContractDueAt(NOW);
        when(payments.findAttentionByRentals(any(), any(), any())).thenReturn(List.of(receipt));
        var reopen = one(SIGNING_REOPEN);
        assertEquals("SIGNING_REOPEN:c", reopen.key());
        assertEquals(Priority.URGENT, reopen.priority());
        assertEquals("/dashboard/rental-requests?requestId=rental", reopen.target().href());
        when(reads.findRentals(any(), eq(OWNER))).thenReturn(List.of(new RentalRow("rental", "listing", TENANT,
                "Tenant", TODAY, RentalRequestStatus.ACCEPTED, true, null, BigDecimal.ZERO, null, null, null, NOW,
                ListingStatus.RESERVED, null)));
        assertEquals("CANCELLATION_DECISION:rental", one(CANCELLATION_DECISION).key());
        absent(SIGNING_REOPEN);
    }

    @Test
    void newCustomerWorkUsesBillingForRentalAndRealForAppointments() {
        listing(ListingStatus.DRAFT, null);
        var future = LocalDate.now(BillingTime.ZONE).plusDays(2);
        when(reads.findListingRentals(any(), eq(OWNER), any())).thenReturn(List.of(
                rental("new", RentalRequestStatus.PENDING, NOW.plusSeconds(60)),
                rental("old", RentalRequestStatus.PENDING, NOW)));
        when(reads.findOpenAppointments(any(), eq(OWNER))).thenReturn(List.of(
                new Appointment("expired", "listing", AppointmentStatus.PENDING, false,
                        future.minusDays(4), LocalTime.NOON, LocalTime.of(13, 0), null, null),
                new Appointment("reschedule", "listing", AppointmentStatus.CONFIRMED, true,
                        future, LocalTime.NOON, LocalTime.of(13, 0), future.plusDays(1), LocalTime.of(9, 0))));
        assertEquals("RENTAL_REQUEST_PENDING:new", one(RENTAL_REQUEST_PENDING).key());
        var visit = one(VIEWING_PENDING);
        assertEquals("VIEWING_PENDING:reschedule", visit.key());
        assertEquals(Clock.REAL, visit.clock());
        assertEquals(midnight(future.plusDays(1)).plusSeconds(9 * 3600), visit.dueAt());
        when(reads.findListingRentals(any(), eq(OWNER), any())).thenReturn(List.of());
        when(reads.findOpenAppointments(any(), eq(OWNER))).thenReturn(List.of());
        absent(RENTAL_REQUEST_PENDING, VIEWING_PENDING);
    }

    @Test
    void roomListingAndTrayWorkDisappearsWhenPlacedAndPublished() {
        managed();
        lease(TODAY.plusDays(15), null);
        listing(ListingStatus.REJECTED, null);
        when(reads.findAssignments(any())).thenReturn(List.of(new BranchContractAssignment("c", BRANCH, null)));
        assertEquals("UNPLACED_CONTRACT:c", one(UNPLACED_CONTRACT).key());
        assertTrue(one(UNPLACED_LISTING).target().href().endsWith("?tab=map#unplaced"));
        assertEquals("ROOM_VACANT_UNLISTED:room", one(ROOM_VACANT_UNLISTED).key());

        when(reads.findRoomLinks(any())).thenReturn(List.of(new RoomLink("listing", "room", BRANCH)));
        assertEquals("LISTING_REJECTED:listing", one(LISTING_REJECTED).key());
        absent(UNPLACED_LISTING);
        listing(ListingStatus.PUBLISHED, Instant.now().minusSeconds(60));
        assertEquals(Clock.REAL, one(LISTING_EXPIRED).clock());
        absent(LISTING_REJECTED);
        listing(ListingStatus.PUBLISHED, Instant.now().plusSeconds(86400));
        absent(LISTING_EXPIRED, ROOM_VACANT_UNLISTED);
        when(reads.findAssignments(any())).thenReturn(List.of(new BranchContractAssignment("c", BRANCH, "room")));
        absent(UNPLACED_CONTRACT);
    }

    @Test
    void paginationAndSummaryUseSameOrderedUniqueWorkAndKeepZeroBranches() {
        slot();
        listing(ListingStatus.REJECTED, null);
        when(reads.findListingRentals(any(), eq(OWNER), any())).thenReturn(List.of(
                rental("later", RentalRequestStatus.PENDING, NOW.plusSeconds(86401)),
                rental("b", RentalRequestStatus.PENDING, NOW.plusSeconds(86400)),
                rental("a", RentalRequestStatus.PENDING, NOW.plusSeconds(86400))));
        var all = service.attention(BRANCH, OWNER, 0, 100);
        assertThat(all.items()).extracting(Work::key).containsExactly(
                "RENTAL_REQUEST_PENDING:a", "RENTAL_REQUEST_PENDING:b", "RENTAL_REQUEST_PENDING:later",
                "LISTING_REJECTED:listing", "ROOM_VACANT_UNLISTED:room", "UNPLACED_LISTING:listing");
        var second = service.attention(BRANCH, OWNER, 1, 2);
        assertEquals(all.items().subList(2, 4), second.items());
        assertEquals(all.totalElements(), second.totalElements());
        var beyond = service.attention(BRANCH, OWNER, Integer.MAX_VALUE, 100);
        assertThat(beyond.items()).isEmpty();
        assertEquals(all.totalElements(), beyond.totalElements());
        assertThat(service.summary(OWNER).branches()).containsExactly(new BranchCount(BRANCH, all.totalElements()),
                new BranchCount("empty", 0));
    }

    @Test
    void summaryComparesRemainingOnEachClockBeforeCountAndId() {
        Instant real = NOW.minus(Duration.ofDays(20));
        var billing = new Work("bill", OWNER_REFUND, null, null, null, null, NOW.plusSeconds(500),
                Clock.BILLING, Priority.URGENT, null);
        var viewing = new Work("visit", VIEWING_PENDING, null, null, null, null, real.plusSeconds(600),
                Clock.REAL, Priority.URGENT, null);
        assertThat(BranchAttentionService.cardOrder(Map.of("z", List.of(billing, viewing), "a", List.of(billing),
                "visit", List.of(viewing), "zero", List.of()), NOW, real)).extracting(BranchCount::branchId)
                .containsExactly("z", "a", "visit", "zero");
    }

    @Test
    void foreignBranchDeniesBeforeReadingAnySource() {
        assertEquals(404, assertThrows(AppException.class, () -> service.attention(BRANCH, "other", 0, 20))
                .getStatusCode().value());
        assertThat(service.summary("other").branches()).isEmpty();
        verifyNoInteractions(reads, assignments, payments, onboarding);
    }

    private Contract amendmentReadyForSigning() {
        managed();
        lease(TODAY.plusDays(15), null);
        var child = Contract.builder().id("annex").parentContractId(root.getId()).agreementKind("AMENDMENT")
                .rentalRequestId(root.getRentalRequestId())
                .tenantId(TENANT).landlordId(OWNER).status(ContractStatus.DRAFT).currentRevisionId("annex-rev").build();
        var source = ContractAmendment.builder().id("amend").contractId(root.getId()).status("APPROVED")
                .agreementContractId(child.getId()).signBy(NOW.plusSeconds(3600)).build();
        when(reads.findChildDocuments(any(), eq(OWNER), any())).thenReturn(List.of(child));
        when(reads.findAmendments(any(), any())).thenReturn(List.of(source));
        return child;
    }

    private void managed() {
        slot();
        when(reads.findManagedRoots(any(), eq(OWNER))).thenReturn(List.of(root));
        when(reads.findAssignments(any())).thenReturn(List.of(new BranchContractAssignment("c", BRANCH, "room")));
        when(reads.findRentals(any(), eq(OWNER))).thenReturn(List.of(rental("rental", RentalRequestStatus.ACCEPTED, null)));
    }

    private void slot() {
        var slot = new BranchSlot();
        slot.setId("room");
        slot.setRoomCode("101");
        when(reads.findSlots(any())).thenReturn(Collections.singletonList(new Object[]{BRANCH, slot}));
    }

    private void lease(LocalDate end, Instant handover) {
        when(assignments.findLeaseRows(any())).thenReturn(List.of(new BranchLeaseRow("c", "HD-1", root.getStatus(),
                end, null, null, TODAY.minusDays(25), 3, "Tenant", handover)));
    }

    private void listing(ListingStatus status, Instant expiresAt) {
        when(reads.findListings(any(), eq(OWNER))).thenReturn(List.of(
                new ListingRow("listing", BRANCH, "Listing title", status, null, expiresAt)));
    }

    private static RentalRow rental(String id, RentalRequestStatus status, Instant respondBy) {
        return new RentalRow(id, "listing", TENANT, "Tenant", TODAY.minusDays(25), status, true, respondBy,
                BigDecimal.ZERO, null, null, null, null, ListingStatus.RESERVED, null);
    }

    private MonthlyInvoice invoice(String id, int period, MonthlyInvoiceStatus status) {
        return MonthlyInvoice.builder().id(id).contractId(root.getId()).periodIndex(period).status(status)
                .periodStart(TODAY.minusMonths(1)).periodEndExclusive(TODAY).dueAt(NOW.minusSeconds(1)).build();
    }

    private PaymentRequest incoming(String id, PaymentType type, PaymentStatus status) {
        return PaymentRequest.builder().id(id).contractId(root.getId()).rentalRequestId(root.getRentalRequestId())
                .type(type).status(status).payerId(TENANT).payeeId(OWNER).direction(PaymentDirection.TENANT_TO_LANDLORD)
                .totalAmount(BigDecimal.TEN).dueAt(NOW.minusSeconds(1)).confirmationDueAt(NOW.plusSeconds(3600)).build();
    }

    private List<Work> work() {
        return service.attention(BRANCH, OWNER, 0, 100).items();
    }

    private Work one(WorkType type) {
        var found = work().stream().filter(w -> w.type() == type).toList();
        assertThat(found).as(type.name()).hasSize(1);
        return found.getFirst();
    }

    private void absent(WorkType... types) {
        assertThat(work()).extracting(Work::type).doesNotContain(types);
    }

    private static Instant midnight(LocalDate date) {
        return date.atStartOfDay(BillingTime.ZONE).toInstant();
    }
}
