package com.hs.contract.service;

import com.hs.contract.dto.billing.*;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.listing.model.RentalRequest;
import com.hs.payment.dto.PaymentRequestResponse;
import com.hs.payment.model.constant.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Stable read model of the original receipt, NOT a second receivable or monthly cost period. */
final class FirstRentInvoice {
    private FirstRentInvoice() {}
    static MonthlyInvoiceResponse from(String contractId, RentalRequest rental, PaymentRequestResponse payment, Instant now) {
        if (payment.type()!=PaymentType.INITIAL) throw new IllegalArgumentException("Expected first rent payment");
        if(payment.lineItems()==null || payment.lineItems().stream().noneMatch(l->"RENT".equals(l.type()) && l.amount()!=null && l.amount().signum()>0))
            throw new com.hs.common.advice.entity.AppException(com.hs.contract.advice.ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        var lines=payment.lineItems().stream().filter(l->Set.of("RENT","SERVICE_FEE").contains(l.type()))
            .map(l->new InvoiceLine(l.type(),l.displayName(),BigDecimal.valueOf(l.quantity()==null?1:l.quantity()),l.unitPrice(),l.amount())).toList();
        var total=lines.stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(payment.totalAmount()==null || total.compareTo(payment.totalAmount())>0)
            throw new com.hs.common.advice.entity.AppException(com.hs.contract.advice.ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
        var closed=Set.of(PaymentStatus.CANCELLED,PaymentStatus.EXPIRED).contains(payment.status());
        var status=payment.status()==PaymentStatus.CONFIRMED?MonthlyInvoiceStatus.PAID
            :closed?MonthlyInvoiceStatus.CANCELLED:payment.dueAt()!=null&&!now.isBefore(payment.dueAt())?MonthlyInvoiceStatus.OVERDUE:MonthlyInvoiceStatus.UNPAID;
        var workflow=Set.of(PaymentStatus.TRANSFER_REPORTED,PaymentStatus.DISPUTED,PaymentStatus.REJECTED).contains(payment.status())?"UNDER_REVIEW":status.name();
        return MonthlyInvoiceResponse.builder().id("first-rent:"+payment.id()).contractId(contractId).periodIndex(-1)
            .periodStart(rental.getMoveInDate()).periodEndExclusive(rental.getMoveInDate().plusMonths(1))
            .billingPeriod(new BillingPeriodView("INITIAL_RENT",1,rental.getMoveInDate(),rental.getMoveInDate().plusMonths(1)))
            .status(status).workflowState(workflow).lines(lines).totalAmount(total).paymentRequestId(payment.id())
            .issuedAt(payment.createdAt()).dueAt(payment.dueAt()).originalDueAt(payment.dueAt()).paidAt(payment.confirmedAt())
            .serverNow(now).lateFeeAmount(BigDecimal.ZERO).lateFeeExempt(true).activeResolution("NONE")
            .draftExtraCharges(List.of()).overdueActions(List.of()).paymentExtensions(List.of()).build();
    }
}
