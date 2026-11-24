package com.hs.payment.service;

import com.hs.payment.model.constant.PaymentDirection;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.mockito.Mockito.*;

class RefundAutoConfirmSchedulerTest {
    final PaymentRequestRepository payments=mock(PaymentRequestRepository.class);
    final PaymentRequestService service=mock(PaymentRequestService.class);
    final RefundAutoConfirmScheduler scheduler=new RefundAutoConfirmScheduler(payments,service,72,24);
    final Duration window=Duration.ofHours(72),lead=Duration.ofHours(24);
    { when(service.businessNow()).thenReturn(java.time.Instant.now()); }

    @Test void warnsOnlyRefundsThatWereNotAutoConfirmedAndSurvivesAFailingRow() {
        when(payments.findReportedRefundIdsAfter(eq(PaymentStatus.TRANSFER_REPORTED),eq(PaymentRequestService.AUTO_CONFIRMABLE_REFUNDS),
            eq(PaymentDirection.LANDLORD_TO_TENANT),any(),isNull(),any())).thenReturn(List.of("confirmed","waiting","broken"));
        when(service.autoConfirmStaleRefund("confirmed",window,lead)).thenReturn(true);
        when(service.autoConfirmStaleRefund("broken",window,lead)).thenThrow(new IllegalStateException("x"));
        scheduler.confirmStaleRefunds();
        verify(service,never()).warnRefundAutoConfirmSoon(eq("confirmed"),any(),any());
        verify(service).warnRefundAutoConfirmSoon("waiting",window,lead);
        verify(service,never()).warnRefundAutoConfirmSoon(eq("broken"),any(),any());
    }

    @Test void walksEveryPageUsingTheLastIdAsCursor() {
        var first=java.util.stream.IntStream.range(0,200).mapToObj(i->String.format("a%03d",i)).toList();
        when(payments.findReportedRefundIdsAfter(any(),any(),any(),any(),isNull(),any())).thenReturn(first);
        when(payments.findReportedRefundIdsAfter(any(),any(),any(),any(),eq("a199"),any())).thenReturn(List.of("b1"));
        scheduler.confirmStaleRefunds();
        verify(service).autoConfirmStaleRefund("b1",window,lead);
        verify(service,times(201)).autoConfirmStaleRefund(anyString(),eq(window),eq(lead));
    }

    @Test void onlyReportsOlderThanWindowMinusLeadAreScanned() {
        var before=java.time.Instant.now().minus(Duration.ofHours(48));
        scheduler.confirmStaleRefunds();
        var cutoff=org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(payments).findReportedRefundIdsAfter(any(),any(),any(),cutoff.capture(),isNull(),any());
        org.junit.jupiter.api.Assertions.assertFalse(cutoff.getValue().isBefore(before));
        org.junit.jupiter.api.Assertions.assertTrue(cutoff.getValue().isBefore(before.plusSeconds(60)));
    }
}
