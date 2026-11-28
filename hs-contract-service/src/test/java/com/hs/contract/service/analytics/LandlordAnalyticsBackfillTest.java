package com.hs.contract.service.analytics;
import com.hs.payment.receipt.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LandlordAnalyticsBackfillTest {
    @Test void rowFailureIsCountedCursorAdvancesAndCancelledRaceIsNotCaptured() {
        var payments=mock(PaymentRequestRepository.class);var receipts=mock(PaymentReceiptRepository.class);
        var capture=mock(PaymentReceiptService.class);var tm=mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(payments.findConfirmedWithoutReceipt(eq(""),any())).thenReturn(List.of("bad","cancelled"));
        when(payments.findConfirmedWithoutReceipt(eq("cancelled"),any())).thenReturn(List.of());
        when(payments.findByIdForUpdate("bad")).thenThrow(new IllegalStateException("test failure"));
        when(payments.findByIdForUpdate("cancelled")).thenReturn(Optional.of(PaymentRequest.builder().status(PaymentStatus.CANCELLED).build()));
        var result=new PaymentReceiptBackfill(payments,receipts,capture,tm).run();
        assertEquals(1,result.failed());assertEquals(0,result.created());
        verifyNoInteractions(capture);verify(payments).findConfirmedWithoutReceipt(eq("cancelled"),any());
    }
    @Test void enumerationFailureIsNotReportedAsSuccessfulBackfill() {
        var payments=mock(PaymentRequestRepository.class);
        when(payments.findConfirmedWithoutReceipt(anyString(),any())).thenThrow(new IllegalStateException("test failure"));
        var result=new PaymentReceiptBackfill(payments,mock(PaymentReceiptRepository.class),mock(PaymentReceiptService.class),mock(PlatformTransactionManager.class)).run();
        assertEquals(1,result.failed());
    }
}
