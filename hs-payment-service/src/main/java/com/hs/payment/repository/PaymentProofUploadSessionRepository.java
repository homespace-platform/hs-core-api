package com.hs.payment.repository;

import com.hs.payment.model.PaymentProofUploadSession;
import com.hs.payment.model.constant.UploadSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentProofUploadSessionRepository extends JpaRepository<PaymentProofUploadSession, String> {

    Optional<PaymentProofUploadSession> findByTokenHash(String tokenHash);

    Optional<PaymentProofUploadSession> findByIdAndPaymentRequestId(String id, String paymentRequestId);

    List<PaymentProofUploadSession> findByPaymentRequestIdAndTenantUserIdAndStatusIn(
            String paymentRequestId,
            String tenantUserId,
            Collection<UploadSessionStatus> statuses
    );
}
