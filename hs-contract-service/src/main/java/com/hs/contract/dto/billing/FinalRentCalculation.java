package com.hs.contract.dto.billing;
import java.math.BigDecimal;
import java.time.LocalDate;
public record FinalRentCalculation(String policy,LocalDate periodStart,LocalDate periodEndExclusive,LocalDate agreedEndExclusive,
    long usedDays,long periodDays,BigDecimal monthlyRent,BigDecimal agreedFinalRent,BigDecimal requiredRefund,
    String sourcePaymentId,String explanation) {}
