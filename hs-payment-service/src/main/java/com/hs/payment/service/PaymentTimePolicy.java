package com.hs.payment.service;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentType;
public final class PaymentTimePolicy {
    private PaymentTimePolicy() {}
    public static final java.util.Set<PaymentType> BILLING_TYPES=java.util.Set.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT,
        PaymentType.INITIAL_PAYMENT_REFUND,PaymentType.MONTHLY_RENT,PaymentType.DEPOSIT_REFUND,PaymentType.DEPOSIT_ADJUSTMENT);
    public static boolean usesBillingTime(PaymentRequest p) {
        return p.getType()!=null && BILLING_TYPES.contains(p.getType())
                || p.getType()==PaymentType.OTHER_CHARGE && p.getContractId()!=null;
    }
}
