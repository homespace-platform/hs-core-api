package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.DepositRecordRepository;
import com.hs.payment.service.PaymentRequestService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnilateralTerminationServiceTest {
    final ContractRepository contracts=mock(ContractRepository.class);
    final ContractRevisionRepository revisions=mock(ContractRevisionRepository.class);
    final RentalRequestRepository rentals=mock(RentalRequestRepository.class);
    final MonthlyInvoiceRepository invoices=mock(MonthlyInvoiceRepository.class);
    final PaymentRequestService payments=mock(PaymentRequestService.class);
    final BillingTime time=mock(BillingTime.class);
    final ListingStatusService listings=mock(ListingStatusService.class);
    final DepositRecordRepository deposits=mock(DepositRecordRepository.class);
    final ParkingReservationService parking=mock(ParkingReservationService.class);
    final com.hs.contract.notification.InvoiceOutboxProbe outbox=new com.hs.contract.notification.InvoiceOutboxProbe();
    final com.hs.contract.notification.TerminationNotificationWriter terminationNotices=mock(com.hs.contract.notification.TerminationNotificationWriter.class);
    final org.springframework.transaction.PlatformTransactionManager tx=mock(org.springframework.transaction.PlatformTransactionManager.class);
    MonthlyBillingService service;
    Contract contract; MonthlyInvoice invoice; ContractRevision revision; PaymentRequest payment; DepositRecord deposit;
    void clock(String local) { var t=LocalDateTime.parse(local).atZone(BillingTime.ZONE);when(time.now()).thenReturn(t.toInstant());when(time.today()).thenReturn(t.toLocalDate()); }
    @BeforeEach void setup() {
        service=new MonthlyBillingService(contracts,revisions,rentals,invoices,payments,new ObjectMapper().findAndRegisterModules(),time,
            tx,listings,deposits,parking,outbox.writer);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"terminationNotices",terminationNotices);
        when(tx.getTransaction(any())).thenAnswer(call->new org.springframework.transaction.support.SimpleTransactionStatus());
        contract=Contract.builder().id("contract").rentalRequestId("rental").listingId("listing").landlordId("owner").tenantId("tenant")
            .currentRevisionId("revision").status(ContractStatus.ACTIVE).build();
        invoice=MonthlyInvoice.builder().id("invoice").contractId("contract").periodIndex(0).periodStart(LocalDate.of(2026,10,1))
            .periodEndExclusive(LocalDate.of(2026,11,1)).dueAt(Instant.parse("2026-11-05T16:59:59Z")).status(MonthlyInvoiceStatus.OVERDUE)
            .issuedAt(Instant.parse("2026-11-01T03:00:00Z")).paymentRequestId("payment").baseAmount(new BigDecimal("1000000"))
            .lateFeeAmount(BigDecimal.ZERO).totalAmount(new BigDecimal("1000000")).lineItemsSnapshot("[]").build();
        revision=ContractRevision.builder().id("revision").financialSnapshot("{\"depositAmountValue\":0}")
            .policiesSnapshot("{\"latePaymentFeeMode\":\"FIXED_PER_DAY\",\"latePaymentFeeAmount\":100000,\"latePaymentFeeGraceDays\":0}").build();
        payment=PaymentRequest.builder().id("payment").type(PaymentType.MONTHLY_RENT).status(PaymentStatus.OVERDUE).build();
        deposit=DepositRecord.builder().originalAmount(BigDecimal.ZERO).heldAmount(BigDecimal.ZERO).refundableAmount(BigDecimal.ZERO).status(DepositStatus.HELD).build();
        when(contracts.findById("contract")).thenReturn(Optional.of(contract));when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(contract));
        when(revisions.findById("revision")).thenReturn(Optional.of(revision));when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().moveInDate(LocalDate.of(2026,10,1)).leaseMonths(6).build()));
        when(invoices.findContractIdByInvoiceId("invoice")).thenReturn(Optional.of("contract"));when(invoices.findByIdForUpdate("invoice")).thenReturn(Optional.of(invoice));
        when(invoices.findByContractIdAndPeriodIndex("contract",0)).thenReturn(Optional.of(invoice));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(invoice));when(invoices.save(any())).thenAnswer(c->c.getArgument(0));
        when(payments.findByInvoiceId("invoice")).thenReturn(Optional.of(payment));when(payments.increaseMonthlyPayment(anyString(),any(),anyString())).thenReturn(true);
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));when(deposits.findByRentalRequestIdForUpdate("rental")).thenReturn(Optional.of(deposit));
        clock("2026-11-11T10:00:00");
    }
    UnilateralTerminationNotice issue() { return service.issueUnilateralTermination("invoice","owner",new UnilateralTerminationPayloads.Issue("Quá hạn thanh toán, chủ nhà chọn chấm dứt")).unilateralTermination(); }
    MonthlyInvoiceResponse current() { return service.list("contract","tenant").stream().filter(i->"invoice".equals(i.id())).findFirst().orElseThrow(); }
    UnilateralTerminationNotice review(UnilateralTerminationNotice n,UnilateralTerminationNotice.Option option) {
        return service.requestTerminationReview("invoice",n.id(),"tenant",new UnilateralTerminationPayloads.Review(n.version(),option,
            option==UnilateralTerminationNotice.Option.PAYMENT_EXTENSION?5:null,"Xin chờ ngày trả lương để thanh toán")).unilateralTermination();
    }
    @Test void noticeStartsExactly24HoursWithoutInventingTenantConsentOrReleasingTheRoom() {
        var n=issue();assertEquals(Instant.parse("2026-11-12T03:00:00Z"),n.respondBy());assertEquals(1,n.version());
        verify(terminationNotices).tenant(contract,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_NOTICE_ISSUED);
        assertEquals(UnilateralTerminationNotice.Status.NOTICE,n.status());assertNull(contract.getTerminationAcceptedAt());
        assertEquals(ContractStatus.ACTIVE,contract.getStatus());assertTrue(current().canRequestTerminationReview());
        assertEquals(new BigDecimal("500000"),invoice.getLateFeeAmount());assertEquals(new BigDecimal("1500000"),invoice.getTotalAmount());
        assertEquals(OverdueAction.Type.UNILATERAL_TERMINATION_NOTICE,current().overdueActions().getLast().type());
        verifyNoInteractions(listings,parking);assertEquals(DepositStatus.HELD,deposit.getStatus());
    }
    @Test void expiryBeforeAtAndAfter24HoursIsIdempotentAndDoesNotPretendHandoverOccurred() {
        issue();clock("2026-11-12T09:59:59");assertEquals(UnilateralTerminationNotice.Status.NOTICE,current().unilateralTermination().status());
        clock("2026-11-12T10:00:00");var ended=current();assertEquals(ContractStatus.TERMINATED,contract.getStatus());
        assertEquals(UnilateralTerminationNotice.Status.ENDED,ended.unilateralTermination().status());assertNull(contract.getTerminationAcceptedAt());
        assertNull(ended.unilateralTermination().handoverAt());assertTrue(ended.canCompleteTerminationHandover());
        int events=ended.overdueActions().size();clock("2026-11-14T10:00:00");assertEquals(events,current().overdueActions().size());
        verify(terminationNotices,times(1)).both(contract,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_ENDED);
        assertEquals(new BigDecimal("1500000"),invoice.getTotalAmount());verifyNoInteractions(listings,parking);
    }
    @Test void exactlyAtDeadlineTenantCannotCreateAnObjectionOrResetTheClock() {
        var n=issue();clock("2026-11-12T10:00:00");assertThrows(AppException.class,()->review(n,UnilateralTerminationNotice.Option.PAYMENT_EXTENSION));
        assertEquals(UnilateralTerminationNotice.Status.ENDED,current().unilateralTermination().status());
    }
    @ParameterizedTest @EnumSource(UnilateralTerminationNotice.Option.class)
    void timelyReviewPausesAutomaticEndAndApprovalReplacesItWithExactlyTheRequestedOption(UnilateralTerminationNotice.Option option) {
        var n=review(issue(),option);assertEquals(2,n.version());
        verify(terminationNotices).landlord(contract,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_REVIEW_REQUESTED);
        clock("2026-11-13T10:00:00");assertEquals(ContractStatus.ACTIVE,contract.getStatus());assertEquals(UnilateralTerminationNotice.Status.REVIEW_REQUESTED,current().unilateralTermination().status());
        assertThrows(AppException.class,()->service.extendPaymentDirectly("invoice","owner",new PaymentExtensionPayload(5,"Không được áp dụng đồng thời")));
        var result=service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),true,"Đồng ý phương án người thuê đã xin"));
        assertEquals(UnilateralTerminationNotice.Status.REPLACED,result.unilateralTermination().status());assertNotNull(contract.getTerminationCancelledAt());
        verify(terminationNotices).tenant(contract,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_REVIEW_APPROVED);
        assertEquals(ContractStatus.ACTIVE,contract.getStatus());assertNull(contract.getTerminationAcceptedAt());
        if(option==UnilateralTerminationNotice.Option.ROLLOVER){assertNotNull(result.deferredAt());assertEquals("ROLLOVER",result.activeResolution());assertTrue(result.paymentExtensions().isEmpty());}
        else {assertEquals("PAYMENT_EXTENSION",result.activeResolution());assertEquals(Instant.parse("2026-11-18T03:00:00Z"),result.dueAt());assertNull(result.deferredAt());}
        assertThrows(AppException.class,()->service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),false,"Quyết định lặp phải bị từ chối")));
    }
    @Test void rejectingReviewImmediatelyEndsTheContractWithoutTenantConfirmation() {
        var n=review(issue(),UnilateralTerminationNotice.Option.PAYMENT_EXTENSION);clock("2026-11-11T11:00:00");
        var result=service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),false,"Không đồng ý cho thanh toán trễ nữa"));
        assertEquals(UnilateralTerminationNotice.Status.ENDED,result.unilateralTermination().status());assertEquals(time.now(),contract.getTerminationCompletedAt());
        assertNull(contract.getTerminationAcceptedAt());assertEquals(ContractStatus.TERMINATED,contract.getStatus());verifyNoInteractions(listings,parking);
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"TRANSFER_REPORTED","DISPUTED"})
    void moneyUnderReviewBlocksAutomaticEndAndConfirmedPaymentCancelsTheNotice(PaymentStatus status) {
        issue();payment.setStatus(status);clock("2026-11-12T11:00:00");assertEquals(UnilateralTerminationNotice.Status.NOTICE,current().unilateralTermination().status());
        assertEquals(ContractStatus.ACTIVE,contract.getStatus());payment.setStatus(PaymentStatus.CONFIRMED);payment.setConfirmedAt(time.now());
        var paid=current();assertEquals(MonthlyInvoiceStatus.PAID,paid.status());assertEquals(UnilateralTerminationNotice.Status.CANCELLED_PAID,paid.unilateralTermination().status());
        assertEquals(ContractStatus.ACTIVE,contract.getStatus());assertFalse(paid.canRequestTerminationReview());
    }
    @Test void reportedTransferAlsoBlocksOwnerRejectionAndRejectedProofResumesAutomaticEnd() {
        var n=review(issue(),UnilateralTerminationNotice.Option.PAYMENT_EXTENSION);payment.setStatus(PaymentStatus.DISPUTED);
        assertThrows(AppException.class,()->service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),false,"Không được kết thúc khi đang đối soát")));
        assertEquals(ContractStatus.ACTIVE,contract.getStatus());
        service.withdrawUnilateralTermination("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Version(n.version()));
        payment.setStatus(PaymentStatus.REJECTED);var next=issue();payment.setStatus(PaymentStatus.TRANSFER_REPORTED);
        clock("2026-11-12T11:00:00");current();assertEquals(ContractStatus.ACTIVE,contract.getStatus());
        payment.setStatus(PaymentStatus.REJECTED);assertEquals(UnilateralTerminationNotice.Status.ENDED,current().unilateralTermination().status());
        assertNotEquals(next.id(),n.id());
    }
    @Test void rolesVersionsAndLegacyEndpointsCannotBypassTheNewDecision() {
        assertThrows(AppException.class,()->service.issueUnilateralTermination("invoice","tenant",new UnilateralTerminationPayloads.Issue("Người thuê không được đơn phương thay chủ")));
        var n=issue();assertThrows(AppException.class,()->service.requestTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Review(1,UnilateralTerminationNotice.Option.ROLLOVER,null,"Sai vai trò cần bị chặn")));
        assertThrows(AppException.class,()->service.requestTerminationReview("invoice",n.id(),"tenant",new UnilateralTerminationPayloads.Review(99,UnilateralTerminationNotice.Option.ROLLOVER,null,"Phiên bản cũ cần bị chặn")));
        assertThrows(AppException.class,()->service.acceptMutualTermination("invoice","tenant",new AcceptTerminationRequest(true,true,true)));
        assertThrows(AppException.class,()->service.declineMutualTermination("invoice","tenant"));
        assertThrows(AppException.class,()->service.withdrawMutualTermination("invoice","owner"));
        assertThrows(AppException.class,()->service.completeMutualTermination("invoice","owner",new CompleteTerminationRequest(true,true)));
        assertThrows(AppException.class,()->service.forceTerminationAfterDecline("invoice","owner",new ForceTerminationRequest(true,true,true,true)));
        assertEquals(UnilateralTerminationNotice.Status.NOTICE,current().unilateralTermination().status());assertNull(contract.getTerminationAcceptedAt());
    }
    @Test void invalidReviewOrUnavailableRolloverDoesNotMutateOrExtendTheNotice() {
        var n=issue();var due=n.respondBy();
        for(int days:new int[]{0,31})assertThrows(AppException.class,()->service.requestTerminationReview("invoice",n.id(),"tenant",new UnilateralTerminationPayloads.Review(1,UnilateralTerminationNotice.Option.PAYMENT_EXTENSION,days,"Số ngày không hợp lệ cần chặn")));
        when(invoices.findByContractIdAndPeriodIndex("contract",1)).thenReturn(Optional.of(MonthlyInvoice.builder().status(MonthlyInvoiceStatus.UNPAID).build()));
        assertThrows(AppException.class,()->review(n,UnilateralTerminationNotice.Option.ROLLOVER));
        assertEquals(due,current().unilateralTermination().respondBy());assertEquals(1,current().unilateralTermination().version());
        var requested=review(n,UnilateralTerminationNotice.Option.PAYMENT_EXTENSION);
        assertThrows(AppException.class,()->review(n,UnilateralTerminationNotice.Option.PAYMENT_EXTENSION));assertEquals(2,requested.version());
    }
    @Test void approvalRechecksWhetherTheNextInvoiceHasAlreadyBeenIssued() {
        var n=review(issue(),UnilateralTerminationNotice.Option.ROLLOVER);
        when(invoices.findByContractIdAndPeriodIndex("contract",1)).thenReturn(Optional.of(MonthlyInvoice.builder().status(MonthlyInvoiceStatus.UNPAID).build()));
        assertThrows(AppException.class,()->service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),true,"Đồng ý nhưng kỳ sau đã phát hành")));
        assertNull(contract.getTerminationCancelledAt());assertNull(invoice.getDeferredAt());assertEquals(UnilateralTerminationNotice.Status.REVIEW_REQUESTED,current().unilateralTermination().status());
    }
    @Test void withdrawalKeepsTheOriginalHistoryAndCannotAffectANewerNotice() {
        var old=issue();service.withdrawUnilateralTermination("invoice",old.id(),"owner",new UnilateralTerminationPayloads.Version(1));
        var fresh=issue();assertNotEquals(old.id(),fresh.id());assertTrue(contract.getUnilateralTerminationSnapshot().contains("WITHDRAWN"));
        assertThrows(AppException.class,()->service.withdrawUnilateralTermination("invoice",old.id(),"owner",new UnilateralTerminationPayloads.Version(2)));
    }
    @Test void zeroDepositHandoverOccursOnlyAfterEndAndDoesNotRecordZeroRetention() {
        var initial=issue();assertThrows(AppException.class,()->service.completeUnilateralHandover("invoice",initial.id(),"owner",new UnilateralTerminationPayloads.Handover(1,true,true)));
        clock("2026-11-12T10:00:00");var n=current().unilateralTermination();var endedAt=n.endedAt();var version=n.version();var noticeId=n.id();
        assertThrows(AppException.class,()->service.completeUnilateralHandover("invoice",noticeId,"tenant",new UnilateralTerminationPayloads.Handover(version,true,true)));
        service.completeUnilateralHandover("invoice",noticeId,"owner",new UnilateralTerminationPayloads.Handover(version,true,true));
        assertEquals(endedAt,contract.getTerminationCompletedAt());assertEquals(DepositStatus.HELD,deposit.getStatus());assertNull(deposit.getRetainedAt());assertEquals(0,deposit.getRetainedAmount().signum());
        assertNotNull(current().unilateralTermination().handoverAt());verify(listings,times(1)).releaseRentedAfterTermination("listing","owner");verify(parking,times(1)).releaseReservationsForContract("contract");
        assertThrows(AppException.class,()->service.completeUnilateralHandover("invoice",noticeId,"owner",new UnilateralTerminationPayloads.Handover(version,true,true)));
        verify(terminationNotices).tenant(contract,com.hs.contract.notification.TerminationNotificationWriter.Event.LEASE_RETURN_COMPLETED);
        verify(terminationNotices,times(1)).both(contract,com.hs.contract.notification.TerminationNotificationWriter.Event.TERMINATION_ENDED);
    }
    @Test void receivedDepositIsRetainedOnlyWhenActualHandoverIsConfirmed() {
        var amount=new BigDecimal("20000000");deposit.setOriginalAmount(amount);deposit.setHeldAmount(amount);deposit.setRefundableAmount(amount);
        revision.setFinancialSnapshot("{\"depositAmountValue\":20000000}");issue();clock("2026-11-12T10:00:00");var n=current().unilateralTermination();
        assertEquals(DepositStatus.HELD,deposit.getStatus());assertEquals(amount,deposit.getHeldAmount());verifyNoInteractions(listings,parking);
        clock("2026-11-15T10:00:00");service.completeUnilateralHandover("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Handover(n.version(),true,true));
        assertEquals(amount,deposit.getRetainedAmount());assertEquals(DepositStatus.RETAINED_BY_LANDLORD,deposit.getStatus());assertEquals(0,deposit.getHeldAmount().signum());
    }
    @Test void schedulerEndsAnUnansweredNoticeWithoutAnyUserOpeningTheInvoice() {
        issue();when(contracts.findLifecycleIdsAfter(anyCollection(),isNull(),any())).thenReturn(List.of("contract"));
        clock("2026-11-12T10:00:00");service.scheduledSync();service.scheduledSync();
        assertEquals(ContractStatus.TERMINATED,contract.getStatus());assertEquals(UnilateralTerminationNotice.Status.ENDED,current().unilateralTermination().status());
        verifyNoInteractions(listings,parking);
    }
    @Test void schedulerCursorDoesNotSkipContractsWhenEarlierRowsLeaveTheActiveSet() {
        var active=new TreeSet<>(List.of("a","b","c"));var processed=new ArrayList<String>();
        when(contracts.findLifecycleIdsAfter(anyCollection(),any(),any())).thenAnswer(call->{String after=call.getArgument(1);
            return active.stream().filter(id->after==null||id.compareTo(after)>0).limit(2).toList();});
        var scheduler=spy(service);doAnswer(call->{String id=call.getArgument(0);processed.add(id);active.remove(id);return null;}).when(scheduler).syncContract(anyString());
        scheduler.scheduledSync();assertEquals(List.of("a","b","c"),processed);assertTrue(active.isEmpty());
    }
    @Test void reducingTheCapDoesNotRewriteAHistoricalFeeAlreadyRecordedAboveFiveDays() {
        invoice.setLateFeeAmount(new BigDecimal("700000"));invoice.setTotalAmount(new BigDecimal("1700000"));
        assertEquals(new BigDecimal("700000"),current().lateFeeAmount());verify(payments,never()).increaseMonthlyPayment(anyString(),any(),anyString());
    }
    @Test void cancellingFutureDraftsDoesNotPresentThemAsUnpaidOrCreateNewDebt() {
        var future=MonthlyInvoice.builder().id("future").contractId("contract").periodIndex(1).periodStart(LocalDate.of(2026,11,1))
            .periodEndExclusive(LocalDate.of(2026,12,1)).status(MonthlyInvoiceStatus.DRAFT).totalAmount(BigDecimal.ZERO).build();
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of(future,invoice));
        when(invoices.findByContractIdAndPeriodIndex("contract",1)).thenReturn(Optional.of(future));
        issue();clock("2026-11-12T10:00:00");var result=service.list("contract","tenant");
        var cancelled=result.stream().filter(b->"future".equals(b.id())).findFirst().orElseThrow();
        assertEquals(MonthlyInvoiceStatus.CANCELLED,cancelled.status());assertEquals("CANCELLED",cancelled.workflowState());
        assertFalse(cancelled.canResolveOverdue());assertEquals(0,cancelled.totalAmount().signum());assertNull(cancelled.paymentRequestId());
        verify(payments,never()).createMonthlyPayment(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any());
    }
    @Test void movingTheBusinessClockBehindAnExistingDecisionCannotRewriteItsHistory() {
        var n=issue();clock("2026-11-11T09:00:00");
        assertThrows(AppException.class,()->service.withdrawUnilateralTermination("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Version(1)));
        assertNull(contract.getTerminationCancelledAt());clock("2026-11-11T10:00:00");
        var requested=review(n,UnilateralTerminationNotice.Option.PAYMENT_EXTENSION);clock("2026-11-11T09:59:59");
        assertThrows(AppException.class,()->service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(requested.version(),false,"Không ghi quyết định trước thời điểm khiếu nại")));
        assertEquals(ContractStatus.ACTIVE,contract.getStatus());assertNull(contract.getTerminationCompletedAt());
    }
    static String k(String type,String who){return "INVOICE_"+type+":"+who+":invoice";}
    @Test void noticeReviewWithdrawalAndAutomaticEndAddNoTerminationNoticesOnlyTheFeeIncreaseTheyTrigger() {
        var n=review(issue(),UnilateralTerminationNotice.Option.PAYMENT_EXTENSION);
        service.withdrawUnilateralTermination("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Version(n.version()));
        issue();clock("2026-11-12T10:00:00");current();current();
        assertEquals(ContractStatus.TERMINATED,contract.getStatus());
        assertEquals(List.of(k("AMOUNT_UPDATED","tenant")),outbox.keys()); // Phase 6 owns notice/review/withdraw/end
    }
    @Test void rejectedReviewEndsContractWithoutAnyTerminationNotice() {
        var n=review(issue(),UnilateralTerminationNotice.Option.ROLLOVER);clock("2026-11-11T11:00:00");
        service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),false,"Không đồng ý cho thanh toán trễ nữa"));
        assertEquals(List.of(k("AMOUNT_UPDATED","tenant")),outbox.keys());
    }
    @ParameterizedTest @EnumSource(UnilateralTerminationNotice.Option.class)
    void approvedReviewEmitsOnlyTheFinancialChildOutcomeAndNeverTheTerminationDecisionItself(UnilateralTerminationNotice.Option option) {
        var n=review(issue(),option);clock("2026-11-13T10:00:00");outbox.clear();
        service.decideTerminationReview("invoice",n.id(),"owner",new UnilateralTerminationPayloads.Decision(n.version(),true,"Đồng ý phương án người thuê đã xin"));
        assertEquals(List.of(k(option==UnilateralTerminationNotice.Option.ROLLOVER?"DEFERRED":"EXTENSION_APPROVED","tenant")),outbox.keys());
    }
}
