package com.hs.contract.service;

import com.hs.common.time.BillingTime;

import java.time.Instant;
import java.time.LocalDate;

/** Payment dates for contracts whose billing periods are anchored to the lease start. */
public final class MonthlyBillingSchedule {
    public static final int PAYMENT_WINDOW_DAYS = 4;
    public static final int OVERDUE_ACTION_DAYS = 5;

    private MonthlyBillingSchedule() {}

    public static String paymentDueDescription() {
        return "Chậm nhất 23:59 ngày thứ " + PAYMENT_WINDOW_DAYS
                + " sau khi kết thúc mỗi kỳ thuê; hạn cụ thể ghi trên hóa đơn";
    }

    public static Instant dueAt(LocalDate periodEndExclusive) {
        return periodEndExclusive.plusDays(PAYMENT_WINDOW_DAYS)
                .atTime(23, 59, 59).atZone(BillingTime.ZONE).toInstant();
    }

    public static Instant actionRequiredAt(Instant dueAt) {
        return dueAt.atZone(BillingTime.ZONE).toLocalDate()
                .plusDays(OVERDUE_ACTION_DAYS).atStartOfDay(BillingTime.ZONE).toInstant();
    }
}
