package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Payment dates for contracts whose billing periods are anchored to the lease start. */
public final class MonthlyBillingSchedule {
    // Four days after the closing date = five payment calendar days INCLUDING the closing date.
    public static final int PAYMENT_WINDOW_DAYS = 4;
    public static final int OVERDUE_ACTION_DAYS = 5;
    public static final int MAXIMUM_PENALTY_DAYS = 5;
    public static final int AUTO_ROLLOVER_DAYS = 10;
    public static final int TERMINATION_REVIEW_HOURS = 24;
    public static final int LATE_FEE_GRACE_DAYS = 0;

    private MonthlyBillingSchedule() {}

    /** Read-only metadata for document labels. Never accepted as landlord configuration. */
    public static Map<String,Object> rules() {
        return Map.of("paymentWindowDays",PAYMENT_WINDOW_DAYS+1,"paymentDueOffsetDays",PAYMENT_WINDOW_DAYS,
                "lateFeeGraceDays",LATE_FEE_GRACE_DAYS,"overdueActionDays",OVERDUE_ACTION_DAYS,"maximumPenaltyDays",MAXIMUM_PENALTY_DAYS,
                "autoRolloverDays",AUTO_ROLLOVER_DAYS,"terminationReviewHours",TERMINATION_REVIEW_HOURS);
    }

    public static String paymentDueDescription() {
        return "Chậm nhất 23:59 ngày thứ " + (PAYMENT_WINDOW_DAYS + 1)
                + " tính từ ngày chốt kỳ (tính ngày chốt kỳ là ngày thứ 1); hạn cụ thể ghi trên hóa đơn";
    }

    public static Instant dueAt(LocalDate periodEndExclusive) {
        return periodEndExclusive.plusDays(PAYMENT_WINDOW_DAYS)
                .atTime(23, 59, 59).atZone(BillingTime.ZONE).toInstant();
    }

    /** Due date under signed terms: their own offset, else the fifth-day term of older signed contracts. */
    public static Instant signedDueAt(Map<String,Object> financial, LocalDate periodEndExclusive) {
        if (financial.get("paymentDueOffsetDays") != null) {
            int offset;
            try { offset = Integer.parseInt(String.valueOf(financial.get("paymentDueOffsetDays"))); }
            catch (NumberFormatException ex) { throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE); }
            if (offset < 0 || offset > 30)
                throw new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE);
            return periodEndExclusive.plusDays(offset).atTime(23,59,59).atZone(BillingTime.ZONE).toInstant();
        }
        LocalDate fifth = periodEndExclusive.withDayOfMonth(5);
        if (fifth.isBefore(periodEndExclusive)) fifth = fifth.plusMonths(1);
        return fifth.atTime(23, 59, 59).atZone(BillingTime.ZONE).toInstant();
    }

    public static Instant actionRequiredAt(Instant dueAt) {
        return dueAt.atZone(BillingTime.ZONE).toLocalDate()
                .plusDays(OVERDUE_ACTION_DAYS + 1L).atStartOfDay(BillingTime.ZONE).toInstant();
    }

    /** Allow ten complete overdue calendar days before the default decision. */
    public static Instant autoRolloverAt(Instant dueAt) {
        return dueAt.atZone(BillingTime.ZONE).toLocalDate()
                .plusDays(AUTO_ROLLOVER_DAYS + 1L).atStartOfDay(BillingTime.ZONE).toInstant();
    }
}
