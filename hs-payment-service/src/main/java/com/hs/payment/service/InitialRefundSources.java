package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.payment.model.PaymentRequest;
import java.math.BigDecimal;
import java.util.List;

/** Frozen source receipts, separate from the displayed rent/deposit allocation. */
record InitialRefundSources(List<Source> sources) {
    record Source(String paymentId, BigDecimal amount) {}

    static String key(String rentalId) { return "CANCEL_REFUND:" + rentalId; }

    static String freeze(List<PaymentRequest> payments, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(new InitialRefundSources(payments.stream()
                    .map(p -> new Source(p.getId(), p.getTotalAmount())).toList()));
        } catch (Exception e) { throw new IllegalStateException("Cannot freeze refund sources", e); }
    }

    static boolean covers(PaymentRequest refund, PaymentRequest source, ObjectMapper mapper) {
        if (!key(source.getRentalRequestId()).equals(refund.getOnboardingKey()) || refund.getRefundSourcesSnapshot() == null) return false;
        try {
            var rows = mapper.readValue(refund.getRefundSourcesSnapshot(), InitialRefundSources.class).sources();
            return rows != null && !rows.isEmpty()
                    && rows.stream().allMatch(r -> r.paymentId() != null && r.amount() != null && r.amount().signum() > 0)
                    && rows.stream().map(Source::paymentId).distinct().count() == rows.size()
                    && rows.stream().map(Source::amount).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(refund.getTotalAmount()) == 0
                    && rows.stream().anyMatch(r -> source.getId().equals(r.paymentId()) && source.getTotalAmount().compareTo(r.amount()) == 0);
        } catch (Exception e) { return false; }
    }
}
