package com.hs.contract.service;

import com.hs.common.time.BillingTime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Payment dates for contracts whose billing periods are anchored to the lease start. */
public final class MonthlyBillingSchedule {
    // Four days after the closing date = five payment calendar days INCLUDING the closing date.
    public static final int PAYMENT_WINDOW_DAYS = 4;
    public static final int OVERDUE_ACTION_DAYS = 5;
    public static final int AUTO_ROLLOVER_DAYS = 10;
    public static final int LATE_FEE_GRACE_DAYS = 0;

    private MonthlyBillingSchedule() {}

    /** Read-only metadata for document labels. Never accepted as landlord configuration. */
    public static Map<String,Object> rules() {
        return Map.of("paymentWindowDays",PAYMENT_WINDOW_DAYS+1,"paymentDueOffsetDays",PAYMENT_WINDOW_DAYS,
                "lateFeeGraceDays",LATE_FEE_GRACE_DAYS,"overdueActionDays",OVERDUE_ACTION_DAYS,"maximumPenaltyDays",AUTO_ROLLOVER_DAYS);
    }

    public static String paymentDueDescription() {
        return "Chậm nhất 23:59 ngày thứ " + (PAYMENT_WINDOW_DAYS + 1)
                + " tính từ ngày chốt kỳ (tính ngày chốt kỳ là ngày thứ 1); hạn cụ thể ghi trên hóa đơn";
    }

    public static Instant dueAt(LocalDate periodEndExclusive) {
        return periodEndExclusive.plusDays(PAYMENT_WINDOW_DAYS)
                .atTime(23, 59, 59).atZone(BillingTime.ZONE).toInstant();
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
