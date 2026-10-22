package com.hs.payment.event;

/** Delivered only after the surrounding money-confirmation transaction commits. */
public record OnboardingReceiptConfirmed(String rentalRequestId) {}
