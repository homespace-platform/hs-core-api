package com.hs.payment.adapter;

import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RentalReceiptSummaryTest {
    private PaymentRequest payment(String id,String rental,PaymentType type,PaymentStatus status) {
        return PaymentRequest.builder().id(id).rentalRequestId(rental).type(type).status(status)
            .totalAmount(new BigDecimal("24680000")).transferReference("REF-"+id)
            .payerReportedAt(status==PaymentStatus.TRANSFER_REPORTED?Instant.parse("2026-10-31T03:00:00Z"):null)
            .confirmationDueAt(Instant.parse("2026-11-01T03:00:00Z")).build();
    }
    @Test void listAndDetailIdentifyTheReportedFirstMonthNotTheAlreadyConfirmedDeposit() {
        var repository=mock(PaymentRequestRepository.class);
        var adapter=new RentalPaymentLifecycleAdapter(mock(PaymentRequestService.class),repository);
        var deposit=payment("deposit","rental",PaymentType.RESERVATION_DEPOSIT,PaymentStatus.CONFIRMED);
        var rent=payment("rent","rental",PaymentType.INITIAL,PaymentStatus.TRANSFER_REPORTED);
        when(repository.findByRentalRequestIdAndType("rental",PaymentType.INITIAL)).thenReturn(Optional.of(rent));
        when(repository.findByRentalRequestIdAndTypeIn("rental",List.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT))).thenReturn(List.of(deposit,rent));
        when(repository.findByRentalRequestIdInAndType(List.of("rental"),PaymentType.RESERVATION_DEPOSIT)).thenReturn(List.of(deposit));
        when(repository.findByRentalRequestIdInAndType(List.of("rental"),PaymentType.INITIAL)).thenReturn(List.of(rent));
        var detail=adapter.getInitialPaymentSummary("rental");
        var list=adapter.getInitialPaymentSummaries(List.of("rental")).get("rental");
        assertEquals(detail,list);assertEquals("rent",list.id());assertEquals("INITIAL",list.type());
        assertEquals("Tiền thuê tháng đầu",list.displayName());assertEquals("TRANSFER_REPORTED",list.status());
        assertEquals(rent.getTotalAmount(),list.totalAmount());assertEquals(rent.getTransferReference(),list.transferReference());
        assertEquals(rent.getConfirmationDueAt(),list.confirmationDueAt());assertFalse(list.canCancelImmediately());
    }
    @Test void batchLabelsRemainCorrectForDepositOnlyCombinedAndSeparateRentWithoutPerRowQueries() {
        var repository=mock(PaymentRequestRepository.class);
        var adapter=new RentalPaymentLifecycleAdapter(mock(PaymentRequestService.class),repository);
        var ids=List.of("deposit-only","combined","split");
        when(repository.findByRentalRequestIdInAndType(ids,PaymentType.RESERVATION_DEPOSIT)).thenReturn(List.of(
            payment("d1","deposit-only",PaymentType.RESERVATION_DEPOSIT,PaymentStatus.TRANSFER_REPORTED),
            payment("d2","split",PaymentType.RESERVATION_DEPOSIT,PaymentStatus.CONFIRMED)));
        when(repository.findByRentalRequestIdInAndType(ids,PaymentType.INITIAL)).thenReturn(List.of(
            payment("r1","combined",PaymentType.INITIAL,PaymentStatus.TRANSFER_REPORTED),
            payment("r2","split",PaymentType.INITIAL,PaymentStatus.TRANSFER_REPORTED)));
        var result=adapter.getInitialPaymentSummaries(ids);
        assertEquals("Tiền cọc giữ chỗ",result.get("deposit-only").displayName());
        assertEquals("Khoản thanh toán ban đầu",result.get("combined").displayName());
        assertEquals("Tiền thuê tháng đầu",result.get("split").displayName());
        verify(repository).findByRentalRequestIdInAndType(ids,PaymentType.RESERVATION_DEPOSIT);
        verify(repository).findByRentalRequestIdInAndType(ids,PaymentType.INITIAL);
        verifyNoMoreInteractions(repository);
    }
}
