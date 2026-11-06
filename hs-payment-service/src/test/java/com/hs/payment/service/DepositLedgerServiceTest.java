package com.hs.payment.service;

import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DepositLedgerServiceTest {
    final DepositRecordRepository deposits=mock(DepositRecordRepository.class);
    final PaymentRequestRepository payments=mock(PaymentRequestRepository.class);
    final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    final DepositLedgerService service=new DepositLedgerService(deposits,payments,mapper);
    DepositRecord deposit;
    PaymentRequest initial;
    @BeforeEach void setup(){
        deposit=DepositRecord.builder().rentalRequestId("rental").initialPaymentRequestId("initial").landlordId("owner").tenantId("tenant")
            .originalAmount(new BigDecimal("20000000")).heldAmount(new BigDecimal("20000000")).refundableAmount(new BigDecimal("20000000")).status(DepositStatus.HELD).build();
        initial=PaymentRequest.builder().id("initial").rentalRequestId("rental").payerId("tenant").payeeId("owner")
            .type(PaymentType.INITIAL).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("25000000")).build();
        when(deposits.findByRentalRequestId("rental")).thenReturn(Optional.of(deposit));when(payments.findById("initial")).thenReturn(Optional.of(initial));
    }
    PaymentRequest amount(String id,PaymentType type,PaymentDirection direction,String amount,PaymentStatus status){
        boolean refund=direction==PaymentDirection.LANDLORD_TO_TENANT;
        return PaymentRequest.builder().id(id).rentalRequestId("rental").payerId(refund?"owner":"tenant").payeeId(refund?"tenant":"owner")
            .type(type).direction(direction).totalAmount(new BigDecimal(amount)).status(status).build();
    }
    @Test void noRecordDoesNotBecomeZeroDeposit(){assertNull(service.forRental("missing"));}
    @Test void combinedInitialPaymentCountsOnlyDepositAndPreservesUnknownRetainedAmount(){
        var result=service.forRental("rental");assertEquals(new BigDecimal("20000000"),result.confirmedReceivedAmount());assertNull(result.retainedAmount());
        assertEquals(0,result.confirmedRefundedAmount().signum());
    }
    @Test void reportedInitialTransferCannotEstablishReceipt(){initial.setStatus(PaymentStatus.TRANSFER_REPORTED);assertNull(service.forRental("rental").confirmedReceivedAmount());}
    @Test void confirmedAdjustmentsAndFinalRefundBalanceWithoutCountingOtherOrUnconfirmedTransfers(){
        deposit.setStatus(DepositStatus.REFUNDED);deposit.setRefundPaymentRequestId("final");deposit.setHeldAmount(BigDecimal.ZERO);deposit.setRetainedAmount(BigDecimal.ZERO);
        var topUp=amount("top",PaymentType.DEPOSIT_ADJUSTMENT,PaymentDirection.TENANT_TO_LANDLORD,"3000000",PaymentStatus.CONFIRMED);
        var partial=amount("partial",PaymentType.DEPOSIT_ADJUSTMENT,PaymentDirection.LANDLORD_TO_TENANT,"2000000",PaymentStatus.CONFIRMED);
        var last=amount("final",PaymentType.DEPOSIT_REFUND,PaymentDirection.LANDLORD_TO_TENANT,"21000000",PaymentStatus.CONFIRMED);
        var wrong=amount("other",PaymentType.DEPOSIT_REFUND,PaymentDirection.LANDLORD_TO_TENANT,"99000000",PaymentStatus.CONFIRMED);
        var pending=amount("pending",PaymentType.DEPOSIT_ADJUSTMENT,PaymentDirection.TENANT_TO_LANDLORD,"5000000",PaymentStatus.DISPUTED);
        when(payments.findByRentalRequestIdAndTypeIn(eq("rental"),any())).thenReturn(List.of(topUp,partial,last,wrong,pending));
        var result=service.forRental("rental");assertEquals(new BigDecimal("23000000"),result.confirmedReceivedAmount());assertEquals(result.confirmedReceivedAmount(),result.confirmedRefundedAmount());
    }
    @Test void legacyRefundedLabelWithoutMatchingReceiptRemainsUnknown(){
        deposit.setStatus(DepositStatus.REFUNDED);deposit.setRefundPaymentRequestId("missing");
        assertNull(service.forRental("rental").confirmedRefundedAmount());assertNull(service.forRental("rental").retainedAmount());
    }
    @Test void confirmedCombinedInitialRefundCountsOnlyTheDepositPart() {
        deposit.setStatus(DepositStatus.REFUNDED);deposit.setHeldAmount(BigDecimal.ZERO);deposit.setRefundPaymentRequestId("full-refund");
        var refund=amount("full-refund",PaymentType.INITIAL_PAYMENT_REFUND,PaymentDirection.LANDLORD_TO_TENANT,"25000000",PaymentStatus.CONFIRMED);
        refund.setOnboardingKey("REFUND:initial");
        when(payments.findByRentalRequestIdAndTypeIn(eq("rental"),any())).thenReturn(List.of(refund));
        var result=service.forRental("rental");assertEquals(new BigDecimal("20000000"),result.confirmedRefundedAmount());
        assertEquals(result.confirmedReceivedAmount(),result.confirmedRefundedAmount());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"valid","wrong-source","wrong-total","duplicate-source","malformed","pending"})
    void groupedRefundCountsOnlyItsVerifiedDepositReceipt(String scenario) {
        initial.setType(PaymentType.RESERVATION_DEPOSIT);initial.setTotalAmount(deposit.getOriginalAmount());
        var rent=amount("rent",PaymentType.INITIAL,PaymentDirection.TENANT_TO_LANDLORD,"5000000",PaymentStatus.CONFIRMED);
        var refund=amount("group",PaymentType.INITIAL_PAYMENT_REFUND,PaymentDirection.LANDLORD_TO_TENANT,"25000000",PaymentStatus.CONFIRMED);
        refund.setOnboardingKey(InitialRefundSources.key("rental"));
        refund.setRefundSourcesSnapshot(InitialRefundSources.freeze(List.of(initial,rent),mapper));
        deposit.setStatus(DepositStatus.REFUNDED);deposit.setRefundPaymentRequestId("group");
        switch(scenario) {
            case "wrong-source" -> refund.setRefundSourcesSnapshot(InitialRefundSources.freeze(List.of(rent),mapper));
            case "wrong-total" -> refund.setTotalAmount(new BigDecimal("30000000"));
            case "duplicate-source" -> refund.setRefundSourcesSnapshot(InitialRefundSources.freeze(List.of(initial,initial),mapper));
            case "malformed" -> refund.setRefundSourcesSnapshot("invalid json");
            case "pending" -> refund.setStatus(PaymentStatus.TRANSFER_REPORTED);
        }
        when(payments.findByRentalRequestIdAndTypeIn(eq("rental"),any())).thenReturn(List.of(refund));
        if("valid".equals(scenario))assertEquals(deposit.getOriginalAmount(),service.forRental("rental").confirmedRefundedAmount());
        else assertNull(service.forRental("rental").confirmedRefundedAmount());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pending","different-source","partial","wrong-recipient","not-linked"})
    void unconfirmedOrUnrelatedInitialRefundDoesNotEstablishDepositRefund(String invalid) {
        deposit.setStatus(DepositStatus.REFUNDED);deposit.setRefundPaymentRequestId("full-refund");
        var refund=amount("full-refund",PaymentType.INITIAL_PAYMENT_REFUND,PaymentDirection.LANDLORD_TO_TENANT,"25000000",PaymentStatus.CONFIRMED);
        refund.setOnboardingKey("REFUND:initial");
        switch(invalid) {
            case "pending"->refund.setStatus(PaymentStatus.TRANSFER_REPORTED);
            case "different-source"->refund.setOnboardingKey("REFUND:other");
            case "partial"->refund.setTotalAmount(new BigDecimal("5000000"));
            case "wrong-recipient"->refund.setPayeeId("other");
            case "not-linked"->refund.setId("other");
        }
        when(payments.findByRentalRequestIdAndTypeIn(eq("rental"),any())).thenReturn(List.of(refund));
        assertNull(service.forRental("rental").confirmedRefundedAmount());
    }
    @Test void zeroDepositDoesNotRequireOrManufactureReceipt(){
        deposit.setOriginalAmount(BigDecimal.ZERO);deposit.setInitialPaymentRequestId(null);
        assertEquals(0,service.forRental("rental").confirmedReceivedAmount().signum());verify(payments,never()).findById(any());
    }
}
