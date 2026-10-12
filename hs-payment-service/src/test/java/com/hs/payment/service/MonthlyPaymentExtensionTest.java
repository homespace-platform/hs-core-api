package com.hs.payment.service;

import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.repository.PaymentEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyPaymentExtensionTest {
    @Mock PaymentRequestRepository payments;
    @Mock PaymentEventRepository events;
    @InjectMocks PaymentRequestService service;
    @Test void updatesDueAndStatusButPreservesMoneyIdReferenceAndQr() {
        var payment = PaymentRequest.builder().id("payment").invoiceId("invoice").type(PaymentType.MONTHLY_RENT)
                .status(PaymentStatus.OVERDUE).totalAmount(new BigDecimal("1300000"))
                .transferReference("HS-DEMO").qrImageUrl("existing-qr").build();
        var due = Instant.parse("2027-01-27T03:00:00Z");
        when(payments.findByInvoiceIdForUpdate("invoice")).thenReturn(Optional.of(payment));
        service.extendMonthlyDueDate("invoice",due,"landlord");
        assertEquals(due,payment.getDueAt()); assertEquals(PaymentStatus.AWAITING_TRANSFER,payment.getStatus());
        assertEquals(new BigDecimal("1300000"),payment.getTotalAmount());
        assertEquals("HS-DEMO",payment.getTransferReference()); assertEquals("existing-qr",payment.getQrImageUrl());
        verify(payments).save(payment); verify(events).save(any());
        verify(payments,never()).findByInvoiceId(anyString());
    }
    @Test void confirmedReportedOrDisputedPaymentCannotBeExtended() {
        for (var status : new PaymentStatus[]{PaymentStatus.CONFIRMED,PaymentStatus.TRANSFER_REPORTED,PaymentStatus.DISPUTED,PaymentStatus.CANCELLED}) {
            when(payments.findByInvoiceIdForUpdate("invoice")).thenReturn(Optional.of(PaymentRequest.builder()
                    .type(PaymentType.MONTHLY_RENT).status(status).build()));
            assertThrows(IllegalStateException.class,() -> service.extendMonthlyDueDate("invoice",Instant.now(),"landlord"));
        }
        verify(payments,never()).save(any()); verifyNoInteractions(events);
    }
}
