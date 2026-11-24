package com.hs.payment.service;

import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;

class ReceiptAutoConfirmSchedulerTest {
    final PaymentRequestRepository payments=mock(PaymentRequestRepository.class);
    final PaymentRequestService service=mock(PaymentRequestService.class);
    final ReceiptAutoConfirmScheduler scheduler=new ReceiptAutoConfirmScheduler(payments,service,12);
    final Duration lead=Duration.ofHours(12);

    @Test void warnsOnlyRowsNotAutoConfirmedAndAPoisonRowDoesNotStopTheOthers() {
        when(service.businessNow()).thenReturn(Instant.now());
        when(payments.findReportedIdsDueByAfter(eq(PaymentStatus.TRANSFER_REPORTED),eq(PaymentRequestService.AUTO_CONFIRMABLE_REFUNDS),any(),isNull(),any()))
            .thenReturn(List.of("confirmed","poison","waiting"));
        when(service.autoConfirmOverdueReceipt(eq("confirmed"),any(),eq(lead))).thenReturn(true);
        when(service.autoConfirmOverdueReceipt(eq("poison"),any(),eq(lead))).thenThrow(new IllegalStateException("x"));
        scheduler.confirmOverdueReceipts();
        verify(service,never()).warnReceiptAutoConfirmSoon(eq("confirmed"),any(),any());
        verify(service,never()).warnReceiptAutoConfirmSoon(eq("poison"),any(),any());
        verify(service).warnReceiptAutoConfirmSoon(eq("waiting"),any(),eq(lead));
        verify(service).autoConfirmOverdueReceipt(eq("waiting"),any(),eq(lead));
    }

    @Test void walksEveryPageUsingTheLastIdAsCursor() {
        when(service.businessNow()).thenReturn(Instant.now());
        var first=java.util.stream.IntStream.range(0,200).mapToObj(i->String.format("a%03d",i)).toList();
        when(payments.findReportedIdsDueByAfter(any(),any(),any(),isNull(),any())).thenReturn(first);
        when(payments.findReportedIdsDueByAfter(any(),any(),any(),eq("a199"),any())).thenReturn(List.of("b1"));
        scheduler.confirmOverdueReceipts();
        verify(service).autoConfirmOverdueReceipt(eq("b1"),any(),eq(lead));
        verify(service,times(201)).autoConfirmOverdueReceipt(anyString(),any(),eq(lead));
    }
}
