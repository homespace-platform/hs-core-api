package com.hs.payment.service;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentType;
public final class PaymentTimePolicy {
    private PaymentTimePolicy() {}
    public static boolean usesBillingTime(PaymentRequest p) {
        return p.getType()==PaymentType.MONTHLY_RENT || p.getType()==PaymentType.DEPOSIT_REFUND
                || p.getType()==PaymentType.OTHER_CHARGE && p.getContractId()!=null;
    }
}
