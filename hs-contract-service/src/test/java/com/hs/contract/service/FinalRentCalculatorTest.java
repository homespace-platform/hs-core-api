package com.hs.contract.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.model.*;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.listing.model.RentalRequest;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FinalRentCalculatorTest {
    PaymentRequestRepository payments=mock(PaymentRequestRepository.class);
    PaymentLineItemRepository lines=mock(PaymentLineItemRepository.class);
    MonthlyInvoiceRepository invoices=mock(MonthlyInvoiceRepository.class);
    ContractTermsResolver terms=mock(ContractTermsResolver.class);
    RentalWorkflowSupport support=new RentalWorkflowSupport(null,null,null,null,null,payments,new ObjectMapper().findAndRegisterModules(),null);
    FinalRentCalculator calc=new FinalRentCalculator(support,invoices,lines,terms);
    RentalWorkflowSupport.Source source(LocalDate start){
        var r=RentalRequest.builder().id("r").moveInDate(start).leaseMonths(6).monthlyRentPrice(new BigDecimal("3000000")).build();
        return new RentalWorkflowSupport.Source(r,Contract.builder().id("c").build(),null,"tenant");
    }
    @BeforeEach void setup(){when(terms.at(any(),any())).thenReturn(ContractRevision.builder().financialSnapshot("{}").build());when(payments.findByRentalRequestIdAndType(anyString(),any())).thenReturn(Optional.empty());when(invoices.findByContractIdOrderByPeriodIndexDesc(anyString())).thenReturn(List.of());}
    PaymentRequest paid(PaymentType type,int months){
        var p=PaymentRequest.builder().id("paid").rentalRequestId("r").type(type).status(PaymentStatus.CONFIRMED).build();
        when(payments.findById("paid")).thenReturn(Optional.of(p));when(lines.findByPaymentRequestIdOrderBySortOrderAsc("paid")).thenReturn(List.of(PaymentLineItem.builder().type("RENT").quantity(months).unitPrice(new BigDecimal("3000000")).amount(new BigDecimal(3000000L*months)).build()));return p;
    }
    @Test void monthEndInvoiceUsesOriginalAnchorAndRefundsUnusedRemainder(){
        var p=paid(PaymentType.MONTHLY_RENT,2);when(invoices.findByContractIdOrderByPeriodIndexDesc("c")).thenReturn(List.of(MonthlyInvoice.builder().paymentRequestId(p.getId()).rentPeriodStart(LocalDate.of(2027,2,28)).rentPeriodEndExclusive(LocalDate.of(2027,4,30)).build()));
        var q=calc.calculate(source(LocalDate.of(2027,1,31)),LocalDate.of(2027,4,15),"DAILY_PRORATED");
        assertEquals(LocalDate.of(2027,3,31),q.periodStart());assertEquals(30,q.periodDays());assertEquals(15,q.usedDays());assertEquals(0,new BigDecimal("1500000").compareTo(q.requiredRefund()));
    }
    @Test void leapFebruaryUses29ActualDays(){var p=paid(PaymentType.INITIAL,1);when(payments.findByRentalRequestIdAndType("r",PaymentType.INITIAL)).thenReturn(Optional.of(p));var q=calc.calculate(source(LocalDate.of(2028,1,31)),LocalDate.of(2028,2,15),"DAILY_PRORATED");assertEquals(29,q.periodDays());assertEquals(15,q.usedDays());assertEquals(new BigDecimal("1551724"),q.agreedFinalRent());}
    @Test void initialThreeMonthPrepaymentRefundsFutureUnusedMonths(){var p=paid(PaymentType.INITIAL,3);when(payments.findByRentalRequestIdAndType("r",PaymentType.INITIAL)).thenReturn(Optional.of(p));var q=calc.calculate(source(LocalDate.of(2027,1,31)),LocalDate.of(2027,2,28),"DAILY_PRORATED");assertEquals(28,q.usedDays());assertEquals(0,new BigDecimal("6000000").compareTo(q.requiredRefund()));}
    @Test void mismatchedInvoiceRangeOrAmountRequiresReconciliation(){var p=paid(PaymentType.MONTHLY_RENT,2);when(invoices.findByContractIdOrderByPeriodIndexDesc("c")).thenReturn(List.of(MonthlyInvoice.builder().paymentRequestId(p.getId()).rentPeriodStart(LocalDate.of(2027,2,28)).rentPeriodEndExclusive(LocalDate.of(2027,4,28)).build()));assertThrows(AppException.class,()->calc.calculate(source(LocalDate.of(2027,1,31)),LocalDate.of(2027,4,15),"DAILY_PRORATED"));}
    @Test void multiplePaidSourcesCannotProduceAmbiguousRefund(){var p=paid(PaymentType.INITIAL,3);when(payments.findByRentalRequestIdAndType("r",PaymentType.INITIAL)).thenReturn(Optional.of(p));when(invoices.findByContractIdOrderByPeriodIndexDesc("c")).thenReturn(List.of(MonthlyInvoice.builder().paymentRequestId(p.getId()).rentPeriodStart(LocalDate.of(2027,1,31)).rentPeriodEndExclusive(LocalDate.of(2027,4,30)).build()));assertThrows(AppException.class,()->calc.calculate(source(LocalDate.of(2027,1,31)),LocalDate.of(2027,2,15),"DAILY_PRORATED"));}
}
