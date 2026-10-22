package com.hs.contract.service;

import com.hs.contract.service.impl.ContractServiceImpl;
import com.hs.listing.model.RentalRequest;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.event.OnboardingReceiptConfirmed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomaticContractDraftsTest {
    RentalRequest rental=RentalRequest.builder().id("r").renterId("tenant").ownerId("owner")
        .monthlyRentPrice(new BigDecimal("100")).depositAmount(new BigDecimal("100")).build();
    PaymentRequest payment(PaymentType type, String amount) {
        return PaymentRequest.builder().rentalRequestId("r").payerId("tenant").payeeId("owner")
            .type(type).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED)
            .totalAmount(new BigDecimal(amount)).build();
    }
    @Test void separateAndCombinedReceiptsNeedAllFunds() {
        var rent=payment(PaymentType.INITIAL,"100");var deposit=payment(PaymentType.RESERVATION_DEPOSIT,"100");
        assertFalse(ContractDraftFunding.ready(rental,null,deposit));
        assertFalse(ContractDraftFunding.ready(rental,rent,null));
        assertTrue(ContractDraftFunding.ready(rental,rent,deposit));
        assertTrue(ContractDraftFunding.ready(rental,payment(PaymentType.INITIAL,"200"),null));
        rent.setTotalAmount(new BigDecimal("99"));assertFalse(ContractDraftFunding.ready(rental,rent,deposit));
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"CONFIRMED"},mode=EnumSource.Mode.EXCLUDE)
    void anythingExceptConfirmedCannotCreate(PaymentStatus status) {
        var rent=payment(PaymentType.INITIAL,"100");var deposit=payment(PaymentType.RESERVATION_DEPOSIT,"100");
        rent.setStatus(status);assertFalse(ContractDraftFunding.ready(rental,rent,deposit));
        rent.setStatus(PaymentStatus.CONFIRMED);deposit.setStatus(status);assertFalse(ContractDraftFunding.ready(rental,rent,deposit));
    }
    @Test void wrongPartyDirectionOrRentalAndChangedAmountsFailClosed() {
        var rent=payment(PaymentType.INITIAL,"100");var deposit=payment(PaymentType.RESERVATION_DEPOSIT,"100");
        rent.setPayeeId("other");assertFalse(ContractDraftFunding.ready(rental,rent,deposit));rent.setPayeeId("owner");
        deposit.setRentalRequestId("other");assertFalse(ContractDraftFunding.ready(rental,rent,deposit));deposit.setRentalRequestId("r");
        rent.setDirection(PaymentDirection.LANDLORD_TO_TENANT);assertFalse(ContractDraftFunding.ready(rental,rent,deposit));
        rent.setDirection(PaymentDirection.TENANT_TO_LANDLORD);rental.setEffectiveMonthlyRent(new BigDecimal("120"));
        assertFalse(ContractDraftFunding.ready(rental,rent,deposit));rent.setTotalAmount(new BigDecimal("120"));
        assertTrue(ContractDraftFunding.ready(rental,rent,deposit));
    }
    @Test void waivedDepositDoesNotRequireATransfer() {
        rental.setDepositAmount(BigDecimal.ZERO);
        assertTrue(ContractDraftFunding.ready(rental,payment(PaymentType.INITIAL,"100"),payment(PaymentType.RESERVATION_DEPOSIT,"0")));
    }
    @Test @SuppressWarnings("unchecked") void eventAndSweepRetryWithoutLeakingCreationFailureIntoMoneyConfirmation() {
        var provider=mock(ObjectProvider.class);var contracts=mock(ContractServiceImpl.class);
        when(provider.getObject()).thenReturn(contracts);
        when(contracts.createDraftAutomatically("r")).thenThrow(new IllegalStateException("template unavailable")).thenReturn(null);
        var automation=new AutomaticContractDrafts(provider,Runnable::run);
        assertDoesNotThrow(()->automation.receiptConfirmed(new OnboardingReceiptConfirmed("r")));
        assertDoesNotThrow(()->automation.tryCreate("r"));verify(contracts,times(2)).createDraftAutomatically("r");
    }
    @Test @SuppressWarnings("unchecked") void createsThenExportsCommittedDraftAndRetriesExportWithoutCreatingAnotherRoot() {
        var provider=mock(ObjectProvider.class);var contracts=mock(ContractServiceImpl.class);
        when(provider.getObject()).thenReturn(contracts);
        when(contracts.createDraftAutomatically("r")).thenReturn(com.hs.contract.dto.response.ContractResponse.builder()
            .id("draft").status(com.hs.contract.model.constant.ContractStatus.DRAFT).build());
        when(contracts.prepareDraftPreviewAutomatically("r","draft")).thenThrow(new IllegalStateException("conversion unavailable")).thenReturn(null);
        var automation=new AutomaticContractDrafts(provider,Runnable::run);
        assertDoesNotThrow(()->automation.tryCreate("r"));
        assertDoesNotThrow(()->automation.tryCreate("r"));
        var order=inOrder(contracts);
        order.verify(contracts).createDraftAutomatically("r");order.verify(contracts).prepareDraftPreviewAutomatically("r","draft");
        order.verify(contracts).createDraftAutomatically("r");order.verify(contracts).prepareDraftPreviewAutomatically("r","draft");
    }
    @Test @SuppressWarnings("unchecked") void receiptDispatchIsNonBlockingAndRejectedDispatchCannotUndoPayment() {
        var provider=mock(ObjectProvider.class);var contracts=mock(ContractServiceImpl.class);
        when(provider.getObject()).thenReturn(contracts);
        var tasks=new java.util.ArrayList<Runnable>();
        var automation=new AutomaticContractDrafts(provider,tasks::add);
        automation.receiptConfirmed(new OnboardingReceiptConfirmed("r"));
        verifyNoInteractions(contracts);assertEquals(1,tasks.size());
        tasks.getFirst().run();verify(contracts).createDraftAutomatically("r");
        var rejected=new AutomaticContractDrafts(provider,task->{throw new org.springframework.core.task.TaskRejectedException("busy");});
        assertDoesNotThrow(()->rejected.receiptConfirmed(new OnboardingReceiptConfirmed("r")));
    }
    @Test @SuppressWarnings("unchecked") void signedOrUnavailableDraftIsNeverRendered() {
        var provider=mock(ObjectProvider.class);var contracts=mock(ContractServiceImpl.class);
        when(provider.getObject()).thenReturn(contracts);
        when(contracts.createDraftAutomatically("r")).thenReturn(null,com.hs.contract.dto.response.ContractResponse.builder()
            .id("signed").status(com.hs.contract.model.constant.ContractStatus.ACTIVE).build());
        var automation=new AutomaticContractDrafts(provider,Runnable::run);
        automation.tryCreate("r");automation.tryCreate("r");
        verify(contracts,never()).prepareDraftPreviewAutomatically(any(),any());
    }
}
