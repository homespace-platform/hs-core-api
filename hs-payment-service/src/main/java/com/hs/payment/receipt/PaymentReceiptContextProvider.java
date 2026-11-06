package com.hs.payment.receipt;

import com.hs.payment.model.PaymentRequest;

/** Supplies contract metadata without making payment depend on the contract module. */
public interface PaymentReceiptContextProvider {
  PaymentReceiptSnapshot.Context context(PaymentRequest payment);
}
