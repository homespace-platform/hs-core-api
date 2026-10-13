package com.hs.contract.dto.billing;
import com.hs.contract.model.MonthlyInvoiceStatus;
import com.hs.payment.model.constant.PaymentStatus;
import java.math.BigDecimal;
import java.util.List;
public record ClosureBillingResponse(boolean allPaid, boolean canIssueSupplement, List<Bill> bills) {
    public record Bill(String id,int periodIndex,boolean finalCostSupplement,MonthlyInvoiceStatus status,
        PaymentStatus paymentStatus,BigDecimal totalAmount,BigDecimal electricityEnd,BigDecimal waterEnd,
        BillingPeriodView billingPeriod) {}
}
