package com.hs.payment.repository;

import com.hs.payment.model.PaymentEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentEvidenceRepository extends JpaRepository<PaymentEvidence, String> {

    List<PaymentEvidence> findByPaymentRequestIdOrderByCreatedAtDesc(String paymentRequestId);
}
