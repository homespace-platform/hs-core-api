package com.hs.payment.event;

public record InitialRefundConfirmed(String rentalRequestId, String recipientId) {}
