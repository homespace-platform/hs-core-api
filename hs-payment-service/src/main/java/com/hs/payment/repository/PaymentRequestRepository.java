package com.hs.payment.repository;

import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, String> {

    Optional<PaymentRequest> findByRentalRequestIdAndType(String rentalRequestId, PaymentType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentRequest p WHERE p.id = :id")
    Optional<PaymentRequest> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentRequest p WHERE p.rentalRequestId = :rentalRequestId AND p.type = :type")
    Optional<PaymentRequest> findByRentalRequestIdAndTypeForUpdate(
            @Param("rentalRequestId") String rentalRequestId,
            @Param("type") PaymentType type);

    List<PaymentRequest> findByPayerIdOrPayeeIdOrderByCreatedAtDesc(String payerId, String payeeId);

    Optional<PaymentRequest> findByTransferReference(String transferReference);

    boolean existsByTransferReference(String transferReference);

    List<PaymentRequest> findByRentalRequestIdInAndType(Collection<String> rentalRequestIds, PaymentType type);
}
