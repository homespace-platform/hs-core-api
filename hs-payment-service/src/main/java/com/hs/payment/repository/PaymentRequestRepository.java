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
import java.time.Instant;
import com.hs.payment.model.constant.PaymentStatus;
import org.springframework.data.domain.Pageable;

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

    @Query("SELECT p FROM PaymentRequest p WHERE p.contractId = :contractId "
            + "AND (p.payerId = :actorId OR p.payeeId = :actorId) ORDER BY p.createdAt DESC")
    List<PaymentRequest> findForContractAndActor(@Param("contractId") String contractId,
                                                 @Param("actorId") String actorId);

    Optional<PaymentRequest> findByTransferReference(String transferReference);
    Optional<PaymentRequest> findByOnboardingKey(String onboardingKey);
    List<PaymentRequest> findByRentalRequestIdAndTypeIn(String rentalRequestId, Collection<PaymentType> types);

    Optional<PaymentRequest> findByInvoiceId(String invoiceId);
    Optional<PaymentRequest> findByContractIdAndType(String contractId, PaymentType type);
    List<PaymentRequest> findAllByContractIdAndType(String contractId, PaymentType type);

    @Query("SELECT p.id FROM PaymentRequest p WHERE p.contractId IS NOT NULL AND p.type IN :types "
            + "AND p.status IN :statuses AND p.dueAt < :now AND (:afterId IS NULL OR p.id > :afterId) ORDER BY p.id")
    List<String> findDueContractObligationIdsAfter(@Param("types") Collection<PaymentType> types,
            @Param("statuses") Collection<PaymentStatus> statuses, @Param("now") Instant now,
            @Param("afterId") String afterId, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentRequest p WHERE p.invoiceId = :invoiceId")
    Optional<PaymentRequest> findByInvoiceIdForUpdate(@Param("invoiceId") String invoiceId);

    boolean existsByTransferReference(String transferReference);

    List<PaymentRequest> findByRentalRequestIdInAndType(Collection<String> rentalRequestIds, PaymentType type);
}
