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
    @Test void newTermsAcceptOnlyRateAndModeAndDeriveAllControls() {
        for(String mode:java.util.List.of("NONE","FIXED_ONCE","FIXED_PER_DAY")) {
            var p=LatePaymentPolicy.forNewAgreement(Map.of("latePaymentFeeMode",mode,"latePaymentFeeAmount","100000"),Map.of());
            assertEquals(0,p.get("latePaymentFeeGraceDays"));assertEquals(MonthlyBillingSchedule.rules(),p.get("billingRules"));
            assertEquals(mode.equals("NONE")?"0":mode.equals("FIXED_ONCE")?"100000":"1000000",p.get("latePaymentFeeCap"));
        }
        for(var injected:java.util.List.of(Map.<String,Object>of("latePaymentFeeGraceDays",2),Map.<String,Object>of("latePaymentFeeCap",700000),Map.<String,Object>of("billingRules",Map.of("maximumPenaltyDays",30)))) {
            var p=new java.util.LinkedHashMap<String,Object>(Map.of("latePaymentFeeMode","FIXED_PER_DAY","latePaymentFeeAmount",100000));p.putAll(injected);
            assertThrows(AppException.class,()->LatePaymentPolicy.forNewAgreement(p,Map.of()));
        }
    }
    @Test void legacyLowerCapAndGraceRemainFrozenButNewRevisionDerivesFreshRules() {
        var old=Map.<String,Object>of("latePaymentFeeMode","FIXED_PER_DAY","latePaymentFeeAmount",100000,"latePaymentFeeGraceDays",2,"latePaymentFeeCap",700000);
        var next=LatePaymentPolicy.forNewAgreement(old,old);
        assertEquals(0,next.get("latePaymentFeeGraceDays"));assertEquals("1000000",next.get("latePaymentFeeCap"));
        assertEquals(2,LatePaymentPolicy.from(old).graceDays());assertEquals(new BigDecimal("700000"),LatePaymentPolicy.from(old).cap());
    }

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

    @Test
    void dailyPenaltyNeverExceedsTenChargedDaysEvenWhenSchedulerWasOffline() {
        LatePaymentPolicy policy = LatePaymentPolicy.from(Map.of(
                "latePaymentFeeMode", "FIXED_PER_DAY", "latePaymentFeeAmount", 100000,
                "latePaymentFeeGraceDays", 0));
        assertEquals(new BigDecimal("1000000"), policy.accrued(DUE,
                Instant.parse("2026-11-15T16:59:59Z"), BillingTime.ZONE));
        assertEquals(new BigDecimal("1000000"), policy.accrued(DUE,
                Instant.parse("2026-12-20T03:00:00Z"), BillingTime.ZONE));
        assertTrue(policy.clause().contains("tối đa 10 ngày"));
    }

    @Test
    void tenDayLimitStillHonorsGraceAndLowerMoneyCap() {
        LatePaymentPolicy policy = LatePaymentPolicy.from(Map.of(
                "latePaymentFeeMode", "FIXED_PER_DAY", "latePaymentFeeAmount", 100000,
                "latePaymentFeeGraceDays", 2, "latePaymentFeeCap", 700000));
        assertEquals(new BigDecimal("100000"), policy.accrued(DUE,
                Instant.parse("2026-11-08T03:00:00Z"), BillingTime.ZONE));
        assertEquals(new BigDecimal("700000"), policy.accrued(DUE,
                Instant.parse("2026-12-20T03:00:00Z"), BillingTime.ZONE));
    }
}
