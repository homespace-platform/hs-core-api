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

    @Query("select p.id from PaymentRequest p where p.active = true and p.status in :statuses "
        + "and p.invoiceId is null and p.type <> com.hs.payment.model.constant.PaymentType.MONTHLY_RENT and p.totalAmount > 0 "
        + "and (p.reminderForDueAt is null or p.reminderForDueAt <> p.dueAt) "
        + "and (((p.type in :billingTypes or p.type = com.hs.payment.model.constant.PaymentType.OTHER_CHARGE and p.contractId is not null) "
        + "and p.dueAt > :billingNow and p.dueAt <= :billingSoon and (p.opensAt is null or p.opensAt <= :billingNow)) "
        + "or (p.type not in :billingTypes and (p.type <> com.hs.payment.model.constant.PaymentType.OTHER_CHARGE or p.contractId is null) "
        + "and p.dueAt > :realNow and p.dueAt <= :realSoon and (p.opensAt is null or p.opensAt <= :realNow))) "
        + "and (:afterId is null or p.id > :afterId) order by p.id")
    List<String> findDueSoonIds(@Param("statuses") Collection<PaymentStatus> statuses,@Param("billingTypes") Collection<PaymentType> billingTypes,
        @Param("billingNow") Instant billingNow,@Param("billingSoon") Instant billingSoon,@Param("realNow") Instant realNow,
        @Param("realSoon") Instant realSoon,@Param("afterId") String afterId,Pageable page);

    Optional<PaymentRequest> findByRentalRequestIdAndType(String rentalRequestId, PaymentType type);

    @Query("SELECT p.id FROM PaymentRequest p WHERE p.status = :status")
    List<String> findIdsByStatus(@Param("status") com.hs.payment.model.constant.PaymentStatus status);

    /** Reported transfers (other than refunds, which have their own window) whose confirmation deadline is at or before {@code horizon}. */
    @Query("SELECT p.id FROM PaymentRequest p WHERE p.status = :status AND p.type NOT IN :excludedTypes "
            + "AND p.confirmationDueAt IS NOT NULL AND p.confirmationDueAt <= :horizon AND (:afterId IS NULL OR p.id > :afterId) ORDER BY p.id")
    List<String> findReportedIdsDueByAfter(@Param("status") PaymentStatus status, @Param("excludedTypes") Collection<PaymentType> excludedTypes,
            @Param("horizon") java.time.Instant horizon, @Param("afterId") String afterId, Pageable page);

    /** Landlord refunds reported at or before {@code reportedBefore} that the tenant has not answered; keyset-paged so no row is starved by a page limit. */
    @Query("SELECT p.id FROM PaymentRequest p WHERE p.status = :status AND p.type IN :types AND p.direction = :direction "
            + "AND p.payerReportedAt IS NOT NULL AND p.payerReportedAt <= :reportedBefore AND (:afterId IS NULL OR p.id > :afterId) ORDER BY p.id")
    List<String> findReportedRefundIdsAfter(@Param("status") PaymentStatus status, @Param("types") Collection<PaymentType> types,
            @Param("direction") com.hs.payment.model.constant.PaymentDirection direction,
            @Param("reportedBefore") Instant reportedBefore, @Param("afterId") String afterId, Pageable page);

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

    // Batch reads for the branch monitoring views; never locked.
    List<PaymentRequest> findByActiveTrueAndInvoiceIdIn(Collection<String> invoiceIds);
    List<PaymentRequest> findByActiveTrueAndRentalRequestIdInAndType(Collection<String> rentalRequestIds, PaymentType type);
    List<PaymentRequest> findByActiveTrueAndContractIdInAndType(Collection<String> contractIds, PaymentType type);

    /** Transfers of these requests in the given states, plus the onboarding receipts in any state. */
    @Query("select p from PaymentRequest p where p.rentalRequestId in :rentalRequestIds and (p.status in :statuses "
            + "or p.type in :anyStatusTypes)")
    List<PaymentRequest> findAttentionByRentals(@Param("rentalRequestIds") Collection<String> rentalRequestIds,
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("anyStatusTypes") Collection<PaymentType> anyStatusTypes);

    List<PaymentRequest> findByContractIdInAndType(Collection<String> contractIds, PaymentType type);

    @Query("select p from PaymentRequest p where p.contractId in :contractIds and p.status in :statuses")
    List<PaymentRequest> findAttentionByContracts(@Param("contractIds") Collection<String> contractIds,
            @Param("statuses") Collection<PaymentStatus> statuses);

    boolean existsByTransferReference(String transferReference);

    /** Receipt backfill cursor: confirmed payments that {@code PaymentReceiptService.capture} would freeze but have no receipt yet. */
    @Query("select p.id from PaymentRequest p where p.status = com.hs.payment.model.constant.PaymentStatus.CONFIRMED "
            + "and p.confirmedAt is not null and p.totalAmount > 0 and p.id > :after "
            + "and not exists (select 1 from PaymentReceipt r where r.paymentRequestId = p.id) order by p.id")
    List<String> findConfirmedWithoutReceipt(@Param("after") String after, Pageable page);

    List<PaymentRequest> findByRentalRequestIdInAndType(Collection<String> rentalRequestIds, PaymentType type);
}
