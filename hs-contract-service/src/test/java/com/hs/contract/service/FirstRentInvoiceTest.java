package com.hs.contract.service;

import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.listing.model.RentalRequest;
import com.hs.payment.dto.*;
import com.hs.payment.model.constant.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class FirstRentInvoiceTest {
    private final Instant now=Instant.parse("2026-11-01T03:00:00Z");
    private final RentalRequest rental=RentalRequest.builder().moveInDate(LocalDate.of(2026,11,1)).build();
    private PaymentRequestResponse payment(PaymentStatus status) {
        return PaymentRequestResponse.builder().id("original").type(PaymentType.INITIAL).status(status).totalAmount(new BigDecimal("15000000"))
            .dueAt(now.plusSeconds(10)).lineItems(List.of(
                PaymentLineItemResponse.builder().type("RENT").displayName("Tiền thuê tháng 1").quantity(1).amount(new BigDecimal("5000000")).unitPrice(new BigDecimal("5000000")).build(),
                PaymentLineItemResponse.builder().type("DEPOSIT").amount(new BigDecimal("10000000")).build())).build();
    }
    @Test void combinedReceiptProducesOnlyRentAndReusesPaymentIdentity() {
        var bill=FirstRentInvoice.from("root",rental,payment(PaymentStatus.CONFIRMED),now);
        assertEquals(new BigDecimal("5000000"),bill.totalAmount());assertEquals(1,bill.lines().size());
        assertEquals("original",bill.paymentRequestId());assertEquals("first-rent:original",bill.id());assertEquals(-1,bill.periodIndex());
        assertEquals("INITIAL_RENT",bill.billingPeriod().kind());assertEquals(1,bill.billingPeriod().paymentPeriodNumber());
        assertEquals(LocalDate.of(2026,12,1),bill.periodEndExclusive());assertEquals(MonthlyInvoiceStatus.PAID,bill.status());
        assertFalse(bill.canRequestExtension());assertFalse(bill.canResolveOverdue());assertFalse(bill.canDeferToNextPeriod());
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"TRANSFER_REPORTED","DISPUTED","REJECTED"})
    void reportedOrDisputedIsNotPaid(PaymentStatus status) {
        var bill=FirstRentInvoice.from("root",rental,payment(status),now);
        assertEquals(MonthlyInvoiceStatus.UNPAID,bill.status());assertEquals("UNDER_REVIEW",bill.workflowState());
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"CANCELLED","EXPIRED"})
    void cancelledNotOutstanding(PaymentStatus status) {assertEquals(MonthlyInvoiceStatus.CANCELLED,FirstRentInvoice.from("root",rental,payment(status),now).status());}
    @Test void exactDueBoundaryHasNoInventedMonthlyPenalty() {
        var bill=FirstRentInvoice.from("root",rental,payment(PaymentStatus.AWAITING_TRANSFER),now.plusSeconds(10));
        assertEquals(MonthlyInvoiceStatus.OVERDUE,bill.status());assertEquals(BigDecimal.ZERO,bill.lateFeeAmount());
    }
    @Test void missingFirstRentLedgerCannotMasqueradeAsZeroPaidInvoice() {
        var incomplete=PaymentRequestResponse.builder().id("bad").type(PaymentType.INITIAL).status(PaymentStatus.CONFIRMED).lineItems(List.of()).build();
        assertThrows(com.hs.common.advice.entity.AppException.class,()->FirstRentInvoice.from("root",rental,incomplete,now));
    }
}
