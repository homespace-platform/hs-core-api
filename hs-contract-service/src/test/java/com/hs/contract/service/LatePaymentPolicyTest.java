package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LatePaymentPolicyTest {
    private static final Instant DUE = Instant.parse("2026-11-05T16:59:59Z");

    @Test
    void legacyContractHasNoPenalty() {
        assertEquals(BigDecimal.ZERO, LatePaymentPolicy.from(Map.of())
                .accrued(DUE, Instant.parse("2026-11-10T03:00:00Z"), BillingTime.ZONE));
    }

    @Test
    void fixedOnceBeginsOnFirstLateCalendarDayAfterGrace() {
        LatePaymentPolicy policy = LatePaymentPolicy.from(Map.of(
                "latePaymentFeeMode", "FIXED_ONCE", "latePaymentFeeAmount", 50000,
                "latePaymentFeeGraceDays", 1));
        assertEquals(BigDecimal.ZERO, policy.accrued(DUE, Instant.parse("2026-11-06T03:00:00Z"), BillingTime.ZONE));
        assertEquals(new BigDecimal("50000"), policy.accrued(DUE,
                Instant.parse("2026-11-07T00:00:00Z"), BillingTime.ZONE));
    }

    @Test
    void simplePoliciesChargeOnceOrPerDayStartingTheDayAfterDue() {
        LatePaymentPolicy once = LatePaymentPolicy.from(Map.of(
                "latePaymentFeeMode", "FIXED_ONCE", "latePaymentFeeAmount", 100000,
                "latePaymentFeeGraceDays", 0));
        LatePaymentPolicy daily = LatePaymentPolicy.from(Map.of(
                "latePaymentFeeMode", "FIXED_PER_DAY", "latePaymentFeeAmount", 100000,
                "latePaymentFeeGraceDays", 0));
        Instant firstLateDay = Instant.parse("2026-11-05T17:00:00Z");
        Instant fifthLateDay = Instant.parse("2026-11-09T17:00:00Z");

        assertEquals(new BigDecimal("100000"), once.accrued(DUE, firstLateDay, BillingTime.ZONE));
        assertEquals(new BigDecimal("100000"), once.accrued(DUE, fifthLateDay, BillingTime.ZONE));
        assertEquals(new BigDecimal("100000"), daily.accrued(DUE, firstLateDay, BillingTime.ZONE));
        assertEquals(new BigDecimal("500000"), daily.accrued(DUE, fifthLateDay, BillingTime.ZONE));
    }

    @Test
    void dailyPenaltyAccruesExactlyOncePerLocalDayAndStopsAtCap() {
        LatePaymentPolicy policy = LatePaymentPolicy.from(Map.of(
                "latePaymentFeeMode", "FIXED_PER_DAY", "latePaymentFeeAmount", 20000,
                "latePaymentFeeGraceDays", 0, "latePaymentFeeCap", 50000));
        assertEquals(BigDecimal.ZERO, policy.accrued(DUE, DUE, BillingTime.ZONE));
        assertEquals(new BigDecimal("20000"), policy.accrued(DUE,
                Instant.parse("2026-11-05T17:00:00Z"), BillingTime.ZONE));
        assertEquals(new BigDecimal("50000"), policy.accrued(DUE,
                Instant.parse("2026-11-09T00:00:00Z"), BillingTime.ZONE));
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThrows(AppException.class, () -> LatePaymentPolicy.from(Map.of(
                "latePaymentFeeMode", "FIXED_PER_DAY", "latePaymentFeeAmount", -1,
                "latePaymentFeeGraceDays", 0)));
    }
}
