package com.hs.payment.config;

import com.hs.payment.model.constant.PaymentStatus;
import org.junit.jupiter.api.Test;
import static com.hs.payment.config.LegacyPaymentDisputeNormalizer.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyPaymentDisputeNormalizerTest {
    @Test void restoresWorkablePreviousStatus() {
        assertEquals(PaymentStatus.TRANSFER_REPORTED, decide(PaymentStatus.TRANSFER_REPORTED, false));
        assertEquals(PaymentStatus.REJECTED, decide(PaymentStatus.REJECTED, true));
        assertEquals(PaymentStatus.AWAITING_TRANSFER, decide(PaymentStatus.AWAITING_TRANSFER, true));
    }
    @Test void neverRestoresConfirmedFinalOrDisputed() {
        for (var s : new PaymentStatus[]{PaymentStatus.CONFIRMED, PaymentStatus.EXPIRED, PaymentStatus.CANCELLED, PaymentStatus.DISPUTED}) {
            assertEquals(PaymentStatus.TRANSFER_REPORTED, decide(s, true));
            assertEquals(PaymentStatus.AWAITING_TRANSFER, decide(s, false));
        }
    }
    @Test void withoutCaseFallsBackOnProof() {
        assertEquals(PaymentStatus.TRANSFER_REPORTED, decide(null, true));
        assertEquals(PaymentStatus.AWAITING_TRANSFER, decide(null, false));
    }
}
