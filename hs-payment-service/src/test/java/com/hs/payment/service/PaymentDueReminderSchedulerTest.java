package com.hs.payment.service;

import com.hs.common.time.BillingTime;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.notification.PaymentNotificationWriter;
import com.hs.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentDueReminderSchedulerTest {
    final PaymentRequestRepository payments=mock(PaymentRequestRepository.class);
    final PaymentNotificationWriter notices=mock(PaymentNotificationWriter.class);
    final BillingTime time=mock(BillingTime.class);
    final PaymentDueReminderScheduler scheduler=new PaymentDueReminderScheduler(payments,notices,time,mock(org.springframework.transaction.PlatformTransactionManager.class));
    final Instant realNow=Instant.parse("2026-11-17T05:00:00Z"),billingNow=Instant.parse("2026-12-17T05:00:00Z");
    PaymentRequest payment(PaymentType type,PaymentStatus status) {
        var p=PaymentRequest.builder().id("payment").payerId("payer").payeeId("payee").type(type).status(status).totalAmount(BigDecimal.TEN)
            .dueAt((type==PaymentType.UTILITY?realNow:billingNow).plus(Duration.ofHours(2))).build();
        when(payments.findByIdForUpdate("payment")).thenReturn(Optional.of(p));return p;
    }
    @Test void dueObligationNotifiesOnlyPayerOnceWithoutChangingFinancialState() {
        var p=payment(PaymentType.DEPOSIT_ADJUSTMENT,PaymentStatus.AWAITING_TRANSFER);
        scheduler.remindLocked("payment",realNow,billingNow);scheduler.remindLocked("payment",realNow,billingNow);
        verify(notices).enqueue(p,PaymentNotificationWriter.Event.PAYMENT_DUE_SOON,"payer");
        assertEquals(PaymentStatus.AWAITING_TRANSFER,p.getStatus());assertEquals(BigDecimal.TEN,p.getTotalAmount());
        assertEquals(p.getDueAt(),p.getReminderForDueAt());
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"CONFIRMED","CANCELLED","EXPIRED","TRANSFER_REPORTED","DISPUTED","OVERDUE"})
    void settledReportedDisputedOrOverdueRowsAreSilent(PaymentStatus status) {
        payment(PaymentType.INITIAL_PAYMENT_REFUND,status);scheduler.remindLocked("payment",realNow,billingNow);verifyNoInteractions(notices);
    }
    @Test void monthlyBillsUseTheirInvoiceReminderAndFutureOpeningAndNoTransferAreSilent() {
        var p=payment(PaymentType.MONTHLY_RENT,PaymentStatus.AWAITING_TRANSFER);scheduler.remindLocked("payment",realNow,billingNow);
        p.setType(PaymentType.INITIAL);p.setOpensAt(billingNow.plusSeconds(1));scheduler.remindLocked("payment",realNow,billingNow);
        p.setOpensAt(null);p.setTotalAmount(BigDecimal.ZERO);scheduler.remindLocked("payment",realNow,billingNow);
        p.setTotalAmount(BigDecimal.TEN);p.setActive(false);scheduler.remindLocked("payment",realNow,billingNow);
        verifyNoInteractions(notices);
    }
    @Test void changedDueDateGetsItsOwnReminderOnlyWhenWithinTheWindow() {
        var p=payment(PaymentType.INITIAL_PAYMENT_REFUND,PaymentStatus.REJECTED);p.setReminderForDueAt(p.getDueAt());
        p.setDueAt(billingNow.plus(Duration.ofDays(3)));scheduler.remindLocked("payment",realNow,billingNow);verifyNoInteractions(notices);
        scheduler.remindLocked("payment",realNow,billingNow.plus(Duration.ofDays(2)));
        verify(notices).enqueue(p,PaymentNotificationWriter.Event.PAYMENT_DUE_SOON,"payer");assertEquals(p.getDueAt(),p.getReminderForDueAt());
    }
    @Test void clockMatchesCentralPaymentTimePolicyAndElapsedObligationsNeverGetAnAdvanceNotice() {
        var p=payment(PaymentType.UTILITY,PaymentStatus.AWAITING_TRANSFER);scheduler.remindLocked("payment",realNow,billingNow);
        verify(notices).enqueue(p,PaymentNotificationWriter.Event.PAYMENT_DUE_SOON,"payer");clearInvocations(notices);
        p.setReminderForDueAt(null);p.setType(PaymentType.DEPOSIT_REFUND);p.setDueAt(billingNow);scheduler.remindLocked("payment",realNow,billingNow);
        verifyNoInteractions(notices);
        for(PaymentType type:PaymentType.values()) {
            p.setType(type);p.setContractId(null);assertEquals(PaymentTimePolicy.BILLING_TYPES.contains(type),PaymentTimePolicy.usesBillingTime(p));
        }
        p.setType(PaymentType.OTHER_CHARGE);p.setContractId("contract");assertTrue(PaymentTimePolicy.usesBillingTime(p));
    }
    @Test void schedulerUsesBothRealAndBillingTimeInOneBoundedCandidateQuery() {
        when(time.now()).thenReturn(billingNow);when(payments.findDueSoonIds(any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(List.of());
        scheduler.remindDueSoon();verify(payments).findDueSoonIds(any(),eq(PaymentTimePolicy.BILLING_TYPES),eq(billingNow),
            eq(billingNow.plus(Duration.ofHours(24))),any(),any(),isNull(),argThat(p->p.getPageSize()==100&&p.getPageNumber()==0));
    }
    @Test void fullPageIsFollowedByTheNextKeysetPageAndAFailingRowDoesNotStarveTheRest() {
        var first=new ArrayList<String>();for(int i=0;i<100;i++)first.add(String.format("p%03d",i));
        when(time.now()).thenReturn(billingNow);
        when(payments.findDueSoonIds(any(),any(),any(),any(),any(),any(),isNull(),any())).thenReturn(first);
        when(payments.findDueSoonIds(any(),any(),any(),any(),any(),any(),eq("p099"),any())).thenReturn(List.of("p100"));
        when(payments.findByIdForUpdate(anyString())).thenThrow(new IllegalStateException("poison"));
        scheduler.remindDueSoon();
        verify(payments).findByIdForUpdate("p000");verify(payments).findByIdForUpdate("p100"); // 101st id reached despite every row failing
    }
}
