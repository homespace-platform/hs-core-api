package com.hs.payment.repository;

import com.hs.payment.model.PaymentLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentLineItemRepository extends JpaRepository<PaymentLineItem, String> {

    List<PaymentLineItem> findByPaymentRequestIdOrderBySortOrderAsc(String paymentRequestId);
}
