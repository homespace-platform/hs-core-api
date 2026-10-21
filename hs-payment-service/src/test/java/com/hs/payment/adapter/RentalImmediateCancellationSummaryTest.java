package com.hs.payment.adapter;

import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RentalImmediateCancellationSummaryTest {
    @Test void singleAndBatchSummaryConsiderDepositEvenWhenFirstRentIsUnpaid() {
        var repository=mock(PaymentRequestRepository.class);
        var adapter=new RentalPaymentLifecycleAdapter(mock(PaymentRequestService.class),repository);
        var deposit=PaymentRequest.builder().id("deposit").rentalRequestId("rental").type(PaymentType.RESERVATION_DEPOSIT)
            .totalAmount(new BigDecimal("10000000")).status(PaymentStatus.CONFIRMED).build();
        var rent=PaymentRequest.builder().id("rent").rentalRequestId("rental").type(PaymentType.INITIAL)
            .totalAmount(new BigDecimal("5000000")).status(PaymentStatus.AWAITING_TRANSFER).build();
        when(repository.findByRentalRequestIdAndType("rental",PaymentType.INITIAL)).thenReturn(Optional.of(rent));
        when(repository.findByRentalRequestIdAndTypeIn("rental",List.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT))).thenReturn(List.of(rent,deposit));
        when(repository.findByRentalRequestIdInAndType(List.of("rental"),PaymentType.RESERVATION_DEPOSIT)).thenReturn(List.of(deposit));
        when(repository.findByRentalRequestIdInAndType(List.of("rental"),PaymentType.INITIAL)).thenReturn(List.of(rent));
        assertFalse(adapter.getInitialPaymentSummary("rental").canCancelImmediately());
        assertFalse(adapter.getInitialPaymentSummaries(List.of("rental")).get("rental").canCancelImmediately());
        deposit.setStatus(PaymentStatus.AWAITING_TRANSFER);
        assertTrue(adapter.getInitialPaymentSummary("rental").canCancelImmediately());
        assertTrue(adapter.getInitialPaymentSummaries(List.of("rental")).get("rental").canCancelImmediately());
        deposit.setPayerReportedAt(Instant.now());
        assertFalse(adapter.getInitialPaymentSummaries(List.of("rental")).get("rental").canCancelImmediately());
    }
}
