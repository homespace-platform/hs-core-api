package com.hs.payment.repository;

import com.hs.payment.model.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, String> {

    List<PaymentEvent> findByPaymentRequestIdOrderByCreatedAtAsc(String paymentRequestId);
}
