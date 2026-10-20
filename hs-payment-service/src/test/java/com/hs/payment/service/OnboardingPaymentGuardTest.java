package com.hs.payment.service;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingPaymentGuardTest {
    @Test void exactDeadlineAndOpeningAreEnforcedWithoutScheduler() {
        var time=mock(BillingTime.class);var rentals=mock(RentalRequestRepository.class);
        var now=Instant.parse("2026-10-21T03:00:00Z");when(time.now()).thenReturn(now);
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(RentalRequest.builder().status(RentalRequestStatus.ACCEPTED).build()));
        var guard=new OnboardingPaymentGuard(time,rentals);
        var p=PaymentRequest.builder().rentalRequestId("rental").type(PaymentType.INITIAL).opensAt(now.plusSeconds(1)).dueAt(now.plusSeconds(60)).build();
        assertThrows(AppException.class,()->guard.validate(p));
        p.setOpensAt(now);assertDoesNotThrow(()->guard.validate(p));
        p.setDueAt(now);assertThrows(AppException.class,()->guard.validate(p));
    }
    @Test void expiredRentalCannotReceiveNewProofEvenIfPaymentStatusIsStale() {
        var time=mock(BillingTime.class);var rentals=mock(RentalRequestRepository.class);
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(RentalRequest.builder().status(RentalRequestStatus.EXPIRED).build()));
        var p=PaymentRequest.builder().rentalRequestId("rental").type(PaymentType.RESERVATION_DEPOSIT).status(PaymentStatus.AWAITING_TRANSFER).build();
        assertThrows(AppException.class,()->new OnboardingPaymentGuard(time,rentals).validate(p));
    }
    @Test void overdueInvoiceOrRefundDoesNotBecomeAnExpiredHold() {
        var time=mock(BillingTime.class);var rentals=mock(RentalRequestRepository.class);var guard=new OnboardingPaymentGuard(time,rentals);
        for(var type:new PaymentType[]{PaymentType.MONTHLY_RENT,PaymentType.INITIAL_PAYMENT_REFUND})
            assertDoesNotThrow(()->guard.validate(PaymentRequest.builder().type(type).dueAt(Instant.EPOCH).build()));
        verifyNoInteractions(rentals);
    }
}
