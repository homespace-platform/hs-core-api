package com.hs.payment.repository;

import com.hs.payment.model.EthPaymentQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface EthPaymentQuoteRepository extends JpaRepository<EthPaymentQuote, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from EthPaymentQuote q where q.paymentId = :paymentId")
    Optional<EthPaymentQuote> findByIdForUpdate(@Param("paymentId") String paymentId);

    boolean existsByTransactionHash(String transactionHash);

    Optional<EthPaymentQuote> findFirstByOrderIdOrderByExpiresAtDesc(String orderId);
}
