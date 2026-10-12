package com.hs.contract.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import com.hs.common.time.BillingTime;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyBillingScheduleTest {
    @Test void autoRolloverStartsAfterTenCompleteLocalOverdueDays() {
        Instant due = MonthlyBillingSchedule.dueAt(LocalDate.of(2026, 11, 12));
        assertEquals(Instant.parse("2026-11-16T16:59:59Z"), due);
        assertEquals(Instant.parse("2026-11-26T17:00:00Z"), MonthlyBillingSchedule.autoRolloverAt(due));
    }

    @Test void extendedDeadlinesAlsoUseLocalCalendarDaysAcrossMonthEnd() {
        assertEquals(Instant.parse("2026-12-07T17:00:00Z"),
                MonthlyBillingSchedule.autoRolloverAt(Instant.parse("2026-11-27T03:00:00Z")));
    }

    @Test void twelveOctoberExampleHasFivePaymentDaysThenTenPenaltyDays() {
        Instant due = MonthlyBillingSchedule.dueAt(LocalDate.of(2026, 10, 12));
        assertEquals(Instant.parse("2026-10-16T16:59:59Z"), due);
        LatePaymentPolicy policy = LatePaymentPolicy.from(Map.of("latePaymentFeeMode", "FIXED_PER_DAY",
                "latePaymentFeeAmount", 100000, "latePaymentFeeGraceDays", 0));
        assertEquals(BigDecimal.ZERO, policy.accrued(due, due, BillingTime.ZONE));
        assertEquals(1, policy.chargedDays(due, Instant.parse("2026-10-16T17:00:00Z"), BillingTime.ZONE));
        assertEquals(10, policy.chargedDays(due, Instant.parse("2026-10-26T16:59:59Z"), BillingTime.ZONE));
        assertEquals(new BigDecimal("1000000"), policy.accrued(due,
                Instant.parse("2026-10-27T03:00:00Z"), BillingTime.ZONE));
        assertEquals(Instant.parse("2026-10-26T17:00:00Z"), MonthlyBillingSchedule.autoRolloverAt(due));
        assertTrue(MonthlyBillingSchedule.paymentDueDescription().contains("ngày thứ 5"));
    }
}
