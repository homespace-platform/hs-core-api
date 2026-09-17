package com.hs.listing.repository;

import com.hs.listing.model.RentalPayment;
import com.hs.listing.model.constant.RentalPaymentStatus;
import com.hs.listing.model.constant.RentalPaymentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RentalPaymentRepository extends JpaRepository<RentalPayment, String> {

    Optional<RentalPayment> findByRentalRequestId(String rentalRequestId);

    Optional<RentalPayment> findByRentalRequestIdAndType(String rentalRequestId, RentalPaymentType type);

    List<RentalPayment> findByRentalRequestIdInAndType(Collection<String> rentalRequestIds, RentalPaymentType type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM RentalPayment p WHERE p.id = :id")
    Optional<RentalPayment> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM RentalPayment p WHERE p.rentalRequestId = :rentalRequestId AND p.type = :type")
    Optional<RentalPayment> findByRentalRequestIdAndTypeForUpdate(
            @Param("rentalRequestId") String rentalRequestId,
            @Param("type") RentalPaymentType type
    );

    List<RentalPayment> findAllByStatusAndExpiresAtLessThanEqual(RentalPaymentStatus status, Instant expiresAt);

    List<RentalPayment> findAllByStatusAndContractDueAtLessThanEqual(RentalPaymentStatus status, Instant contractDueAt);

    boolean existsByRentalRequestIdAndStatusIn(String rentalRequestId, Collection<RentalPaymentStatus> statuses);
}
