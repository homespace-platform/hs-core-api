package com.hs.contract.service;

import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.hs.common.time.BillingTime;
import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.notification.AgreementSigningNotificationWriter;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentRequestRepository;

class AgreementSigningReminderSchedulerTest {
    final ContractRepository contracts = mock(ContractRepository.class);
    final PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
    final RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    final AgreementSigningNotificationWriter notices = mock(AgreementSigningNotificationWriter.class);
    final AgreementSigningReminderScheduler scheduler = new AgreementSigningReminderScheduler(contracts, payments, rentals,
            notices, mock(BillingTime.class), mock(PlatformTransactionManager.class), 24);
    final Instant now = Instant.parse("2026-11-24T03:00:00Z"), soon = now.plus(Duration.ofHours(24));
    final Instant due = now.plus(Duration.ofHours(10));
    final RentalRequest rental = RentalRequest.builder().id("rental").status(RentalRequestStatus.ACCEPTED).build();

    Contract contract(ContractStatus status, Instant tenantDue) {
        var c = Contract.builder().id("contract").rentalRequestId("rental").landlordId("landlord").tenantId("tenant")
                .status(status).tenantSigningDueAt(tenantDue).build();
        when(contracts.findByIdForUpdate("contract")).thenReturn(Optional.of(c));
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        return c;
    }

    @Test void tenantIsRemindedAboutTheExistingDeadlineAndOnlyInsideTheWindow() {
        var c = contract(ContractStatus.TENANT_SIGNATURE_PENDING, due);
        scheduler.remindLocked("contract", true, now, soon);
        verify(notices).dueSoon(c, "tenant", due);
        clearInvocations(notices);
        c.setTenantSigningDueAt(soon.plusSeconds(1)); scheduler.remindLocked("contract", true, now, soon);
        c.setTenantSigningDueAt(now); scheduler.remindLocked("contract", true, now, soon); // already elapsed
        c.setTenantSigningDueAt(null); scheduler.remindLocked("contract", true, now, soon);
        c.setTenantSigningDueAt(due); c.setStatus(ContractStatus.ACTIVE); scheduler.remindLocked("contract", true, now, soon);
        verifyNoInteractions(notices);
    }

    @Test void pausedOrClosedRentalsAndDefaultNoticeDeadlinesStaySilent() {
        contract(ContractStatus.TENANT_SIGNATURE_PENDING, due);
        rental.setCancellationRequestedAt(now); scheduler.remindLocked("contract", true, now, soon);
        rental.setCancellationRequestedAt(null); rental.setResolutionWorkflowId("workflow"); scheduler.remindLocked("contract", true, now, soon);
        rental.setResolutionWorkflowId(null); rental.setDefaultRespondBy(due); scheduler.remindLocked("contract", true, now, soon);
        rental.setDefaultRespondBy(null); rental.setStatus(RentalRequestStatus.CANCELLED_BY_SYSTEM); scheduler.remindLocked("contract", true, now, soon);
        verifyNoInteractions(notices);
    }

    @Test void landlordIsRemindedOnlyBeforeSigningAndOnlyForAConfirmedFirstRentDeadline() {
        var c = contract(ContractStatus.DRAFT, null);
        var rent = PaymentRequest.builder().type(PaymentType.INITIAL).status(PaymentStatus.CONFIRMED).contractDueAt(due).build();
        when(payments.findByRentalRequestIdAndType("rental", PaymentType.INITIAL)).thenReturn(Optional.of(rent));
        scheduler.remindLocked("contract", false, now, soon);
        verify(notices).dueSoon(c, "landlord", due);
        clearInvocations(notices);
        rent.setStatus(PaymentStatus.AWAITING_TRANSFER); scheduler.remindLocked("contract", false, now, soon);
        rent.setStatus(PaymentStatus.CONFIRMED); c.setTenantSigningDueAt(due); scheduler.remindLocked("contract", false, now, soon); // tenant's turn now
        c.setTenantSigningDueAt(null); c.setStatus(ContractStatus.TENANT_SIGNATURE_PENDING); scheduler.remindLocked("contract", false, now, soon);
        verifyNoInteractions(notices);
    }
}
