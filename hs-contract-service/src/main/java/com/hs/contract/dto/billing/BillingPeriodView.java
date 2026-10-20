package com.hs.contract.dto.billing;

import com.hs.contract.model.MonthlyInvoice;
import com.hs.contract.model.MonthlyInvoiceStatus;
import java.time.LocalDate;

/** Payment numbering is separate from the zero-based expense period stored on invoices. */
public record BillingPeriodView(String kind, Integer paymentPeriodNumber,
                                LocalDate rentPeriodStart, LocalDate rentPeriodEndExclusive) {
    public static BillingPeriodView from(MonthlyInvoice invoice, Integer leaseMonths,
                                         LocalDate leaseStart, boolean issuedWithRent) {
        if (invoice.isFinalCostSupplement()) return new BillingPeriodView("SUPPLEMENT", null, null, null);
        if(invoice.getRentPeriodStart()!=null && invoice.getRentPeriodEndExclusive()!=null)
            return new BillingPeriodView("RECURRING",invoice.getPeriodIndex()+2,invoice.getRentPeriodStart(),invoice.getRentPeriodEndExclusive());
        // Issued bills retain their original meaning even after a renewal changes the lease length.
        boolean hasRent = invoice.getStatus() == MonthlyInvoiceStatus.DRAFT
                ? leaseMonths != null && invoice.getPeriodIndex() + 1 < leaseMonths : issuedWithRent;
        if (!hasRent) {
            String kind=invoice.getStatus()==MonthlyInvoiceStatus.DRAFT
                    ? (leaseMonths!=null&&invoice.getPeriodIndex()+1<leaseMonths?"COSTS_ONLY":"FINAL_SETTLEMENT")
                    : (invoice.getIssuedBillingKind()!=null?invoice.getIssuedBillingKind():"FINAL_SETTLEMENT");
            return new BillingPeriodView(kind,null,null,null);
        }
        return new BillingPeriodView("RECURRING", invoice.getPeriodIndex() + 2,
                invoice.getPeriodEndExclusive(), leaseStart == null ? null
                : leaseStart.plusMonths(invoice.getPeriodIndex() + 2L));
    }
}
