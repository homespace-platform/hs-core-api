package com.hs.contract.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.time.LocalDate;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyBillingScheduleTest {
    @ParameterizedTest
    @CsvSource(value = {
            "2026-11-05,-,2026-11-05T16:59:59Z",
            "2026-11-06,-,2026-12-05T16:59:59Z",
            "2026-11-30,0,2026-11-30T16:59:59Z",
            "2026-11-30,4,2026-12-04T16:59:59Z",
            "2026-11-30,30,2026-12-30T16:59:59Z"
    }, nullValues = "-")
    void signedDueDatesPreserveLegacyFifthDayAndExplicitOffsets(LocalDate end, Integer offset, Instant expected) {
        var financial = offset == null ? Map.<String, Object>of() : Map.<String, Object>of("paymentDueOffsetDays", offset);
        assertEquals(expected, MonthlyBillingSchedule.signedDueAt(financial, end));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "31", "4.0", "invalid"})
    void invalidSignedOffsetStillReturnsInvoiceTermsError(String offset) {
        var error = assertThrows(AppException.class, () -> MonthlyBillingSchedule.signedDueAt(
                Map.of("paymentDueOffsetDays", offset), LocalDate.of(2026, 11, 30)));
        assertEquals(ContractErrorCode.INVOICE_TERMS_INCOMPLETE.getCode(), error.getCode());
    }

    @Test void autoRolloverStartsAfterTenCompleteLocalOverdueDays() {
        Instant due = MonthlyBillingSchedule.dueAt(LocalDate.of(2026, 11, 12));
        assertEquals(Instant.parse("2026-11-16T16:59:59Z"), due);
        assertEquals(Instant.parse("2026-11-26T17:00:00Z"), MonthlyBillingSchedule.autoRolloverAt(due));
    }

    @Test void extendedDeadlinesAlsoUseLocalCalendarDaysAcrossMonthEnd() {
        assertEquals(Instant.parse("2026-12-07T17:00:00Z"),
                MonthlyBillingSchedule.autoRolloverAt(Instant.parse("2026-11-27T03:00:00Z")));
    }

    @Test void twelveOctoberExampleHasFivePaymentDaysFivePenaltyDaysAndTenDayRollover() {
        Instant due = MonthlyBillingSchedule.dueAt(LocalDate.of(2026, 10, 12));
        assertEquals(Instant.parse("2026-10-16T16:59:59Z"), due);
        LatePaymentPolicy policy = LatePaymentPolicy.from(Map.of("latePaymentFeeMode", "FIXED_PER_DAY",
                "latePaymentFeeAmount", 100000, "latePaymentFeeGraceDays", 0));
        assertEquals(BigDecimal.ZERO, policy.accrued(due, due, BillingTime.ZONE));
        assertEquals(1, policy.chargedDays(due, Instant.parse("2026-10-16T17:00:00Z"), BillingTime.ZONE));
        assertEquals(5, policy.chargedDays(due, Instant.parse("2026-10-26T16:59:59Z"), BillingTime.ZONE));
        assertEquals(new BigDecimal("500000"), policy.accrued(due,
                Instant.parse("2026-10-27T03:00:00Z"), BillingTime.ZONE));
        assertEquals(Instant.parse("2026-10-26T17:00:00Z"), MonthlyBillingSchedule.autoRolloverAt(due));
        assertTrue(MonthlyBillingSchedule.paymentDueDescription().contains("ngày thứ 5"));
    }
}
