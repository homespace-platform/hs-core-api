package com.hs.contract.service;

import com.hs.contract.dto.billing.BillingPeriodView;
import com.hs.contract.model.MonthlyInvoice;
import com.hs.contract.model.MonthlyInvoiceStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BillingPeriodViewTest {
    @Test void everyLeaseLengthSeparatesPaymentPeriodsFromFinalExpenseSettlement() {
        var start = LocalDate.of(2026, 10, 13);
        for (int months : new int[]{1, 3, 12, 36}) {
            for (int index = 0; index < months; index++) {
                var bill = MonthlyInvoice.builder().periodIndex(index).periodEndExclusive(start.plusMonths(index + 1L))
                        .status(MonthlyInvoiceStatus.DRAFT).build();
                var view = BillingPeriodView.from(bill, months, start, false);
                if (index == months - 1) {
                    assertEquals("FINAL_SETTLEMENT", view.kind());
                    assertNull(view.paymentPeriodNumber()); assertNull(view.rentPeriodStart());
                } else {
                    assertEquals("RECURRING", view.kind()); assertEquals(index + 2, view.paymentPeriodNumber());
                    assertEquals(start.plusMonths(index + 1L), view.rentPeriodStart());
                    assertEquals(start.plusMonths(index + 2L), view.rentPeriodEndExclusive());
                }
            }
        }
    }
    @Test void signedRenewalChangesDraftButNeverReclassifiesIssuedFinalSettlement() {
        var start = LocalDate.of(2026,10,13);
        var bill = MonthlyInvoice.builder().periodIndex(2).periodEndExclusive(start.plusMonths(3)).status(MonthlyInvoiceStatus.DRAFT).build();
        assertEquals(4, BillingPeriodView.from(bill,6,start,false).paymentPeriodNumber());
        bill.setStatus(MonthlyInvoiceStatus.PAID);
        assertEquals("FINAL_SETTLEMENT", BillingPeriodView.from(bill,6,start,false).kind());
        assertEquals(4, BillingPeriodView.from(bill,6,start,true).paymentPeriodNumber());
        bill.setFinalCostSupplement(true);
        assertEquals("SUPPLEMENT", BillingPeriodView.from(bill,6,start,true).kind());
    }
    @Test void rentDatesStayAnchoredToLeaseStartAcrossShortMonths() {
        var start = LocalDate.of(2027,1,31);
        var bill = MonthlyInvoice.builder().periodIndex(0).periodEndExclusive(start.plusMonths(1)).status(MonthlyInvoiceStatus.DRAFT).build();
        assertEquals(LocalDate.of(2027,3,31), BillingPeriodView.from(bill,12,start,false).rentPeriodEndExclusive());
    }
}
